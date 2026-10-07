package com.example.tms.tenant;

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

    /** 默认租户：存量数据与漏头请求的兜底归属（与 38 号迁移的 DEFAULT 对齐）。 */
    public static final String DEFAULT = "alibaba";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 记录当前请求的租户；null/空白头回落 {@link #DEFAULT}，保证 ThreadLocal 里永远有值。 */
    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            HOLDER.set(DEFAULT);
        } else {
            HOLDER.set(tenantId.trim());
        }
    }

    /** 取当前线程的租户供 SQL 参数绑定；未设置（如异步线程）时返回 {@link #DEFAULT}。 */
    public static String get() {
        String tenantId = HOLDER.get();
        return tenantId == null || tenantId.isBlank() ? DEFAULT : tenantId;
    }

    /** 请求结束必须调用：Servlet 线程池复用线程，不清会把租户泄漏给下一个请求。 */
    public static void clear() {
        HOLDER.remove();
    }
}
