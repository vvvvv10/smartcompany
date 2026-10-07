package com.example.crm.tenant;

/**
 * 请求级租户上下文。
 *
 * <p>网关 AuthGlobalFilter 从 JWT 的 tid claim 注入 X-Tenant-Id（可信头，客户端伪造会被剥），
 * TenantFilter 解析后放进 ThreadLocal，Service 拼 SQL 时统一从 {@link #get} 取——
 * 不必给上百个方法逐一加参数（与 X-User-Id 由 Controller 显式读不同：
 * 那些方法签名只有一处调用点，而租户要渗透进每一条 SQL）。</p>
 *
 * <p>缺省/为空一律回 'alibaba'（示例集团集团）：老令牌没有 tid、或内部直连漏头时，
 * 行为与改造前一致，不会把数据读成空。</p>
 */
public final class TenantContext {

    /** 默认租户：存量数据与缺失头时的兜底值，与 38 号迁移的 DEFAULT 对齐。 */
    public static final String DEFAULT = "alibaba";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 写入当前线程的租户 id；null/空白统一落成 {@link #DEFAULT}，避免各处重复判空。 */
    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            HOLDER.set(DEFAULT);
        } else {
            HOLDER.set(tenantId.trim());
        }
    }

    /** 取当前线程租户 id；线程上没有值（如内部调用、异步线程）时回 {@link #DEFAULT}。 */
    public static String get() {
        String tenantId = HOLDER.get();
        return tenantId == null || tenantId.isBlank() ? DEFAULT : tenantId;
    }

    /**
     * 移除当前线程的租户 id。必须在请求结束时调用（见 TenantFilter 的 finally），
     * 否则 Tomcat 线程池复用线程后，下一个请求会读到上一个请求的租户。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
