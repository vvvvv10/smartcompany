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
 * 角色与权限点配置（权限中心）。
 *
 * <p>谁能进：请求头 X-User-Roles（网关清洗注入）里带 role:manage 权限点对应的角色。
 * 判定走 PermissionService.firstMissing —— ADMIN 角色代码兜底放行，
 * 防止配置页把 ADMIN 的权限勾光后所有人被锁死在门外。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminPermissionController {

    private static final String HEADER_ROLES = "X-User-Roles";
    private static final String PERMISSION_MANAGE = "role:manage";

    private final PermissionService permissions;

    /** 角色创建入参 */
    public record RolePayload(String code, String name, String description) {
    }

    /** 权限点整体替换入参 */
    public record PermissionAssignment(List<String> permissions) {
    }

    @GetMapping("/permissions")
    public List<PermissionService.PermissionRow> listPermissions(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        require(roles, PERMISSION_MANAGE);
        return permissions.listPermissions();
    }

    /** 角色列表（含用户数） */
    @GetMapping("/role-list")
    public List<PermissionService.RoleRow> listRoles(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        require(roles, PERMISSION_MANAGE);
        return permissions.listRoles();
    }

    /** 角色 → 权限矩阵：一次拉全量，配置页直接渲染表格 */
    @GetMapping("/role-matrix")
    public Map<String, Object> matrix(@RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        require(roles, PERMISSION_MANAGE);
        return Map.of(
                "permissions", permissions.listPermissions(),
                "roles", permissions.listRoles(),
                "granted", permissions.matrix());
    }

    /** 角色更新入参（编码不可改） */
    public record RoleUpdatePayload(String name, String description) {
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        require(roles, PERMISSION_MANAGE);
        PermissionService.Overview ov = permissions.overview();
        return Map.of(
                "roleCount", ov.roleCount(),
                "permissionCount", ov.permissionCount(),
                "grantCount", ov.grantCount(),
                "customRoleCount", ov.customRoleCount(),
                "moduleCount", ov.moduleCount());
    }

    @GetMapping("/roles/{code}")
    public PermissionService.RoleDetail roleDetail(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable String code) {
        require(roles, PERMISSION_MANAGE);
        return permissions.roleDetail(code);
    }

    /** 修改角色名称/描述 */
    @PutMapping("/roles/{code}")
    public PermissionService.RoleRow updateRole(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable String code,
            @RequestBody RoleUpdatePayload payload) {
        require(roles, PERMISSION_MANAGE);
        return permissions.updateRole(code, payload.name(), payload.description());
    }

    /** 角色持有的成员列表 */
    @GetMapping("/roles/{code}/members")
    public java.util.List<PermissionService.RoleMember> roleMembers(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable String code) {
        require(roles, PERMISSION_MANAGE);
        return permissions.roleMembers(code);
    }

    /** 替换某角色的权限点勾选 */
    @PutMapping("/roles/{code}/permissions")
    public Map<String, Object> replacePermissions(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable String code,
            @RequestBody PermissionAssignment payload) {
        require(roles, PERMISSION_MANAGE);
        List<String> granted = permissions.replaceRolePermissions(code, payload.permissions());
        return Map.of("code", code, "permissions", granted);
    }

    @PostMapping("/roles")
    public PermissionService.RoleRow createRole(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @RequestBody RolePayload payload) {
        require(roles, PERMISSION_MANAGE);
        return permissions.createRole(payload.code(), payload.name(), payload.description());
    }

    @DeleteMapping("/roles/{code}")
    public Map<String, Object> deleteRole(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable String code) {
        require(roles, PERMISSION_MANAGE);
        permissions.deleteRole(code);
        return Map.of("code", code, "deleted", true);
    }

    private void require(String roles, String permission) {
        String missing = permissions.firstMissing(roles, permission);
        if (missing != null) {
            throw new ForbiddenException("缺少权限点: " + missing + "，请联系管理员在「权限配置」中授予");
        }
    }
}
