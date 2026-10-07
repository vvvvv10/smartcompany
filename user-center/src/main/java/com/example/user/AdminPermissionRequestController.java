package com.example.user;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限申请审批（「审批中心」的第二个页签，与花名审批并列）。
 *
 * <p>41 号起是两级串行审批：第 1 级组织上一级（申请人所在团队的负责人）、
 * 第 2 级系统管理员（权限所属模块的 system_admins），ADMIN 任意一级可整单批。
 * 本 Controller 的门槛（permission:review）由 resolve 派生给负责人与系统管理员；
 * 「这一级轮没轮到你」由 PermissionRequestService 按单判，列表也按查看者圈范围。</p>
 *
 * <p>批准的效果是写 user_permissions 直授行（见 PermissionRequestService），
 * 用户侧最晚在下次拉 /users/me 时看到权限变化；本人不需重登。</p>
 */
@RestController
@RequestMapping("/api/admin/permission-requests")
@RequiredArgsConstructor
public class AdminPermissionRequestController {

    private static final String HEADER_ROLES = "X-User-Roles";
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String REVIEW_PERMISSION = "permission:review";

    private final PermissionRequestService requests;
    private final PermissionService permissions;

    /** 驳回理由必填——没有理由的驳回对申请人毫无帮助（与花名驳回同一口径）。 */
    public record RejectPayload(
            @jakarta.validation.constraints.NotBlank(message = "驳回必须填写理由") String note) {
    }

    @GetMapping
    public PermissionRequestService.PageResult<PermissionRequestService.RequestRow> list(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        require(roles);
        return requests.search(status, page, size, parseUserId(userId), isAdmin(roles));
    }

    @GetMapping("/pending-count")
    public long pendingCount(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId) {
        require(roles);
        return requests.pendingCount(parseUserId(userId), isAdmin(roles));
    }

    @PostMapping("/{id}/approve")
    public PermissionRequestService.RequestRow approve(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @PathVariable long id) {
        require(roles);
        return requests.approve(id, parseUserId(userId), isAdmin(roles));
    }

    @PostMapping("/{id}/reject")
    public PermissionRequestService.RequestRow reject(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @PathVariable long id,
            @RequestBody @jakarta.validation.Valid RejectPayload payload) {
        require(roles);
        // 校验不生效的兜底：空理由的驳回对申请人毫无帮助，宁可 400 也不写脏数据
        String note = payload.note() == null ? "" : payload.note().trim();
        if (note.isEmpty()) {
            throw new IllegalArgumentException("驳回必须填写理由");
        }
        return requests.reject(id, parseUserId(userId), note, isAdmin(roles));
    }

    private void require(String roles) {
        String missing = permissions.firstMissing(roles, REVIEW_PERMISSION);
        if (missing != null) {
            throw new ForbiddenException("缺少权限点: " + missing + "，请联系管理员在「权限配置」中授予");
        }
    }

    /** ADMIN 不按级走（一并批），单独判——firstMissing 对 ADMIN 也返回 null，分不出来。 */
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
