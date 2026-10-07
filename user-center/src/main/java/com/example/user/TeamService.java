package com.example.user;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 团队（可嵌套）。
 *
 * <p><b>团队不参与授权本身</b>——权限点仍然只勾给角色，团队是把「谁和谁一组、谁带队、
 * 谁归谁管」与「这一组实际拥有哪些权限」摆在一起给人看的派生视图。之所以只做展示层，
 * 是因为真正的授权入口只有角色矩阵一处，多一个入口就多一处可能与它打架。</p>
 *
 * <p>层级（{@code teams.parent_id}）带来的两条递归语义：</p>
 * <ul>
 *   <li><b>团队有效权限 = 该节点 + 整棵子树的在职成员「自身」权限的并集</b>
 *       ——父团队自然涵盖下面所有小队；</li>
 *   <li><b>负责人有效权限 = 自身权限 ∪ 所带队节点的子树并集</b>，
 *       即父团队的 lead 自动拥有下面所有人的权限。</li>
 * </ul>
 *
 * <p>四条不变量，全部在本类里维护：</p>
 * <ul>
 *   <li><b>负责人一定是该团队的成员</b>——否则「lead 继承全队权限」这句话没有主体；</li>
 *   <li><b>同一团队最多一个负责人</b>——设新负责人时把旧的清掉，而不是允许多个后
 *       在读取时「取第一个」，后者会让继承结果依赖行序；</li>
 *   <li><b>只统计在职成员</b>——禁用账号登不进来，让它继续给上级凭空供权限
 *       等于绕过了启停；</li>
 *   <li><b>父子关系不能成环</b>——写入时校验上级不是自己的下级，读取时再用 visited
 *       集合兜一道，脏数据最多让该子树少算权限，不会把请求卡死。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private static final String ADMIN_ROLE = "ADMIN";

    private final NamedParameterJdbcTemplate jdbc;
    private final PermissionService permissions;

    /** 成员（团队直接成员，不含下级团队的人）。 */
    public record MemberRow(long userId,
                            String account,
                            String nickname,
                            int status,
                            boolean lead,
                            String joinedAt,
                            // 成员挂的角色（只读展示用；角色本身在「角色管理」页维护）
                            List<String> roles,
                            // 成员自身（角色带来的）权限，不含任何团队继承
                            List<String> ownPermissions,
                            // 成员的有效权限 = 自身 ∪ 其所带队节点的子树并集；
                            // 不带队时与 ownPermissions 相同
                            List<String> effectivePermissions) {
    }

    public record TeamRow(long id,
                          String name,
                          String description,
                          // null = 根节点；层级仅用于呈现，权限按子树求并集
                          Long parentId,
                          String createdAt,
                          // 只列**直属**成员，下级团队的人不算进来（否则成员管理会乱）
                          List<MemberRow> members,
                          // 团队有效权限 = 整棵子树在职成员自身权限的并集
                          List<String> effectivePermissions) {
    }

    /** 从库里读出来的原始成员行，权限算完后再组装成 MemberRow。 */
    private record RawMember(long teamId,
                             long userId,
                             String account,
                             String nickname,
                             int status,
                             boolean lead,
                             String joinedAt) {
    }

    // ---------------- 查询 ----------------

    public List<TeamRow> listTeams() {
        List<TeamRow> teams = jdbc.query("""
                select id, name, description, parent_id, created_at
                  from teams order by id
                """, new MapSqlParameterSource(), (rs, i) -> new TeamRow(
                rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                (Long) rs.getObject("parent_id"), rs.getString("created_at"),
                List.of(), List.of()));
        if (teams.isEmpty()) {
            return teams;
        }

        // 一次把关联表拉平，避免「每个成员查一次权限」的 N+1：
        // 团队页是管理员偶发打开的，但一个团队十几个成员时，逐个查会放大到几十次往返
        Map<String, List<String>> roleMatrix = permissions.matrix();
        Map<Long, List<String>> userRoles = loadUserRoles();
        List<String> allCodes = permissions.listPermissions().stream()
                .map(PermissionService.PermissionRow::code)
                .toList();
        List<Long> teamIds = teams.stream().map(TeamRow::id).toList();
        List<RawMember> raw = loadRawMembers(teamIds);

        // 每人「自身权限」只算一次——一个人可能同时在好几个团队
        Map<Long, List<String>> ownByUser = new HashMap<>();
        for (RawMember m : raw) {
            ownByUser.computeIfAbsent(m.userId(),
                    k -> ownPermissions(userRoles.getOrDefault(k, List.of()), roleMatrix, allCodes));
        }

        Map<Long, Set<Long>> subtree = subtreesOf(teams);

        // 1) 直属成员的自身权限并到**本团队**
        Map<Long, Set<String>> directEffective = new LinkedHashMap<>();
        for (RawMember m : raw) {
            if (m.status() != 1) {
                continue; // 禁用账号不给上级供权限
            }
            directEffective.computeIfAbsent(m.teamId(), k -> new LinkedHashSet<>())
                    .addAll(ownByUser.get(m.userId()));
        }

        // 2) 本团队的有效权限 = 自己 + 整棵子树的直接成员并集（自底向上天然被覆盖，
        //    因为每个节点都独立遍历自己的子树）
        Map<Long, Set<String>> teamEffective = new LinkedHashMap<>();
        for (TeamRow team : teams) {
            Set<String> merged = new LinkedHashSet<>();
            for (Long id : subtree.get(team.id())) {
                merged.addAll(directEffective.getOrDefault(id, Set.of()));
            }
            teamEffective.put(team.id(), merged);
        }

        // 3) 个人有效权限 = 自身 ∪ 其所带队团队的（子树）有效权限
        Map<Long, Set<String>> userEffective = new LinkedHashMap<>();
        for (RawMember m : raw) {
            userEffective.computeIfAbsent(m.userId(), k -> new LinkedHashSet<>())
                    .addAll(ownByUser.get(m.userId()));
        }
        for (RawMember m : raw) {
            if (m.lead()) {
                userEffective.get(m.userId()).addAll(teamEffective.get(m.teamId()));
            }
        }

        // 4) 按团队把直属成员组装回去
        Map<Long, List<MemberRow>> membersByTeam = new LinkedHashMap<>();
        for (Long id : teamIds) {
            membersByTeam.put(id, new ArrayList<>());
        }
        for (RawMember m : raw) {
            membersByTeam.get(m.teamId()).add(new MemberRow(
                    m.userId(), m.account(), m.nickname(), m.status(), m.lead(), m.joinedAt(),
                    userRoles.getOrDefault(m.userId(), List.of()),
                    ownByUser.get(m.userId()),
                    ordered(allCodes, userEffective.get(m.userId()))));
        }

        List<TeamRow> result = new ArrayList<>();
        for (TeamRow team : teams) {
            result.add(new TeamRow(team.id(), team.name(), team.description(), team.parentId(),
                    team.createdAt(),
                    membersByTeam.getOrDefault(team.id(), List.of()),
                    ordered(allCodes, teamEffective.get(team.id()))));
        }
        return result;
    }

    public TeamRow getTeam(long id) {
        return listTeams().stream()
                .filter(t -> t.id() == id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("团队不存在: " + id));
    }

    /**
     * 「我的团队」：只返回该负责人自己带队节点的<b>整棵子树</b>。
     *
     * <p><b>这是行级边界，必须在服务端过滤</b>——前端藏了但接口还在，换个调用方式照样能看到
     * 兄弟团队与上级团队。这里连响应体都不带：被过滤掉的团队根本不进组装环节。</p>
     *
     * <p>复用 {@link #listTeams()} 再过滤，而不是另写一套子树统计：两边口径必须完全一致，
     * 否则会出现「管理员看到 7 项、负责人看到 6 项」这种对不上的账。团队数量是管理员
     * 手工维护的量级，多算一遍的开销可以忽略，而口径漂移得人肉排查。</p>
     *
     * <p>不带队时返回空列表。能走到这里必然已过 {@code team:view}（团队成员是它的唯一来源），
     * 这里再兜一次是防「带队但团队刚被删」这类边界别把全量漏出去。</p>
     */
    public List<TeamRow> myTeams(long userId) {
        List<Long> subtree = permissions.leadSubtreeTeamIds(userId);
        if (subtree.isEmpty()) {
            return List.of();
        }
        Set<Long> keep = new LinkedHashSet<>(subtree);
        return listTeams().stream().filter(t -> keep.contains(t.id())).toList();
    }

    /**
     * 「我所在的团队」：该用户作为<b>成员</b>加入的每个团队，不递归下级。
     *
     * <p>这是权限中心「团队」页签对<b>普通成员</b>的只读口径——与 {@link #myTeams} 的
     * 带队子树不同：普通成员不带队，他该看到的是「自己在哪几个团队里」，而不是全量
     * （全量仍然锁在 {@code role:manage} 后面的 {@code /api/admin/teams}）。</p>
     *
     * <p>同样复用 {@link #listTeams()} 再过滤，理由与 myTeams 一致：成员、有效权限的
     * 统计算法只有一份，管理员视图与成员视图不会出现「两边数目对不上」的账；
     * 被过滤掉的团队不进组装环节，响应体里连痕迹都不带。</p>
     */
    public List<TeamRow> joinedTeams(long userId) {
        List<Long> teamIds = jdbc.queryForList("""
                select team_id from team_members
                 where user_id = :userId
                 order by team_id
                """, new MapSqlParameterSource("userId", userId), Long.class);
        if (teamIds == null || teamIds.isEmpty()) {
            return List.of();
        }
        Set<Long> keep = new LinkedHashSet<>(teamIds);
        return listTeams().stream().filter(t -> keep.contains(t.id())).toList();
    }

    // ---------------- 写操作 ----------------

    public TeamRow createTeam(String rawName, String rawDescription, Long parentId) {
        String name = requireValidName(rawName);
        if (StringUtils.hasText(rawName) && jdbc.queryForList(
                "select id from teams where name = :name",
                new MapSqlParameterSource("name", name), Long.class).stream().findFirst().isPresent()) {
            throw new ConflictException("team_exists", "团队「" + name + "」已存在，换一个名称");
        }
        // 新团队还没有 id，只会做「上级是否存在」这一半校验
        requireValidParent(parentId, null);
        jdbc.update("""
                insert into teams (name, description, parent_id)
                values (:name, :description, :parentId)
                """, new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("description", trimTo(rawDescription, 255))
                .addValue("parentId", parentId));
        Long id = jdbc.queryForObject("select max(id) from teams where name = :name",
                new MapSqlParameterSource("name", name), Long.class);
        return getTeam(id == null ? 0L : id);
    }

    public TeamRow updateTeam(long id, String rawName, String rawDescription, Long parentId) {
        getTeam(id);
        String name = requireValidName(rawName);
        List<Long> clash = jdbc.queryForList(
                "select id from teams where name = :name and id <> :id",
                new MapSqlParameterSource(Map.of("name", name, "id", id)), Long.class);
        if (clash != null && !clash.isEmpty()) {
            throw new ConflictException("team_exists", "团队「" + name + "」已存在，换一个名称");
        }
        // 改上级是最容易造出环的操作，必须连同「不能挂到自己下级下面」一起校验
        requireValidParent(parentId, id);
        jdbc.update("""
                update teams set name = :name, description = :description, parent_id = :parentId
                 where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("description", trimTo(rawDescription, 255))
                .addValue("parentId", parentId));
        return getTeam(id);
    }

    /**
     * 删团队。
     *
     * <p>有下级团队时直接拒绝，而不是把子节点一并删掉或让它们悬空——
     * 后者会让下级的 `parent_id` 指向不存在的节点，树从此少一整棵分支，
     * 而且这个后果在页面上只是「树突然变平了」，很难察觉。</p>
     */
    @Transactional
    public void deleteTeam(long id) {
        getTeam(id);
        Integer children = jdbc.queryForObject(
                "select count(1) from teams where parent_id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        if (children != null && children > 0) {
            throw new ConflictException("team_has_children",
                    "该团队下还有 " + children + " 个子团队，请先把它们移走或删除");
        }
        jdbc.update("delete from team_members where team_id = :id",
                new MapSqlParameterSource("id", id));
        jdbc.update("delete from teams where id = :id",
                new MapSqlParameterSource("id", id));
    }

    /**
     * 加人，或调整其负责人身份。
     *
     * <p>设为负责人时先清掉同队其他人的 is_lead —— 多个负责人会让「子树权限并集」
     * 归属不明，所以干脆不允许。</p>
     */
    public TeamRow addMember(long teamId, long userId, boolean lead) {
        getTeam(teamId);
        // 只收在职账号：一进来就是禁用状态的话，它的权限统计立刻要被排除，
        // 等于加了个「看得见但不算数」的人，不如当场拦下
        Integer status = jdbc.queryForObject(
                "select status from users where id = :id",
                new MapSqlParameterSource("id", userId), Integer.class);
        if (status == null) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        if (status != 1) {
            throw new ConflictException("user_disabled", "该账号已禁用，不能加入团队");
        }
        if (lead) {
            clearOtherLeads(teamId, userId);
        }
        jdbc.update("""
                insert into team_members (team_id, user_id, is_lead)
                values (:teamId, :userId, :lead)
                on duplicate key update is_lead = values(is_lead)
                """, new MapSqlParameterSource(Map.of(
                "teamId", teamId, "userId", userId, "lead", lead ? 1 : 0)));
        return getTeam(teamId);
    }

    @Transactional
    public TeamRow removeMember(long teamId, long userId) {
        getTeam(teamId);
        jdbc.update("delete from team_members where team_id = :teamId and user_id = :userId",
                new MapSqlParameterSource(Map.of("teamId", teamId, "userId", userId)));
        return getTeam(teamId);
    }

    /** 指定负责人。该用户必须已在团队里——负责人一定是成员。 */
    @Transactional
    public TeamRow setLead(long teamId, long userId) {
        Integer inTeam = jdbc.queryForObject(
                "select count(1) from team_members where team_id = :teamId and user_id = :userId",
                new MapSqlParameterSource(Map.of("teamId", teamId, "userId", userId)), Integer.class);
        if (inTeam == null || inTeam == 0) {
            throw new ConflictException("not_member", "该用户不在团队里，请先加入团队再指定负责人");
        }
        clearOtherLeads(teamId, userId);
        jdbc.update("update team_members set is_lead = 1 where team_id = :teamId and user_id = :userId",
                new MapSqlParameterSource(Map.of("teamId", teamId, "userId", userId)));
        return getTeam(teamId);
    }

    // ---------------- 内部 ----------------

    private String requireValidName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() < 1 || name.length() > 64) {
            throw new IllegalArgumentException("团队名称长度 1-64 字");
        }
        return name;
    }

    /**
     * 上级团队校验。{@code selfId} 为 null 表示新建（只校验存在性）。
     *
     * <p>环只可能在「把 A 挂到 A 的下级下面」时产生，所以校验方式是算出 selfId 的
     * 全部后代，看上级在不在里面。自己挂自己是后代集包含自身的特例，一并覆盖。</p>
     */
    private void requireValidParent(Long parentId, Long selfId) {
        if (parentId == null) {
            return;
        }
        Integer exists = jdbc.queryForObject(
                "select count(1) from teams where id = :id",
                new MapSqlParameterSource("id", parentId), Integer.class);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("上级团队不存在: " + parentId);
        }
        if (selfId == null) {
            return;
        }
        Set<Long> descendants = descendantsOf(loadParentOf(), selfId);
        if (descendants.contains(parentId)) {
            throw new ConflictException("team_cycle",
                    "上级团队不能选它自己的下级，否则会形成环");
        }
    }

    private Map<Long, Long> loadParentOf() {
        Map<Long, Long> map = new LinkedHashMap<>();
        jdbc.query("select id, parent_id from teams", new MapSqlParameterSource(), rs -> {
            Long parentId = (Long) rs.getObject("parent_id");
            if (parentId != null) {
                map.put(rs.getLong("id"), parentId);
            }
        });
        return map;
    }

    /** 自己 + 所有直接、间接下级。 */
    private Set<Long> descendantsOf(Map<Long, Long> parentOf, long rootId) {
        Map<Long, List<Long>> children = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> e : parentOf.entrySet()) {
            children.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        Set<Long> seen = new LinkedHashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            Long cur = stack.pop();
            if (!seen.add(cur)) {
                continue; // 已访问 = 脏数据成环，就地截断而不是死循环
            }
            for (Long child : children.getOrDefault(cur, List.of())) {
                stack.push(child);
            }
        }
        return seen;
    }

    /** 每个团队的子树（含自己）的 team_id。 */
    private Map<Long, Set<Long>> subtreesOf(List<TeamRow> teams) {
        Map<Long, List<Long>> children = new LinkedHashMap<>();
        for (TeamRow t : teams) {
            if (t.parentId() != null) {
                children.computeIfAbsent(t.parentId(), k -> new ArrayList<>()).add(t.id());
            }
        }
        Map<Long, Set<Long>> result = new LinkedHashMap<>();
        for (TeamRow t : teams) {
            Set<Long> seen = new LinkedHashSet<>();
            Deque<Long> stack = new ArrayDeque<>();
            stack.push(t.id());
            while (!stack.isEmpty()) {
                Long cur = stack.pop();
                if (!seen.add(cur)) {
                    continue; // 同上：宁可少算一层，也不能把请求卡死
                }
                for (Long child : children.getOrDefault(cur, List.of())) {
                    stack.push(child);
                }
            }
            result.put(t.id(), seen);
        }
        return result;
    }

    private void clearOtherLeads(long teamId, long exceptUserId) {
        jdbc.update("""
                update team_members set is_lead = 0
                 where team_id = :teamId and user_id <> :userId and is_lead = 1
                """, new MapSqlParameterSource(Map.of("teamId", teamId, "userId", exceptUserId)));
    }

    private Map<Long, List<String>> loadUserRoles() {
        Map<Long, List<String>> map = new LinkedHashMap<>();
        jdbc.query("select user_id, role_code from user_roles",
                new MapSqlParameterSource(), rs -> {
                    map.computeIfAbsent(rs.getLong("user_id"), k -> new ArrayList<>())
                            .add(rs.getString("role_code"));
                });
        return map;
    }

    private List<RawMember> loadRawMembers(List<Long> teamIds) {
        if (teamIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select tm.team_id, tm.user_id, u.account, u.nickname, u.status,
                       tm.is_lead, tm.joined_at
                  from team_members tm
                  join users u on u.id = tm.user_id
                 where tm.team_id in (:ids)
                 order by tm.is_lead desc, tm.user_id
                """, new MapSqlParameterSource("ids", teamIds), (rs, i) -> new RawMember(
                rs.getLong("team_id"), rs.getLong("user_id"),
                rs.getString("account"), rs.getString("nickname"), rs.getInt("status"),
                rs.getInt("is_lead") == 1, rs.getString("joined_at")));
    }

    /**
     * 成员自身权限 = 其角色权限的并集；持 ADMIN 时是全部（与 PermissionService 同一条规则）。
     * 这里刻意不调 resolve() —— resolve 会再算一遍团队继承，把 lead 继承来的权限算回团队，
     * 形成自我引用。
     */
    private List<String> ownPermissions(List<String> roles,
                                        Map<String, List<String>> roleMatrix,
                                        List<String> allCodes) {
        if (roles.isEmpty()) {
            return List.of();
        }
        if (roles.stream().anyMatch(ADMIN_ROLE::equalsIgnoreCase)) {
            return allCodes;
        }
        Set<String> granted = new LinkedHashSet<>();
        for (String role : roles) {
            granted.addAll(roleMatrix.getOrDefault(role, List.of()));
        }
        return allCodes.stream().filter(granted::contains).toList();
    }

    /** 按权限点定义顺序输出，顺手去掉并集过程中的重复。 */
    private List<String> ordered(List<String> allCodes, Set<String> granted) {
        if (granted == null || granted.isEmpty()) {
            return List.of();
        }
        return allCodes.stream().filter(granted::contains).toList();
    }

    private String trimTo(String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
