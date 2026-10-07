package com.example.wms.intl.batch;

import com.example.wms.intl.IntlCurrentUser;
import com.example.wms.intl.IntlPage;
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
 * 库存批次接口（/api/wms/intl/inventory-batches）。
 *
 * <p>{@code /fefo} 排在 {@code /{id}} 前面声明——Spring 的路径匹配是「最具体的优先」，
 * 但显式把静态段写在前面更省心，也避免有人后来在 {@code /{id}} 上加了正则把它抢走。</p>
 */
@RestController
@RequestMapping("/api/wms/intl/inventory-batches")
@RequiredArgsConstructor
public class InventoryBatchController {

    private final InventoryBatchService service;

    @GetMapping
    public IntlPage<InventoryBatch> list(
            @RequestParam(value = "warehouseId", required = false) Long warehouseId,
            @RequestParam(value = "sku", required = false) String sku,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "expiringWithinDays", required = false) Integer expiringWithinDays,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(warehouseId, sku, keyword, status, expiringWithinDays, page, size);
    }

    /** FEFO 推荐（按效期升序，只给还有在库量的批次）。 */
    @GetMapping("/fefo")
    public List<InventoryBatch> fefo(@RequestParam("warehouseId") long warehouseId,
                                     @RequestParam("sku") String sku) {
        return service.fefo(warehouseId, sku);
    }

    @GetMapping("/{id}")
    public InventoryBatch get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public InventoryBatch create(@Valid @RequestBody InventoryBatchPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改库存批次", "wms:intl:edit");
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public InventoryBatch update(@PathVariable long id, @Valid @RequestBody InventoryBatchPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改库存批次", "wms:intl:edit");
        return service.update(payload.withId(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("新增/修改库存批次", "wms:intl:edit");
        service.delete(id);
    }
}
