package com.example.gateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 网关注入权限码的唯一来源。user-center 的 {@code GET /api/users/me} 已经会解析
 * 当前用户全部权限点（角色矩阵 + 身份派生），网关在这里拿出来转发给下游业务服务，
 * 这样业务服务才能按「权限点」而不是「角色码」做标准 RBAC 判断。
 *
 * <p><b>缓存</b>：每请求调一次 user-center 太贵。TTL 60 秒的内存缓存——改了
 * 角色矩阵最坏 60 秒生效，和 Redis token 吊销清单的时效同一个量级理解即可。</p>
 *
 * <p><b>失败语义</b>：user-center 不可用/超时时返回空列表。下游业务服务在
 * 「权限头缺失」时退化到旧的 ADMIN 角色闸（见各服务的 IntlCurrentUser），
 * 保证管理员在 user-center 故障期仍可操作，普通用户则拿不到权限点被拒绝——
 * fail-closed 的方向对。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionResolver {

    private static final long TTL_MILLIS = 60_000L;
    /** 缓存上限：超过就整体清空重建，防极端情况下的内存膨胀（用户量小，够用）。 */
    private static final int MAX_ENTRIES = 4096;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(long expireAt, List<String> codes) {
    }

    public Mono<List<String>> resolve(String userId, String roles, String tenantId, String traceId) {
        String key = userId + "|" + (roles == null ? "" : roles) + "|" + (tenantId == null ? "" : tenantId);
        CacheEntry hit = cache.get(key);
        if (hit != null && hit.expireAt > System.currentTimeMillis()) {
            return Mono.just(hit.codes);
        }
        WebClient client = webClientBuilder.build();
        WebClient.RequestHeadersSpec<?> spec = client.get()
                .uri("http://user-center/api/users/me")
                .header(AuthGlobalFilter.HEADER_USER_ID, userId)
                .header(AuthGlobalFilter.HEADER_ROLES, roles == null ? "" : roles)
                .header(AuthGlobalFilter.HEADER_TRACE_ID, traceId != null ? traceId : "");
        if (tenantId != null) {
            spec = spec.header(AuthGlobalFilter.HEADER_TENANT_ID, tenantId);
        }
        return spec.retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(3))
                .map(this::parseCodes)
                .doOnNext(codes -> {
                    if (cache.size() >= MAX_ENTRIES) {
                        cache.clear();
                    }
                    cache.put(key, new CacheEntry(System.currentTimeMillis() + TTL_MILLIS, codes));
                })
                .onErrorResume(ex -> {
                    log.warn("解析权限点失败（走 ADMIN 角色闸降级）: {}", ex.getMessage());
                    return Mono.just(List.of());
                });
    }

    private List<String> parseCodes(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.path("permissionCodes");
            List<String> codes = new ArrayList<>();
            if (arr.isArray()) {
                arr.forEach(n -> codes.add(n.asText()));
            }
            return codes;
        } catch (Exception e) {
            log.warn("解析权限响应失败: {}", e.getMessage());
            return List.of();
        }
    }
}
