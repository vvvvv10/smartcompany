package com.example.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端接口。
 *
 * <p>身份判定读的是请求头 X-User-Roles —— 这个头由网关清洗并注入：
 * 外部伪造的同名头已经在 AuthGlobalFilter 第一步被删掉，所以这里可以无条件信任。</p>
 *
 * <p>鉴权粒度已从「必须 ADMIN」细化为权限点：
 * 查列表要 user:list，改状态/改角色要 user:manage，看运营看板要 dashboard:view。
 * 自定义角色（比如只给运营看板权限的 OPS）由「权限配置」页勾选即可，不用改代码。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminUserController {

    /** 与网关 AuthGlobalFilter.HEADER_ROLES 保持一致 */
    private static final String HEADER_ROLES = "X-User-Roles";

    /**
     * 建号只发这一种角色。请求体里压根没有 roles 字段——要给谁 ADMIN，
     * 建完在「分配角色」里单独加，一次表单同时定身份和权限太容易建出权限过大的号。
     */
    private static final String USER_ROLE = "USER";

    private final AdminUserService adminService;
    private final PermissionService permissions;
    /** 建号时可选入队，所以这里也要它；团队的写校验一律走 TeamService，不在本类重写一遍 */
    private final TeamService teams;
    /** 租户字典与归属校验：改归属/建号指定租户都要先过它 */
    private final TenantService tenants;
    /** 建号成功且勾选同步时把同手机号拉进企业通讯录；失败只透传提示，不影响建号 */
    private final DingDingService dingding;
    /** 同上，企业微信 provider */
    private final WeComService wecom;

    public record RoleAssignment(List<String> roles) {
    }

    public record StatusUpdate(int status) {
    }

    public record TenantAssignment(String tenantId) {
    }

    /**
     * 管理端建号入参——三件客观事实，剩下的全由服务端生成。
     *
     * <p>与自助注册的差别：注册是「自己给自己起花名」，后台建号是「替人建档」，
     * 再让管理员临场编一个花名和密码，只会编出 admin123 这种东西。
     * 所以花名按姓名自动取（撞车加序号），初始密码随机生成、只回一次。</p>
     *
     * <p>不收 roles：缺省就是 USER，要给管理员用列表里的「分配角色」补——
     * 一次表单同时决定身份和权限，反而容易建出权限过大的号。</p>
     *
     * <p>teamId 是<b>可选</b>的第四件事：不传就是不加入任何团队，权限完全由角色决定
     * （这也正是「团队不参与授权」的口径）。传了则在建号成功后顺手入队，
     * 省掉「先建号、再去团队页签加人」这两步——中间那段时间此人是游离的，
     * 组织架构上根本看不到他。</p>
     */
    public record CreateUserRequest(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的 11 位手机号")
            String account,
            @NotBlank(message = "姓名不能为空")
            @Size(max = 64, message = "姓名最长 64 字")
            String realName,
            @NotBlank(message = "身份证号不能为空")
            @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "身份证号应为 18 位")
            String idCard,
            /** 所属团队 id；null = 不加入任何团队 */
            Long teamId,
            /**
             * 归属租户 id；null/空 = alibaba（示例集团集团）。字典由 GET /api/admin/tenants 下发，
             * 前端做下拉，服务端落库前仍会 requireActive 复验一次——
             * 表单可以被绕过，校验必须在服务端。
             */
            String tenantId,
            /**
             * 是否同步到企业通讯录（钉钉 / 企业微信，配了凭据的才真正调用）。
             * null/ false = 不同步——外部系统的副作用必须显式勾选才发生。
             */
            Boolean corpSync) {
    }

    @GetMapping("/users")
    public AdminUserService.PageResult<AdminUserService.UserRow> list(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        require(roles, "user:list");
        return adminService.search(keyword, page, size);
    }

    /**
     * 新增用户。写权限复用 user:manage（与启停、分配角色同一把锁），不额外加权限点。
     *
     * <p>唯一性在应用层先预检是为了给出「账号撞了」还是「花名撞了」的准确文案；
     * 真正的兜底仍是 uk_account / uk_nickname —— 撞车时由 GlobalExceptionHandler
     * 翻成 409。预检与插入之间的并发窗口就落在这一层，所以这里也要接住
     * DuplicateKeyException，并按索引名区分是账号撞了还是花名撞了。</p>
     *
     * <p>入队放在建号<b>之后</b>，但团队存在性必须在建号<b>之前</b>验：
     * 顺序反过来就会出现「号建成功了、入队却失败」的半截状态，页面上只看得见
     * 一个没有归属的新号，无从知道刚才发生了什么。验过之后入队只剩
     * 「团队不存在」一种失败可能（新号必然在职），所以它不会再把状态劈开。</p>
     */
    @PostMapping("/users")
    public ResponseEntity<?> create(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestBody @Valid CreateUserRequest payload) {
        require(roles, "user:manage");

        String account = payload.account().trim();
        String realName = payload.realName().trim();
        String idCard = payload.idCard().trim().toUpperCase();
        if (adminService.accountExists(account)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "account_exists", "message", "该手机号已被占用，换一个试试"));
        }
        if (payload.teamId() != null) {
            teams.getTeam(payload.teamId());
        }
        String tenantId = payload.tenantId() == null || payload.tenantId().isBlank()
                ? "alibaba" : payload.tenantId().trim();
        tenants.requireActive(tenantId);

        try {
            AdminUserService.CreatedUser created = adminService.create(
                    account, realName, idCard, List.of(USER_ROLE), tenantId);
            if (payload.teamId() != null) {
                teams.addMember(payload.teamId(), created.user().id(), false);
            }
            // 建号落库后再同步企业通讯录：外部系统再慢再挂，也不能回滚一个已建好的号。
            // 勾选了才调用（外部副作用必须显式授权）；配了凭据的 provider 才真正调用，
            // 结果按「provider: 文案」拼接回传，前端在建号弹窗里展示。
            String corpResult = null;
            if (Boolean.TRUE.equals(payload.corpSync())) {
                java.util.List<String> parts = new java.util.ArrayList<>();
                String d = dingding.invite(realName, account);
                if (d != null) {
                    parts.add("钉钉: " + d);
                }
                String w = wecom.invite(realName, account);
                if (w != null) {
                    parts.add("企业微信: " + w);
                }
                corpResult = parts.isEmpty()
                        ? "未配置企业通讯录凭据（管理台「集成配置」页维护），未同步"
                        : String.join("；", parts);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    new AdminUserService.CreatedUser(created.user(), created.initialPassword(), corpResult));
        } catch (DuplicateKeyException ex) {
            String detail = String.valueOf(ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "code", detail.contains("uk_nickname") ? "nickname_exists" : "account_exists",
                            "message", detail.contains("uk_nickname")
                                    ? "这个姓名的花名刚刚被别人占了，再试一次即可"
                                    : "该手机号已被占用，换一个试试"));
        }
    }

    @PatchMapping("/users/{id}/status")
    public Map<String, Object> updateStatus(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable long id,
            @RequestBody StatusUpdate payload) {
        require(roles, "user:manage");
        int updated = adminService.updateStatus(id, payload.status());
        if (updated == 0) {
            throw new IllegalArgumentException("用户不存在: " + id);
        }
        return Map.of("id", id, "status", payload.status() == 0 ? 0 : 1);
    }

    @PutMapping("/users/{id}/roles")
    public Map<String, Object> replaceRoles(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable long id,
            @RequestBody RoleAssignment payload) {
        require(roles, "user:manage");
        return Map.of("id", id, "roles", adminService.replaceRoles(id, payload.roles()));
    }

    /**
     * 租户字典。挂在 user:list 上（用户管理页打开就要），不新增权限点——
     * 它只是给归属下拉用的静态字典，没有可被滥用的写面。
     */
    @GetMapping("/tenants")
    public List<TenantService.Tenant> listTenants(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        require(roles, "user:list");
        return tenants.list();
    }

    /**
     * 改用户归属租户。写权限沿用 user:manage（与启停、分配角色同一把锁）。
     *
     * <p>生效不是即时的：access token 里的 tid 已固化，15 分钟后 rotate 才重读库；
     * 让用户重新登录则立即生效（见 AdminUserService.updateTenant 注释）。</p>
     */
    @PutMapping("/users/{id}/tenant")
    public Map<String, Object> updateTenant(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable long id,
            @RequestBody TenantAssignment payload) {
        require(roles, "user:manage");
        String tenantId = payload.tenantId() == null ? "" : payload.tenantId().trim();
        if (tenantId.isEmpty()) {
            throw new IllegalArgumentException("租户ID不能为空");
        }
        tenants.requireActive(tenantId);
        if (adminService.updateTenant(id, tenantId) == 0) {
            throw new IllegalArgumentException("用户不存在: " + id);
        }
        return Map.of("id", id, "tenantId", tenantId, "tenantName", tenants.displayName(tenantId));
    }

    @GetMapping("/roles")
    public List<String> roles(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        require(roles, "user:manage");
        return adminService.listRoleCodes();
    }

    @GetMapping("/dashboard")
    public AdminUserService.Dashboard dashboard(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestParam(value = "days", defaultValue = "14") int days) {
        require(roles, "dashboard:view");
        return adminService.dashboard(days);
    }

    private void require(String roles, String permission) {
        String missing = permissions.firstMissing(roles, permission);
        if (missing != null) {
            throw new ForbiddenException("缺少权限点: " + missing + "，请联系管理员在「权限配置」中授予");
        }
    }
}
