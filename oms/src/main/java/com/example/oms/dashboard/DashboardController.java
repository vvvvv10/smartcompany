package com.example.oms.dashboard;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** OMS 统计看板接口：/api/oms/dashboard。所有登录用户可读。 */
@RestController
@RequestMapping("/api/oms/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService service;

    /** 合并看板数据：订单状态/渠道分布 + 销售汇总 + 款号 TOP5。 */
    @GetMapping
    public Map<String, Object> summary() {
        return Map.of(
                "orders", service.orderSummary(),
                "sales", service.salesSummary(),
                "topStyles", service.topStyles());
    }

    /** 订单状态与渠道分布。 */
    @GetMapping("/orders")
    public DashboardService.OrderSummary orders() {
        return service.orderSummary();
    }

    /** 销售汇总。 */
    @GetMapping("/sales")
    public DashboardService.SalesSummary sales() {
        return service.salesSummary();
    }

    /** 款号销量 TOP5。 */
    @GetMapping("/top-styles")
    public List<DashboardService.TopStyle> topStyles() {
        return service.topStyles();
    }
}
