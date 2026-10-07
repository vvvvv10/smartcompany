package com.example.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 网关工作台的数据聚合（只读）。
 *
 * <p>为什么不直接读网关自己的 actuator：{@code /actuator/gateway} 一旦放开，
 * 它由 {@code WebFluxEndpointHandlerMapping} 直接处理、<b>绕过 AuthGlobalFilter</b>，
 * 等于把「列出/新增/删除路由」的写接口完全公开。所以这里由 user-center
 * 从三个可信来源拼出一张只读视图：</p>
 * <ul>
 *   <li>Nacos 配置中心 <b>gateway-routes.yaml</b> —— 路由的唯一事实来源；</li>
 *   <li>Nacos 配置中心 <b>gateway-whitelist.yaml</b> —— 免鉴权白名单；</li>
 *   <li>网关内网 <b>/actuator/health</b> —— 磁盘、Redis、注册中心等运行健康。</li>
 * </ul>
 *
 * <p>服务注册情况走 Spring 的 {@link DiscoveryClient}（由 Nacos 提供），
 * 既给「路由指向的服务是否还活着」，也给服务发现列表本身。</p>
 *
 * <p>三路全部带超时与降级：任何一路失败只是少几块数据，接口本身不会 500。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayWorkbenchService {

    /** 单次外呼总超时，避免页面被慢请求拖死 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final DiscoveryClient discovery;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${app.gateway.nacos-base-url:http://nacos:8848}")
    private String nacosBaseUrl;

    @Value("${app.gateway.routes-data-id:gateway-routes.yaml}")
    private String routesDataId;

    @Value("${app.gateway.whitelist-data-id:gateway-whitelist.yaml}")
    private String whitelistDataId;

    @Value("${app.gateway.health-url:http://api-gateway:8080/actuator/health}")
    private String healthUrl;

    /** 整页数据 */
    public record Workbench(
            GatewayHealth gateway,
            List<RouteRow> routes,
            List<ServiceRow> services,
            List<String> whitelist,
            long generatedAt) {
    }

    /** 网关运行健康 */
    public record GatewayHealth(
            boolean reachable,
            String status,
            Long diskFree,
            Long diskTotal,
            String redisStatus,
            String redisVersion,
            List<ComponentStatus> components) {
    }

    /** 健康检查里的一个组件（ping / diskSpace / redis / nacosConfig ...） */
    public record ComponentStatus(String name, String status) {
    }

    /** 一条路由：目标、匹配路径、限流参数，以及目标服务在注册中心的情况 */
    public record RouteRow(
            String id,
            String uri,
            int order,
            String path,
            List<String> predicates,
            String target,
            boolean lb,
            boolean registered,
            int instances,
            Integer replenishRate,
            Integer burstCapacity,
            String keyResolver) {
    }

    /** 注册中心里的一个服务 */
    public record ServiceRow(String name, int instances, boolean registered, List<String> endpoints) {
    }

    /**
     * SCG 的谓词/过滤器在 YAML 里有两种写法：简写 {@code Path=/a/**}
     * 与完整 {@code {name: X, args: {...}}}。统一成一个结构再往后走。
     */
    private record Def(String name, Map<String, String> args, String shorthand) {
    }

    public Workbench workbench() {
        List<RouteRow> routes = parseRoutes(fetch(configUrl(routesDataId), "gateway-routes.yaml"));
        return new Workbench(
                parseHealth(fetch(healthUrl, "网关 health")),
                routes,
                listServices(),
                parseWhitelist(fetch(configUrl(whitelistDataId), "gateway-whitelist.yaml")),
                System.currentTimeMillis());
    }

    // ---------------- 数据源 ----------------

    private String configUrl(String dataId) {
        return nacosBaseUrl + "/nacos/v1/cs/configs?dataId=" + dataId + "&group=DEFAULT_GROUP";
    }

    /** 取一个文本型数据源，任何失败都返回 null 并留日志，绝不抛出去。 */
    private String fetch(String url, String what) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("读取 {} 返回 {}", what, response.statusCode());
                return null;
            }
            String body = response.body();
            // Nacos 找不到配置时回的是一段纯文本而不是 404
            if (body != null && body.startsWith("config data not exist")) {
                return null;
            }
            return body;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            log.warn("读取 {} 失败: {}", what, ex.toString());
            return null;
        }
    }

    // ---------------- 路由 ----------------

    private List<RouteRow> parseRoutes(String yamlText) {
        if (!StringUtils.hasText(yamlText)) {
            return List.of();
        }
        try {
            Map<String, Object> root = loadYaml(yamlText);
            Map<String, Object> spring = asMap(root.get("spring"));
            Map<String, Object> cloud = asMap(spring.get("cloud"));
            Map<String, Object> gateway = asMap(cloud.get("gateway"));

            List<RouteRow> rows = new ArrayList<>();
            int index = 0;
            for (Object item : asList(gateway.get("routes"))) {
                Map<String, Object> route = asMap(item);
                if (route.isEmpty()) {
                    continue;
                }
                rows.add(toRouteRow(route, index++));
            }
            return rows;
        } catch (Exception ex) {
            log.warn("解析 gateway-routes.yaml 失败: {}", ex.toString());
            return List.of();
        }
    }

    private RouteRow toRouteRow(Map<String, Object> route, int index) {
        String uri = str(route.get("uri"));
        String id = str(route.get("id"));
        if (!StringUtils.hasText(id)) {
            id = "route-" + index;
        }
        int order = route.get("order") instanceof Number number ? number.intValue() : 0;

        // 匹配谓词：优先取出 Path 的模式，顺便留一份可读原文
        String path = null;
        List<String> predicates = new ArrayList<>();
        for (Def def : toDefs(route.get("predicates"))) {
            predicates.add(describe(def));
            if (path == null && "Path".equalsIgnoreCase(def.name()) && !def.args().isEmpty()) {
                path = String.join(",", def.args().values());
            }
        }

        // 限流参数只认 RequestRateLimiter
        Integer replenishRate = null;
        Integer burstCapacity = null;
        String keyResolver = null;
        for (Def def : toDefs(route.get("filters"))) {
            if (!"RequestRateLimiter".equals(def.name())) {
                continue;
            }
            Map<String, String> args = flatArgs(def);
            replenishRate = intArg(args, "replenishRate");
            burstCapacity = intArg(args, "burstCapacity");
            keyResolver = args.get("key-resolver");
        }

        // 目标服务：lb:// 才需要查注册中心；http:// 是直连地址，不涉及注册
        boolean lb = uri != null && uri.startsWith("lb://");
        String target = null;
        int instances = 0;
        boolean registered = false;
        if (lb) {
            target = uri.substring("lb://".length());
            instances = instanceCount(target);
            registered = instances > 0;
        } else if (uri != null) {
            target = hostOf(uri);
            registered = true;
        }

        return new RouteRow(id, uri, order, path, predicates, target, lb, registered,
                instances, replenishRate, burstCapacity, keyResolver);
    }

    // ---------------- 白名单 ----------------

    private List<String> parseWhitelist(String yamlText) {
        if (!StringUtils.hasText(yamlText)) {
            return List.of();
        }
        try {
            Map<String, Object> security = asMap(asMap(loadYaml(yamlText).get("app")).get("security"));
            return asList(security.get("whitelist")).stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toList();
        } catch (Exception ex) {
            log.warn("解析 gateway-whitelist.yaml 失败: {}", ex.toString());
            return List.of();
        }
    }

    // ---------------- 健康 ----------------

    private GatewayHealth parseHealth(String json) {
        if (!StringUtils.hasText(json)) {
            return new GatewayHealth(false, "DOWN", null, null, null, null, List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode components = root.path("components");

            List<ComponentStatus> statuses = new ArrayList<>();
            if (components.isObject()) {
                components.fields().forEachRemaining(entry -> collect(entry.getKey(), entry.getValue(), statuses));
            }

            JsonNode disk = components.path("diskSpace").path("details");
            Long total = disk.path("total").isNumber() ? disk.path("total").asLong() : null;
            Long free = disk.path("free").isNumber() ? disk.path("free").asLong() : null;

            JsonNode redis = components.path("redis");
            String redisStatus = redis.path("status").isMissingNode() ? null : redis.path("status").asText();
            String redisVersion = redis.path("details").path("version").isMissingNode()
                    ? null
                    : redis.path("details").path("version").asText();

            return new GatewayHealth(true, root.path("status").asText("UNKNOWN"),
                    free, total, redisStatus, redisVersion, statuses);
        } catch (Exception ex) {
            log.warn("解析网关 health 失败: {}", ex.toString());
            return new GatewayHealth(false, "DOWN", null, null, null, null, List.of());
        }
    }

    /** 展开复合组件：discoveryComposite / reactiveDiscoveryClients 这类只留叶子，避免一堆无意义的分组标签 */
    private void collect(String name, JsonNode node, List<ComponentStatus> out) {
        JsonNode children = node.path("components");
        if (children.isObject() && children.size() > 0) {
            children.fields().forEachRemaining(entry -> collect(entry.getKey(), entry.getValue(), out));
        } else {
            out.add(new ComponentStatus(name, node.path("status").asText("UNKNOWN")));
        }
    }

    // ---------------- 注册中心 ----------------

    private List<ServiceRow> listServices() {
        try {
            List<ServiceRow> rows = new ArrayList<>();
            for (String name : discovery.getServices()) {
                List<String> endpoints = discovery.getInstances(name).stream()
                        .map(instance -> instance.getHost() + ":" + instance.getPort())
                        .toList();
                rows.add(new ServiceRow(name, endpoints.size(), !endpoints.isEmpty(), endpoints));
            }
            rows.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            return rows;
        } catch (Exception ex) {
            log.warn("查询注册中心服务列表失败: {}", ex.toString());
            return List.of();
        }
    }

    private int instanceCount(String service) {
        try {
            List<ServiceInstance> instances = discovery.getInstances(service);
            return instances == null ? 0 : instances.size();
        } catch (Exception ex) {
            log.warn("查询服务 {} 实例失败: {}", service, ex.toString());
            return 0;
        }
    }

    // ---------------- 小工具 ----------------

    private Map<String, Object> loadYaml(String text) {
        return asMap(new Yaml(new SafeConstructor(new LoaderOptions())).load(text));
    }

    private static Def toDef(Object element) {
        if (element instanceof Map<?, ?> map) {
            String rawName = str(map.get("name"));
            String name = rawName == null ? "" : rawName;
            Map<String, String> args = new LinkedHashMap<>();
            if (map.get("args") instanceof Map<?, ?> raw) {
                raw.forEach((key, value) -> args.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
            }
            return new Def(name, args, null);
        }
        String text = String.valueOf(element);
        int split = text.indexOf('=');
        if (split < 0) {
            return new Def(text, Map.of(), text);
        }
        Map<String, String> args = new LinkedHashMap<>();
        args.put("value", text.substring(split + 1));
        return new Def(text.substring(0, split), args, text);
    }

    private static List<Def> toDefs(Object value) {
        List<Def> defs = new ArrayList<>();
        for (Object element : asList(value)) {
            if (element != null) {
                defs.add(toDef(element));
            }
        }
        return defs;
    }

    /** 还原成 `Path=/a/**` 这种人能读的写法 */
    private static String describe(Def def) {
        if (def.shorthand() != null) {
            return def.shorthand();
        }
        if (def.args().isEmpty()) {
            return def.name();
        }
        return def.name() + "=" + String.join(",", def.args().values());
    }

    /**
     * 简写形式 {@code RequestRateLimiter=redis-rate-limiter.replenishRate,10,...}
     * 要按逗号两两拆成 key/value；完整形式本来就是 args map，直接用。
     */
    private static Map<String, String> flatArgs(Def def) {
        if (def.shorthand() == null) {
            return def.args();
        }
        String[] parts = def.args().getOrDefault("value", "").split(",");
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < parts.length; i += 2) {
            out.put(parts[i].trim(), parts[i + 1].trim());
        }
        return out;
    }

    /** 按后缀取参数，兼容 `redis-rate-limiter.replenishRate` 与 `replenishRate` 两种键名 */
    private static Integer intArg(Map<String, String> args, String suffix) {
        for (Map.Entry<String, String> entry : args.entrySet()) {
            if (entry.getKey() != null && entry.getKey().endsWith(suffix)) {
                try {
                    return Integer.parseInt(entry.getValue().trim());
                } catch (NumberFormatException | NullPointerException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    /** http://web-console:80/path → web-console */
    private static String hostOf(String uri) {
        String rest = uri.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.\\-]*://", "");
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            rest = rest.substring(0, colon);
        }
        return rest;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
