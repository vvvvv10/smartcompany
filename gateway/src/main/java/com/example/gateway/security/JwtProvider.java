package com.example.gateway.security;

import com.example.gateway.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * JWT 校验器。只负责"验签 + 解析"，不查库、不落库，保证网关可以在任意节点横向扩容。
 */
@Slf4j
@Component
public class JwtProvider {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthProperties properties;

    private final SecretKey secretKey;

    public JwtProvider(AuthProperties properties) {
        this.properties = properties;
        this.secretKey = Keys.hmacShaKeyFor(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public Optional<Claims> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(properties.getJwt().getIssuer())
                    .clockSkewSeconds(properties.getJwt().getAllowedClockSkewSeconds())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("token rejected: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从 Authorization 头里取 Bearer 令牌，格式不对就返回空。
     */
    public Optional<String> resolveBearer(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    /**
     * 限流用的静默解析：拿不到身份就退化为 IP 维度，不能因为令牌异常把限流链路打断。
     */
    public Optional<String> peekSubject(HttpHeaders headers) {
        return resolveBearer(headers.getFirst(HttpHeaders.AUTHORIZATION))
                .flatMap(this::parse)
                .map(Claims::getSubject);
    }
}
