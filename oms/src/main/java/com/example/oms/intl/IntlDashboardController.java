package com.example.oms.intl;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 国际出口看板接口（/api/oms/intl/dashboard）。 */
@RestController
@RequestMapping("/api/oms/intl/dashboard")
@RequiredArgsConstructor
public class IntlDashboardController {

    private final IntlDashboardService service;

    @GetMapping
    public IntlDashboardService.Stat dashboard() {
        return service.dashboard();
    }
}
