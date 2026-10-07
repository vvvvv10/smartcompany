package com.example.wms;

/**
 * 角色不够、禁止执行该操作时抛出，映射 403 permission_denied。
 *
 * <p>与 user-center 的 {@code ForbiddenException}（缺细粒度权限点）是两回事：
 * 这里判的是「网关注入的角色码里有没有 ADMIN」，属于粗粒度闸门。国际物流的写
 * 动作（报 customs、改客户授信、推进开航状态、造轨迹）全部对外承担法律责任，
 * 「登录即可写」的国内零售口径在这里不适用。</p>
 */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}
