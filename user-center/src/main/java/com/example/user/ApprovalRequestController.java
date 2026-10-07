package com.example.user;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一审批的对外端点（花名变更 + 权限申请，同一套两级审批）。
 *
 * <p>路径刻意与旧的两个控制器<b>不重叠</b>（旧的走 {@code /admin/nickname-requests} 与
 * {@code /admin/permission-requests}）：同一个路径注册两个 Controller 会在启动时直接冲突，
 * 而 {@code user-center} 的审批域此刻正有另一条开发线在改。新旧并存一段时间，双方行为
 * 可对照，确认无回退后再删旧端点。</p>
 *
 * <p><b>审批门槛</b>：{@code nickname:review} 与 {@code permission:review} <b>任一</b>即可
 * 进入审批台。合并成一个新码（比如 {@code approval:review}）会让所有已有审批人突然
 * 失去权限，所以先并集受理，等权限目录统一收口时再收敛。</p>
 */
@RestController
@RequiredArgsConstructor
public class ApprovalRequestController {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_ROLES = "X-User-Roles";

    /** 两类审批各自的门槛，并集受理（见类注释）。 */
    private static final String[] REVIEW_PERMISSIONS = {"permission:review", "nickname:review"};

    private final ApprovalRequestService approvals;
    private final PermissionService permissions;
    private final UserDirectory users;

    /** 花名变更入参。新花名必填。 */
    public record NicknameCreate(@NotBlank(message = "新花名不能为空") String nickname) {
    }

    /** 权限申请入参。权限码必填，理由选填。 */
    public record PermissionCreate(@NotBlank(message = "权限点不能为空") String code, String note) {
    }

    /** 驳回入参。理由必填——空理由后端直接 400。 */
    public record RejectNote(@NotBlank(message = "驳回必须填理由") String note) {
    }

    // ---------------- 提交（申请人自助）----------------

    /** 提交花名变更申请 → 两级审批：团队负责人 → 系统管理员。 */
    @PostMapping("/api/users/me/approvals/nickname")
    public ResponseEntity<?> submitNickname(
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @RequestBody NicknameCreate payload) {
        return ResponseEntity.status(201).body(approvals.submitNickname(parseUserId(userId), payload.nickname()));
    }

    /** 提交权限申请 → 两级审批：团队负责人 → 该模块的系统管理员。 */
    @PostMapping("/api/users/me/approvals/permission")
    public ResponseEntity<?> submitPermission(
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @RequestBody PermissionCreate payload) {
        return ResponseEntity.status(201).body(approvals.submitPermission(
                parseUserId(userId), payload.code(), payload.note()));
    }

    /** 我的申请（含已办结）。登录即可看自己的，不设权限点。 */
    @GetMapping("/api/users/me/approvals")
    public Object mine(
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return approvals.my(parseUserId(userId), page, size);
    }

    // ---------------- 审批（审批视角）----------------

    /** 审批列表。`status` 不传 = 全部，传 `PENDING` 只看待审批。范围按查看者圈。 */
    @GetMapping("/api/admin/approvals/requests")
    public Object list(
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long uid = parseUserId(userId);
        requireReviewer(roles);
        return approvals.list(status, page, size, approvals.viewerOf(uid, false));
    }

    /** 待我处理的条数（徽标专用）。与列表同一套范围口径，不会出现"徽标有、列表空"。 */
    @GetMapping("/api/admin/approvals/pending-count")
    public Object pendingCount(
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        long uid = parseUserId(userId);
        requireReviewer(roles);
        return Map.of("count", approvals.pendingCount(approvals.viewerOf(uid, false)));
    }

    /** 批准。按级次流转：第 1 级批完转第 2 级；ADMIN 任意一级可整单批。 */
    @PostMapping("/api/admin/approvals/requests/{id}/approve")
    public Object approve(
            @PathVariable("id") long id,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        long uid = parseUserId(userId);
        requireReviewer(roles);
        return approvals.approve(id, uid, isAdmin(uid));
    }

    /** 驳回。理由必填。 */
    @PostMapping("/api/admin/approvals/requests/{id}/reject")
    public Object reject(
            @PathVariable("id") long id,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestBody RejectNote payload) {
        long uid = parseUserId(userId);
        requireReviewer(roles);
        return approvals.reject(id, uid, payload.note());
    }

    // ---------------- 内部 ----------------

    private void requireReviewer(String roles) {
        for (String permission : REVIEW_PERMISSIONS) {
            if (permissions.firstMissing(roles, permission) == null) {
                return;
            }
        }
        throw new ForbiddenException(
                "缺少审批权限（需要 nickname:review 或 permission:review 之一）");
    }

    /** 是否 ADMIN：ADMIN 走整单批分支，不按级次卡。 */
    private boolean isAdmin(long userId) {
        return users.findById(userId)
                .map(a -> a.roles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r)))
                .orElse(false);
    }

    /**
     * 直连本服务（绕过网关）时没有这个头。不抛 401——那会把"缺内部头"和"未登录"混为一谈，
     * 与其它业务服务同一处理。
     */
    private long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("缺少网关身份头 X-User-Id，请通过网关访问");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("X-User-Id 不是合法数字: " + raw);
        }
    }
}
