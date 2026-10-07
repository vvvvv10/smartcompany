package com.example.wms.dashboard;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** WMS 统计看板接口：/api/wms/dashboard/**。所有登录用户可读。 */
@RestController
@RequestMapping("/api/wms/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService service;

    /** 合并看板数据：库存汇总 + 出入库统计。 */
    @GetMapping
    public Map<String, Object> summary() {
        var stock = service.stockSummary();
        var records = service.recordSummary();
        return Map.of(
                "inventory", Map.of(
                        "totalProducts", stock.productTypes(),
                        "totalQuantity", stock.totalQuantity(),
                        "lowStock", stock.lowStockCount()),
                "stockRecords", Map.of(
                        "totalIn", records.monthIn(),
                        "totalOut", records.monthOut(),
                        "todayIn", records.todayIn(),
                        "todayOut", records.todayOut()));
    }

    @GetMapping("/stock")
    public DashboardService.StockSummary stock() {
        return service.stockSummary();
    }

    @GetMapping("/records")
    public DashboardService.RecordSummary records() {
        return service.recordSummary();
    }
}
