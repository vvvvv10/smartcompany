package com.example.gateway.security;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 令牌吊销名单（Token 黑名单）。
 *
 * <p>只存被提前作废的 jti，TTL 取令牌剩余有效期即可自动清理，
 * 不要在这里存全量在线令牌，否则 Redis 会随用户量线性膨胀。</p>
 */
@Component
@RequiredArgsConstructor
public class TokenDenyList {

    private static final String KEY_PREFIX = "auth:deny:jti:";

    private final ReactiveStringRedisTemplate redis;

    public Mono<Boolean> isRevoked(String jti) {
        return redis.hasKey(KEY_PREFIX + jti);
    }

    public Mono<Boolean> revoke(String jti, Duration ttl) {
        return redis.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
    }
}
