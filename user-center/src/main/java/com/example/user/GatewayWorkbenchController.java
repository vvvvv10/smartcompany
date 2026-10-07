package com.example.user;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关工作台：首页「网关工作台」Tab 的只读数据源。
 *
 * <p>权限点复用 dashboard:view —— 菜单显隐、页面守卫、后端取数三处用同一份权限点，
 * 保证「看得见就能进」。</p>
 *
 * <p>身份判定读 X-User-Roles（网关清洗注入），伪造的同名头已在 AuthGlobalFilter
 * 第一步被剥掉，所以这里可以无条件信任。</p>
 */
@RestController
@RequestMapping("/api/admin/gateway")
@RequiredArgsConstructor
public class GatewayWorkbenchController {

    /** 与网关 AuthGlobalFilter.HEADER_ROLES 保持一致 */
    private static final String HEADER_ROLES = "X-User-Roles";

    private final GatewayWorkbenchService workbench;
    private final PermissionService permissions;

    @GetMapping("/workbench")
    public GatewayWorkbenchService.Workbench get(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        String missing = permissions.firstMissing(roles, "dashboard:view");
        if (missing != null) {
            throw new ForbiddenException("缺少权限点: " + missing + "，请联系管理员在「权限配置」中授予");
        }
        return workbench.workbench();
    }
}
