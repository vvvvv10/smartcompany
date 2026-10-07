package com.example.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户注册 / 登录入口。
 *
 * <p>这些接口之所以能被匿名访问，是因为网关把 /api/auth/** 配成了白名单；
 * 也正因为如此，验证码校验、密码强度、频率限制必须在这里做扎实——
 * 网关层面已经没有第二道防线了。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String CAPTCHA_KEY_PREFIX = "auth:captcha:";
    private static final String CAPTCHA_RESEND_LOCK = "auth:captcha:lock:";
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);
    private static final Duration RESEND_LOCK_TTL = Duration.ofSeconds(60);

    private final UserDirectory users;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final AdminUserService adminService;

    public record CaptchaRequest(@NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String phone) {
    }

    /**
     * 注册即取花名：nickname 全局唯一（uk_nickname 索引兜底），
     * 注册后可通过 PUT /api/users/me/nickname 修改。
     */
    public record RegisterRequest(@NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String phone,
                                  @NotBlank @Size(min = 8, max = 64) String password,
                                  @NotBlank @Size(min = 4, max = 6) String captcha,
                                  @NotBlank(message = "花名不能为空")
                                  @Size(min = 2, max = 32, message = "花名长度 2-32 字")
                                  String nickname) {
    }

    public record LoginRequest(@NotBlank String account, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record UserRegisteredEvent(long userId, String account) {
    }

    @Value("${app.dev.reveal-captcha:false}")
    private boolean revealCaptcha;

    @PostMapping("/captcha")
    public ResponseEntity<?> sendCaptcha(@RequestBody @Valid CaptchaRequest request) {
        String phone = request.phone();
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(CAPTCHA_RESEND_LOCK + phone, "1", RESEND_LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("code", "too_many_requests", "message", "请稍后再试"));
        }

        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        redis.opsForValue().set(CAPTCHA_KEY_PREFIX + phone, code, CAPTCHA_TTL);

        // 生产环境这里是调用短信/邮件通道，返回值不能包含 code。
        // REVEAL_CAPTCHA=true 时把验证码回给前端，只用于自己搭的联调/演示环境。
        Map<String, Object> body = new HashMap<>();
        body.put("sent", true);
        body.put("expiresIn", CAPTCHA_TTL.toSeconds());
        if (revealCaptcha) {
            body.put("code", code);
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody @Valid RegisterRequest request) {
        String captchaKey = CAPTCHA_KEY_PREFIX + request.phone();
        String expected = redis.opsForValue().get(captchaKey);
        if (expected == null || !expected.equals(request.captcha())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "captcha_invalid", "message", "验证码错误或已过期"));
        }
        if (users.existsByAccount(request.phone())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "account_exists", "message", "该账号已注册"));
        }
        // 花名全局唯一：应用侧预检只为报错友好，真正兜底是注册写入时的 uk_nickname 唯一索引
        if (users.existsByNickname(request.nickname().trim())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "nickname_exists", "message", "这个花名已被占用，换一个试试"));
        }

        long userId;
        try {
            userId = users.create(request.phone(), passwordEncoder.encode(request.password()),
                    request.nickname().trim());
        } catch (DuplicateKeyException ex) {
            // 并发注册撞花名/撞账号：唯一索引拦下的，翻译成业务错误而不是 500
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "duplicate", "message", "账号或花名已被占用"));
        }
        redis.delete(captchaKey);

        // 注册成功事件：积分账户、营销档案、风控基线等下游在这里订阅，不要在注册接口里同步调用它们
        events.publishEvent(new UserRegisteredEvent(userId, request.phone()));

        return ResponseEntity.status(HttpStatus.CREATED).body(tokens.issue(userId));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody @Valid LoginRequest request) {
        // 账号不存在和密码错误返回完全一致的结果，避免账号枚举
        var unauthorized = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", "invalid_credential", "message", "账号或密码错误"));

        var account = users.findByAccount(request.account());
        if (account.isEmpty() || !passwordEncoder.matches(request.password(), account.get().passwordHash())) {
            return unauthorized;
        }

        // TODO 风控钩子：设备指纹、异地登录、连续失败次数，命中后走二次验证
        UserDirectory.Account target = account.get();
        adminService.touchLogin(target.id());
        return ResponseEntity.ok(tokens.issue(target.id()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody @Valid RefreshRequest request) {
        return tokens.rotate(request.refreshToken())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", "refresh_invalid", "message", "登录状态已失效，请重新登录")));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody(required = false) RefreshRequest request) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        if (token != null) {
            tokens.parseAllowingExpired(token).ifPresent(tokens::revokeAccessToken);
        }
        if (request != null && request.refreshToken() != null) {
            tokens.revokeRefreshToken(request.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }
}
