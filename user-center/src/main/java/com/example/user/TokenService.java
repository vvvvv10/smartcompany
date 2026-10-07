package com.example.user;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Access / Refresh 双令牌的签发与吊销。
 *
 * <p>Access Token：JWT，无状态，短有效期，网关本地验签即可。
 * Refresh Token：随机串，落 Redis，可吊销，是唯一能换发 Access Token 的凭证。</p>
 */
@Slf4j
@Service
public class TokenService {

    /** 必须和网关 TokenDenyList 的 key 前缀保持一致，否则登出不生效。 */
    public static final String DENY_KEY_PREFIX = "auth:deny:jti:";
    public static final String REFRESH_KEY_PREFIX = "auth:refresh:";

    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TTL = Duration.ofDays(15);

    private final SecretKey secretKey;
    private final String issuer;
    private final StringRedisTemplate redis;
    private final UserDirectory users;

    public TokenService(@Value("${app.jwt.secret}") String secret,
                        @Value("${app.jwt.issuer}") String issuer,
                        StringRedisTemplate redis,
                        UserDirectory users) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.redis = redis;
        this.users = users;
    }

    public record TokenPair(String accessToken, String refreshToken, String tokenType, long expiresIn) {
    }

    public TokenPair issue(long userId) {
        UserDirectory.Account account = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
        return issue(userId, account.roles(), account.tenantId());
    }

    public TokenPair issue(long userId, List<String> roles, String tenantId) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
        String accessToken = Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ACCESS_TTL)))
                .claim("typ", "at")
                .claim("roles", roles)
                .claim("tid", tenantId)
                .signWith(secretKey)
                .compact();

        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(REFRESH_KEY_PREFIX + refreshToken, userId + ":" + jti, REFRESH_TTL);

        return new TokenPair(accessToken, refreshToken, "Bearer", ACCESS_TTL.toSeconds());
    }

    /**
     * 刷新令牌轮换：旧 Refresh 用后即焚，重放旧值直接失败。
     */
    public Optional<TokenPair> rotate(String refreshToken) {
        String key = REFRESH_KEY_PREFIX + refreshToken;
        String value = redis.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        redis.delete(key);
        long userId = Long.parseLong(value.substring(0, value.indexOf(':')));
        return Optional.of(issue(userId));
    }

    /**
     * 作废某个 Access Token。登出、改密、风控封号都走这里。
     * TTL 取令牌剩余寿命，过期后 Redis 自动清理，不需要额外维护。
     */
    public void revokeAccessToken(Claims claims) {
        String jti = claims.getId();
        Instant expiry = claims.getExpiration().toInstant();
        Duration remaining = Duration.between(Instant.now(), expiry);
        if (jti == null || remaining.isNegative() || remaining.isZero()) {
            return;
        }
        redis.opsForValue().set(DENY_KEY_PREFIX + jti, "1", remaining);
    }

    public void revokeRefreshToken(String refreshToken) {
        redis.delete(REFRESH_KEY_PREFIX + refreshToken);
    }

    /**
     * 允许解析已过期的令牌：登出时令牌可能刚好过期，但仍要把它记入黑名单兜底。
     */
    public Optional<Claims> parseAllowingExpired(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (ExpiredJwtException ex) {
            return Optional.ofNullable(ex.getClaims());
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
