package com.example.wms.intl;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 取网关注入的操作人身份（国际运单域共用）。
 *
 * <p>与 {@code OrderService.create} 里 Controller 显式读 {@code X-User-Id} 相比，
 * 这里走 RequestContextHolder，好处是 Service 深处（比如运单状态机留轨迹时）
 * 不用把 operator 一路当参数传下去——少一个参数就少一处「忘了传」的可能。</p>
 *
 * <p>取不到就返回 0 / 空串（对应「系统操作」），**不抛异常**：
 * 内部异步/定时任务上下文里没有 Servlet 请求，那是正常情况，不是错误。</p>
 */
public final class IntlCurrentUser {

    private IntlCurrentUser() {
    }

    public static long id() {
        String header = header("X-User-Id");
        if (header == null) {
            return 0L;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static String name() {
        String header = header("X-User-Roles");
        // 业务服务不校验角色，只透传；这里取昵称类字段拿不到就用角色串兜底，
        // 目的是「轨迹上有一行人能对上号」，不是安全边界
        String nickname = header("X-User-Nickname");
        return nickname != null ? nickname : (header == null ? "系统" : header);
    }

    /**
     * 写操作的兜底闸：国际物流写动作在「网关/权限服务未注入 X-User-Permissions」时的退化路径，
     * 退化为「只放 ADMIN」。正常请求都走 {@link #requirePermission}（标准 RBAC 权限点闸）。
     *
     * <p>历史说明：写操作由角色闸升级为标准 RBAC 权限点闸（49 个写端点已全部切到
     * requirePermission），本方法仅保留为兜底——网关注入 X-User-Permissions 正常时不会命中。</p>
     *
         *
     * <p>取不到角色头时按「拒绝」处理：内部异步上下文不经过这里，真走到写端点
     * 却拿不到身份，说明调用链有问题，宁可拒也不能默认放行。</p>
     */
    public static void requireAdminForWrite(String action) {
        String roles = header("X-User-Roles");
        if (roles == null || !containsAdmin(roles)) {
            throw new com.example.wms.PermissionDeniedException(action + " 仅限管理员（ADMIN）");
        }
    }

    /**
     * 写操作的 RBAC 闸：持有模块对应的权限点（如 {@code wms:intl:edit}）才放行。
     * 权限点由网关注入 {@code X-User-Permissions}（网关从 user-center 解析）。
     *
     * <p>权限头缺失或空（旧网关未注入、内部直连、user-center 故障降级）时退化为
     * ADMIN 角色闸——管理员永远可写；权限头在位的非 ADMIN 账号必须通过矩阵授权。</p>
     */
    public static void requirePermission(String action, String permission) {
        String perms = header("X-User-Permissions");
        if (perms == null || perms.trim().isEmpty()) {
            requireAdminForWrite(action);
            return;
        }
        for (String p : perms.split(",")) {
            if (permission.equalsIgnoreCase(p.trim())) {
                return;
            }
        }
        throw new com.example.wms.PermissionDeniedException(action + " 需要权限点 " + permission);
    }

    private static boolean containsAdmin(String roles) {
        for (String role : roles.split(",")) {
            if ("ADMIN".equalsIgnoreCase(role.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String header(String name) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getHeader(name);
        }
        return null;
    }
}
