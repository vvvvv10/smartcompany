package com.example.tms.intl.shipment;

import com.example.tms.intl.IntlCurrentUser;
import com.example.tms.intl.IntlPage;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 国际运单接口（/api/tms/intl/shipments）——M1 的核心端点。
 *
 * <p>OMS 的母单「发起订舱」会**先查 GET ?batchNos= 再 POST**，两步都在这里：
 * GET 命中就复用（不重复建），POST 带 batchNo 也会在 TMS 侧再兜一道同样的幂等。</p>
 */
@RestController
@RequestMapping("/api/tms/intl/shipments")
@RequiredArgsConstructor
public class ShipmentController {

    private final ShipmentService service;

    @GetMapping
    public IntlPage<Shipment> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "carrierId", required = false) Long carrierId,
            @RequestParam(value = "batchNos", required = false) String batchNos,
            @RequestParam(value = "orderNos", required = false) String orderNos,
            @RequestParam(value = "polCode", required = false) String polCode,
            @RequestParam(value = "podCode", required = false) String podCode,
            @RequestParam(value = "etdFrom", required = false) String etdFrom,
            @RequestParam(value = "etdTo", required = false) String etdTo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, status, mode, carrierId, batchNos, orderNos,
                polCode, podCode, etdFrom, etdTo, page, size);
    }

    @GetMapping("/brief")
    public List<Shipment> brief() {
        return service.brief();
    }

    @GetMapping("/{id}")
    public ShipmentDetail get(@PathVariable long id) {
        return service.detail(id);
    }

    /** 按运单号取详情——OMS 列表聚合与人工对账时按单号查比按 id 方便。 */
    @GetMapping("/by-no/{shipmentNo}")
    public ShipmentDetail getByNo(@PathVariable String shipmentNo) {
        return service.detailByNo(shipmentNo);
    }

    @PostMapping
    public ShipmentDetail create(@Valid @RequestBody ShipmentPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改国际运单", "tms:intl:edit");
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public ShipmentDetail update(@PathVariable long id, @Valid @RequestBody ShipmentPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改国际运单", "tms:intl:edit");
        return service.update(payload.withId(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("新增/修改国际运单", "tms:intl:edit");
        service.delete(id);
    }

    /** 推进状态（唯一的推进入口，必过状态机）。 */
    @PatchMapping("/{id}/status")
    public ShipmentDetail changeStatus(@PathVariable long id,
                                       @Valid @RequestBody ShipmentStatusPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改国际运单", "tms:intl:edit");
        return service.changeStatus(id, payload);
    }

    /** 人工录轨迹（source 服务端固定 MANUAL，幂等键 = 运单号+节点码+节点时间）。 */
    @PostMapping("/{id}/tracking")
    public ShipmentDetail addTracking(@PathVariable long id, @Valid @RequestBody TrackingPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改国际运单", "tms:intl:edit");
        return service.addTracking(id, payload);
    }

    @GetMapping("/{id}/tracking")
    public List<TrackingNode> tracking(@PathVariable long id) {
        service.get(id); // 过租户闸
        return service.tracking(id);
    }

    /**
     * 取某条轨迹的承运商原始报文（M2-4）。
     *
     * <p>为什么单独一个端点而不是把原文塞进轨迹列表：raw_payload 存的是**整条报文**的
     * JSON，一页 10~100 条轨迹各带一份会让详情接口体积翻几倍，而这个字段一年也点不开
     * 几次。它真正的使用场景是对账扯皮——对方拿着他们的报文质疑我们的节点时间，
     * 那一刻需要能把两边并排摆出来。</p>
     */
    @GetMapping("/{id}/tracking/{nodeId}/raw")
    public Map<String, Object> trackingRaw(@PathVariable long id, @PathVariable long nodeId) {
        service.get(id); // 过租户闸
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shipmentId", id);
        body.put("nodeId", nodeId);
        body.put("rawPayload", service.rawPayloadOf(id, nodeId));
        return body;
    }

    @GetMapping("/{id}/items")
    public List<ShipmentItem> items(@PathVariable long id) {
        service.get(id); // 过租户闸
        return service.items(id);
    }
}
