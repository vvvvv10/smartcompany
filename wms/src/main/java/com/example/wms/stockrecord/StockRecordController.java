package com.example.wms.stockrecord;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出入库记录接口。
 *
 * <p>身份头由网关清洗后注入：外部伪造的 X-User-Id 已在网关第一步被删掉，
 * 所以这里可以无条件信任。所有登录用户均可读写，不做角色限制。</p>
 */
@RestController
@RequestMapping("/api/wms/stock-records")
@RequiredArgsConstructor
public class StockRecordController {

    private final StockRecordService service;

    @GetMapping
    public StockRecordService.PageResult<StockRecord> list(
            @RequestParam(value = "warehouseId", required = false) Long warehouseId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "orderNos", required = false) String orderNos,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(warehouseId, type, orderNos, page, size);
    }

    @GetMapping("/{id}")
    public StockRecord detail(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public StockRecord create(@Valid @RequestBody StockRecordPayload payload) {
        return service.create(payload);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
