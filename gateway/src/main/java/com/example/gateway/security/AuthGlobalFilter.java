package com.example.gateway.security;

import com.example.gateway.config.AuthProperties;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关身份过滤器 —— 整个体系里唯一有权判定"你是谁"的地方。
 *
 * <p>执行顺序不可调整：
 * 1) 无条件剥离外部传入的内部协议头（防伪造）
 * 2) 白名单 / 跨域预检放行
 * 3) Bearer 令牌校验（验签 + 过期 + 吊销）
 * 4) 注入网关签发的身份头，转发给下游
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_ROLES = "X-User-Roles";
    public static final String HEADER_PERMISSIONS = "X-User-Permissions";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 必须早于内置转发过滤器，否则请求会在鉴权之前就被发出去。 */
    private static final int ORDER = -100;

    private final AuthProperties properties;
    private final JwtProvider jwtProvider;
    private final TokenDenyList denyList;
    private final PermissionResolver permissionResolver;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();

        ServerHttpRequest cleaned = ensureTraceId(stripInternalHeaders(request));
        ServerWebExchange sanitized = exchange.mutate().request(cleaned).build();

        // 只有 API 需要令牌：前端页面和静态资源本身是公开的（登录页也要能打开），
        // 页面级访问控制交给前端路由守卫 + 后端接口鉴权，网关不拦 HTML。
        if (!path.startsWith("/api/")) {
            return chain.filter(sanitized);
        }

        if (HttpMethod.OPTIONS == request.getMethod() || isWhitelisted(path)) {
            return chain.filter(sanitized);
        }

        Optional<String> token = jwtProvider.resolveBearer(cleaned.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (token.isEmpty()) {
            return reject(sanitized, HttpStatus.UNAUTHORIZED, "missing_token", "缺少访问令牌");
        }

        Optional<Claims> claims = jwtProvider.parse(token.get());
        if (claims.isEmpty()) {
            return reject(sanitized, HttpStatus.UNAUTHORIZED, "invalid_token", "令牌无效或已过期");
        }

        Claims payload = claims.get();
        String jti = payload.getId();
        String userId = payload.getSubject();
        if (!StringUtils.hasText(jti) || !StringUtils.hasText(userId)) {
            return reject(sanitized, HttpStatus.UNAUTHORIZED, "malformed_token", "令牌缺少 jti 或 subject");
        }

        return denyList.isRevoked(jti).flatMap(revoked -> {
            if (Boolean.TRUE.equals(revoked)) {
                return reject(sanitized, HttpStatus.UNAUTHORIZED, "token_revoked", "令牌已失效，请重新登录");
            }
            // 先解析权限点再注入：业务服务（oms/wms/tms/crm）的写闸从「角色是 ADMIN」
            // 升级为标准 RBAC「持有 xxx:intl:edit 权限点」，推进到注入 X-User-Permissions。
            String roles = flattenRoles(payload.get("roles"));
            Object tid = payload.get("tid");
            String traceId = cleaned.getHeaders().getFirst(HEADER_TRACE_ID);
            return permissionResolver
                    .resolve(userId, roles, tid == null ? null : String.valueOf(tid), traceId)
                    .flatMap(codes -> {
                        ServerHttpRequest.Builder mutated = sanitized.getRequest().mutate()
                                .header(HEADER_USER_ID, userId)
                                .header(HEADER_ROLES, roles);
                        if (tid != null) {
                            mutated = mutated.header(HEADER_TENANT_ID, String.valueOf(tid));
                        }
                        String joined = String.join(",", codes);
                        if (!joined.isEmpty()) {
                            mutated = mutated.header(HEADER_PERMISSIONS, joined);
                        }
                        return chain.filter(sanitized.mutate().request(mutated.build()).build());
                    });
        });
    }

    /**
     * 关键的安全动作：把客户端可能伪造的内部协议头全部删掉。
     * 少了这一步，任何人都能用 {@code curl -H "X-User-Id: 1"} 冒充任意用户。
     */
    private ServerHttpRequest stripInternalHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> properties.getTrustedHeaders().forEach(headers::remove))
                .build();
    }

    private ServerHttpRequest ensureTraceId(ServerHttpRequest request) {
        if (StringUtils.hasText(request.getHeaders().getFirst(HEADER_TRACE_ID))) {
            return request;
        }
        return request.mutate()
                .header(HEADER_TRACE_ID, UUID.randomUUID().toString().replace("-", ""))
                .build();
    }

    private boolean isWhitelisted(String path) {
        return properties.getWhitelist().stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private String flattenRoles(Object roles) {
        if (roles instanceof Collection<?> values) {
            return String.join(",", values.stream().map(String::valueOf).toArray(String[]::new));
        }
        return roles == null ? "" : String.valueOf(roles);
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        String traceId = exchange.getRequest().getHeaders().getFirst(HEADER_TRACE_ID);
        String body = String.format("{\"code\":\"%s\",\"message\":\"%s\",\"traceId\":\"%s\"}",
                code, message, traceId == null ? "" : traceId);
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
