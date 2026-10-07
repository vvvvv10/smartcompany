package com.example.user;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「我自己的团队」两套只读视图：带队负责人看子树，普通成员看自己所在的团队。
 *
 * <p><b>为什么挂在这里</b>：网关只放行 {@code /api/auth|users|admin/**} 三条路由，
 * 所以「非管理员也能进」的接口只能落在 {@code /api/users/**} 下。同时它语义上就是
 * 「我自己的团队」，与已有的 {@code /api/users/me/organizations} 同一形态。</p>
 *
 * <p><b>只读</b>。两个端点的差别只在行级边界怎么划：</p>
 * <ul>
 *   <li>{@code GET /teams}（带队子树）——入口由 {@code team:view} 守卫，而这个权限码
 *       只能从 {@code team_members.is_lead} 派生（不在 permissions 表里），普通成员
 *       勾不出来也删不掉；数据范围 = 带队节点的<b>整棵子树</b>；</li>
 *   <li>{@code GET /joined-teams}（我加入的团队）——<b>登录即可</b>，行级边界 = 本人
 *       （user_id 取自网关身份头，不收任何入参），只有「我所在的团队」进响应体。
 *       它是权限中心「团队」页签给普通成员的只读口径（42 号把 role:manage 从 USER
 *       收回后，全量视图归 /api/admin/teams 管，普通成员只剩这一份）；</li>
 *   <li>两者都没有任何写接口——改角色、启停、加人减人仍然只在权限中心
 *       （{@code role:manage}），延续「团队只做展示层」的既有约定。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class MyTeamController {

    private final PermissionService permissions;
    private final TeamService teams;

    /**
     * 我带队的团队及其下级（含成员、角色、自身与有效权限）。
     *
     * <p>能返回什么由 {@link TeamService#myTeams} 决定，能调这个接口由 team:view 决定，
     * 两者都和前端无关——前端只负责不显示，不负责拦截。</p>
     */
    @GetMapping("/teams")
    public List<TeamService.TeamRow> myTeams(
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        require(roles, PermissionService.PERMISSION_TEAM_VIEW);
        long userId = permissions.currentUserId();
        if (userId <= 0) {
            throw new ForbiddenException("缺少网关身份头 X-User-Id，请通过网关访问");
        }
        return teams.myTeams(userId);
    }

    /**
     * 我加入的团队（作为成员，不递归下级）——权限中心「团队」页签给<b>普通成员</b>
     * 的只读数据源（42 号：USER 不再持 role:manage，看不了 /api/admin/teams 的全量）。
     *
     * <p><b>登录即可，不设 require</b>：行级边界 = 本人，userId 取自网关身份头、
     * 不收任何入参，没有可越权的口子；响应体里只有「我所在的团队」，与
     * {@link #myTeams} 同样在服务端过滤，前端藏不藏都不影响边界。</p>
     */
    @GetMapping("/joined-teams")
    public List<TeamService.TeamRow> joinedTeams() {
        long userId = permissions.currentUserId();
        if (userId <= 0) {
            throw new ForbiddenException("缺少网关身份头 X-User-Id，请通过网关访问");
        }
        return teams.joinedTeams(userId);
    }

    /**
     * 权限点目录：只有 code / name / module / description 这四列<b>定义</b>，
     * 不含「哪个角色勾了它」「谁有哪些权限」。
     *
     * <p>不设 require——登录即可。理由是它本身不敏感：每个登录用户在 {@code /users/me}
     * 里已经拿到自己的 permissionCodes，权限点的名字只是静态配置；真正敏感的是授权矩阵
     * （role_permissions），那个仍然锁在 {@code role:manage} 后面。</p>
     *
     * <p>负责人视图需要它才能把 {@code user:list} 这种码翻译成「用户管理」给人看，
     * 否则界面上只剩一串裸编码。</p>
     */
    @GetMapping("/permission-catalog")
    public List<PermissionService.PermissionRow> permissionCatalog() {
        return permissions.listPermissions();
    }

    private void require(String roles, String permission) {
        String missing = permissions.firstMissing(roles, permission);
        if (missing != null) {
            throw new ForbiddenException(
                    "缺少权限点: " + missing + "，只有团队负责人可以查看自己的团队");
        }
    }
}
