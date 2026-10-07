package com.example.tms.intl.customs;

import com.example.tms.intl.IntlCurrentUser;
import com.example.tms.intl.IntlPage;
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
 * 出口报关接口（/api/tms/intl/customs）。
 *
 * <p>PATCH /{id}/status 进 RELEASED 时联动运单推进到 EXPORT_DECLARED，
 * 见 {@link CustomsService#changeStatus}。</p>
 */
@RestController
@RequestMapping("/api/tms/intl/customs")
@RequiredArgsConstructor
public class CustomsController {

    private final CustomsService service;

    @GetMapping
    public IntlPage<CustomsDeclaration> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "shipmentNos", required = false) String shipmentNos,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, status, shipmentNos, page, size);
    }

    @GetMapping("/{id}")
    public CustomsDeclaration get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public CustomsDeclaration create(@Valid @RequestBody CustomsDeclarationPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改出口报关单", "tms:intl:edit");
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public CustomsDeclaration update(@PathVariable long id, @Valid @RequestBody CustomsDeclarationPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改出口报关单", "tms:intl:edit");
        return service.update(payload.withId(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("新增/修改出口报关单", "tms:intl:edit");
        service.delete(id);
    }

    /** 推进报关状态（进 RELEASED 联动运单）。 */
    @PatchMapping("/{id}/status")
    public CustomsDeclaration changeStatus(@PathVariable long id,
                                           @Valid @RequestBody CustomsStatusPayload payload) {
        IntlCurrentUser.requirePermission("新增/修改出口报关单", "tms:intl:edit");
        return service.changeStatus(id, payload);
    }
}
