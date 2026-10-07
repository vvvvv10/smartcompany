package com.example.crm.followup;

import com.example.crm.customer.CustomerService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 跟进记录接口。所有登录用户可读写。 */
@RestController
@RequestMapping("/api/crm")
@RequiredArgsConstructor
public class FollowUpController {

    private static final String HEADER_USER_ID = "X-User-Id";

    private final FollowUpService service;
    private final CustomerService customerService;

    @GetMapping("/customers/{customerId}/follow-ups")
    public List<FollowUp> listByCustomer(@PathVariable long customerId) {
        customerService.get(customerId); // 客户不存在直接 404
        return service.list(customerId, 200);
    }

    /** 全局时间线：看板"最近跟进"用。 */
    @GetMapping("/follow-ups")
    public List<FollowUp> listAll(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        return service.list(null, limit);
    }

    @PostMapping("/customers/{customerId}/follow-ups")
    public FollowUp create(
            @PathVariable long customerId,
            @Valid @RequestBody FollowUpPayload payload,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId) {
        customerService.get(customerId);
        long creatorId = requireUserId(userId);
        return service.create(customerId, payload, creatorId, "用户" + creatorId);
    }

    @DeleteMapping("/follow-ups/{id}")
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
