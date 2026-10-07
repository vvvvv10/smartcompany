package com.example.user;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册后可选加入钉钉 / 企业微信。
 *
 * <p>注册接口本身保持"纯净"（只管发令牌），加入企业组织是注册成功之后的可选动作，
 * 前端注册完成页给出两个按钮，点了才调这里 —— 与「注册成功后，可选加入」的需求一致。</p>
 *
 * <p>身份只信网关注入的 X-User-Id；需要登录态（这些路径不在网关白名单内）。</p>
 */
@RestController
@RequestMapping("/api/users/me/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private static final String HEADER_USER_ID = "X-User-Id";

    private final OrganizationService organizations;
    private final UserDirectory users;

    @GetMapping
    public OrganizationService.MyOrganizations list(
            @RequestHeader(value = HEADER_USER_ID, required = false) Long userId) {
        return new OrganizationService.MyOrganizations(organizations.listMine(requireUser(userId)));
    }

    /**
     * 加入某个渠道。重复调用是幂等刷新，不会产生重复记录。
     *
     * <p>注意：X-User-Nickname 不是网关信任头（可被客户端伪造，且网关不注入它），
     * 所以花名从数据库取，不从请求头取。</p>
     */
    @PostMapping("/{channel}")
    public ResponseEntity<?> join(
            @RequestHeader(value = HEADER_USER_ID, required = false) Long userId,
            @PathVariable String channel) {
        long id = requireUser(userId);
        String nickname = users.findById(id)
                .map(UserDirectory.Account::nickname)
                .orElse("u" + id);
        OrganizationService.JoinResult result = organizations.join(id, channel, nickname);
        // FAILED 也返回 200：失败信息在 body 里，前端按 status 展示错误态而不是当网络异常处理
        return ResponseEntity.ok(Map.of(
                "channel", result.channel(),
                "status", result.status(),
                "externalId", result.externalId(),
                "detail", result.detail(),
                "mock", result.mock()));
    }

    @DeleteMapping("/{channel}")
    public ResponseEntity<?> leave(
            @RequestHeader(value = HEADER_USER_ID, required = false) Long userId,
            @PathVariable String channel) {
        organizations.leave(requireUser(userId), channel);
        return ResponseEntity.noContent().build();
    }

    private long requireUser(Long userId) {
        if (userId == null) {
            // 缺身份头说明绕过了网关，显式失败而不是匿名放行
            throw new ForbiddenException("缺少网关身份头 X-User-Id，请通过网关访问");
        }
        return userId;
    }
}
