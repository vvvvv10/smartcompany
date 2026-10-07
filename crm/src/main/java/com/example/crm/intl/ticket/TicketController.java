package com.example.crm.intl.ticket;

import com.example.crm.intl.IntlCurrentUser;
import com.example.crm.intl.IntlPage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 出口异常工单接口（/api/crm/intl/tickets）。 */
@RestController
@RequestMapping("/api/crm/intl/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService service;

    @GetMapping
    public IntlPage<Ticket> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "severity", required = false) String severity,
            @RequestParam(value = "customerId", required = false) Long customerId,
            @RequestParam(value = "orderNos", required = false) String orderNos,
            @RequestParam(value = "shipmentNos", required = false) String shipmentNos,
            @RequestParam(value = "ownerId", required = false) Long ownerId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, status, category, severity, customerId, orderNos, shipmentNos,
                ownerId, page, size);
    }

    @GetMapping("/{id}")
    public Ticket get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Ticket create(@Valid @RequestBody TicketPayload payload) {
        IntlCurrentUser.requirePermission("新增/处理异常工单", "crm:intl:edit");
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public Ticket update(@PathVariable long id, @Valid @RequestBody TicketPayload payload) {
        IntlCurrentUser.requirePermission("新增/处理异常工单", "crm:intl:edit");
        return service.update(payload.withId(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("新增/处理异常工单", "crm:intl:edit");
        service.delete(id);
    }

    /** 推进状态（进 RESOLVED 必须带 resolution）。 */
    @PatchMapping("/{id}/status")
    public Ticket changeStatus(@PathVariable long id, @Valid @RequestBody TicketStatusPayload payload) {
        IntlCurrentUser.requirePermission("新增/处理异常工单", "crm:intl:edit");
        return service.changeStatus(id, payload);
    }
}
