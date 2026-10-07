package com.example.oms.intl.exportorder;

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
 * 出口子单接口（/api/oms/intl/export-orders）。
 *
 * <p>列表里「运单/备货」几列是跨库聚合（TMS/WMS）：下游挂了那几列显示「—」，
 * 列表本身照常渲染（见 LinkageService.enrichExportOrders）。</p>
 */
@RestController
@RequestMapping("/api/oms/intl/export-orders")
@RequiredArgsConstructor
public class ExportOrderController {

    private final ExportOrderService service;

    @GetMapping
    public IntlPage<ExportOrder> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "batchNo", required = false) String batchNo,
            @RequestParam(value = "customerId", required = false) Long customerId,
            @RequestParam(value = "consigneeCountry", required = false) String consigneeCountry,
            @RequestParam(value = "incoterm", required = false) String incoterm,
            @RequestParam(value = "currency", required = false) String currency,
            @RequestParam(value = "etdFrom", required = false) String etdFrom,
            @RequestParam(value = "etdTo", required = false) String etdTo,
            @RequestParam(value = "hasException", defaultValue = "false") boolean hasException,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, status, batchNo, customerId, consigneeCountry, incoterm,
                currency, etdFrom, etdTo, hasException, page, size);
    }

    @GetMapping("/brief")
    public List<ExportOrder> brief(@RequestParam(value = "status", required = false) String status) {
        return service.brief(status);
    }

    @GetMapping("/{id}")
    public ExportOrderDetail get(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping
    public ExportOrderDetail create(@Valid @RequestBody ExportOrderPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改出口子单", "oms:intl:edit");
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public ExportOrderDetail update(@PathVariable long id, @Valid @RequestBody ExportOrderPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改出口子单", "oms:intl:edit");
        return service.update(payload.withId(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("新增/修改出口子单", "oms:intl:edit");
        service.delete(id);
    }

    /** 推进子单状态（唯一推进入口，必过状态机）。 */
    @PatchMapping("/{id}/status")
    public ExportOrderDetail changeStatus(@PathVariable long id,
                                          @Valid @RequestBody ExportOrderStatusPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改出口子单", "oms:intl:edit");
        return service.changeStatus(id, payload);
    }

    @GetMapping("/{id}/items")
    public List<ExportOrderItem> items(@PathVariable long id) {
        service.get(id); // 过租户闸
        return service.items(id);
    }
}
