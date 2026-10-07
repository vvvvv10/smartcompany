package com.example.user;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 管理端读写。与 UserDirectory（面向认证链路）分开，
 * 避免把分页、统计这类运营逻辑混进认证路径里。
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String USER_ROLE = "USER";

    private final NamedParameterJdbcTemplate jdbc;
    /** 建号走和注册同一条写入路径（uk_account / uk_nickname 唯一索引兜底 + 默认 USER 角色） */
    private final UserDirectory users;
    private final PasswordEncoder passwordEncoder;

    /** 系统初始密码用 SecureRandom 而不是 Random——这是安全用途，不能是可预测的伪随机 */
    private static final SecureRandom RANDOM = new SecureRandom();

    public record UserRow(long id,
                          String account,
                          String nickname,
                          /** 真实姓名；注册页不采集，所以自助注册进来的行是空串 */
                          String realName,
                          /** 身份证号脱敏值（前 6 位 + 8 个 * + 后 4 位）。完整值不进任何响应 */
                          String idCardMasked,
                          String tenantId,
                          /** 租户显示名（tenants 字典 LEFT JOIN）；字典缺行时回退成 id 本身 */
                          String tenantName,
                          int status,
                          List<String> roles,
                          String createdAt,
                          String lastLoginAt) {
    }

    /** 建号结果。初始密码明文只在这一个响应体里出现，服务端不回存、查不出来。 */
    public record CreatedUser(UserRow user, String initialPassword, String corpSyncResult) {
    }

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    public record Totals(long totalUsers,
                         long activeUsers,
                         long disabledUsers,
                         long todayRegistrations,
                         long weekRegistrations,
                         long monthActiveUsers) {
    }

    public record RoleCount(String roleCode, long count) {
    }

    public record TrendPoint(String date, long registrations) {
    }

    public record Dashboard(Totals totals, List<RoleCount> roleDistribution, List<TrendPoint> trend) {
    }

    public PageResult<UserRow> search(String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", safeSize)
                .addValue("offset", (safePage - 1) * safeSize);

        String where = "";
        if (StringUtils.hasText(keyword)) {
            where = " where u.account like :kw or u.nickname like :kw or u.real_name like :kw ";
            params.addValue("kw", "%" + keyword.trim() + "%");
        }

        String query = """
                select u.id, u.account, u.nickname, u.real_name, u.id_card, u.tenant_id,
                       coalesce(t.name, u.tenant_id) as tenant_name,
                       u.status, u.created_at, u.last_login_at,
                       coalesce(group_concat(r.role_code), '') as roles
                from users u
                left join user_roles r on r.user_id = u.id
                left join tenants t on t.id = u.tenant_id
                """ + where + """
                group by u.id, u.account, u.nickname, u.real_name, u.id_card, u.tenant_id,
                         coalesce(t.name, u.tenant_id), u.status,
                         u.created_at, u.last_login_at
                order by u.id desc
                limit :limit offset :offset
                """;
        String count = "select count(1) from users u" + where;

        List<UserRow> rows = jdbc.query(query, params, userRowMapper());

        Long total = jdbc.queryForObject(count, params, Long.class);
        return new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize);
    }

    /** 单个用户，新建后回读给前端用。 */
    public Optional<UserRow> findById(long id) {
        return jdbc.query("""
                select u.id, u.account, u.nickname, u.real_name, u.id_card, u.tenant_id,
                       coalesce(t.name, u.tenant_id) as tenant_name,
                       u.status, u.created_at, u.last_login_at,
                       coalesce(group_concat(r.role_code), '') as roles
                from users u
                left join user_roles r on r.user_id = u.id
                left join tenants t on t.id = u.tenant_id
                where u.id = :id
                group by u.id, u.account, u.nickname, u.real_name, u.id_card, u.tenant_id,
                         coalesce(t.name, u.tenant_id), u.status, u.created_at, u.last_login_at
                """, new MapSqlParameterSource("id", id), userRowMapper()).stream().findFirst();
    }

    public boolean accountExists(String account) {
        return users.existsByAccount(account);
    }

    public boolean nicknameExists(String nickname) {
        return users.existsByNickname(nickname);
    }

    /**
     * 管理端建号：和自助注册共用 {@link UserDirectory#create}，因此密码同样落 BCrypt、
     * 花名同样受 uk_nickname 唯一索引保护。差别只是不走短信验证码——
     * 花名与初始密码都改由服务端生成，管理员只提供三件客观事实：手机号、姓名、身份证。
     *
     * <p>roles 为空时 replaceRoles 会兜回 USER，避免建出没有任何角色、登录后四处 403 的账号。</p>
     *
     * <p>花名撞车靠序号让位而不是报错：同名的张三来三个，就有 张三 / 张三2 / 张三3。
     * 预检与插入之间仍可能被并发抢走，那一层由 uk_nickname 兜底翻成 409，
     * 所以这里的 for 只是尽量把「加序号」做在应用侧，不是唯一防线。</p>
     *
     * <p>tenantId 归属由调用方先过 TenantService.requireActive；这里只负责落库。</p>
     */
    @Transactional
    public CreatedUser create(String account, String realName, String idCard, List<String> roles,
                              String tenantId) {
        String name = realName == null ? "" : realName.trim();
        String plainPassword = generatePassword();
        // 「用户{id}」要拿到自增 id 才算得出来，姓名为空时只能先落库再回填。
        // 这一支在接口上不可达（姓名是必填），留着是为了 service 被别处复用时不出错。
        String nickname = name.isEmpty() ? "" : nextNickname(name);

        long id = users.create(account, passwordEncoder.encode(plainPassword), nickname, name, idCard, tenantId);
        if (name.isEmpty()) {
            nickname = "用户" + id;
            users.updateNickname(id, nickname);
        }
        replaceRoles(id, roles);
        return new CreatedUser(findById(id).orElseThrow(() ->
                new IllegalStateException("用户创建成功但回读失败: id=" + id)), plainPassword, null);
    }

    /**
     * 花名 = 姓名同名，撞车加序号。序号从 2 起（本人拿到的是不带后缀的那个），
     * 并且不能把 32 字的花名上限撑破——姓名越长，序号可用的余地越小。
     */
    private String nextNickname(String name) {
        String base = name.length() > 30 ? name.substring(0, 30) : name;
        String candidate = base;
        for (int seq = 2; users.existsByNickname(candidate) && seq < 1000; seq++) {
            String suffix = String.valueOf(seq);
            String stem = base;
            while (stem.length() + suffix.length() > 32) {
                stem = stem.substring(0, stem.length() - 1);
            }
            candidate = stem + suffix;
        }
        return candidate;
    }

    /**
     * 系统初始密码。12 位、大小写数字齐全，去掉 0/O/1/l/I 这些念不清的字符——
     * 反正要线下转交，念得清比难猜更重要；12 位混合字符的熵仍然远超暴力破解的门槛。
     */
    private String generatePassword() {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnpqrstuvwxyz";
        String digit = "23456789";
        String all = upper + lower + digit;

        StringBuilder sb = new StringBuilder(12);
        sb.append(upper.charAt(RANDOM.nextInt(upper.length())));
        sb.append(lower.charAt(RANDOM.nextInt(lower.length())));
        sb.append(digit.charAt(RANDOM.nextInt(digit.length())));
        for (int i = 0; i < 9; i++) {
            sb.append(all.charAt(RANDOM.nextInt(all.length())));
        }
        // 前三位固定了「各类至少一个」，洗牌后这个保证依然成立，但顺序不再可预测
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    /**
     * 启用一个账号。这里多一道检查，不是多余的：
     * uk_account / uk_nickname 只约束在职行，所以「禁用 → 启用」完全可能点亮
     * 一个账号（或花名）已经被新人接管的老人——直接 update 会撞唯一索引，
     * 抛出来是一句只有 DBA 看得懂的 409，必须先说人话。
     */
    public int updateStatus(long id, int status) {
        int statusValue = status == 0 ? 0 : 1;
        if (statusValue == 1) {
            assertNotTaken(id);
        }
        return jdbc.update("update users set status = :status where id = :id",
                new MapSqlParameterSource(Map.of("id", id, "status", statusValue)));
    }

    /**
     * 改用户归属租户。生效时机由令牌机制决定：access token 里已固化的 tid
     * 不会热改，15 分钟内到期走 rotate 重签时从库里重读——所以改完归属，
     * 用户最晚一个刷新周期后才在新租户里读写业务数据，立即重登则马上生效。
     *
     * @return 更新行数；0 = 用户不存在
     */
    public int updateTenant(long id, String tenantId) {
        return jdbc.update("update users set tenant_id = :tenantId where id = :id",
                new MapSqlParameterSource(Map.of("id", id, "tenantId", tenantId)));
    }

    private void assertNotTaken(long id) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        Integer accountClash = jdbc.queryForObject("""
                select count(1) from users
                 where status = 1 and id <> :id
                   and account = (select account from users where id = :id)
                """, params, Integer.class);
        if (accountClash != null && accountClash > 0) {
            throw new ConflictException("account_exists", "该手机号已被新用户占用，无法重新启用");
        }
        Integer nicknameClash = jdbc.queryForObject("""
                select count(1) from users
                 where status = 1 and id <> :id
                   and nickname = (select nickname from users where id = :id)
                """, params, Integer.class);
        if (nicknameClash != null && nicknameClash > 0) {
            throw new ConflictException("nickname_exists", "该花名已被新用户占用，无法重新启用");
        }
    }

    /**
     * 整体替换角色。空列表会兜回 USER，避免出现没有任何角色的僵尸账号。
     */
    @Transactional
    public List<String> replaceRoles(long id, List<String> roles) {
        List<String> target = roles == null || roles.isEmpty() ? List.of(USER_ROLE) : roles;
        jdbc.update("delete from user_roles where user_id = :id", new MapSqlParameterSource("id", id));
        for (String role : target) {
            jdbc.update("insert ignore into user_roles (user_id, role_code) values (:id, :role)",
                    new MapSqlParameterSource(Map.of("id", id, "role", role)));
        }
        return listUserRoles(id);
    }

    public List<String> listUserRoles(long id) {
        return jdbc.queryForList("select role_code from user_roles where user_id = :id order by role_code",
                new MapSqlParameterSource("id", id), String.class);
    }

    public List<String> listRoleCodes() {
        List<String> codes = new ArrayList<>(jdbc.queryForList(
                "select distinct role_code from user_roles order by role_code",
                new MapSqlParameterSource(), String.class));
        if (codes.isEmpty()) {
            codes.add(USER_ROLE);
        }
        return codes;
    }

    public void touchLogin(long id) {
        jdbc.update("update users set last_login_at = now() where id = :id",
                new MapSqlParameterSource("id", id));
    }

    /** 趋势 SQL 里的日期维表用 union 写了 14 天，上限不能超过它。 */
    public Dashboard dashboard(int trendDays) {
        int days = Math.min(Math.max(trendDays, 3), 14);
        MapSqlParameterSource daysParam = new MapSqlParameterSource("days", days);

        Totals totals = jdbc.query("""
                select
                    count(1) as total,
                    sum(case when status = 1 then 1 else 0 end) as active,
                    sum(case when status = 0 then 1 else 0 end) as disabled,
                    sum(case when date(created_at) = curdate() then 1 else 0 end) as today,
                    sum(case when created_at >= date_sub(curdate(), interval 7 day) then 1 else 0 end) as week,
                    sum(case when last_login_at >= date_sub(now(), interval 30 day) then 1 else 0 end) as month_active
                from users
                """, new MapSqlParameterSource(), rs -> {
            rs.next();
            return new Totals(rs.getLong("total"), rs.getLong("active"), rs.getLong("disabled"),
                    rs.getLong("today"), rs.getLong("week"), rs.getLong("month_active"));
        });

        List<RoleCount> distribution = jdbc.query("""
                select role_code, count(1) as cnt from user_roles group by role_code order by cnt desc
                """, new MapSqlParameterSource(), (rs, rowNum) ->
                new RoleCount(rs.getString("role_code"), rs.getLong("cnt")));

        // 用日期维表的方式回填没有注册量的那一天，否则折线图会缺 x 轴点位
        List<TrendPoint> trend = jdbc.query("""
                select d.day as day, coalesce(count(u.id), 0) as registrations
                from (
                    select date_sub(curdate(), interval seq day) as day
                    from (select 0 as seq union select 1 union select 2 union select 3 union select 4
                          union select 5 union select 6 union select 7 union select 8 union select 9
                          union select 10 union select 11 union select 12 union select 13) s
                ) d
                left join users u on date(u.created_at) = d.day
                where d.day >= date_sub(curdate(), interval :days day)
                group by d.day
                order by d.day
                """, daysParam, (rs, rowNum) ->
                new TrendPoint(rs.getString("day"), rs.getLong("registrations")));

        return new Dashboard(totals, distribution, trend);
    }

    private List<String> splitRoles(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).filter(StringUtils::hasText).distinct().toList();
    }

    /** 列表与单查共用同一个映射，避免两处字段悄悄跑偏 */
    private RowMapper<UserRow> userRowMapper() {
        return (rs, rowNum) -> new UserRow(
                rs.getLong("id"),
                rs.getString("account"),
                rs.getString("nickname"),
                Optional.ofNullable(rs.getString("real_name")).orElse(""),
                maskIdCard(rs.getString("id_card")),
                rs.getString("tenant_id"),
                Optional.ofNullable(rs.getString("tenant_name")).orElse(rs.getString("tenant_id")),
                rs.getInt("status"),
                splitRoles(rs.getString("roles")),
                Optional.ofNullable(rs.getString("created_at")).orElse(""),
                Optional.ofNullable(rs.getString("last_login_at")).orElse(""));
    }

    /**
     * 身份证脱敏：前 6 位 + 8 个 * + 后 4 位，正好凑回 18 位的长度，
     * 肉眼一眼能对上是哪张证，但拿不走完整号码。
     *
     * <p>库里存的是完整值（建档留痕用），但凡出接口的一律先过这里。
     * 空值直接回空串——注册进来的行没有身份证，不要显示成「****」吓人一跳。</p>
     */
    static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 10) {
            return "";
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(idCard.length() - 4);
    }
}
