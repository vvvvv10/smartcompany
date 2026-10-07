package com.example.tms.driver;

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
 * 司机接口。
 *
 * <p>身份头由网关清洗后注入：外部伪造的 X-User-Id 已在网关第一步被删掉，
 * 所以这里可以无条件信任。所有登录用户均可读写，不做角色限制。</p>
 */
@RestController
@RequestMapping("/api/tms/drivers")
@RequiredArgsConstructor
public class DriverController {

    private final DriverService service;

    @GetMapping
    public DriverService.PageResult<Driver> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, status, page, size);
    }

    /** 下拉/选择器用的轻量列表。 */
    @GetMapping("/brief")
    public List<Driver> brief() {
        return service.brief();
    }

    @GetMapping("/{id}")
    public Driver detail(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Driver create(@Valid @RequestBody DriverPayload payload) {
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public Driver update(@PathVariable long id, @Valid @RequestBody DriverPayload payload) {
        if (payload.id() == null) {
            payload = new DriverPayload(id, payload.name(), payload.phone(),
                    payload.status(), payload.remark());
        }
        return service.update(payload);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
