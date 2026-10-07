package com.example.crm.intl;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 国际 CRM 看板接口（/api/crm/intl/dashboard）。 */
@RestController
@RequestMapping("/api/crm/intl/dashboard")
@RequiredArgsConstructor
public class IntlDashboardController {

    private final IntlDashboardService service;

    @GetMapping
    public IntlDashboardService.Dashboard dashboard() {
        return service.dashboard();
    }
}
