package com.example.user;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 权限自助申请与移除（37 号迁移的两层模型 + 41 号两级审批）。
 *
 * <p>权限主干仍由角色发放（role_permissions），本服务只管用户级覆盖层：
 * <ul>
 *   <li><b>申请</b>（无此权限 → 提交 permission_requests 单 → 两级审批通过 →
 *       写 user_permissions effect=1 直授）——提权必须过审批，与花名变更同流程；</li>
 *   <li><b>删除</b>（已有 → 直接写 effect=0 拉黑，即时生效）——降权是自己放弃权限，
 *       没有可被利用的风险面，不设审批；但它压的是有效权限，所以要用覆盖而不是
 *       删 role_permissions（那是角色的资产，动它会影响所有同角色成员）。</li>
 * </ul>
 * 有效权限 = (角色 ∪ 团队继承 ∪ 直授) − 拉黑，收敛在
 * {@link PermissionService#resolve(long, List)} 末尾，菜单与接口校验共用同一份结果。</p>
 *
 * <p><b>两级串行审批（41 号）</b>：
 * <ul>
 *   <li>第 1 级 = 组织上一级 = 申请人所在团队的<b>直属负责人</b>；申请人没加入任何
 *       团队、或所在团队的负责人就是本人 → 提交时直接落第 2 级；</li>
 *   <li>第 2 级 = <b>系统管理员</b> = 该权限所属模块（permissions.module）在
 *       system_admins 里登记的人；模块没配管理员 → 只能等 ADMIN；</li>
 *   <li>ADMIN 不按级走：任意一级点批准 = 整单直接通过（硬兜底，避免点两次）；</li>
 *   <li><b>非 ADMIN 不能自批</b>（审批人 == 申请人一律拒绝）：第 1 级在提交时
 *       就跳级（申请人自己带队 → 直接落第 2 级），第 2 级由<b>其他</b>系统管理员
 *       接手；提交时若发现「能批这单的只有我自己」，当场拒绝而不是留下一张永远
 *       PENDING 的死单。<b>ADMIN 例外</b>——他本就是全量权限，自批不产生提权，
 *       沿用「任意一级点批准 = 整单通过」那条硬兜底。</li>
 * </ul>
 * 三道关各管一层：Controller 的 firstMissing 挡「你是不是负责人/系统管理员之一」
 * （resolve 给这两类人派生了 permission:review），本服务按单判「这一级你能不能批
 * （非 ADMIN 再加一条是不是申请人本人）」，状态推进/终审都带
 * {@code where status='PENDING'} 抢占兜并发。</p>
 *
 * <p>与 {@link NicknameRequestService} 同款的原子点：PENDING 判重靠应用层预检，
 * 批准/推进靠抢占（affected=0 即已被别人处理，回 409）。</p>
 */
@Service
@RequiredArgsConstructor
public class PermissionRequestService {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /** 第 1 级：组织上一级（申请人所在团队的负责人）。 */
    public static final int STAGE_LEAD = 1;
    /** 第 2 级：系统管理员（权限所属模块的 system_admins）。 */
    public static final int STAGE_SYSADMIN = 2;

    private final NamedParameterJdbcTemplate jdbc;
    private final PermissionService permissions;
    private final UserDirectory users;

    /** 我的权限全景：有效权限 + 覆盖行 + 申请单，一次拉全（权限点页签渲染用）。 */
    public record MyPermissions(List<String> effective,
                                List<String> grants,
                                List<String> revokes,
                                List<RequestRow> requests) {
    }

    /**
     * 申请单（我的视角与审批视角共用行结构，申请人信息 join 出来）。
     *
     * <p>stage / leadNames / sysAdminNames 描述两级链路；canAct 是<b>查看者</b>
     * 视角的「这一级轮到我批吗」——前端据此渲染按钮还是灰字提示，不自算任何权限。
     * 非 ADMIN 看自己的单恒 false（禁自批）；ADMIN 全量可见可批，自批放行。</p>
     */
    public record RequestRow(long id,
                             long userId,
                             String account,
                             String nickname,
                             String permissionCode,
                             String permissionName,
                             String module,
                             String note,
                             String status,
                             String reviewNote,
                             Long reviewerId,
                             String createdAt,
                             String reviewedAt,
                             int stage,
                             Long leadReviewerId,
                             String leadReviewedAt,
                             String leadNames,
                             String sysAdminNames,
                             boolean canAct) {
    }

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    /**
     * 审批视角的查看者（数据范围 + 按级授权共用一份事实）：
     * ADMIN 看全量；团队负责人看直属成员的第 1 级单与自己处理过的单；
     * 系统管理员看所辖模块的第 2 级单与自己终审过的单。
     */
    private record Viewer(long id, boolean admin, Set<Long> leadMembers, Set<String> modules) {
    }

    /** 回读/无审批上下文（get、pendingOf）：canAct 恒 false，调用方只看状态字段。 */
    private static final Viewer NO_VIEWER = new Viewer(0L, false, Set.of(), Set.of());

    private static final String SELECT_ROW = """
            select r.id, r.user_id, u.account, u.nickname,
                   r.permission_code, coalesce(p.name, r.permission_code) as permission_name,
                   coalesce(p.module, '') as module,
                   r.note, r.status, r.review_note, r.reviewer_id, r.created_at, r.reviewed_at,
                   r.stage, r.lead_reviewer_id, r.lead_reviewed_at,
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
                     where sa.module = coalesce(p.module, '')) as sysadmin_names
              from permission_requests r
              join users u on u.id = r.user_id
              left join permissions p on p.code = r.permission_code
            """;

    /** 计数不带 SELECT 里那两段相关子查询；范围谓词会用到 p.module，所以 join 保留。 */
    private static final String COUNT_FROM = """
            from permission_requests r
            left join permissions p on p.code = r.permission_code
            """;

    /**
     * 非 ADMIN 的数据范围：待我按级处理的（第 1 级轮到我 / 第 2 级轮到我）。
     * 两个分支都带 status='PENDING'——处理完的单子靠下面的历史条件回来。
     * 第 2 级分支排除自己的申请（r.user_id <> :me）：自己的单不能自批，
     * 留在「待我处理」里只会变成点了没反应的死按钮；看进度回概览的「我的申请」。
     * 第 1 级分支的 ld.user_id <> tm.user_id 已隐含同样排除（:me 即 ld）。
     */
    private static final String PENDING_SCOPE = """
            ((r.status = 'PENDING' and r.stage = 1 and exists (
                  select 1 from team_members tm
                  join team_members ld on ld.team_id = tm.team_id
                                       and ld.is_lead = 1
                                       and ld.user_id <> tm.user_id
                 where tm.user_id = r.user_id and ld.user_id = :me))
             or (r.status = 'PENDING' and r.stage = 2
                 and coalesce(p.module, '') in (:sysModules)
                 and r.user_id <> :me))
            """;

    /** 列表范围 = 待我处理的 ∪ 我处理过的（第 1 级我批的、终审我批的都回得来）。 */
    private static final String SCOPE_WITH_HISTORY =
            "(" + PENDING_SCOPE + " or r.lead_reviewer_id = :me or r.reviewer_id = :me)";

    // ---------------- 本人自助 ----------------

    /** 全部权限点（目录）。就是 permissions 全表，接口不要求任何权限点——登录即可看。 */
    public List<PermissionService.PermissionRow> catalog() {
        return permissions.listPermissions();
    }

    /**
     * 我的权限全景。effective 走 resolve（与 /users/me 的 permissionCodes 同源），
     * 这里再给一份是为了让页签不必串行拉两个接口。
     */
    public MyPermissions my(long userId) {
        UserDirectory.Account account = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在或已禁用: " + userId));

        List<String> effective = permissions.resolve(userId, account.roles());

        List<String> grants = new ArrayList<>();
        List<String> revokes = new ArrayList<>();
        jdbc.query("select permission_code, effect from user_permissions where user_id = :id",
                new MapSqlParameterSource("id", userId), rs -> {
                    if (rs.getInt("effect") == 1) {
                        grants.add(rs.getString("permission_code"));
                    } else {
                        revokes.add(rs.getString("permission_code"));
                    }
                });

        // 申请人也可能是某个系统的管理员（第 2 级「别人」里有他）。这里固定按
        // 非管理员视角算——概览里自己的单恒 canAct=false，本就不该给按钮；
        // 审批中心里 ADMIN 自批放行，那边传 admin=true 走 viewer 的 admin 分支
        Viewer v = viewer(userId, false);
        List<RequestRow> requests = jdbc.query(
                SELECT_ROW + " where r.user_id = :userId order by r.id desc limit 50",
                new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> row(rs, v));

        return new MyPermissions(effective, grants, revokes, requests);
    }

    /**
     * 提交申请。四道预检都挡在写入前，给前端可操作的中文文案：
     * 目录里没有的码（防构造请求）、已有效拥有（该点「删除」而不是申请）、
     * 已有 PENDING（一人一权一单在途）、以及「能批这单的只有我自己」。
     *
     * <p>起始级在此刻定格：有「非本人的直属负责人」→ 第 1 级，否则直接第 2 级。
     * 之后团队调整不影响在途单的级次，但每级审批时会按<b>当时</b>的负责人/系统
     * 管理员验资格——新上任的负责人能批在途的单。</p>
     *
     * <p>最后一道拦的是自批死单：只要「同模块其他系统管理员 = 0、其他 ADMIN = 0」，
     * 单子无论从第几级起步，走到第 2 级都只剩申请人自己——而这永远不允许，
     * 与其留一张谁也推不动的 PENDING，不如当场报错让他先去补审批人。
     * 起步级不豁免这道检查：第 1 级能被负责人推进，卡的还是第 2 级的终审。
     * <b>ADMIN 本人提交则放行</b>：他能自批，单子不会卡住。</p>
     */
    @Transactional
    public RequestRow submit(long userId, String rawCode, String rawNote) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("权限点不能为空");
        }
        if (!permissionExists(code)) {
            throw new ConflictException("unknown_permission", "权限点不存在: " + code);
        }

        UserDirectory.Account account = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在或已禁用: " + userId));
        if (permissions.resolve(userId, account.roles()).contains(code)) {
            throw new ConflictException("already_has", "你已经拥有该权限，无需申请");
        }
        if (pendingOf(userId, code).isPresent()) {
            throw new ConflictException("already_pending", "该权限的申请正在审批中，请耐心等待");
        }

        String note = rawNote == null ? "" : rawNote.trim();
        int stage = hasOtherLead(userId) ? STAGE_LEAD : STAGE_SYSADMIN;
        boolean adminApplicant = account.roles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r));
        if (!adminApplicant && !hasOtherReviewer(userId, code)) {
            throw new ConflictException("no_other_reviewer",
                    "该权限所属模块的系统管理员只有你本人，且没有其他 ADMIN 能接手，"
                            + "提交后将无人可批；请先在「系统管理员」页签为该模块增加一位审批人");
        }
        jdbc.update("""
                insert into permission_requests (user_id, permission_code, note, status, stage)
                values (:userId, :code, :note, 'PENDING', :stage)
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("code", code)
                .addValue("note", note.isEmpty() ? null : note)
                .addValue("stage", stage));

        return pendingOf(userId, code)
                .orElseThrow(() -> new IllegalStateException("申请写入后读不回来"));
    }

    /**
     * 移除我的这条权限（即时生效）。写的是覆盖行而不是删角色授权——角色是大家的，
     * 拉黑只作用于本人：upsert effect=0，之后 resolve 把它从有效权限里减掉。
     */
    public Map<String, Object> revoke(long userId, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (!permissionExists(code)) {
            throw new ConflictException("unknown_permission", "权限点不存在: " + code);
        }
        UserDirectory.Account account = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在或已禁用: " + userId));
        if (!permissions.resolve(userId, account.roles()).contains(code)) {
            throw new ConflictException("not_granted", "你本来就没有这个权限，无需移除");
        }
        jdbc.update("""
                insert into user_permissions (user_id, permission_code, effect, note)
                values (:userId, :code, 0, '本人移除')
                on duplicate key update effect = 0, reviewer_id = null,
                                        note = '本人移除', updated_at = now()
                """, new MapSqlParameterSource(Map.of("userId", userId, "code", code)));
        return Map.of("code", code, "effect", 0);
    }

    private Optional<RequestRow> pendingOf(long userId, String code) {
        return jdbc.query(SELECT_ROW + """
                         where r.user_id = :userId and r.permission_code = :code
                           and r.status = 'PENDING'
                         limit 1
                        """, new MapSqlParameterSource(Map.of("userId", userId, "code", code)),
                (rs, rowNum) -> row(rs, NO_VIEWER)).stream().findFirst();
    }

    private boolean permissionExists(String code) {
        Integer n = jdbc.queryForObject(
                "select count(1) from permissions where code = :code",
                new MapSqlParameterSource("code", code), Integer.class);
        return n != null && n > 0;
    }

    /**
     * 有没有「非本人的直属负责人」——申请人所在团队里 is_lead 且不是本人的人。
     * 没有（没团队 / 负责人就是自己）就跳过第 1 级，直接进系统管理员级。
     */
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
     * 第 2 级除了申请人自己，还有没有别人能批：同模块登记的<b>其他</b>系统
     * 管理员 ∪ <b>其他</b> ADMIN。一个都没有 = 提交即死单（自批永远不允许），
     * 由 {@link #submit} 在写入前拦下——与单子从第几级起步无关，卡点永远在
     * 第 2 级终审。模块没归入任何 module（''）时 system_admins 天然匹配不上，
     * 等价于「只剩其他 ADMIN」，与审批时的「第 2 级只有 ADMIN 能批」口径一致。
     */
    private boolean hasOtherReviewer(long userId, String code) {
        String module = jdbc.queryForObject(
                "select coalesce(module, '') from permissions where code = :code",
                new MapSqlParameterSource("code", code), String.class);
        Integer others = jdbc.queryForObject("""
                select (select count(1) from system_admins
                         where module = :module and user_id <> :me)
                     + (select count(1) from user_roles
                         where role_code = 'ADMIN' and user_id <> :me)
                """, new MapSqlParameterSource(Map.of(
                "module", module == null ? "" : module, "me", userId)), Integer.class);
        return others != null && others > 0;
    }

    // ---------------- 管理员审批 ----------------

    /**
     * 待审批列表（按查看者圈数据范围）。
     *
     * <p>ADMIN = 全量；其他人 = 待我按级处理的 ∪ 我处理过的。范围在 SQL 里做，
     * 分页与 total 才是同一份口径。</p>
     */
    public PageResult<RequestRow> search(String status, int page, int size,
                                         long viewerId, boolean admin) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Viewer v = viewer(viewerId, admin);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", safeSize)
                .addValue("offset", (long) (safePage - 1) * safeSize)
                .addValue("me", viewerId);

        List<String> conditions = new ArrayList<>();
        if (!admin) {
            params.addValue("sysModules", moduleParam(v));
            conditions.add(SCOPE_WITH_HISTORY);
        }
        if (StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status.trim())) {
            conditions.add("r.status = :status");
            params.addValue("status", status.trim().toUpperCase());
        }
        String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);

        List<RequestRow> list = jdbc.query(
                SELECT_ROW + where + " order by r.id desc limit :limit offset :offset",
                params, (rs, rowNum) -> row(rs, v));
        Long total = jdbc.queryForObject(
                "select count(1)" + COUNT_FROM + where, params, Long.class);
        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    /** 待我处理的条数（角标）：ADMIN 数全量 PENDING，其他人只数轮到自己那级的。 */
    public long pendingCount(long viewerId, boolean admin) {
        Viewer v = viewer(viewerId, admin);
        MapSqlParameterSource params = new MapSqlParameterSource("me", viewerId);
        String where = " where r.status = 'PENDING'";
        if (!admin) {
            params.addValue("sysModules", moduleParam(v));
            where += " and " + PENDING_SCOPE;
        }
        Long n = jdbc.queryForObject(
                "select count(1)" + COUNT_FROM + where, params, Long.class);
        return n == null ? 0 : n;
    }

    /**
     * 批准。两级串行 + ADMIN 一并批：
     * <ul>
     *   <li>ADMIN：任意一级点批准 = 整单通过（跳过剩余级，状态直接 APPROVED），
     *       <b>且不受禁自批约束</b>——他本就是全量权限，自批不产生提权；</li>
     *   <li>非 ADMIN：申请人自己一律拒绝（禁自批），第 1 级还需验「申请人所在
     *       团队的负责人且非本人」→ stage 推进到 2，留痕 lead_reviewer_id /
     *       lead_reviewed_at，单子转给系统管理员；</li>
     *   <li>第 2 级：验「该模块登记在册的系统管理员」→ 终审，抢占状态后写直授。</li>
     * </ul>
     */
    @Transactional
    public RequestRow approve(long requestId, long reviewerId, boolean reviewerIsAdmin) {
        RequestRow row = get(requestId);
        if (!PENDING.equals(row.status())) {
            throw new ConflictException("already_processed", "这条申请已被处理，刷新看看最新状态");
        }
        if (!reviewerIsAdmin && row.userId() == reviewerId) {
            throw new ForbiddenException(
                    "不能审批自己提交的申请，请由其他系统管理员或 ADMIN 处理");
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
                    update permission_requests
                       set stage = 2, lead_reviewer_id = :reviewerId, lead_reviewed_at = now()
                     where id = :id and status = 'PENDING'
                    """, new MapSqlParameterSource(Map.of(
                    "id", requestId, "reviewerId", reviewerId)));
            if (taken == 0) {
                throw new ConflictException("already_processed", "这条申请已被处理，刷新看看最新状态");
            }
            return get(requestId);
        }
        if (!eligibleSysAdmin(row.module(), reviewerId)) {
            throw new ForbiddenException(row.module().isEmpty()
                    ? "该权限点未归入任何模块，第 2 级只有 ADMIN 能审批"
                    : "第 2 级只有「" + row.module() + "」系统的管理员能审批，或由 ADMIN 直接批准");
        }
        return finalizeApprove(row, reviewerId);
    }

    /** 驳回：按当前级验资格后整单驳回（不级联、不打回重批），理由必填由 Controller 校验。ADMIN 不受禁自批约束。 */
    @Transactional
    public RequestRow reject(long requestId, long reviewerId, String note, boolean reviewerIsAdmin) {
        RequestRow row = get(requestId);
        if (!PENDING.equals(row.status())) {
            throw new ConflictException("already_processed", "这条申请已被处理，刷新看看最新状态");
        }
        if (!reviewerIsAdmin && row.userId() == reviewerId) {
            throw new ForbiddenException(
                    "不能审批自己提交的申请，请由其他系统管理员或 ADMIN 处理");
        }
        if (!reviewerIsAdmin) {
            boolean eligible = row.stage() == STAGE_LEAD
                    ? eligibleLead(row.userId(), reviewerId)
                    : eligibleSysAdmin(row.module(), reviewerId);
            if (!eligible) {
                throw new ForbiddenException(row.stage() == STAGE_LEAD
                        ? "第 1 级只有申请人所在团队的负责人能处理，或由 ADMIN 驳回"
                        : "第 2 级只有该系统的管理员能处理，或由 ADMIN 驳回");
            }
        }
        int taken = jdbc.update("""
                update permission_requests
                   set status = 'REJECTED', reviewer_id = :reviewerId,
                       review_note = :note, reviewed_at = now()
                 where id = :id and status = 'PENDING'
                """, new MapSqlParameterSource(Map.of(
                "id", requestId, "reviewerId", reviewerId, "note", note)));
        if (taken == 0) {
            throw new ConflictException("already_processed", "这条申请已被处理，刷新看看最新状态");
        }
        return get(requestId);
    }

    /**
     * 终审（ADMIN 与第 2 级共用）：抢占状态 → 写直授，同事务。
     *
     * <p>顺序刻意是抢占在前——抢占失败（已被处理）时不碰覆盖表；
     * 覆盖表 upsert 幂等，重复批准同一单不会产生第二行。</p>
     */
    private RequestRow finalizeApprove(RequestRow row, long reviewerId) {
        int taken = jdbc.update("""
                update permission_requests
                   set status = 'APPROVED', reviewer_id = :reviewerId, reviewed_at = now()
                 where id = :id and status = 'PENDING'
                """, new MapSqlParameterSource(Map.of(
                "id", row.id(), "reviewerId", reviewerId)));
        if (taken == 0) {
            throw new ConflictException("already_processed", "这条申请已被处理，刷新看看最新状态");
        }
        jdbc.update("""
                insert into user_permissions (user_id, permission_code, effect, reviewer_id, note)
                values (:userId, :code, 1, :reviewerId, '审批通过')
                on duplicate key update effect = 1, reviewer_id = :reviewerId,
                                        note = '审批通过', updated_at = now()
                """, new MapSqlParameterSource(Map.of(
                "userId", row.userId(), "code", row.permissionCode(), "reviewerId", reviewerId)));
        return get(row.id());
    }

    /** 第 1 级资格：申请人所在团队的负责人之一，且不是申请人本人（不许自批）。 */
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

    /** 第 2 级资格：该模块登记在册的系统管理员（模块没配管理员时只有 ADMIN 能批）。 */
    private boolean eligibleSysAdmin(String module, long reviewerId) {
        Integer n = jdbc.queryForObject(
                "select count(1) from system_admins where module = :module and user_id = :reviewer",
                new MapSqlParameterSource(Map.of(
                        "module", module, "reviewer", reviewerId)), Integer.class);
        return n != null && n > 0;
    }

    public RequestRow get(long id) {
        return jdbc.query(SELECT_ROW + " where r.id = :id",
                new MapSqlParameterSource("id", id), (rs, rowNum) -> row(rs, NO_VIEWER))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("申请不存在: " + id));
    }

    // ---------------- 查看者上下文 ----------------

    /**
     * 载入审批视角的查看者。带团队的人同时拿到「我能批谁的第 1 级」（我带队的团队里
     * 除我之外的成员）与「我能批哪些模块的第 2 级」（system_admins 登记的模块）——
     * 一次查库，列表 canAct 与范围谓词共用。
     */
    private Viewer viewer(long userId, boolean admin) {
        if (admin) {
            return new Viewer(userId, true, Set.of(), Set.of());
        }
        Set<Long> leadMembers = new HashSet<>(jdbc.queryForList("""
                select distinct tm.user_id
                  from team_members tm
                  join team_members ld on ld.team_id = tm.team_id
                                       and ld.is_lead = 1
                                       and ld.user_id <> tm.user_id
                 where ld.user_id = :me
                """, new MapSqlParameterSource("me", userId), Long.class));
        Set<String> modules = new HashSet<>(jdbc.queryForList(
                "select module from system_admins where user_id = :me",
                new MapSqlParameterSource("me", userId), String.class));
        return new Viewer(userId, false, leadMembers, modules);
    }

    /** IN (:sysModules) 的占位：一个模块都没配时给永不命中的哨兵，避免空 IN 语法错。 */
    private static List<String> moduleParam(Viewer v) {
        return v.modules().isEmpty() ? List.of("__none__") : List.copyOf(v.modules());
    }

    private RequestRow row(java.sql.ResultSet rs, Viewer v) throws java.sql.SQLException {
        // wasNull 只对「上一次 getXxx」有效，必须取完 reviewer_id / lead_reviewer_id
        // 立刻判，否则中间再读几个字符串，wasNull 就变成那些字段的了
        long reviewerRaw = rs.getLong("reviewer_id");
        Long reviewer = rs.wasNull() ? null : reviewerRaw;
        long leadRaw = rs.getLong("lead_reviewer_id");
        Long leadReviewer = rs.wasNull() ? null : leadRaw;
        String created = String.valueOf(rs.getTimestamp("created_at"));
        java.sql.Timestamp reviewed = rs.getTimestamp("reviewed_at");
        java.sql.Timestamp leadReviewed = rs.getTimestamp("lead_reviewed_at");
        String status = rs.getString("status");
        int stage = rs.getInt("stage");
        long applicant = rs.getLong("user_id");
        String module = rs.getString("module");
        // applicant != v.id()：非 ADMIN 不能自批，canAct 恒 false（ADMIN 走 v.admin()
        // 分支先短路，全量权限下自批不算提权）。NO_VIEWER 的 id=0 不命中任何申请人，
        // get/pendingOf 的回读不受影响
        boolean canAct = PENDING.equals(status)
                && (v.admin()
                        || (applicant != v.id()
                                && (stage == STAGE_LEAD
                                        ? v.leadMembers().contains(applicant)
                                        : v.modules().contains(module))));
        return new RequestRow(
                rs.getLong("id"),
                applicant,
                rs.getString("account"),
                rs.getString("nickname"),
                rs.getString("permission_code"),
                rs.getString("permission_name"),
                module,
                Optional.ofNullable(rs.getString("note")).orElse(""),
                status,
                Optional.ofNullable(rs.getString("review_note")).orElse(""),
                reviewer,
                created,
                reviewed == null ? "" : String.valueOf(reviewed),
                stage,
                leadReviewer,
                leadReviewed == null ? "" : String.valueOf(leadReviewed),
                Optional.ofNullable(rs.getString("lead_names")).orElse(""),
                Optional.ofNullable(rs.getString("sysadmin_names")).orElse(""),
                canAct);
    }
}
