package com.example.tms.vehicle;

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
 * 车辆接口。
 *
 * <p>身份头由网关清洗后注入：外部伪造的 X-User-Id 已在网关第一步被删掉，
 * 所以这里可以无条件信任。所有登录用户均可读写，不做角色限制。</p>
 */
@RestController
@RequestMapping("/api/tms/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService service;

    @GetMapping
    public VehicleService.PageResult<Vehicle> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, status, page, size);
    }

    /** 下拉/选择器用的轻量列表。 */
    @GetMapping("/brief")
    public List<Vehicle> brief() {
        return service.brief();
    }

    @GetMapping("/{id}")
    public Vehicle detail(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Vehicle create(@Valid @RequestBody VehiclePayload payload) {
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public Vehicle update(@PathVariable long id, @Valid @RequestBody VehiclePayload payload) {
        if (payload.id() == null) {
            payload = new VehiclePayload(id, payload.plateNo(), payload.type(),
                    payload.status(), payload.driverName(), payload.remark(),
                    payload.costPerKm(), payload.dailyFixedCost(), payload.isExternal());
        }
        return service.update(payload);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
