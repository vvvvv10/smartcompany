package com.example.crm.customer;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户接口。
 *
 * <p>身份头由网关清洗后注入：外部伪造的 X-User-Id 已在网关第一步被删掉，
 * 所以这里可以无条件信任。所有登录用户均可读写，不做角色限制。</p>
 */
@RestController
@RequestMapping("/api/crm/customers")
@RequiredArgsConstructor
public class CustomerController {

    /** 与网关 AuthGlobalFilter.HEADER_USER_ID 保持一致 */
    private static final String HEADER_USER_ID = "X-User-Id";

    private final CustomerService service;

    @GetMapping
    public CustomerService.PageResult<Customer> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, status, level, page, size);
    }

    /** 下拉/选择器用的轻量列表。 */
    @GetMapping("/brief")
    public List<Customer> brief() {
        return service.brief();
    }

    @GetMapping("/{id}")
    public Customer detail(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Customer create(
            @Valid @RequestBody CustomerPayload payload,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId) {
        long ownerId = parseUserId(userId);
        // 网关只注入 X-User-Id，昵称不在信任头里（可被伪造），归属人名称先用占位，
        // 前端展示时可自行补全；不要读取客户端传来的"昵称"字段。
        return service.create(payload, ownerId, "用户" + ownerId);
    }

    @PutMapping("/{id}")
    public Customer update(@PathVariable long id, @Valid @RequestBody CustomerPayload payload) {
        // payload.id() 可能漏传或传错，以路径 id 为准（8 个国际属性原样带过去）
        return service.update(payload.withId(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    private long parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            // 缺身份头说明绕过了网关，显式失败而不是匿名放行
            throw new IllegalArgumentException("缺少网关身份头 X-User-Id，请通过网关访问");
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("非法的用户身份: " + userId);
        }
    }
}
