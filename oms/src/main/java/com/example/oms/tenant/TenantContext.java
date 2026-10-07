package com.example.oms.tenant;

/**
 * 请求级租户上下文。
 *
 * <p>网关 AuthGlobalFilter 从 JWT 的 tid claim 注入 X-Tenant-Id（可信头，客户端伪造会被剥），
 * TenantFilter 解析后放进 ThreadLocal，Service 拼 SQL 时统一从 {@link #get()} 取——
 * 不必给上百个方法逐一加参数（与 X-User-Id 由 Controller 显式读不同：
 * 那些方法签名只有一处调用点，而租户要渗透进每一条 SQL）。</p>
 *
 * <p>缺省/为空一律回 'alibaba'（示例集团集团）：老令牌没有 tid、或内部直连漏头时，
 * 行为与改造前一致，不会把数据读成空。</p>
 */
public final class TenantContext {

    public static final String DEFAULT = "alibaba";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 请求开始时由 TenantFilter 写入；空/blank 归一为 {@link #DEFAULT}。 */
    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            HOLDER.set(DEFAULT);
        } else {
            HOLDER.set(tenantId.trim());
        }
    }

    /** 取当前请求租户；上下文缺失（如异步线程、漏注册过滤器）同样兜底 {@link #DEFAULT}。 */
    public static String get() {
        String tenantId = HOLDER.get();
        return tenantId == null || tenantId.isBlank() ? DEFAULT : tenantId;
    }

    /** 请求结束必须清理（TenantFilter 的 finally），否则线程池复用会把租户串给下一个请求。 */
    public static void clear() {
        HOLDER.remove();
    }
}
