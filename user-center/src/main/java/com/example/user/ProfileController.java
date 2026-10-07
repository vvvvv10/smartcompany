package com.example.user;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户的资料。
 *
 * <p>这个接口的意义是演示「身份到底是谁」：它全部读网关注入的请求头，不读任何客户端参数。
 * 直接打到 user-center 而不经过网关时，这几个头不存在，会立刻报 missing_identity_header。</p>
 *
 * <p>写操作只有「提交花名变更申请」：它由本人发起，但"你是谁"仍然只信 X-User-Id，
 * 客户端传来的 id 一律忽略。直接改名的 PUT /nickname 已停用（花名变更需审批）。</p>
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class ProfileController {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_ROLES = "X-User-Roles";
    private static final String HEADER_TENANT_ID = "X-Tenant-Id";
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    private final UserDirectory users;
    private final PermissionService permissions;
    private final NicknameRequestService nicknameRequests;
    /** 租户显示名：dbTenantId 只有 id，给人看的中文名从字典取 */
    private final TenantService tenants;

    public record Profile(long id,
                          String account,
                          String nickname,
                          String dbTenantId,
                          /** 租户显示名（tenants 字典）；App「我的」页与 web 资料页展示用 */
                          String tenantName,
                          List<String> gatewayRoles,
                          List<String> permissionCodes,
                          String gatewayTenantId,
                          String traceId) {
    }

    /** 改花名入参。只改昵称，账号与角色不在这个接口的职责范围内。 */
    public record NicknameUpdate(String nickname) {
    }

    /** 提交花名变更申请。 */
    public record NicknameRequestCreate(String nickname) {
    }

    @GetMapping("/me")
    public Profile me(@RequestHeader(HEADER_USER_ID) long userId,
                      @RequestHeader(value = HEADER_ROLES, required = false) String roles,
                      @RequestHeader(value = HEADER_TENANT_ID, required = false) String tenantId,
                      @RequestHeader(value = HEADER_TRACE_ID, required = false) String traceId) {
        UserDirectory.Account account = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在或已禁用: " + userId));

        List<String> roleList = split(roles);
        return new Profile(
                account.id(),
                account.account(),
                account.nickname(),
                account.tenantId(),
                tenants.displayName(account.tenantId()),
                roleList,
                permissions.resolve(userId, roleList),
                tenantId,
                traceId);
    }

    /**
     * 直接改花名 —— 已停用。
     *
     * <p>花名是全局唯一身份标识，改名要留痕并经审批，所以这里不再写库，只返回 403
     * 并指路。保留接口而不是删掉，是为了让还缓存着旧前端的浏览器拿到一句可操作的
     * 提示，而不是一个莫名其妙的 404。</p>
     */
    @PutMapping("/me/nickname")
    public ResponseEntity<?> updateNickname(
            @RequestHeader(HEADER_USER_ID) long userId,
            @RequestBody NicknameUpdate payload) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(GlobalExceptionHandler.ApiError.of(
                        "nickname_approval_required",
                        "花名变更需审批：请在「我的身份」提交申请，管理员批准后生效"));
    }

    /**
     * 提交花名变更申请。审批通过前身份不变（users.nickname 不动）。
     */
    @PostMapping("/me/nickname/requests")
    public ResponseEntity<?> createNicknameRequest(
            @RequestHeader(HEADER_USER_ID) long userId,
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestBody NicknameRequestCreate payload) {
        require(roles, "nickname:request");
        return ResponseEntity.ok(nicknameRequests.submit(userId, payload.nickname()));
    }

    /** 我的最新一条申请，供「我的身份」展示 待审批/已驳回+理由 的进度。 */
    @GetMapping("/me/nickname/request")
    public ResponseEntity<?> myNicknameRequest(@RequestHeader(HEADER_USER_ID) long userId) {
        // 状态查询不设权限点：只要有账号就能看自己的申请（提交才需要 nickname:request）
        return nicknameRequests.myLatest(userId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(GlobalExceptionHandler.ApiError.of("not_found", "暂无申请记录")));
    }

    /** 我的全部申请历史，供「审批」页展示完整记录。 */
    @GetMapping("/me/nickname/requests")
    public ResponseEntity<?> myNicknameRequests(
            @RequestHeader(HEADER_USER_ID) long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 状态查询不设权限点：只要有账号就能看自己的申请（提交才需要 nickname:request）
        return ResponseEntity.ok(nicknameRequests.myList(userId, page, size));
    }

    private void require(String roles, String permission) {
        String missing = permissions.firstMissing(roles, permission);
        if (missing != null) {
            throw new ForbiddenException("缺少权限点: " + missing + "，请联系管理员在「权限配置」中授予");
        }
    }

    private List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(","));
    }
}
