package com.example.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限自助：看全部权限点、提交申请、移除自己的权限。
 *
 * <p>挂在 /api/users/** 下（网关已把该前缀路由到 user-center，零路由改动）。
 * <b>不设权限点门槛</b>——「申请」本身就是没有权限的人要做的事，用 permission:request
 * 挡住等于把门焊死；真正的边界在数据：所有写操作都只作用于 X-User-Id 本人，
 * 这个头由网关在剥掉客户端同名头之后注入，伪造不了。</p>
 *
 * <p>审批在 AdminPermissionRequestController（permission:review），与花名审批同在
 * 「审批中心」。</p>
 */
@RestController
@RequestMapping("/api/users/permissions")
@RequiredArgsConstructor
public class PermissionRequestController {

    private static final String HEADER_USER_ID = "X-User-Id";

    private final PermissionRequestService requests;

    /** 申请入参。note 选填——多数申请不需要解释，理由留给驳回时反查。 */
    public record RequestCreate(
            @NotBlank(message = "权限点不能为空") String code,
            String note) {
    }

    public record RevokePayload(
            @NotBlank(message = "权限点不能为空") String code) {
    }

    /** 全部权限点（目录）。登录即可：权限中心的「权限点」页签对所有用户开放。 */
    @GetMapping("/catalog")
    public List<PermissionService.PermissionRow> catalog() {
        return requests.catalog();
    }

    /** 我的权限全景：有效权限 + 直授/拉黑覆盖行 + 我的申请单（最新在前）。 */
    @GetMapping("/my")
    public PermissionRequestService.MyPermissions my(
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId) {
        return requests.my(parseUserId(userId));
    }

    /** 提交申请 → 管理员审批 → 批准后写直授。 */
    @PostMapping("/requests")
    public org.springframework.http.ResponseEntity<?> submit(
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @RequestBody @Valid RequestCreate payload) {
        PermissionRequestService.RequestRow row =
                requests.submit(parseUserId(userId), payload.code(), payload.note());
        return org.springframework.http.ResponseEntity.status(201).body(row);
    }

    /** 移除我的这条权限：即时生效，写用户级拉黑覆盖。 */
    @PostMapping("/revoke")
    public java.util.Map<String, Object> revoke(
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId,
            @RequestBody @Valid RevokePayload payload) {
        return requests.revoke(parseUserId(userId), payload.code());
    }

    /**
     * 直连本服务（绕过网关）时没有这个头。不抛 401——那会把「缺内部头」和
     * 「未登录」混为一谈；这里给一句可操作的提示，与各业务服务的处理一致。
     */
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
