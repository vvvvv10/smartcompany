package com.example.tms.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 把网关注入的 X-Tenant-Id 头落进 {@link TenantContext}。
 *
 * <p>头是可信的：网关 AuthGlobalFilter 先剥掉客户端伪造的同名头，再按 JWT 的 tid claim
 * 重新注入，本服务只解析不校验（与 X-User-Id 由 Controller 逐个显式读不同：
 * 租户要渗透进每一条 SQL，统一在过滤器挂 ThreadLocal，Service 随取随用）。</p>
 *
 * <p>ThreadLocal 必须在 finally 里清：Servlet 容器线程池会复用线程，
 * 不清就会把上一个请求的租户带给下一个请求，造成跨租户串数。</p>
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final String HEADER_TENANT_ID = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TenantContext.set(request.getHeader(HEADER_TENANT_ID));
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
