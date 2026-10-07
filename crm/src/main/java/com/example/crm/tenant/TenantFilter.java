package com.example.crm.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从网关注入的 X-Tenant-Id 头解析租户，放进 {@link TenantContext}。
 *
 * <p>头是网关的可信头（客户端伪造会被网关剥掉），本服务直接采信即可；
 * 本服务 Controller 现有的 X-User-Id 读取逻辑与此正交，一律不动。</p>
 *
 * <p>ThreadLocal 必须在 finally 里清：Servlet 容器用线程池处理请求，
 * 不清的话线程复用会把上一个请求（可能属于别的租户）的 tenant 带进下一个请求，
 * 造成跨租户串数据。</p>
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TenantContext.set(request.getHeader(HEADER));
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
