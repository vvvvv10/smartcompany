package com.example.crm.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人工作台接口：/api/crm/workbench。
 *
 * <p>身份头 X-User-Id 由网关清洗后注入（外部伪造的头已在网关第一步删掉），
 * 这里只读不写，缺失时直接拒绝，避免把别人的数据当成自己的。</p>
 */
@RestController
@RequestMapping("/api/crm")
@RequiredArgsConstructor
public class WorkbenchController {

    private static final String HEADER_USER_ID = "X-User-Id";

    private final WorkbenchService service;

    @GetMapping("/workbench")
    public WorkbenchService.Workbench workbench(
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId) {
        return service.summary(requireUserId(userId));
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
