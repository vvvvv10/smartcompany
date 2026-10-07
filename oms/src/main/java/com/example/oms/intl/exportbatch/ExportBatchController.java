package com.example.oms.intl.exportbatch;

import com.example.oms.intl.IntlCurrentUser;
import com.example.oms.intl.IntlPage;
import jakarta.validation.Valid;
import java.util.List;
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
 * 出口母单接口（/api/oms/intl/batches）。
 *
 * <p>{@code POST /{id}/ship} 是 M1 唯一的跨库写入口：OMS 事务内经 LinkageService
 * 先查后建 TMS 运单，失败 502 linkage_failed 并回滚 OMS 状态（可安全重试）。</p>
 */
@RestController
@RequestMapping("/api/oms/intl/batches")
@RequiredArgsConstructor
public class ExportBatchController {

    private final ExportBatchService service;

    @GetMapping
    public IntlPage<ExportBatch> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "polCode", required = false) String polCode,
            @RequestParam(value = "podCode", required = false) String podCode,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, status, mode, polCode, podCode, page, size);
    }

    @GetMapping("/brief")
    public List<ExportBatch> brief() {
        return service.brief();
    }

    @GetMapping("/{id}")
    public ExportBatchDetail get(@PathVariable long id) {
        return service.detail(id);
    }

    /** 组批：建母单并把 orderNos 里的子单挂上去。 */
    @PostMapping
    public ExportBatchDetail create(@Valid @RequestBody ExportBatchPayload payload) {
        IntlCurrentUser.requirePermission("组批/修改出口母单", "oms:intl:edit");
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public ExportBatchDetail update(@PathVariable long id, @Valid @RequestBody ExportBatchPayload payload) {
        IntlCurrentUser.requirePermission("组批/修改出口母单", "oms:intl:edit");
        return service.update(payload.withId(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("组批/修改出口母单", "oms:intl:edit");
        service.delete(id);
    }

    /** 加入母批（子单未组批才允许）。 */
    @PostMapping("/{id}/orders")
    public ExportBatchDetail addOrders(@PathVariable long id, @RequestBody List<String> orderNos) {
        IntlCurrentUser.requirePermission("组批/修改出口母单", "oms:intl:edit");
        return service.addOrders(id, orderNos);
    }

    /** 移出母批。 */
    @DeleteMapping("/{id}/orders/{orderNo}")
    public ExportBatchDetail removeOrder(@PathVariable long id, @PathVariable String orderNo) {
        IntlCurrentUser.requirePermission("组批/修改出口母单", "oms:intl:edit");
        return service.removeOrder(id, orderNo);
    }

    @PatchMapping("/{id}/status")
    public ExportBatchDetail changeStatus(@PathVariable long id,
                                          @Valid @RequestBody ExportBatchStatusPayload payload) {
        IntlCurrentUser.requirePermission("组批/修改出口母单", "oms:intl:edit");
        return service.changeStatus(id, payload);
    }

    /**
     * 发起订舱：跨库写 TMS 运单（先查后建、失败回滚）。
     *
     * <p>返回 {@link ExportBatchDetail} 而不是计划里写的 TMS ShipmentDetail：
     * 后者需要在 OMS 里再抄一份 TMS 的 DTO（shipment/items/trackingNodes/declaration），
     * 而这个副本只会被前端浅用一两个字段，却带来「TMS 加字段 → OMS 的副本要跟着改」
     * 这条耦合。母单详情里已经带了 shipment 快照（运单号 + 状态），
     * 前端刷新一下母单详情就够了；运单的完整信息去 /intl/shipments 看。</p>
     */
    @PostMapping("/{id}/ship")
    public ExportBatchDetail ship(@PathVariable long id, @RequestBody ShipBatchRequest request) {
        IntlCurrentUser.requirePermission("组批/修改出口母单", "oms:intl:edit");
        return service.ship(id, request);
    }
}
