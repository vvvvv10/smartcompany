package com.example.oms.linkage;

import com.example.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OMS → TMS/WMS 的直连 HTTP 客户端（三系统关联方案，39 号迁移配套）。
 *
 * <p>为什么不用网关：这是服务端发起的内部调用，走网关要带用户 JWT、
 * 还要过权限路由，纯属绕路；compose 网络里服务名直达即可
 * （{@code http://tms:8084}，配 {@code TMS_BASE_URL}/{@code WMS_BASE_URL} 覆盖）。</p>
 *
 * <p><b>租户头是硬要求</b>：TMS/WMS 的 TenantFilter 靠 X-Tenant-Id 落租户 ThreadLocal，
 * 漏发会回落到默认租户 'alibaba'——别的租户发货就会把运单/出库记录写进阿里巴巴名下（跨租户污染）。
 * 这里统一取 {@link TenantContext#get()}（OMS TenantFilter 已解析过，缺省也归一）。X-User-Id/X-User-Roles
 * 仅透传当前请求的原值做审计，两个下游都不校验它们。</p>
 *
 * <p>超时必须给：这两个调用发生在 OMS 写订单的事务里（见 {@link LinkageService}），
 * 无限等会把连接池拖死。连不上一律抛 {@link LinkageException}，由全局异常处理器
 * 转成明确文案并回滚 OMS 状态。</p>
 */
@Slf4j
@Component
public class LinkageClient {

    /** 下游列表接口统一的 {list,total,page,size} 信封。 */
    public record Page<T>(List<T> list, long total, int page, int size) {
    }

    /** TMS 运单（只取关联需要的字段，其余 Jackson 忽略）。 */
    public record TmsOrderInfo(Long id, String orderNo, String origin, String destination, String status) {
    }

    /** WMS 出入库记录。 */
    public record WmsRecordInfo(Long id, String productName, String type, Integer quantity, String orderNo) {
    }

    /** WMS 仓库（brief 端点全量返回，取第一个 ACTIVE 的当默认发货仓）。 */
    public record WarehouseInfo(Long id, String name, String location, String status) {
    }

    /** 建 TMS 运单入参，字段对齐 tms OrderPayload。 */
    public record TmsOrderCreate(
            String orderNo, String customerName, String origin, String destination, String status, String remark) {
    }

    /** 建 WMS 出库记录入参，字段对齐 wms StockRecordPayload（orderNo 关联 OMS 单号）。 */
    public record WmsRecordCreate(
            Long warehouseId, String productName, String type, Integer quantity, String remark, String orderNo) {
    }

    /**
     * TMS 国际运单（只取列表聚合与详情要显示的字段，其余 Jackson 忽略）。
     *
     * <p>awbNo/bookingNo 都在这里：出口订单列表上的「订舱单号」列
     * 空运显示 AWB、海运显示 S/O，缺一个这列就有一半的行是空的。</p>
     */
    public record ShipmentInfo(
            Long id, String shipmentNo, String batchNo, String awbNo, String blNo, String bookingNo,
            String mode, String containerType, Integer containerQty, String polCode, String podCode,
            String status, LocalDateTime etdAt, LocalDateTime etaAt) {
    }

    /** WMS 备货任务（列表聚合只要状态与箱号，够 OMS 列表那一列用）。 */
    public record PackTaskInfo(Long id, String taskNo, String orderNo, String boxNo, String status) {
    }

    /**
     * 建 TMS 国际运单入参（对应 POST /api/tms/intl/shipments）。
     *
     * <p>母单「发起订舱」时由 OMS 组装：batchNo + 运输方案 + 箱明细。
     * shipmentNo 由 TMS 生成——**OMS 不分配运单号**，
     * 避免两个库各有一套号生成器，日后必然出现「两边算出的号不一样」。</p>
     */
    public record ShipmentCreate(
            String batchNo, String awbNo, String blNo, String bookingNo, Long sailingId,
            String mode, Long carrierId, String polCode, String podCode,
            String containerType, Integer containerQty, String incoterm,
            Integer totalPieces, BigDecimal totalGrossWeight, BigDecimal totalVolume,
            LocalDateTime etdAt, LocalDateTime etaAt, LocalDateTime cutoffAt,
            String remark,
            /** 危险品标记与 UN 号/class（逗号分隔去重）。TMS 侧没有子单明细，只能靠这里传。
             *  用 Integer 而非 boolean：TMS 的 ShipmentPayload.isDangerous 是 Integer，
             *  boolean 会序列化成 JSON true 导致对方反序列化失败（502）。 */
            Integer isDangerous, String unNumber, String dgClass,
            List<ShipmentItemCreate> items) {
    }

    /** 运单箱明细（House/实箱）。orderNo 是出口子单号，两库共用的关联键。 */
    public record ShipmentItemCreate(
            String orderNo, String houseNo, String containerNo, String containerType,
            String sealNo, String marks, Integer pieces,
            BigDecimal grossWeight, BigDecimal volume, BigDecimal volumetricWeight) {
    }

    private final RestTemplate rest;
    private final String tmsBaseUrl;
    private final String wmsBaseUrl;

    public LinkageClient(
            @Value("${tms.base-url:http://tms:8084}") String tmsBaseUrl,
            @Value("${wms.base-url:http://wms:8085}") String wmsBaseUrl) {
        this.tmsBaseUrl = tmsBaseUrl;
        this.wmsBaseUrl = wmsBaseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.rest = new RestTemplate(factory);
    }

    // ---------------- TMS ----------------

    /** 按 OMS 订单号查运单（order_no 即关联键），没有返回 empty。 */
    public Optional<TmsOrderInfo> findTmsOrder(String orderNo) {
        return findTmsOrders(List.of(orderNo)).stream()
                .filter(o -> orderNo.equals(o.orderNo()))
                .findFirst();
    }

    /**
     * 批量按 OMS 订单号查运单（列表聚合用，一页订单一次 HTTP）。
     * TMS order_no 唯一且与 OMS 订单 1:1，size=100 与 OMS 分页上限对齐，一趟能拉全。
     */
    public List<TmsOrderInfo> findTmsOrders(List<String> orderNos) {
        if (orderNos.isEmpty()) {
            return List.of();
        }
        String url = UriComponentsBuilder.fromHttpUrl(tmsBaseUrl + "/api/tms/orders")
                .queryParam("orderNos", String.join(",", orderNos))
                .queryParam("page", 1)
                .queryParam("size", 100)
                .toUriString();
        Page<TmsOrderInfo> page = get(url, new ParameterizedTypeReference<Page<TmsOrderInfo>>() {
        });
        if (page == null || page.list() == null) {
            return List.of();
        }
        return page.list();
    }

    /** 发货联动建运单；失败（连不上/校验不过）抛 {@link LinkageException}。 */
    public void createTmsOrder(TmsOrderCreate payload) {
        post(tmsBaseUrl + "/api/tms/orders", payload, "创建 TMS 运单");
    }

    // ---------------- WMS ----------------

    /** 按单个 OMS 订单号查全部 OUT 记录（发货联动幂等比对用）。 */
    public List<WmsRecordInfo> findOutRecords(String orderNo) {
        return findOutRecords(List.of(orderNo));
    }

    /**
     * 批量按 OMS 订单号查 OUT 记录（列表聚合用）。
     * 单页上限 100 条，一页订单 × 明细行可能超，按 total 翻页拉全（最多 5 页=500 条，够明细量级）。
     */
    public List<WmsRecordInfo> findOutRecords(List<String> orderNos) {
        if (orderNos.isEmpty()) {
            return List.of();
        }
        List<WmsRecordInfo> all = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            String url = UriComponentsBuilder.fromHttpUrl(wmsBaseUrl + "/api/wms/stock-records")
                    .queryParam("orderNos", String.join(",", orderNos))
                    .queryParam("type", "OUT")
                    .queryParam("page", page)
                    .queryParam("size", 100)
                    .toUriString();
            Page<WmsRecordInfo> result = get(url, new ParameterizedTypeReference<Page<WmsRecordInfo>>() {
            });
            List<WmsRecordInfo> list = result == null || result.list() == null ? List.of() : result.list();
            all.addAll(list);
            if (list.isEmpty() || result == null || all.size() >= result.total()) {
                break;
            }
        }
        return all;
    }

    /** 发货联动建出库记录；失败抛 {@link LinkageException}。 */
    public void createWmsRecord(WmsRecordCreate payload) {
        post(wmsBaseUrl + "/api/wms/stock-records", payload, "创建 WMS 出库记录");
    }

    // ---------------- 国际跨境物流（M1：OMS → TMS 建运单 + 列表聚合） ----------------

    /**
     * 按母单号批量查国际运单（出口订单列表聚合用，一页订单一次 HTTP）。
     *
     * <p>分页逻辑照抄 {@link #findOutRecords}：单页 100、按 total 翻页、最多 5 页。
     * 截断到 200 个母单号是 URL 长度上限的现实约束（一个母单号 14 字符，
     * 200 个就是 2800 字节，再多 nginx/网关的请求行会先撑不住）。</p>
     */
    public List<ShipmentInfo> findShipmentsByBatchNos(List<String> batchNos) {
        if (batchNos == null || batchNos.isEmpty()) {
            return List.of();
        }
        List<String> capped = batchNos.stream()
                .filter(nos -> nos != null && !nos.isBlank())
                .map(String::trim)
                .distinct()
                .limit(200)
                .toList();
        if (capped.isEmpty()) {
            return List.of();
        }
        List<ShipmentInfo> all = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            String url = UriComponentsBuilder.fromHttpUrl(tmsBaseUrl + "/api/tms/intl/shipments")
                    .queryParam("batchNos", String.join(",", capped))
                    .queryParam("page", page)
                    .queryParam("size", 100)
                    .toUriString();
            Page<ShipmentInfo> result = get(url, new ParameterizedTypeReference<Page<ShipmentInfo>>() {
            });
            List<ShipmentInfo> list = result == null || result.list() == null ? List.of() : result.list();
            all.addAll(list);
            if (list.isEmpty() || result == null || all.size() >= result.total()) {
                break;
            }
        }
        return all;
    }

    /** 按出口子单号批量查备货任务（列表聚合「备货」列）。分页口径同上。 */
    public List<PackTaskInfo> findPackTasksByOrderNos(List<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return List.of();
        }
        List<String> capped = orderNos.stream()
                .filter(nos -> nos != null && !nos.isBlank())
                .map(String::trim)
                .distinct()
                .limit(200)
                .toList();
        if (capped.isEmpty()) {
            return List.of();
        }
        List<PackTaskInfo> all = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            String url = UriComponentsBuilder.fromHttpUrl(wmsBaseUrl + "/api/wms/intl/pack-tasks")
                    .queryParam("orderNos", String.join(",", capped))
                    .queryParam("page", page)
                    .queryParam("size", 100)
                    .toUriString();
            Page<PackTaskInfo> result = get(url, new ParameterizedTypeReference<Page<PackTaskInfo>>() {
            });
            List<PackTaskInfo> list = result == null || result.list() == null ? List.of() : result.list();
            all.addAll(list);
            if (list.isEmpty() || result == null || all.size() >= result.total()) {
                break;
            }
        }
        return all;
    }

    /** 母单「发起订舱」建 TMS 运单；失败抛 {@link LinkageException}（OMS 事务回滚 → 502）。 */
    public ShipmentDetailResult createIntlShipment(ShipmentCreate payload) {
        String url = tmsBaseUrl + "/api/tms/intl/shipments";
        try {
            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(payload, headers()),
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            Map<String, Object> body = resp.getBody();
            Map<String, Object> shipment = body == null ? null : asMap(body.get("shipment"));
            if (shipment == null) {
                throw new LinkageException("创建 TMS 运单失败：响应里没有 shipment");
            }
            return new ShipmentDetailResult(asLong(shipment.get("id")), str(shipment.get("shipmentNo")));
        } catch (RestClientException e) {
            throw new LinkageException("创建 TMS 运单失败（" + hostOf(url) + "）: " + reason(e), e);
        }
    }

    /** 建单响应里只用得到 id 与运单号，其他字段要查详情。 */
    public record ShipmentDetailResult(Long shipmentId, String shipmentNo) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 第一个 ACTIVE 仓库（brief 按 id 倒序返回，倒着取=最老的一个，当默认发货仓与运单起点）。 */
    public Optional<WarehouseInfo> firstActiveWarehouse() {
        String url = wmsBaseUrl + "/api/wms/warehouses/brief";
        List<WarehouseInfo> all;
        try {
            ResponseEntity<List<WarehouseInfo>> resp = rest.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers()),
                    new ParameterizedTypeReference<List<WarehouseInfo>>() {
                    });
            all = resp.getBody();
        } catch (RestClientException e) {
            throw new LinkageException("联动服务调用失败（" + hostOf(url) + "）: " + reason(e), e);
        }
        if (all == null) {
            return Optional.empty();
        }
        // brief 按 id 倒序返回，正序取第一个 ACTIVE = 最老的启用仓库
        return all.stream()
                .filter(w -> "ACTIVE".equals(w.status()))
                .min(Comparator.comparing(WarehouseInfo::id));
    }

    // ---------------- 传输层 ----------------

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Tenant-Id", TenantContext.get());
        // 审计头透传：X-User-Id/X-User-Roles 取自当前入站请求（网关注入过的原值）
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            String userId = sra.getRequest().getHeader("X-User-Id");
            if (StringUtils.hasText(userId)) {
                h.set("X-User-Id", userId);
            }
            String roles = sra.getRequest().getHeader("X-User-Roles");
            if (StringUtils.hasText(roles)) {
                h.set("X-User-Roles", roles);
            }
        }
        return h;
    }

    private <T> T get(String url, ParameterizedTypeReference<T> responseType) {
        try {
            ResponseEntity<T> resp = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers()), responseType);
            return resp.getBody();
        } catch (RestClientException e) {
            throw new LinkageException("联动服务调用失败（" + hostOf(url) + "）: " + reason(e), e);
        }
    }

    private void post(String url, Object payload, String what) {
        try {
            rest.exchange(url, HttpMethod.POST, new HttpEntity<>(payload, headers()), String.class);
        } catch (RestClientException e) {
            throw new LinkageException(what + "失败（" + hostOf(url) + "）: " + reason(e), e);
        }
    }

    /** 从下游 {code,message} 错误体里抠出人话；抠不出退回异常摘要。 */
    private static String reason(RestClientException e) {
        if (e instanceof HttpStatusCodeException hse) {
            String body = hse.getResponseBodyAsString();
            Matcher m = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            if (m.find()) {
                return m.group(1);
            }
            return "HTTP " + hse.getStatusCode().value();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String hostOf(String url) {
        try {
            return UriComponentsBuilder.fromUriString(url).build().getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
