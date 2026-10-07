package com.example.wms.intl;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 国际仓储看板接口（/api/wms/intl/dashboard）。 */
@RestController
@RequestMapping("/api/wms/intl/dashboard")
@RequiredArgsConstructor
public class IntlDashboardController {

    private final IntlDashboardService service;

    @GetMapping
    public IntlDashboardService.Dashboard dashboard() {
        return service.dashboard();
    }
}
