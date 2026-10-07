package com.example.tms.intl.carrier;

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

/**
 * 承运商接口（/api/tms/intl/carriers）。
 *
 * <p>与本服务其它 Controller 一样：**登录即可读写，不做角色限制**。
 * 权限点 tms:intl:view / tms:intl:edit 只管 Web 菜单与路由显隐
 * （沿用四个业务服务的现状，见计划假设 A5）。</p>
 */
@RestController
@RequestMapping("/api/tms/intl/carriers")
@RequiredArgsConstructor
public class CarrierController {

    private final CarrierService service;

    @GetMapping
    public IntlPage<Carrier> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "carrierType", required = false) String carrierType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, carrierType, status, page, size);
    }

    @GetMapping("/brief")
    public List<Carrier> brief(@RequestParam(value = "carrierType", required = false) String carrierType) {
        return service.brief(carrierType);
    }

    @GetMapping("/{id}")
    public Carrier get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Carrier create(@Valid @RequestBody CarrierPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改承运商", "tms:intl:edit");
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public Carrier update(@PathVariable long id, @Valid @RequestBody CarrierPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改承运商", "tms:intl:edit");
        return service.update(withId(payload, id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("新增/修改承运商", "tms:intl:edit");
        service.delete(id);
    }

    /** PUT 路径上有 id 时以路径为准（payload 里的 id 可能漏传或传错）。 */
    static CarrierPayload withId(CarrierPayload payload, long id) {
        if (payload.id() != null && payload.id().equals(id)) {
            return payload;
        }
        return new CarrierPayload(id, payload.code(), payload.nameZh(), payload.nameEn(),
                payload.carrierType(), payload.country(), payload.contactName(), payload.contactPhone(),
                payload.contactEmail(), payload.serviceLevel(), payload.transitDays(), payload.cutOffHours(),
                payload.freeTimeDays(), payload.status(), payload.remark());
    }
}
