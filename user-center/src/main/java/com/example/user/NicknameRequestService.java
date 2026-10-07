package com.example.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 花名变更审批。
 *
 * <p>花名是全局唯一的身份标识——通讯录、看板、CRM 归属、工作台统计都认它。
 * 改一次等于换一个对外身份，所以「我的身份」不再直接改，而是：
 * 提交申请（PENDING）→ 管理员在「审批中心」批准 → 才真正写进 users.nickname。</p>
 *
 * <p>两个必须原子的点，都靠 SQL 而不是"先查后改"：
 * <ul>
 *   <li>同一时刻只能有一个待审批申请 → 提交前查 PENDING，真正兜底靠应用层校验 + 单事务；</li>
 *   <li>同一申请不能被两个管理员同时处理 → 批准时用 {@code where status='PENDING'}
 *       抢占，affected=0 即说明已被处理。</li>
 * </ul>
 * 批准的顺序也刻意是「先抢占 status，再改花名」：花名被别人抢先占用时抛
 * ConflictException，整个事务回滚，申请仍停在 PENDING，管理员换个时间再批即可。</p>
 *
 * <p><b>行级范围与资格（与权限申请同口径）</b>：花名是单级审批，审批人 =
 * 申请人所在团队的<b>非本人</b>负责人 ∪ ADMIN。列表与待办角标都按查看者圈
 * 范围（ADMIN 全量，其他人 = 下属的在途单 ∪ 我处理过的历史单），批准/驳回在
 * 抢占前先验资格并对<b>非 ADMIN</b>禁自批——nickname:review 挂在 USER 矩阵上
 * 人人持有，入口权限只决定进得了页签，不给全量视野；ADMIN 已是全量权限，
 * 自批不产生提权，放行。</p>
 */
@Service
@RequiredArgsConstructor
public class NicknameRequestService {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private static final String SELECT_ROW = """
            select r.id, r.user_id, u.account, u.nickname, r.old_nickname, r.new_nickname,
                   r.status, r.reviewer_id, r.review_note, r.created_at, r.reviewed_at
              from nickname_change_requests r
              join users u on u.id = r.user_id
            """;

    /**
     * 行级范围（非 ADMIN）：申请人所在团队的<b>非本人</b>负责人的在途单
     * ∪ 我处理过的历史单。与 PermissionRequestService 的 PENDING_SCOPE
     * 第 1 级分支同一 SQL 形状（直接团队 + is_lead + 负责人≠申请人）；
     * 申请人自己的单不在此内——看进度走「我的身份」的 myLatest。
     */
    private static final String SCOPE = """
            ((r.status = 'PENDING' and exists (
                  select 1 from team_members tm
                  join team_members ld on ld.team_id = tm.team_id
                                       and ld.is_lead = 1
                                       and ld.user_id <> tm.user_id
                 where tm.user_id = r.user_id and ld.user_id = :me))
             or r.reviewer_id = :me)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final UserDirectory users;

    /** 审批中心/我的申请共用的一行：申请人信息 + 申请内容 + 审批结果。 */
    public record RequestRow(long id,
                             long userId,
                             String account,
                             /** 申请人当前花名（审批通过前一直显示它） */
                             String nickname,
                             String oldNickname,
                             String newNickname,
                             String status,
                             Long reviewerId,
                             String reviewNote,
                             String createdAt,
                             String reviewedAt) {
    }

    public record PageResult<T>(List<T> list, long total, int page, int size) {
    }

    /**
     * 提交申请。
     *
     * <p>唯一性在这里只能做友好预检（"我查这一刻没人用"），真正的兜底是批准时再查一次
     * 加上 uk_nickname 索引——从提交到审批之间随时可能有新人注册占掉这个名字。</p>
     */
    public RequestRow submit(long userId, String rawNickname) {
        String nickname = rawNickname == null ? "" : rawNickname.trim();
        if (nickname.length() < 2 || nickname.length() > 32) {
            throw new IllegalArgumentException("花名长度 2-32 字");
        }
        UserDirectory.Account account = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在或已禁用: " + userId));

        if (nickname.equals(account.nickname())) {
            throw new ConflictException("same_nickname", "新花名与当前花名相同，无需申请");
        }
        if (users.existsByNickname(nickname)) {
            throw new ConflictException("nickname_exists", "这个花名已被占用，换一个试试");
        }
        if (pendingOf(userId).isPresent()) {
            throw new ConflictException("request_exists", "你已有一条待审批的申请，请等待审批结果");
        }

        jdbc.update("""
                insert into nickname_change_requests (user_id, old_nickname, new_nickname, status)
                values (:userId, :oldNickname, :newNickname, 'PENDING')
                """, new MapSqlParameterSource(Map.of(
                "userId", userId,
                "oldNickname", account.nickname(),
                "newNickname", nickname)));

        return myLatest(userId)
                .orElseThrow(() -> new IllegalStateException("申请写入后读不回来"));
    }

    /** 我的在途申请（提交前判重用）。 */
    public Optional<RequestRow> pendingOf(long userId) {
        return queryOne("""
                select r.id, r.user_id, u.account, u.nickname, r.old_nickname, r.new_nickname,
                       r.status, r.reviewer_id, r.review_note, r.created_at, r.reviewed_at
                  from nickname_change_requests r
                  join users u on u.id = r.user_id
                 where r.user_id = :userId and r.status = 'PENDING'
                 limit 1
                """, new MapSqlParameterSource("userId", userId));
    }

    /** 我的最新一条申请（不管状态），「我的身份」页展示进度用。 */
    public Optional<RequestRow> myLatest(long userId) {
        return queryOne("""
                select r.id, r.user_id, u.account, u.nickname, r.old_nickname, r.new_nickname,
                       r.status, r.reviewer_id, r.review_note, r.created_at, r.reviewed_at
                  from nickname_change_requests r
                  join users u on u.id = r.user_id
                 where r.user_id = :userId
                 order by r.created_at desc, r.id desc
                 limit 1
                """, new MapSqlParameterSource("userId", userId));
    }

    /** 我的全部申请历史，供「审批」页展示完整记录。 */
    public PageResult<RequestRow> myList(long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", safeSize)
                .addValue("offset", (safePage - 1) * safeSize);

        Long total = jdbc.queryForObject(
                "select count(1) from nickname_change_requests r where r.user_id = :userId",
                params, Long.class);

        List<RequestRow> list = jdbc.query(SELECT_ROW + """
                         where r.user_id = :userId
                         order by r.created_at desc, r.id desc
                         limit :limit offset :offset
                        """, params, (rs, i) -> new RequestRow(
                rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("account"), rs.getString("nickname"),
                rs.getString("old_nickname"), rs.getString("new_nickname"),
                rs.getString("status"),
                (Long) rs.getObject("reviewer_id"),
                rs.getString("review_note"),
                rs.getString("created_at"), rs.getString("reviewed_at")));

        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    /**
     * 审批中心列表。status 传空表示全部；待审批的排在最前、先提的先审。
     * 数据范围按查看者圈（ADMIN 全量，其他人在 {@link #SCOPE} 内），
     * 分页与 total 同一份口径——与权限申请的 search 同款写法。
     */
    public PageResult<RequestRow> list(String status, int page, int size,
                                       long viewerId, boolean admin) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", safeSize)
                .addValue("offset", (safePage - 1) * safeSize);

        List<String> conditions = new ArrayList<>();
        if (!admin) {
            params.addValue("me", viewerId);
            conditions.add(SCOPE);
        }
        if (StringUtils.hasText(status)) {
            conditions.add("r.status = :status");
            params.addValue("status", status.trim().toUpperCase());
        }
        String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);

        Long total = jdbc.queryForObject(
                "select count(1) from nickname_change_requests r" + where,
                params, Long.class);

        List<RequestRow> list = jdbc.query(SELECT_ROW + where + """
                         order by case r.status when 'PENDING' then 0 else 1 end,
                                  r.created_at desc, r.id desc
                         limit :limit offset :offset
                        """, params, (rs, i) -> new RequestRow(
                rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("account"), rs.getString("nickname"),
                rs.getString("old_nickname"), rs.getString("new_nickname"),
                rs.getString("status"),
                (Long) rs.getObject("reviewer_id"),
                rs.getString("review_note"),
                rs.getString("created_at"), rs.getString("reviewed_at")));

        return new PageResult<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    /** 待审批条数（角标）：ADMIN 数全量，其他人只数自己范围内的在途单。 */
    public long pendingCount(long viewerId, boolean admin) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = " where r.status = 'PENDING'";
        if (!admin) {
            params.addValue("me", viewerId);
            where += " and " + SCOPE;
        }
        Long n = jdbc.queryForObject(
                "select count(1) from nickname_change_requests r" + where,
                params, Long.class);
        return n == null ? 0 : n;
    }

    /**
     * 批准：真正把花名写进 users。
     *
     * <p>先过 {@link #requireCanReview}（状态 → 禁自批 → 非本人负责人或 ADMIN），
     * 再用 {@code where status='PENDING'} 抢占审批权（并发的第二个审批人拿到 0 行），
     * 最后改花名。两步在同一事务里，花名被占则整体回滚，申请退回可重试状态。</p>
     */
    @Transactional
    public RequestRow approve(long id, long reviewerId, boolean reviewerIsAdmin) {
        RequestRow row = findById(id);
        requireCanReview(row, reviewerId, reviewerIsAdmin);

        // 抢占：只有仍处 PENDING 的申请能被改判
        int claimed = jdbc.update("""
                update nickname_change_requests
                   set status = 'APPROVED', reviewer_id = :reviewerId, reviewed_at = now()
                 where id = :id and status = 'PENDING'
                """, new MapSqlParameterSource(Map.of("id", id, "reviewerId", reviewerId)));
        if (claimed == 0) {
            throw new ConflictException("already_reviewed",
                    "该申请已被处理（当前状态: " + row.status() + "）");
        }

        // 提交到审批之间可能有新人注册占了这个名字，这里才是唯一性的真正把关点
        if (users.existsByNickname(row.newNickname())) {
            throw new ConflictException("nickname_exists",
                    "「" + row.newNickname() + "」已被其他用户占用，无法批准，请让申请人重新提交");
        }
        if (!users.updateNickname(row.userId(), row.newNickname())) {
            throw new ConflictException("nickname_exists",
                    "「" + row.newNickname() + "」写入失败，可能已被占用，请重试");
        }
        return findById(id);
    }

    /** 驳回。理由必填——申请人需要知道下次该改什么。资格校验与批准同一道。 */
    @Transactional
    public RequestRow reject(long id, long reviewerId, String rawNote, boolean reviewerIsAdmin) {
        String note = rawNote == null ? "" : rawNote.trim();
        if (note.length() < 2 || note.length() > 255) {
            throw new IllegalArgumentException("驳回理由长度 2-255 字");
        }
        RequestRow row = findById(id); // 不存在先 404/400，避免把"没有这条"报成"已被处理"
        requireCanReview(row, reviewerId, reviewerIsAdmin);

        int claimed = jdbc.update("""
                update nickname_change_requests
                   set status = 'REJECTED', reviewer_id = :reviewerId,
                       review_note = :note, reviewed_at = now()
                 where id = :id and status = 'PENDING'
                """, new MapSqlParameterSource(Map.of(
                "id", id, "reviewerId", reviewerId, "note", note)));
        if (claimed == 0) {
            throw new ConflictException("already_reviewed", "该申请已被处理");
        }
        return findById(id);
    }

    /**
     * 资格校验（批准/驳回共用，都在抢占之前）：①状态 ②非 ADMIN 禁自批
     * ③非 ADMIN 还必须是申请人所在团队的非本人负责人——与权限申请第 1 级
     * 同口径。<b>ADMIN 全量放行</b>：他已是全量权限，自批不产生提权。
     */
    private void requireCanReview(RequestRow row, long reviewerId, boolean reviewerIsAdmin) {
        if (!PENDING.equals(row.status())) {
            throw new ConflictException("already_reviewed",
                    "该申请已被处理（当前状态: " + row.status() + "）");
        }
        if (!reviewerIsAdmin && row.userId() == reviewerId) {
            throw new ForbiddenException("不能审批自己提交的申请，请由其他负责人或 ADMIN 处理");
        }
        if (!reviewerIsAdmin && !eligibleLead(row.userId(), reviewerId)) {
            throw new ForbiddenException(
                    "只有申请人所在团队的负责人能审批花名变更，或由 ADMIN 直接处理");
        }
    }

    /** 资格：申请人所在团队的负责人之一，且不是申请人本人（不许自批）。 */
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

    private RequestRow findById(long id) {
        return queryOne(SELECT_ROW + " where r.id = :id",
                        new MapSqlParameterSource("id", id))
                .orElseThrow(() -> new IllegalArgumentException("申请不存在: " + id));
    }

    private Optional<RequestRow> queryOne(String sql, MapSqlParameterSource params) {
        List<RequestRow> rows = jdbc.query(sql, params, (rs, i) -> new RequestRow(
                rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("account"), rs.getString("nickname"),
                rs.getString("old_nickname"), rs.getString("new_nickname"),
                rs.getString("status"),
                (Long) rs.getObject("reviewer_id"),
                rs.getString("review_note"),
                rs.getString("created_at"), rs.getString("reviewed_at")));
        return rows.stream().findFirst();
    }
}
