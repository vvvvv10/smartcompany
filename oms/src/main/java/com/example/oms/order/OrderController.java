package com.example.oms.order;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口。
 *
 * <p>身份头由网关清洗后注入：外部伪造的 X-User-Id 已在网关第一步被删掉，
 * 所以这里可以无条件信任。所有登录用户均可读写，不做角色限制。</p>
 */
@RestController
@RequestMapping("/api/oms/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService service;

    @GetMapping
    public OrderService.PageResult<Order> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "channel", required = false) String channel,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, status, channel, page, size);
    }

    /** 下拉/选择器用的轻量列表。 */
    @GetMapping("/brief")
    public List<Order> brief() {
        return service.brief();
    }

    /** 订单详情：主表 + 明细。 */
    @GetMapping("/{id}")
    public OrderDetail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping
    public OrderDetail create(@Valid @RequestBody OrderPayload payload) {
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public OrderDetail update(@PathVariable long id, @Valid @RequestBody OrderPayload payload) {
        if (payload.id() == null) {
            payload = new OrderPayload(id, payload.orderNo(), payload.channel(), payload.customerName(),
                    payload.phone(), payload.address(), payload.status(), payload.totalQty(),
                    payload.totalAmount(), payload.remark(), payload.items());
        }
        return service.update(payload);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
