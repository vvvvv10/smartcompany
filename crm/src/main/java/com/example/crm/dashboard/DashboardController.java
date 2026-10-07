package com.example.crm.dashboard;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CRM 统计看板接口：/api/crm/dashboard/**。所有登录用户可读。 */
@RestController
@RequestMapping("/api/crm/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService service;

    @GetMapping
    public DashboardService.Dashboard summary() {
        return service.summary();
    }

    @GetMapping("/stages")
    public List<DashboardService.StageCount> stages() {
        return service.byStage();
    }

    @GetMapping("/sources")
    public List<DashboardService.NameCount> sources() {
        return service.bySource();
    }

    @GetMapping("/levels")
    public List<DashboardService.NameCount> levels() {
        return service.byLevel();
    }

    @GetMapping("/recent")
    public Map<String, List<DashboardService.RecentItem>> recent() {
        return Map.of(
                "followUps", service.recentFollowUps(),
                "customers", service.recentCustomers());
    }
}
