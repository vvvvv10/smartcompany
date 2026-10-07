package com.example.user;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 统一审批：花名变更 与 权限申请 走**同一张表、同一套两级串行审批**。
 *
 * <p>【为什么要合并】原来两者各走各的：两张表、两套列表/待办数/批准驳回端点、两个审批
 * 权限点（{@code nickname:review} 与 {@code permission:review}），而且级次不一致
 * （花名单级、权限两级）。业务上它们是同一件事——"员工提申请 → 领导批 → 系统管理员批
 * → 落地"，合成一条链路后审批人只判一个队列、待办数只有一个口径、级次只实现一次。</p>
 *
 * <p><b>本类不改动也不依赖</b> {@code NicknameRequestService} /
 * {@code PermissionRequestService}：那两套正在被另一条开发线改动，这里自包含实现，
 * 冲突面为零。两级判定的 SQL 与它们逐条对齐（改了一处，另一处也要跟着改——
 * TODO 待旧链路下线后删掉重复）。</p>
 *
 * <p><b>两级链路</b>：① 申请人所在团队的负责人 → ② 该模块的系统管理员；
 * ADMIN 任意一级可整单批。提交时按"有没有非本人的直属负责人"定格起始级次，
 * 之后每级审批按<b>当时</b>的负责人/系统管理员验资格（换人不影响在途单）。</p>
 *
 * <p><b>三条硬规则</b>（都是出过人命的地方）：</p>
 * <ul>
 *   <li><b>禁自批</b>：审批人 == 申请人一律拒绝（ADMIN 除外，ADMIN 全量权限下自批
 *       不构成提权，见 {@code 4c2a745}）</li>
 *   <li><b>驳回必填理由</b>：没有理由的驳回对申请人毫无帮助</li>
 *   <li><b>列表按查看者圈范围</b>：只看得到"该我这一级处理的"与"我处理过的"，
 *       别人的单子不该出现在审批台里</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ApprovalRequestService {

    public static final String TYPE_NICKNAME = "NICKNAME";
    public static final String TYPE_PERMISSION = "PERMISSION";

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /** 第 1 级：申请人所在团队的负责人 */
    public static final int STAGE_LEAD = 1;
    /** 第 2 级：该模块的系统管理员 */
    public static final int STAGE_SYSADMIN = 2;

    /** 花名变更固定归到 profile 模块——第 2 级由 profile 的系统管理员/ADMIN 批 */
    private static final String NICKNAME_MODULE = "profile";

    private final NamedParameterJdbcTemplate jdbc;
    private final UserDirectory users;
    private final PermissionService permissions;

    // ---------------- 对外的行模型 ----------------

    /**
     * 审批视角的一行。
     *
     * <p>{@code type} 告诉前端这是花名还是权限申请（两者字段差异大，取值展示各不相同），
     * {@code target} 是"申请的东西"：花名=新花名，权限=权限码。
     * {@code canAct} 是<b>查看者</b>在<b>这一级</b>能不能动手，由后端算好，前端只渲染开关。</p>
     */
    public record Row(long id,
                      long userId,
                      String account,
                      String nickname,
                      String type,
                      String prevValue,
                      String target,
                      String module,
                      String note,
                      String status,
                      int stage,
                      Long leadReviewerId,
                      String leadReviewedAt,
                      Long reviewerId,
                      String reviewNote,
                      String reviewedAt,
                      String createdAt,
                      String leadNames,
                      String sysAdminNames,
                      boolean canAct) {
    }

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    /** 查看者视角：ID、是否 ADMIN、带的人（我是他领导的）、管着的模块（我是其管理员）。 */
    public record Viewer(long id, boolean admin, Set<Long> leadMembers, Set<String> modules) {
    }

    private static final Viewer NO_VIEWER = new Viewer(0L, false, Set.of(), Set.of());

    // ---------------- SQL 片段 ----------------

    private static final String SELECT_ROW = """
            select r.id, r.user_id, u.account, u.nickname, r.type, r.prev_value, r.target,
                   r.module, r.note, r.status, r.stage, r.lead_reviewer_id, r.lead_reviewed_at,
                   r.reviewer_id, r.review_note, r.reviewed_at, r.created_at,
                   (select group_concat(distinct lu.nickname separator '、')
                      from team_members tm
                      join team_members lm on lm.team_id = tm.team_id
                                           and lm.is_lead = 1
                                           and lm.user_id <> tm.user_id
                      join users lu on lu.id = lm.user_id
                     where tm.user_id = r.user_id) as lead_names,
                   (select group_concat(distinct su.nickname separator '、')
                      from system_admins sa
                      join users su on su.id = sa.user_id
                     where sa.module = r.module) as sys_admin_names
              from approval_requests r
              join users u on u.id = r.user_id
            """;

    /**
     * 非 ADMIN 的数据范围：待我按级处理的。
     *
     * <p>两个分支都带 {@code status='PENDING'}——处理完的单子靠 {@link #scopeWithHistory}
     * 回来。第 2 级分支排除自己的申请：自己的单不能自批，留在列表里只会变成点了没反应
     * 的死按钮；看进度回「我的申请」。第 1 级分支的 {@code ld.user_id <> tm.user_id}
     * 已隐含同样排除（:me 即 ld）。</p>
     *
     * <p><b>sysModules 空集守卫</b>：账号不是任何模块的系统管理员时，{@code in (:sysModules)}
     * 会展开成 MySQL 不支持的 {@code in ()} → 500。这种用户本就不该批第 2 级，所以把
     * 分支条件换成 {@code 0=1}（该分支不产任何行），而不是传空列表。</p>
     */
    private String pendingScope(Viewer v) {
        String moduleCond = v.modules().isEmpty()
                ? "0=1"
                : "r.module in (:sysModules)";
        return "((r.status = 'PENDING' and r.stage = 1 and exists (\n"
                + "                  select 1 from team_members tm\n"
                + "                  join team_members ld on ld.team_id = tm.team_id\n"
                + "                                       and ld.is_lead = 1\n"
                + "                                       and ld.user_id <> tm.user_id\n"
                + "                 where tm.user_id = r.user_id and ld.user_id = :me))\n"
                + "             or (r.status = 'PENDING' and r.stage = 2\n"
                + "                 and " + moduleCond + "\n"
                + "                 and r.user_id <> :me))\n";
    }

    /** 列表范围 = 待我处理的 ∪ 我处理过的。 */
    private String scopeWithHistory(Viewer v) {
        return "(" + pendingScope(v) + " or r.lead_reviewer_id = :me or r.reviewer_id = :me)";
    }

    // ---------------- 查询 ----------------

    public PageResult<Row> list(String status, int page, int size, Viewer v) {
        int p = Math.max(1, page);
        int n = Math.max(1, Math.min(200, size));
        MapSqlParameterSource q = new MapSqlParameterSource()
                .addValue("me", v.id())
                .addValue("sysModules", v.modules())
                .addValue("lim", n)
                .addValue("off", (p - 1) * n);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        boolean pendingOnly = false;
        if (StringUtils.hasText(status)) {
            String s = status.trim().toUpperCase();
            if (PENDING.equals(s)) {
                // PENDING 直接写死而不是绑参数：它要同时出现在排序分支里，绑参数反而多一层
                where.append(" and r.status = 'PENDING'");
                pendingOnly = true;
            } else {
                where.append(" and r.status = :status");
                q.addValue("status", s);
            }
        }
        if (!v.admin()) {
            where.append(" and ").append(scopeWithHistory(v));
        }
        Long total = jdbc.queryForObject(
                "select count(1) from approval_requests r" + where, q, Long.class);

        // 「待审批 + 最早提的先审」；其余按 id 倒序（近期优先）
        String order = pendingOnly ? " order by r.created_at, r.id" : " order by r.id desc";
        List<Row> rows = jdbc.query(
                SELECT_ROW + where + order + " limit :lim offset :off", q, (rs, i) -> row(rs, v));
        return new PageResult<>(rows, total == null ? 0L : total, p, n);
    }

    /** 待我处理的条数（徽标专用）。ADMIN 返回全量 PENDING。 */
    public long pendingCount(Viewer v) {
        String sql = v.admin()
                ? "select count(1) from approval_requests r where r.status = 'PENDING'"
                : "select count(1) from approval_requests r where " + pendingScope(v);
        Long n = jdbc.queryForObject(sql,
                new MapSqlParameterSource(Map.of("me", v.id(), "sysModules", v.modules())), Long.class);
        return n == null ? 0L : n;
    }

    /**
     * 我的申请（含已办结，审批页「我的申请」用）。
     *
     * <p>不设权限点：谁能看自己的。{@code canAct} 对自己恒 false。</p>
     */
    public List<Row> my(long userId, int page, int size) {
        int p = Math.max(1, page);
        int n = Math.max(1, Math.min(200, size));
        return jdbc.query(
                SELECT_ROW + " where r.user_id = :uid order by r.id desc limit :lim offset :off",
                new MapSqlParameterSource(Map.of("uid", userId, "lim", n, "off", (p - 1) * n)),
                (rs, i) -> row(rs, myViewer(userId)));
    }

    public Optional<Row> find(long id, Viewer v) {
        List<Row> rows = jdbc.query(
                SELECT_ROW + " where r.id = :id",
                new MapSqlParameterSource(Map.of("id", id)),
                (rs, i) -> row(rs, v));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Row row(java.sql.ResultSet rs, Viewer v) throws java.sql.SQLException {
        long applicantId = rs.getLong("user_id");
        int stage = rs.getInt("stage");
        boolean pending = PENDING.equals(rs.getString("status"));
        // applicant != v.id()：非 ADMIN 不能自批，canAct 恒 false。NO_VIEWER 的 id=0
        // 不命中任何申请人，所以回读路径不受影响（与旧链路同一条判据）
        String module = rs.getString("module");
        boolean canAct = pending
                && (v.admin()
                        || (applicantId != v.id()
                                && (stage == STAGE_LEAD
                                        ? v.leadMembers().contains(applicantId)
                                        : v.modules().contains(module))));
        long leadId = rs.getLong("lead_reviewer_id");
        Long leadReviewerId = rs.wasNull() ? null : leadId;
        long reviewerId = rs.getLong("reviewer_id");
        Long finalReviewerId = rs.wasNull() ? null : reviewerId;
        return new Row(
                rs.getLong("id"),
                applicantId,
                rs.getString("account"),
                rs.getString("nickname"),
                rs.getString("type"),
                rs.getString("prev_value"),
                rs.getString("target"),
                rs.getString("module"),
                rs.getString("note"),
                rs.getString("status"),
                stage,
                leadReviewerId,
                rs.getString("lead_reviewed_at"),
                finalReviewerId,
                rs.getString("review_note"),
                rs.getString("reviewed_at"),
                rs.getString("created_at"),
                rs.getString("lead_names"),
                rs.getString("sys_admin_names"),
                canAct);
    }

    // ---------------- 提交 ----------------

    /**
     * 提交花名变更申请。
     *
     * <p>起始级：有没有"非本人的直属负责人" → 第 1 级，否则直接第 2 级（与权限申请同规则）。
     * 另有三道预检：与当前花名相同、已有在途、且**能批这单的只有他自己**（那就是谁也推不动
     * 的死单，不如当场报错让他先去补审批人）。</p>
     */
    @Transactional
    public Row submitNickname(long userId, String newNickname) {
        String target = newNickname == null ? "" : newNickname.trim();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("新花名不能为空");
        }
        if (target.length() > 32) {
            throw new IllegalArgumentException("花名最长 32 个字符");
        }
        UserDirectory.Account account = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在或已禁用: " + userId));
        if (target.equals(account.nickname())) {
            throw new IllegalArgumentException("新花名与当前花名相同");
        }
        if (pendingOf(userId, TYPE_NICKNAME, target).isPresent()) {
            throw new ConflictException("already_pending", "你已经有一条待审的花名申请了");
        }
        assertNotSelfOnly(userId, NICKNAME_MODULE);

        int stage = hasOtherLead(userId) ? STAGE_LEAD : STAGE_SYSADMIN;
        jdbc.update("""
                insert into approval_requests (user_id, type, prev_value, target, module, status, stage)
                values (:userId, 'NICKNAME', :prev, :target, :module, 'PENDING', :stage)
                """, new MapSqlParameterSource(Map.of(
                "userId", userId,
                "prev", account.nickname(),
                "target", target,
                "module", NICKNAME_MODULE,
                "stage", stage)));
        return findBySubmit(userId, TYPE_NICKNAME, target);
    }

    /**
     * 提交权限申请。
     *
     * <p>四道预检都在写入前，给的是可操作的中文文案：目录里没有的码（防构造请求）、
     * 已有效拥有（该点该走"删除"）、已有在途、只能自己批（死单）。</p>
     */
    @Transactional
    public Row submitPermission(long userId, String rawCode, String note) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("权限点不能为空");
        }
        if (!permissionExists(code)) {
            throw new IllegalArgumentException("权限点不存在: " + code);
        }
        UserDirectory.Account account = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在或已禁用: " + userId));
        if (permissions.resolve(userId, account.roles()).contains(code)) {
            throw new ConflictException("already_has", "你已经拥有该权限，无需申请");
        }
        if (pendingOf(userId, TYPE_PERMISSION, code).isPresent()) {
            throw new ConflictException("already_pending", "你已经有一条待审的该权限申请了");
        }
        String module = moduleOf(code);
        assertNotSelfOnly(userId, module);

        int stage = hasOtherLead(userId) ? STAGE_LEAD : STAGE_SYSADMIN;
        jdbc.update("""
                insert into approval_requests
                    (user_id, type, target, module, note, status, stage)
                values (:userId, 'PERMISSION', :code, :module, :note, 'PENDING', :stage)
                """, new MapSqlParameterSource(Map.of(
                "userId", userId,
                "code", code,
                "module", module,
                "note", note == null ? "" : note.trim(),
                "stage", stage)));
        return findBySubmit(userId, TYPE_PERMISSION, code);
    }

    private Optional<Row> pendingOf(long userId, String type, String target) {
        try {
            Row r = jdbc.queryForObject("""
                    select id from approval_requests
                     where user_id = :userId and type = :type and target = :target and status = 'PENDING'
                     order by id desc limit 1
                    """, new MapSqlParameterSource(Map.of(
                    "userId", userId, "type", type, "target", target)),
                    (rs, i) -> row(rs, NO_VIEWER));
            return Optional.of(r);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Row findBySubmit(long userId, String type, String target) {
        return jdbc.queryForObject("""
                select r.id from approval_requests r
                 where r.user_id = :userId and r.type = :type and r.target = :target
                 order by r.id desc limit 1
                """, new MapSqlParameterSource(Map.of("userId", userId, "type", type, "target", target)),
                (rs, i) -> find(rs.getLong("id"), myViewer(userId)).orElseThrow());
    }

    // ---------------- 审批 ----------------

    /**
     * 批准。按级次流转：第 1 级批完转第 2 级，ADMIN 直接整单批并落地。
     */
    @Transactional
    public Row approve(long requestId, long reviewerId, boolean reviewerIsAdmin) {
        Row row = require(requestId);
        if (!PENDING.equals(row.status())) {
            throw new ConflictException("already_processed", "这条申请已被处理，刷新看看最新状态");
        }
        if (!reviewerIsAdmin && row.userId() == reviewerId) {
            throw new ForbiddenException("不能审批自己提交的申请，请由上级或系统管理员处理");
        }
        if (reviewerIsAdmin) {
            return finalizeApprove(row, reviewerId);
        }
        if (row.stage() == STAGE_LEAD) {
            if (!eligibleLead(row.userId(), reviewerId)) {
                throw new ForbiddenException(
                        "第 1 级只有申请人所在团队的负责人能审批，或由 ADMIN 直接批准");
            }
            int taken = jdbc.update("""
                    update approval_requests
                       set stage = 2, lead_reviewer_id = :reviewerId, lead_reviewed_at = now()
                     where id = :id and status = 'PENDING'
                    """, new MapSqlParameterSource(Map.of(
                    "id", requestId, "reviewerId", reviewerId)));
            if (taken == 0) {
                throw new ConflictException("already_processed", "这条申请已被处理，刷新看看最新状态");
            }
            return find(requestId, viewerOf(reviewerId, false)).orElseThrow();
        }
        if (!eligibleSysAdmin(row.module(), reviewerId)) {
            throw new ForbiddenException(row.module().isEmpty()
                    ? "该申请未归入任何模块，第 2 级只有 ADMIN 能审批"
                    : "第 2 级只有「" + row.module() + "」系统的管理员能审批，或由 ADMIN 直接批准");
        }
        return finalizeApprove(row, reviewerId);
    }

    /** 驳回。理由必填——没有理由的驳回对申请人毫无帮助（与两个 App 的文案同一口径）。 */
    @Transactional
    public Row reject(long requestId, long reviewerId, String note) {
        String reason = note == null ? "" : note.trim();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("驳回必须填理由");
        }
        if (reason.length() > 255) {
            throw new IllegalArgumentException("驳回理由最长 255 个字符");
        }
        Row row = require(requestId);
        if (!PENDING.equals(row.status())) {
            throw new ConflictException("already_processed", "这条申请已被处理，刷新看看最新状态");
        }
        if (row.userId() == reviewerId) {
            throw new ForbiddenException("不能审批自己提交的申请，请由上级或系统管理员处理");
        }
        int taken = jdbc.update("""
                update approval_requests
                   set status = 'REJECTED', reviewer_id = :reviewerId,
                       review_note = :note, reviewed_at = now()
                 where id = :id and status = 'PENDING'
                """, new MapSqlParameterSource(Map.of(
                "id", requestId, "reviewerId", reviewerId, "note", reason)));
        if (taken == 0) {
            throw new ConflictException("already_processed", "这条申请已被处理，刷新看看最新状态");
        }
        return find(requestId, viewerOf(reviewerId, false)).orElseThrow();
    }

    /**
     * 终审通过并<b>落地</b>：花名改 users.nickname，权限写直授覆盖行。
     *
     * <p>落在同一个事务里：先占住单子（{@code status='PENDING'} 条件更新）再写业务数据，
     * 两个人同时点「批准」只有一个能成，另一个拿到 409 而不是把直授写两遍。</p>
     */
    private Row finalizeApprove(Row row, long reviewerId) {
        int taken = jdbc.update("""
                update approval_requests
                   set status = 'APPROVED', reviewer_id = :reviewerId, reviewed_at = now()
                 where id = :id and status = 'PENDING'
                """, new MapSqlParameterSource(Map.of("id", row.id(), "reviewerId", reviewerId)));
        if (taken == 0) {
            throw new ConflictException("already_processed", "这条申请已被处理，刷新看看最新状态");
        }
        if (TYPE_NICKNAME.equals(row.type())) {
            jdbc.update("update users set nickname = :nickname where id = :userId",
                    new MapSqlParameterSource(Map.of(
                            "nickname", row.target(), "userId", row.userId())));
        } else {
            jdbc.update("""
                    insert into user_permissions (user_id, permission_code, effect, reviewer_id, note)
                    values (:userId, :code, 1, :reviewerId, '审批通过')
                    on duplicate key update effect = 1, reviewer_id = :reviewerId,
                                            note = '审批通过', updated_at = now()
                    """, new MapSqlParameterSource(Map.of(
                    "userId", row.userId(),
                    "code", row.target(),
                    "reviewerId", reviewerId)));
        }
        return find(row.id(), viewerOf(reviewerId, true)).orElseThrow();
    }

    private Row require(long requestId) {
        return jdbc.queryForObject(SELECT_ROW + " where r.id = :id",
                new MapSqlParameterSource(Map.of("id", requestId)),
                (rs, i) -> row(rs, NO_VIEWER));
    }

    // ---------------- 资格判定 ----------------

    /** 第 1 级：申请人所在团队里、**不是申请人自己**的负责人。 */
    private boolean eligibleLead(long applicantId, long reviewerId) {
        Integer n = jdbc.queryForObject("""
                select count(1)
                  from team_members tm
                  join team_members ld on ld.team_id = tm.team_id
                                       and ld.is_lead = 1
                                       and ld.user_id <> tm.user_id
                 where tm.user_id = :applicant and ld.user_id = :reviewer
                """, new MapSqlParameterSource(Map.of(
                "applicant", applicantId, "reviewer", reviewerId)), Integer.class);
        return n != null && n > 0;
    }

    /** 第 2 级：该模块登记的系统管理员。 */
    private boolean eligibleSysAdmin(String module, long reviewerId) {
        if (!StringUtils.hasText(module)) return false;
        Integer n = jdbc.queryForObject(
                "select count(1) from system_admins where module = :module and user_id = :reviewer",
                new MapSqlParameterSource(Map.of("module", module, "reviewer", reviewerId)),
                Integer.class);
        return n != null && n > 0;
    }

    private boolean hasOtherLead(long userId) {
        Integer n = jdbc.queryForObject("""
                select count(1)
                  from team_members tm
                  join team_members ld on ld.team_id = tm.team_id
                                       and ld.is_lead = 1
                                       and ld.user_id <> tm.user_id
                 where tm.user_id = :uid
                """, new MapSqlParameterSource("uid", userId), Integer.class);
        return n != null && n > 0;
    }

    /**
     * 死单预检：走到第 2 级只剩申请人自己 → 当场报错让他先补审批人。
     *
     * <p>判据：没有"非本人的团队负责人"，且该模块的其他系统管理员为 0、其他 ADMIN 为 0。
     * 留一张谁也推不动的 PENDING 是最坏的结果——它在审批台里躺着，点了没反应。</p>
     */
    private void assertNotSelfOnly(long userId, String module) {
        boolean hasLead = hasOtherLead(userId);
        if (hasLead) return;
        Integer otherSysAdmins = jdbc.queryForObject(
                "select count(1) from system_admins where module = :m and user_id <> :me",
                new MapSqlParameterSource(Map.of("m", module == null ? "" : module, "me", userId)),
                Integer.class);
        if (otherSysAdmins != null && otherSysAdmins > 0) return;
        Integer otherAdmins = jdbc.queryForObject(
                "select count(1) from user_roles ur join roles r on r.code = ur.role_code"
                        + " where r.code = 'ADMIN' and ur.user_id <> :me",
                new MapSqlParameterSource("me", userId), Integer.class);
        if (otherAdmins != null && otherAdmins > 0) return;
        throw new IllegalArgumentException(
                "没有能审批这条申请的人（既没有你的团队负责人，也没有该模块的其他系统管理员），"
                        + "请先让管理员把你纳入团队或把你设为该模块的系统管理员");
    }

    private boolean permissionExists(String code) {
        Integer n = jdbc.queryForObject(
                "select count(1) from permissions where code = :code",
                new MapSqlParameterSource("code", code), Integer.class);
        return n != null && n > 0;
    }

    private String moduleOf(String code) {
        try {
            return jdbc.queryForObject(
                    "select coalesce(module, '') from permissions where code = :code",
                    new MapSqlParameterSource("code", code), String.class);
        } catch (EmptyResultDataAccessException e) {
            return "";
        }
    }

    // ---------------- 查看者 ----------------

    /** 按用户建视角。{@code admin} 传 true 表示"ADMIN 且走整单批分支"。 */
    public Viewer viewerOf(long userId, boolean admin) {
        if (admin) {
            return new Viewer(userId, true, Set.of(), Set.of());
        }
        UserDirectory.Account account = users.findById(userId).orElse(null);
        boolean isAdmin = account != null && account.roles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r));
        if (isAdmin) {
            return new Viewer(userId, true, Set.of(), Set.of());
        }
        Set<Long> leadMembers = new LinkedHashSet<>(jdbc.queryForList("""
                select distinct tm.user_id
                  from team_members tm
                  join team_members ld on ld.team_id = tm.team_id
                                       and ld.is_lead = 1
                                       and ld.user_id <> tm.user_id
                 where ld.user_id = :me
                """, new MapSqlParameterSource("me", userId), Long.class));
        Set<String> modules = new LinkedHashSet<>(jdbc.queryForList(
                "select module from system_admins where user_id = :me",
                new MapSqlParameterSource("me", userId), String.class));
        return new Viewer(userId, false, leadMembers, modules);
    }

    /** 「我的申请」用：空 leadMembers，{@code canAct} 恒 false（自己不能批自己）。 */
    private Viewer myViewer(long userId) {
        return new Viewer(userId, false, Set.of(), Set.of());
    }
}
