package com.example.user;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 团队管理（权限中心-团队页）。
 *
 * <p>门槛用 role:manage 而不是新造一个权限点：团队页就开在权限中心里，
 * 若两者门槛不同，会出现「进得来却看不见团队」或反过来的割裂。</p>
 *
 * <p>这里只管「谁和谁一组、谁带队」，<b>不提供任何勾权限的入口</b>——
 * 授权始终只有角色矩阵一处，团队只是把成员权限取并集后展示出来。</p>
 */
@RestController
@RequestMapping("/api/admin/teams")
@RequiredArgsConstructor
public class AdminTeamController {

    /** 与网关 AuthGlobalFilter 保持一致 */
    private static final String HEADER_ROLES = "X-User-Roles";
    private static final String PERMISSION_MANAGE = "role:manage";

    private final TeamService teams;
    private final PermissionService permissions;

    /**
     * 团队的基本信息。{@code parentId} 为 null（或不传）表示挂在根上；
     * 这里靠「缺省即根」而不是另设一个布尔位，是因为改上级本来就是
     * 「把字改成空」这种自然表达，不需要额外状态。
     */
    public record TeamPayload(String name, String description, Long parentId) {
    }

    /** userId + lead：加入团队并（可选）指定为负责人。 */
    public record MemberPayload(long userId, boolean lead) {
    }

    /**
     * 只指定负责人、不动成员关系。
     *
     * <p>与 {@link MemberPayload} 分开是有必要的：「把已在队里的人升为负责人」和
     * 「拉人进队并顺手指定带队」是两件事，后者允许被升的人原本不在队里，
     * 前者必须拒绝——负责人一定是成员，否则「全队权限并集」没有归属主体。</p>
     */
    public record LeadPayload(long userId) {
    }

    @GetMapping
    public List<TeamService.TeamRow> list(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        require(roles, PERMISSION_MANAGE);
        return teams.listTeams();
    }

    @PostMapping
    public TeamService.TeamRow create(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestBody TeamPayload payload) {
        require(roles, PERMISSION_MANAGE);
        return teams.createTeam(payload.name(), payload.description(), payload.parentId());
    }

    @PutMapping("/{id}")
    public TeamService.TeamRow update(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable long id,
            @RequestBody TeamPayload payload) {
        require(roles, PERMISSION_MANAGE);
        return teams.updateTeam(id, payload.name(), payload.description(), payload.parentId());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable long id) {
        require(roles, PERMISSION_MANAGE);
        teams.deleteTeam(id);
        return Map.of("ok", true, "id", id);
    }

    @PostMapping("/{id}/members")
    public TeamService.TeamRow addMember(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable long id,
            @RequestBody MemberPayload payload) {
        require(roles, PERMISSION_MANAGE);
        return teams.addMember(id, payload.userId(), payload.lead());
    }

    @PutMapping("/{id}/lead")
    public TeamService.TeamRow setLead(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable long id,
            @RequestBody LeadPayload payload) {
        require(roles, PERMISSION_MANAGE);
        return teams.setLead(id, payload.userId());
    }

    @DeleteMapping("/{id}/members/{memberUserId}")
    public TeamService.TeamRow removeMember(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable long id,
            @PathVariable long memberUserId) {
        require(roles, PERMISSION_MANAGE);
        return teams.removeMember(id, memberUserId);
    }

    private void require(String roles, String permission) {
        String missing = permissions.firstMissing(roles, permission);
        if (missing != null) {
            throw new ForbiddenException("缺少权限点: " + missing + "，请联系管理员在「权限配置」中授予");
        }
    }
}
