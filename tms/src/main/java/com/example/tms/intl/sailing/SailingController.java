package com.example.tms.intl.sailing;

import com.example.tms.intl.IntlCurrentUser;
import com.example.tms.intl.IntlPage;
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

/** 船期接口（/api/tms/intl/sailings）。登录即可读写。 */
@RestController
@RequestMapping("/api/tms/intl/sailings")
@RequiredArgsConstructor
public class SailingController {

    private final SailingService service;

    @GetMapping
    public IntlPage<Sailing> list(
            @RequestParam(value = "routeId", required = false) Long routeId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "etdFrom", required = false) String etdFrom,
            @RequestParam(value = "etdTo", required = false) String etdTo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(routeId, status, etdFrom, etdTo, page, size);
    }

    /** 订舱下拉用：只看未截关的船期，按 ETD 升序。 */
    @GetMapping("/brief")
    public List<Sailing> brief(@RequestParam(value = "routeId", required = false) Long routeId) {
        return service.brief(routeId);
    }

    @GetMapping("/{id}")
    public Sailing get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Sailing create(@Valid @RequestBody SailingPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改船期", "tms:intl:edit");
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public Sailing update(@PathVariable long id, @Valid @RequestBody SailingPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改船期", "tms:intl:edit");
        if (payload.id() == null) {
            payload = new SailingPayload(id, payload.routeId(), payload.vesselName(), payload.voyageNo(),
                    payload.etdAt(), payload.etaAt(), payload.cutoffAt(), payload.freeTimeDays(),
                    payload.spaceLeft(), payload.status(), payload.remark());
        }
        return service.update(payload);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("新增/修改船期", "tms:intl:edit");
        service.delete(id);
    }
}
