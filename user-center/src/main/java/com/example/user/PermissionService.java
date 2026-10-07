package com.example.user;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 角色-权限矩阵。
 *
 * <p>权限点（permission）是功能级开关：前端菜单显隐、后端接口校验都认它。
 * 角色是权限点的集合，用户通过 user_roles 挂角色 —— 这样新增"运营"之类的角色时，
 * 只要在配置页勾选权限点，不用改任何代码。</p>
 *
 * <p>权限解析读的是 X-User-Roles（网关清洗注入），权限定义读库；
 * ADMIN 角色代码里硬兜底为"全部权限点"，防止配置页把管理员勾光导致自己锁死。</p>
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    static final String ADMIN_ROLE = "ADMIN";

    /**
     * 「我的团队」入口的权限码。
     *
     * <p><b>刻意不放进 {@code permissions} 表</b>：它不是能勾给角色的功能开关，而是从
     * {@code team_members.is_lead} 派生出来的身份标识——权限点一旦入表就会进授权矩阵、
     * 能被勾给 USER，于是人人都有个空的「我的团队」入口。所以它只在读取时贴上，
     * 排序与查表都绕过它（见 {@link #resolve(long, List)}）。</p>
     *
     * <p>三处必须一字不差：后端 {@code MyTeamController.requireView}、
     * 前端菜单显隐、前端路由守卫。</p>
     */
    public static final String PERMISSION_TEAM_VIEW = "team:view";

    /**
     * 「审批中心 → 权限申请」的权限码（37 号入表，原只授 ADMIN）。
     *
     * <p><b>41 号起改为派生</b>：团队负责人（批第 1 级）与系统管理员（批第 2 级）
     * 在读取时也贴上——与 {@link #PERMISSION_TEAM_VIEW} 同一先例：身份在库里、
     * 请求头里只有角色，菜单显隐、路由守卫、Controller 的 firstMissing 都走
     * {@link #resolve(long, List)} 这一份结果。单子级别「这一级你能不能批」不在
     * 这里管，由 PermissionRequestService 按申请单的 stage 判。</p>
     */
    public static final String PERMISSION_REVIEW = "permission:review";

    private final NamedParameterJdbcTemplate jdbc;

    public record PermissionRow(String code, String name, String module, String description) {
    }

    public record RoleRow(String code, String name, String description, boolean builtin, long userCount) {
    }

    public record RoleDetail(String code, String name, String description, boolean builtin,
                             List<String> permissions) {
    }

    /** 全部权限点，按模块排序返回（配置页渲染矩阵用）。 */
    public List<PermissionRow> listPermissions() {
        return jdbc.query("""
                select code, name, module, description
                  from permissions
                 order by module, code
                """, new MapSqlParameterSource(), (rs, i) -> new PermissionRow(
                rs.getString("code"), rs.getString("name"),
                rs.getString("module"), rs.getString("description")));
    }

    /** 角色列表，附带每个角色的用户数。 */
    public List<RoleRow> listRoles() {
        return jdbc.query("""
                select r.code, r.name, r.description, r.builtin,
                       coalesce(u.cnt, 0) as user_count
                  from roles r
                  left join (
                        select role_code, count(1) as cnt from user_roles group by role_code
                  ) u on u.role_code = r.code
                 order by r.builtin desc, r.code
                """, new MapSqlParameterSource(), (rs, i) -> new RoleRow(
                rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getInt("builtin") == 1, rs.getLong("user_count")));
    }

    /** 单个角色的权限勾选情况。 */
    public RoleDetail roleDetail(String code) {
        RoleRow role = jdbc.query("""
                select r.code, r.name, r.description, r.builtin, 0 as user_count
                  from roles r where r.code = :code
                """, new MapSqlParameterSource("code", code),
                (rs, i) -> new RoleRow(rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getInt("builtin") == 1, 0L))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("角色不存在: " + code));

        List<String> granted = jdbc.queryForList("""
                select permission_code from role_permissions
                 where role_code = :code order by permission_code
                """, new MapSqlParameterSource("code", code), String.class);
        return new RoleDetail(role.code(), role.name(), role.description(), role.builtin(), granted);
    }

    /**
     * 整体替换某角色的权限点。
     *
     * <p>ADMIN 有一道保险：无论怎么提交，只要 code 是 ADMIN 就强制补齐全部权限点，
     * 否则配置失误会把所有管理员锁死在系统外面。</p>
     */
    @Transactional
    public List<String> replaceRolePermissions(String code, List<String> permissionCodes) {
        roleDetail(code); // 角色不存在先 404/400

        List<String> target = permissionCodes == null ? List.of()
                : permissionCodes.stream().filter(StringUtils::hasText).distinct().toList();

        // 校验权限点都存在，避免往关联表里塞幽灵 code
        List<String> known = jdbc.queryForList("select code from permissions",
                new MapSqlParameterSource(), String.class);
        for (String permission : target) {
            if (!known.contains(permission)) {
                throw new IllegalArgumentException("权限点不存在: " + permission);
            }
        }
        if (ADMIN_ROLE.equals(code)) {
            target = known;
        }

        jdbc.update("delete from role_permissions where role_code = :code",
                new MapSqlParameterSource("code", code));
        for (String permission : target) {
            jdbc.update("insert ignore into role_permissions (role_code, permission_code) values (:code, :perm)",
                    new MapSqlParameterSource(Map.of("code", code, "perm", permission)));
        }
        return target;
    }

    /** 新建自定义角色（默认不带任何权限，建完去矩阵里勾）。 */
    public RoleRow createRole(String code, String name, String description) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (!normalized.matches("[A-Z][A-Z0-9_]{1,31}")) {
            throw new IllegalArgumentException("角色编码需 2-32 位大写字母/数字/下划线，且以字母开头");
        }
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("角色名称不能为空");
        }
        jdbc.update("""
                insert into roles (code, name, description, builtin)
                values (:code, :name, :description, 0)
                """, new MapSqlParameterSource(Map.of(
                "code", normalized, "name", name.trim(),
                "description", description == null ? "" : description.trim())));
        return new RoleRow(normalized, name.trim(), description == null ? "" : description.trim(), false, 0);
    }

    /** 更新角色名称/描述（编码不可改，权限点走 replaceRolePermissions）。 */
    public RoleRow updateRole(String code, String name, String description) {
        RoleDetail role = roleDetail(code);
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("角色名称不能为空");
        }
        jdbc.update("""
                update roles set name = :name, description = :description where code = :code
                """, new MapSqlParameterSource(Map.of(
                "code", code, "name", name.trim(),
                "description", description == null ? "" : description.trim())));
        return new RoleRow(code, name.trim(), description == null ? "" : description.trim(),
                role.builtin(), roleUserCount(code));
    }

    /** 角色持有的成员列表（权限中心-角色详情用）。 */
    public record RoleMember(long id, String account, String nickname, int status, String createdAt) {
    }

    public List<RoleMember> roleMembers(String code) {
        roleDetail(code); // 角色不存在先 400
        return jdbc.query("""
                select u.id, u.account, u.nickname, u.status, u.created_at
                  from users u
                  join user_roles r on r.user_id = u.id
                 where r.role_code = :code
                 order by u.id
                """, new MapSqlParameterSource("code", code), (rs, i) -> new RoleMember(
                rs.getLong("id"), rs.getString("account"), rs.getString("nickname"),
                rs.getInt("status"), rs.getString("created_at")));
    }

    /** 权限中心概览：角色数/权限点数/授权关系数/内置角色。 */
    public record Overview(int roleCount, int permissionCount, int grantCount,
                           int customRoleCount, int moduleCount) {
    }

    public Overview overview() {
        Map<String, Object> row = jdbc.queryForMap("""
                select (select count(1) from roles) as role_count,
                       (select count(1) from permissions) as permission_count,
                       (select count(1) from role_permissions) as grant_count,
                       (select count(1) from roles where builtin = 0) as custom_role_count,
                       (select count(distinct module) from permissions) as module_count
                """, new MapSqlParameterSource());
        return new Overview(
                ((Number) row.get("role_count")).intValue(),
                ((Number) row.get("permission_count")).intValue(),
                ((Number) row.get("grant_count")).intValue(),
                ((Number) row.get("custom_role_count")).intValue(),
                ((Number) row.get("module_count")).intValue());
    }

    private long roleUserCount(String code) {
        Long count = jdbc.queryForObject(
                "select count(1) from user_roles where role_code = :code",
                new MapSqlParameterSource("code", code), Long.class);
        return count == null ? 0 : count;
    }

    /** 删除自定义角色：内置角色、仍有人在用的角色都不许删。 */
    public void deleteRole(String code) {
        RoleDetail role = roleDetail(code);
        if (role.builtin()) {
            throw new IllegalArgumentException("内置角色不可删除");
        }
        Integer used = jdbc.queryForObject(
                "select count(1) from user_roles where role_code = :code",
                new MapSqlParameterSource("code", code), Integer.class);
        if (used != null && used > 0) {
            throw new IllegalArgumentException("仍有 " + used + " 个用户持有该角色，请先移除");
        }
        jdbc.update("delete from role_permissions where role_code = :code",
                new MapSqlParameterSource("code", code));
        jdbc.update("delete from roles where code = :code",
                new MapSqlParameterSource("code", code));
    }

    /**
     * 角色本身带来的权限点（<b>不含</b>团队继承）。
     *
     * <p>ADMIN 直接返回全部权限点（代码兜底）；其余角色查 role_permissions 求并集。
     * 返回顺序按权限点定义顺序，方便前端稳定渲染。</p>
     *
     * <p>与 {@link #resolve(long, List)} 的区别只有团队继承这一项——团队的「有效权限」
     * 要按成员的<b>自身</b>权限取并集，如果连成员自己的继承都算进去，就会出现
     * 「lead 继承团队 → 团队又包含 lead 继承来的权限」这种自己喂自己的循环。</p>
     */
    public List<String> rolePermissions(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        if (roleCodes.stream().anyMatch(ADMIN_ROLE::equalsIgnoreCase)) {
            return jdbc.queryForList("select code from permissions order by module, code",
                    new MapSqlParameterSource(), String.class);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("codes", roleCodes);
        List<PermissionRow> granted = jdbc.query("""
                select distinct p.code, p.module
                  from permissions p
                  join role_permissions rp on rp.permission_code = p.code
                 where rp.role_code in (:codes)
                """, params, (rs, i) -> new PermissionRow(
                rs.getString("code"), null, rs.getString("module"), null));
        // MySQL 3065: DISTINCT 查询的 ORDER BY 不能引用未 SELECT 的列（p.module），
        // 所以排序放在内存里做，保证返回顺序与权限点定义顺序一致
        if (granted == null) {
            return List.of();
        }
        return granted.stream()
                .sorted(Comparator.comparing(PermissionRow::module).thenComparing(PermissionRow::code))
                .map(PermissionRow::code)
                .toList();
    }

    /**
     * 某人带队节点的<b>整棵子树</b> team_id（含带队节点自己）。
     *
     * <p>同时供权限继承（{@link #leadInheritedPermissions}）与「我的团队」的行级可见性用——
     * 两边算的必须是同一个集合，否则会出现「继承来的权限」和「看得见的成员」对不上。</p>
     *
     * <p><b>返回空 ⟺ 不是负责人</b>：递归的锚点就是「该用户 is_lead=1 的团队」，
     * 所以只要带队就至少返回一个自己的团队。这个等价关系让调用方不必再查一次，
     * 也意味着「是负责人」这个判定和「能看到哪些团队」永远一致。</p>
     */
    public List<Long> leadSubtreeTeamIds(long userId) {
        if (userId <= 0) {
            return List.of();
        }
        List<Long> ids = jdbc.queryForList("""
                with recursive subtree as (
                    select t.id
                      from teams t
                     where t.id in (select team_id from team_members
                                     where user_id = :userId and is_lead = 1)
                    union all
                    select t.id
                      from teams t
                      join subtree s on t.parent_id = s.id
                )
                select id from subtree
                """, new MapSqlParameterSource("userId", userId), Long.class);
        return ids == null ? List.of() : ids;
    }

    /**
     * 某人作为团队负责人，从所带团队继承到的权限点 =
     * <b>所带队节点整棵子树</b>里在职成员「自身」权限的并集。
     *
     * <p>用递归 CTE 一次把子树 team_id 取全：父团队的 lead 因此自动拥有下面所有小队
     * 的权限，这是「team 下面的人有权限，teamlead 也自动有」在层级结构下的自然延伸。</p>
     *
     * <p>子树里有 ADMIN 时按“成员拥有全部权限”处理，所以继承结果也是全部——这与
     * 「管理员拥有所有全部」是同一条规则的自然延伸，而不是给团队开的后门。
     * 排除禁用账号（{@code u.status = 1}）与 TeamService 的统计口径保持一致，
     * 否则会出现「禁用的人给上级供权限、却不在团队页的并集里」这种对不上的情况。</p>
     *
     * <p>不是负责人（或没加入任何团队）时返回空，这里不报错：团队功能对普通用户是透明的。</p>
     */
    public List<String> leadInheritedPermissions(long userId) {
        return leadInheritedFrom(leadSubtreeTeamIds(userId));
    }

    /** 见 {@link #leadInheritedPermissions(long)}；把子树算好是为了和 resolve() 共用同一次查询。 */
    private List<String> leadInheritedFrom(List<Long> subtree) {
        if (subtree == null || subtree.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("teamIds", subtree);

        Integer adminPeers = jdbc.queryForObject("""
                select count(1)
                  from team_members tm
                  join users u on u.id = tm.user_id and u.status = 1
                  join user_roles ur on ur.user_id = tm.user_id
                 where tm.team_id in (:teamIds) and ur.role_code = 'ADMIN'
                """, params, Integer.class);
        if (adminPeers != null && adminPeers > 0) {
            return jdbc.queryForList("select code from permissions order by module, code",
                    new MapSqlParameterSource(), String.class);
        }

        List<String> codes = jdbc.queryForList("""
                select distinct rp.permission_code
                  from team_members tm
                  join users u on u.id = tm.user_id and u.status = 1
                  join user_roles ur on ur.user_id = tm.user_id
                  join role_permissions rp on rp.role_code = ur.role_code
                 where tm.team_id in (:teamIds)
                """, params, String.class);
        return codes == null ? List.of() : codes;
    }

    /**
     * 有效权限点 = 角色权限 ∪ 负责人从团队继承到的权限 ∪（带队时）`team:view`
     * ∪ 用户级直授 − 用户级拉黑（37 号迁移的覆盖层）。
     *
     * <p>前端菜单（/users/me 的 permissionCodes）和后端 firstMissing 都走这一个方法，
     * 保证「菜单里看得见」与「接口调得动」永远是同一份判定。</p>
     */
    public List<String> resolve(long userId, List<String> roleCodes) {
        // 子树只递归一次，同时喂给「继承来的权限」和「有没有 team:view」两处
        List<Long> subtree = leadSubtreeTeamIds(userId);
        List<String> own = rolePermissions(roleCodes);
        List<String> inherited = leadInheritedFrom(subtree);
        List<String> merged;
        if (inherited.isEmpty()) {
            merged = own;
        } else {
            // 用权限点全表当排序基准，既保证顺序与定义一致，也顺手去掉重复
            List<String> all = jdbc.queryForList(
                    "select code from permissions order by module, code",
                    new MapSqlParameterSource(), String.class);
            java.util.Set<String> union = new java.util.LinkedHashSet<>(own);
            union.addAll(inherited);
            merged = all.stream().filter(union::contains).toList();
        }
        List<String> base;
        if (subtree.isEmpty()) {
            // 不带队：既没有 team:view，也没有任何团队继承
            base = merged;
        } else {
            // team:view 不在 permissions 表里，上面那道「按全表过滤」会把它筛掉，
            // 所以只能在过滤之后贴上
            List<String> withLeadView = new ArrayList<>(merged);
            if (!withLeadView.contains(PERMISSION_TEAM_VIEW)) {
                withLeadView.add(PERMISSION_TEAM_VIEW);
            }
            base = withLeadView;
        }
        // 41 号两级审批：负责人（subtree 非空即是）与系统管理员贴审批权，
        // 让他们进得了「审批中心」；能不能批某张单由申请单的 stage 再判一道。
        // 角色里本来就授了 permission:review 的（USER 矩阵被勾过）不受影响——
        // 这里只做追加，且排在覆盖层之前，拉黑仍压得住。
        List<String> base2 = base;
        if (userId > 0 && (!subtree.isEmpty() || isSystemAdmin(userId))) {
            base2 = new ArrayList<>(base);
            if (!base2.contains(PERMISSION_REVIEW)) {
                base2.add(PERMISSION_REVIEW);
            }
        }
        return applyUserOverrides(userId, base2);
    }

    /**
     * 是否任一系统（模块）的管理员（41 号 system_admins）。
     *
     * <p>resolve 的热路径上每请求可能多次走到这里，走 idx_user 等值查询，
     * 表行数 = 模块数 × 管理员数，量级可忽略。</p>
     */
    private boolean isSystemAdmin(long userId) {
        if (userId <= 0) {
            return false;
        }
        Integer n = jdbc.queryForObject(
                "select count(1) from system_admins where user_id = :id",
                new MapSqlParameterSource("id", userId), Integer.class);
        return n != null && n > 0;
    }

    /**
     * 应用用户级覆盖（user_permissions，37 号迁移）：effect=1 直授追加、
     * effect=0 拉黑移除。拉黑排在最后，压过角色与团队继承——这正是
     * 「我在权限中心点删除」要的效果。
     *
     * <p>同 (user, code) 在覆盖表里只有一行（主键），两种 effect 不会同时存在，
     * 不存在直授与拉黑打架的次序问题。userId<=0（无请求上下文）直接跳过：
     * 覆盖只可能是本人操作出来的，取不到本人就没有可应用的行。</p>
     */
    private List<String> applyUserOverrides(long userId, List<String> base) {
        if (userId <= 0) {
            return base;
        }
        List<String> result = base;
        var rows = jdbc.queryForList(
                "select permission_code, effect from user_permissions where user_id = :id",
                new MapSqlParameterSource("id", userId));
        for (var row : rows) {
            String code = String.valueOf(row.get("permission_code"));
            int effect = ((Number) row.get("effect")).intValue();
            if (effect == 1) {
                if (!result.contains(code)) {
                    result = new ArrayList<>(result);
                    result.add(code);
                }
            } else {
                if (result.contains(code)) {
                    result = new ArrayList<>(result);
                    result.remove(code);
                }
            }
        }
        return result;
    }

    /**
     * 判定单个权限点（当前请求上下文版本）。
     *
     * <p>各 Controller 的 {@code require(roles, permission)} 都走这个重载，不必在二十来个
     * 接口方法上逐个挂一遍 userId —— 团队负责人身份只存在于库里，请求头里只有角色，
     * 所以这里从网关注入的 X-User-Id 把操作者捞出来。X-User-Id 由网关在
     * stripInternalHeaders 之后统一注入，客户端伪造不了。</p>
     */
    public String firstMissing(String rolesHeader, String requiredPermission) {
        return firstMissing(rolesHeader, currentUserId(), requiredPermission);
    }

    /** 网关注入的操作者 id。取不到时返回 0——只意味着「不参与团队继承」，不该把鉴权判断炸成 500。 */
    long currentUserId() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return 0L;
        }
        String raw = servletAttrs.getRequest().getHeader("X-User-Id");
        if (!StringUtils.hasText(raw)) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * 判定单个权限点。返回被拒绝的权限码（null 表示放行）。
     *
     * <p>供管理端接口校验用：ADMIN 一律放行，其余按角色 + 团队负责人继承判定。
     * userId 是团队继承唯一的线索来源——负责人身份只在库里，头里只有角色。</p>
     */
    public String firstMissing(String rolesHeader, long userId, String requiredPermission) {
        List<String> roles = new ArrayList<>();
        if (StringUtils.hasText(rolesHeader)) {
            for (String role : rolesHeader.split(",")) {
                if (StringUtils.hasText(role)) {
                    roles.add(role.trim());
                }
            }
        }
        if (roles.stream().anyMatch(ADMIN_ROLE::equalsIgnoreCase)) {
            return null;
        }
        // 角色为空不等于没权限：带团队的人可能一个角色都没有，照样该走继承判定
        if (roles.isEmpty() && userId <= 0) {
            return requiredPermission;
        }
        return resolve(userId, roles).contains(requiredPermission) ? null : requiredPermission;
    }

    /** 角色 → 权限矩阵全量（配置页一次拉完渲染表格）。 */
    public Map<String, List<String>> matrix() {
        List<String[]> rows = jdbc.query(
                "select role_code, permission_code from role_permissions",
                new MapSqlParameterSource(), (rs, i) ->
                        new String[]{rs.getString(1), rs.getString(2)});
        Map<String, List<String>> matrix = new LinkedHashMap<>();
        for (String[] row : rows) {
            matrix.computeIfAbsent(row[0], k -> new ArrayList<>()).add(row[1]);
        }
        return matrix;
    }
}
