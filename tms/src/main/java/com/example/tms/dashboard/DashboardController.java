package com.example.tms.dashboard;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** TMS 统计看板接口：/api/tms/dashboard/**。所有登录用户可读。 */
@RestController
@RequestMapping("/api/tms/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService service;

    @GetMapping
    public DashboardService.Dashboard summary() {
        return service.summary();
    }

    @GetMapping("/orders")
    public List<DashboardService.StatusCount> orderStatus() {
        return service.byOrderStatus();
    }

    @GetMapping("/vehicles")
    public List<DashboardService.StatusCount> vehicleStatus() {
        return service.byVehicleStatus();
    }

    @GetMapping("/drivers")
    public List<DashboardService.StatusCount> driverStatus() {
        return service.byDriverStatus();
    }

    /** 成本对比：自有车辆 vs 外部车辆。 */
    @GetMapping("/cost")
    public DashboardService.CostComparison costComparison() {
        return service.costComparison();
    }

    /** 智能派车推荐：根据订单 ID 推荐最优车辆。 */
    @GetMapping("/recommend")
    public List<DashboardService.DispatchRecommendation> recommend(@org.springframework.web.bind.annotation.RequestParam("orderId") Long orderId) {
        return service.recommendDispatch(orderId);
    }
}
