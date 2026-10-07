package com.example.tms.intl;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 国际运单看板接口（/api/tms/intl/dashboard）。
 *
 * <p>与现有 {@code /api/tms/dashboard} 并存：那个是国内陆运的口径（车辆/司机/成本），
 * 与国际运单的箱型/ETD/报关没有可比性，混在一起会让任一边的数字都变难解释。</p>
 */
@RestController
@RequestMapping("/api/tms/intl/dashboard")
@RequiredArgsConstructor
public class IntlDashboardController {

    private final IntlDashboardService service;

    @GetMapping
    public IntlDashboardService.Dashboard dashboard() {
        return service.dashboard();
    }
}
