package com.example.wms.intl.intransit;

import com.example.wms.intl.IntlCurrentUser;
import com.example.wms.intl.IntlPage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 在途库存接口（/api/wms/intl/in-transit）。 */
@RestController
@RequestMapping("/api/wms/intl/in-transit")
@RequiredArgsConstructor
public class InTransitController {

    private final InTransitService service;

    @GetMapping
    public IntlPage<InTransit> list(
            @RequestParam(value = "shipmentNos", required = false) String shipmentNos,
            @RequestParam(value = "orderNos", required = false) String orderNos,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(shipmentNos, orderNos, status, page, size);
    }

    @GetMapping("/{id}")
    public InTransit get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public InTransit create(@Valid @RequestBody InTransitPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改在途库存", "wms:intl:edit");
        return service.create(payload);
    }

    @PatchMapping("/{id}/status")
    public InTransit changeStatus(@PathVariable long id,
                                  @Valid @RequestBody InTransitStatusPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改在途库存", "wms:intl:edit");
        return service.changeStatus(id, payload);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("新增/修改在途库存", "wms:intl:edit");
        service.delete(id);
    }
}
