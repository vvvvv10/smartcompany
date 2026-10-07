package com.example.user;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 花名变更审批（审批中心）。
 *
 * <p>身份判定读 X-User-Roles（网关清洗注入，外部伪造的同名头已在 AuthGlobalFilter
 * 第一步被删掉），与 AdminUserController 同一套约定。</p>
 *
 * <p>审批走权限点 nickname:review 而不是写死 ADMIN——这样自定义角色（比如给人事
 * 单独建一个 HR 角色）也能在「权限中心」勾上审批权，不必改代码。ADMIN 由
 * PermissionService 硬兜底为全权限，天然可见。</p>
 *
 * <p>入口权限之外还有行级范围（Service 内按查看者圈）：非 ADMIN 只看得到
 * 「下属团队的在途单 ∪ 我处理过的」，且批准/驳回验资格、对非 ADMIN 禁自批
 * （ADMIN 全量权限，自批放行）。</p>
 */
@RestController
@RequestMapping("/api/admin/nickname-requests")
@RequiredArgsConstructor
public class AdminNicknameRequestController {

    /** 与网关 AuthGlobalFilter.HEADER_ROLES 保持一致 */
    private static final String HEADER_ROLES = "X-User-Roles";
    private static final String HEADER_USER_ID = "X-User-Id";

    private final NicknameRequestService requests;
    private final PermissionService permissions;

    public record ReviewNote(String note) {
    }

    /** 审批列表。status 不传=全部，传 PENDING 只看待审批。范围由 Service 圈。 */
    @GetMapping
    public NicknameRequestService.PageResult<NicknameRequestService.RequestRow> list(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        require(roles, "nickname:review");
        return requests.list(status, page, size, parseUserId(userId), isAdmin(roles));
    }

    /** 待审批条数。菜单徽标专用——为一个数字拉整页数据太浪费。按查看者圈范围。 */
    @GetMapping("/pending-count")
    public Map<String, Long> pendingCount(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId) {
        require(roles, "nickname:review");
        return Map.of("count", requests.pendingCount(parseUserId(userId), isAdmin(roles)));
    }

    /** 批准：真正把花名写进 users（Service 内部先验资格再抢占 PENDING 再改名，同事务）。 */
    @PostMapping("/{id}/approve")
    public NicknameRequestService.RequestRow approve(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable long id,
            @RequestHeader(HEADER_USER_ID) long reviewerId) {
        require(roles, "nickname:review");
        return requests.approve(id, reviewerId, isAdmin(roles));
    }

    /** 驳回：理由必填，申请人要靠它知道下次改什么。 */
    @PostMapping("/{id}/reject")
    public NicknameRequestService.RequestRow reject(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable long id,
            @RequestHeader(HEADER_USER_ID) long reviewerId,
            @RequestBody ReviewNote payload) {
        require(roles, "nickname:review");
        return requests.reject(id, reviewerId, payload.note(), isAdmin(roles));
    }

    private void require(String roles, String permission) {
        String missing = permissions.firstMissing(roles, permission);
        if (missing != null) {
            throw new ForbiddenException("缺少权限点: " + missing + "，请联系管理员在「权限配置」中授予");
        }
    }

    /** ADMIN 全量可见/可批（但自批仍由 Service 拦），单独判——与权限申请控制器同款。 */
    private boolean isAdmin(String roles) {
        if (roles == null || roles.isBlank()) {
            return false;
        }
        for (String role : roles.split(",")) {
            if ("ADMIN".equalsIgnoreCase(role.trim())) {
                return true;
            }
        }
        return false;
    }

    private long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("缺少网关身份头 X-User-Id，请通过网关访问");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("X-User-Id 不是合法数字: " + raw);
        }
    }
}
