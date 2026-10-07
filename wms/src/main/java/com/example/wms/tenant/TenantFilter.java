package com.example.wms.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 把网关注入的 X-Tenant-Id 解析进 {@link TenantContext}。
 *
 * <p>ThreadLocal 必须在 finally 里清，否则容器线程被线程池复用时，
 * 上一个请求的租户会串给下一个请求（跨租户读到别人的数据）。</p>
 *
 * <p>Controller 现有的 X-User-Id 显式读头逻辑不受本过滤器影响，保持原样。</p>
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        TenantContext.set(request.getHeader(HEADER_TENANT_ID));
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
