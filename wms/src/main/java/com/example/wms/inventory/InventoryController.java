package com.example.wms.inventory;

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
 * 库存接口。
 *
 * <p>身份头由网关清洗后注入：外部伪造的 X-User-Id 已在网关第一步被删掉，
 * 所以这里可以无条件信任。所有登录用户均可读写，不做角色限制。</p>
 */
@RestController
@RequestMapping("/api/wms/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService service;

    @GetMapping
    public InventoryService.PageResult<Inventory> list(
            @RequestParam(value = "warehouseId", required = false) Long warehouseId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(warehouseId, keyword, page, size);
    }

    @GetMapping("/{id}")
    public Inventory detail(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Inventory create(@Valid @RequestBody InventoryPayload payload) {
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public Inventory update(@PathVariable long id, @Valid @RequestBody InventoryPayload payload) {
        if (payload.id() == null) {
            payload = new InventoryPayload(id, payload.warehouseId(), payload.productName(),
                    payload.sku(), payload.quantity());
        }
        return service.update(payload);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
