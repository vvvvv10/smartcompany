package com.example.gateway.config;

import com.example.gateway.security.JwtProvider;
import java.net.InetSocketAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

@Configuration
@RequiredArgsConstructor
public class GatewayConfig {

    private final JwtProvider jwtProvider;

    /**
     * 限流维度：已登录用户按 userId，匿名用户按客户端 IP。
     * 比单纯按 IP 限流公平得多——NAT 出口后面的用户不会被互相误伤。
     */
    @Bean
    public KeyResolver principalKeyResolver() {
        return exchange -> {
            ServerHttpRequest request = exchange.getRequest();
            String principal = jwtProvider.peekSubject(request.getHeaders())
                    .orElseGet(() -> resolveClientIp(request));
            return Mono.just(principal);
        };
    }

    /**
     * 真实客户端 IP 的取法依赖部署形态：
     * 网关前面必须有 LB，并且 LB 要重写 X-Forwarded-For。
     * 直接信任客户端传来的该头等于把限流开关交给攻击者。
     */
    private String resolveClientIp(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        InetSocketAddress address = request.getRemoteAddress();
        return address == null ? "unknown" : address.getAddress().getHostAddress();
    }
}
