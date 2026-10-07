package com.example.wms.intl.packtask;

import com.example.wms.intl.IntlCurrentUser;
import com.example.wms.intl.IntlPage;
import jakarta.validation.Valid;
import java.util.List;
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

/**
 * 备货任务接口（/api/wms/intl/pack-tasks）。
 *
 * <p>本服务被 OMS 的 LinkageClient 调用的两个端点：
 * {@code GET ?orderNos=}（列表聚合）与本 POST（OMS→WMS 派单，M2 才启用）。
 * M1 里 OMS 不主动建任务，任务由人工在本页填出口子单号建。</p>
 */
@RestController
@RequestMapping("/api/wms/intl/pack-tasks")
@RequiredArgsConstructor
public class PackTaskController {

    private final PackTaskService service;

    @GetMapping
    public IntlPage<PackTask> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "orderNos", required = false) String orderNos,
            @RequestParam(value = "warehouseId", required = false) Long warehouseId,
            @RequestParam(value = "assigneeId", required = false) Long assigneeId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, status, orderNos, warehouseId, assigneeId, page, size);
    }

    @GetMapping("/brief")
    public List<PackTask> brief(@RequestParam(value = "status", required = false) String status) {
        return service.brief(status);
    }

    @GetMapping("/{id}")
    public PackTaskDetail get(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping
    public PackTaskDetail create(@Valid @RequestBody PackTaskPayload payload) {
        IntlCurrentUser.requirePermission("处理备货任务", "wms:intl:edit");
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public PackTaskDetail update(@PathVariable long id, @Valid @RequestBody PackTaskPayload payload) {
        IntlCurrentUser.requirePermission("处理备货任务", "wms:intl:edit");
        return service.update(payload.withId(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("处理备货任务", "wms:intl:edit");
        service.delete(id);
    }

    /** 推进状态（唯一推进入口）：待处理→拣货中→已装箱→已贴标→已交接。 */
    @PatchMapping("/{id}/status")
    public PackTaskDetail changeStatus(@PathVariable long id,
                                       @Valid @RequestBody PackTaskStatusPayload payload) {
        IntlCurrentUser.requirePermission("处理备货任务", "wms:intl:edit");
        return service.changeStatus(id, payload);
    }

    @GetMapping("/{id}/items")
    public List<PackTaskItem> items(@PathVariable long id) {
        service.get(id); // 过租户闸
        return service.items(id);
    }

    @PostMapping("/{id}/items")
    public PackTaskItem addItem(@PathVariable long id, @Valid @RequestBody PackTaskItemPayload payload) {
        IntlCurrentUser.requirePermission("处理备货任务", "wms:intl:edit");
        return service.addItem(id, payload);
    }

    @PutMapping("/{id}/items/{itemId}")
    public PackTaskItem updateItem(@PathVariable long id, @PathVariable long itemId,
                                   @Valid @RequestBody PackTaskItemPayload payload) {
        IntlCurrentUser.requirePermission("处理备货任务", "wms:intl:edit");
        return service.updateItem(id, itemId, payload);
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public void deleteItem(@PathVariable long id, @PathVariable long itemId) {
        IntlCurrentUser.requirePermission("处理备货任务", "wms:intl:edit");
        service.deleteItem(id, itemId);
    }
}
