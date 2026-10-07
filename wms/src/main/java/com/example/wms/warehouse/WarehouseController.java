package com.example.wms.warehouse;

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
 * 仓库接口。
 *
 * <p>身份头由网关清洗后注入：外部伪造的 X-User-Id 已在网关第一步被删掉，
 * 所以这里可以无条件信任。所有登录用户均可读写，不做角色限制。</p>
 */
@RestController
@RequestMapping("/api/wms/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService service;

    @GetMapping
    public WarehouseService.PageResult<Warehouse> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, status, page, size);
    }

    /** 下拉/选择器用的轻量列表。 */
    @GetMapping("/brief")
    public List<Warehouse> brief() {
        return service.brief();
    }

    @GetMapping("/{id}")
    public Warehouse detail(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Warehouse create(@Valid @RequestBody WarehousePayload payload) {
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public Warehouse update(@PathVariable long id, @Valid @RequestBody WarehousePayload payload) {
        // payload.id() 可能漏传或传错，以路径 id 为准（5 个国际仓属性原样带过去）
        return service.update(payload.withId(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
