package com.example.crm.intl.partner;

import com.example.crm.intl.IntlCurrentUser;
import com.example.crm.intl.IntlPage;
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

/** 渠道商接口（/api/crm/intl/partners）。登录即可读写。 */
@RestController
@RequestMapping("/api/crm/intl/partners")
@RequiredArgsConstructor
public class PartnerController {

    private final PartnerService service;

    @GetMapping
    public IntlPage<CarrierPartner> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "partnerType", required = false) String partnerType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, partnerType, status, page, size);
    }

    @GetMapping("/brief")
    public List<CarrierPartner> brief(@RequestParam(value = "partnerType", required = false) String partnerType) {
        return service.brief(partnerType);
    }

    @GetMapping("/{id}")
    public CarrierPartner get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public CarrierPartner create(@Valid @RequestBody PartnerPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改货代渠道商", "crm:intl:edit");
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public CarrierPartner update(@PathVariable long id, @Valid @RequestBody PartnerPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改货代渠道商", "crm:intl:edit");
        return service.update(payload.withId(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("新增/修改货代渠道商", "crm:intl:edit");
        service.delete(id);
    }
}
