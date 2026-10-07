package com.example.crm.opportunity;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 商机接口。所有登录用户可读写。 */
@RestController
@RequestMapping("/api/crm/opportunities")
@RequiredArgsConstructor
public class OpportunityController {

    private static final String HEADER_USER_ID = "X-User-Id";

    private final OpportunityService service;
    private final com.example.crm.customer.CustomerService customerService;

    @GetMapping
    public OpportunityService.PageResult<Opportunity> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "customerId", required = false) Long customerId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, stage, customerId, page, size);
    }

    @GetMapping("/{id}")
    public Opportunity detail(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Opportunity create(
            @Valid @RequestBody OpportunityPayload payload,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId) {
        customerService.get(payload.customerId()); // 客户必须存在
        long ownerId = requireUserId(userId);
        return service.create(payload, ownerId, "用户" + ownerId);
    }

    @PutMapping("/{id}")
    public Opportunity update(@PathVariable long id, @Valid @RequestBody OpportunityPayload payload) {
        if (payload.id() == null) {
            payload = new OpportunityPayload(id, payload.customerId(), payload.name(), payload.amount(),
                    payload.stage(), payload.probability(), payload.expectedCloseDate(), payload.remark());
        }
        customerService.get(payload.customerId());
        return service.update(payload);
    }

    /** 阶段流转：PATCH /api/crm/opportunities/{id}/stage {"stage":"NEGOTIATION"} */
    @PatchMapping("/{id}/stage")
    public Opportunity moveStage(@PathVariable long id, @RequestBody Map<String, String> body) {
        String stage = body.get("stage");
        if (stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("缺少 stage");
        }
        return service.moveStage(id, stage);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    private long requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少网关身份头 X-User-Id，请通过网关访问");
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("非法的用户身份: " + userId);
        }
    }
}
