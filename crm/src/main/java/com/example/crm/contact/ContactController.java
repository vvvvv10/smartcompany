package com.example.crm.contact;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 联系人接口：/api/crm/customers/{customerId}/contacts 与 /api/crm/contacts/{id}。 */
@RestController
@RequestMapping("/api/crm")
@RequiredArgsConstructor
public class ContactController {

    private static final String HEADER_USER_ID = "X-User-Id";

    private final ContactService service;
    private final com.example.crm.customer.CustomerService customerService;

    @GetMapping("/customers/{customerId}/contacts")
    public List<Contact> list(@PathVariable long customerId) {
        customerService.get(customerId); // 404 提前抛出，避免对不存在的客户静默返回空列表
        return service.listByCustomer(customerId);
    }

    @PostMapping("/customers/{customerId}/contacts")
    public Contact create(
            @PathVariable long customerId,
            @Valid @RequestBody ContactPayload payload,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId) {
        customerService.get(customerId);
        long ownerId = requireUserId(userId);
        return service.create(customerId, payload, ownerId, "用户" + ownerId);
    }

    @PutMapping("/contacts/{id}")
    public Contact update(@PathVariable long id, @Valid @RequestBody ContactPayload payload) {
        return service.update(id, payload);
    }

    @DeleteMapping("/contacts/{id}")
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
