package com.example.user;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统（模块）管理员配置（权限中心「系统管理员」页签，role:manage）。
 *
 * <p>权限申请第 2 级的审批人名册（41 号 system_admins）：谁批「某个系统的权限」
 * 由这里定；第 1 级（组织上一级）是申请人所在团队的负责人，两级串行，ADMIN 兜底。</p>
 *
 * <p>增删都返回最新全量（8 个模块的小数据，前端拿到直接 setState，不必再拉）。</p>
 */
@RestController
@RequestMapping("/api/admin/system-admins")
@RequiredArgsConstructor
public class AdminSystemAdminController {

    private static final String HEADER_ROLES = "X-User-Roles";
    private static final String PERMISSION_MANAGE = "role:manage";

    private final SystemAdminService service;
    private final PermissionService permissions;

    /** 入参用字符串承载 userId：缺字段/非数字都由后端归到 400，不引入第二种数字格式。 */
    public record AddPayload(String module, String userId) {
    }

    @GetMapping
    public List<SystemAdminService.ModuleAdmins> list(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        require(roles);
        return service.list();
    }

    @PostMapping
    public List<SystemAdminService.ModuleAdmins> add(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestBody AddPayload payload) {
        require(roles);
        return service.add(payload.module(), parseUserId(payload.userId()));
    }

    @DeleteMapping("/{module}/{userId}")
    public List<SystemAdminService.ModuleAdmins> remove(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable String module,
            @PathVariable long userId) {
        require(roles);
        return service.remove(module, userId);
    }

    private void require(String roles) {
        String missing = permissions.firstMissing(roles, PERMISSION_MANAGE);
        if (missing != null) {
            throw new ForbiddenException("缺少权限点: " + missing + "，请联系管理员在「权限配置」中授予");
        }
    }

    private long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("必须指定用户");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("用户 id 不是合法数字: " + raw);
        }
    }
}
