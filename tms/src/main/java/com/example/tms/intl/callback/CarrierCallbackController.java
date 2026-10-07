package com.example.tms.intl.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 承运商回调入口（M2-4）。
 *
 * <p><b>为什么路径是 {@code /api/tms/intl/callback/tracking} 而不是
 * {@code /api/tms/intl/shipments/{id}/tracking}</b>：后者要求调用方先知道我们的
 * 自增主键 {@code tms_shipments.id}。而回调的调用方是<b>承运商的服务器</b>，
 * 它手里只有它自己签发的单据号（海运 BL、空运 AWB）——我们的自增 id 是数据库内部身份，
 * 它既拿不到也不该知道。所以回调是<b>按单号反查</b>的：路径里不能有 id，
 * 报文里必须带单号（{@code messageReference} 或 {@code transportDocumentNumber}）。
 * 强行套用 REST 的「按资源 id 操作」惯例在这里就是错的形状。</p>
 *
 * <p><b>为什么不做 {@code IntlCurrentUser.requireAdminForWrite} 角色闸</b>
 * （P1-6 刚给 intl 包全部写端点加的那一道）：</p>
 * <p>那一道闸的前提是「操作是人发起的」——网关注入 {@code X-User-Roles}，
 * 没有 ADMIN 就拒。但回调<b>不是人在操作，是承运商的服务器在调</b>：
 * 它没有登录态、没有 JWT、也就没有角色头。挂上这道闸的结果是 100% 被拒，
 * 功能等于不存在。</p>
 * <p><b>这为什么不是一个提权通道</b>：鉴权边界整个搬到了 {@code X-Internal-Token} 上——
 * 没有正确 token 的请求根本进不到 Service（{@link #requireToken} 在方法第一行执行），
 * 角色闸本来就不在这条路径的职责范围内。一个合法的 token 意味着「网关已经确认了
 * 这个调用方是那个承运商」，角色对它没有意义（承运商不是我们的员工）。</p>
 * <p>与之相对的风险也已经想清楚了：<b>token 泄露 = 任何人都能伪造承运商状态</b>。
 * 缓解手段是 token 只从配置读、不进代码不进日志、比对用<b>定长比较</b>
 * （{@link #constantTimeEquals}，防时序侧信道），并且回调<b>推不动状态机</b>——
 * 它仍要过制裁/VGM 门禁，且映射不上的码只落轨迹。真正的收口方案（M4）是
 * 「token 按承运商粒度签发 + 与租户/承运商绑定表」，本期单 token 是最小可用形态。</p>
 *
 * <p><b>部署形态提醒</b>：网关的 {@code app.security.trusted-headers} 里含
 * {@code X-Internal-Token}，而 {@code AuthGlobalFilter.stripInternalHeaders} 会
 * 无条件剥掉所有 trusted header —— 所以<b>这个端点走网关是拿不到 token 的</b>，
 * 必然 401。这既是缺陷也是设计意图：用户网关的入站口不应该对外开一条「带个 token
 * 就能写轨迹」的路。承运商应当直连本服务（容器 8084）。M4 要把它做成公开可达时，
 * 需要在网关上为这一条路径单独开口并放行该头。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/tms/intl/callback")
@RequiredArgsConstructor
public class CarrierCallbackController {

    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    private final CarrierCallbackService callbackService;
    private final TmsIntlProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 承运商推 IFTSTA 形状的状态报文。
     *
     * <p>报文形状见 {@link IftstaMessage} 的类注释（逐字段标了 IFTSTA 段名与元素号）。
     * 逐条独立处理，一条失败不影响其余——见 {@link CarrierCallbackService}。</p>
     */
    @PostMapping(value = "/tracking", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CarrierCallbackService.CallbackResponse receive(
            @RequestHeader(value = HEADER_INTERNAL_TOKEN, required = false) String internalToken,
            HttpServletRequest request) throws Exception {
        requireToken(internalToken);

        // 原文必须逐字留存：raw_payload 是「对账时承运商会拿他们的报文质疑我们的节点时间」
        // 这句话的物质基础。反序列化之后就再也拿不回原始字节了，所以先读 String。
        String raw = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            throw new IllegalArgumentException("请求体为空");
        }
        IftstaMessage message = objectMapper.readValue(raw, IftstaMessage.class);
        // 回填原文到 DTO 内部（record 不可变，所以造一个带 rawPayload 的副本）
        message = new IftstaMessage(message.messageType(), message.messageVersion(),
                message.messageRelease(), message.controllingAgency(), message.messageReferenceNumber(),
                message.documentMessageName(), message.messageFunctionCode(), message.events(), raw);

        log.info("收到承运商回调 类型={} 报文={} 事件数={}",
                message.messageType(), message.messageReferenceNumber(),
                message.events() == null ? 0 : message.events().size());

        return callbackService.handle(message);
    }

    /**
     * 回调 token 校验：无 → 401，错 → 403（评审 M2-4 验收标准 ④）。
     *
     * <p><b>未配置时一律拒绝</b>，而不是「没配就跳过校验」：漏配环境变量导致的默认放行，
     * 是这类内部端点最经典也最致命的事故形态（上线时没人发现，直到有人拿任意单号
     * 把全公司的运单状态改了）。</p>
     */
    private void requireToken(String presented) {
        String expected = properties.callback() == null ? null : properties.callback().internalToken();
        if (expected == null || expected.isBlank()) {
            log.error("回调端点未配置 tms.intl.callback.internal-token，拒绝一切回调请求");
            throw new CallbackAuthException(403, "internal_token_not_configured",
                    "服务端未配置回调 token，请联系管理员");
        }
        if (presented == null || presented.isBlank()) {
            throw new CallbackAuthException(401, "missing_internal_token",
                    "缺少 " + HEADER_INTERNAL_TOKEN + " 请求头");
        }
        if (!constantTimeEquals(expected, presented.trim())) {
            // 只说「不匹配」，不说长度、不说前缀、不回显任何一方的值：
            // 错误信息量对调用方应当为零，否则这个端点就成了逐位猜 token 的 oracle。
            log.warn("回调 token 不匹配 来源={}", requesterHint());
            throw new CallbackAuthException(403, "invalid_internal_token",
                    HEADER_INTERNAL_TOKEN + " 不匹配");
        }
    }

    /**
     * 定长时间比较，**不用 {@code String.equals}**。
     *
     * <p>{@code equals} 会在第一个不同字符处短路返回，逐位比较的时间差可以被用来
     * 逐位猜出正确 token（时序侧信道）。这里的对手方是拿到端点访问权的攻击者，
     * 所以哪怕当前部署在内网，也不能把这个「省下的几毫秒」当成安全余量。
     * 做法是把两边都摘要成定长字节再比，摘要耗时与内容无关。</p>
     */
    private boolean constantTimeEquals(String expected, String presented) {
        byte[] a = sha256(expected);
        byte[] b = sha256(presented);
        return MessageDigest.isEqual(a, b);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺 SHA-256，无法做定长比较", e);
        }
    }

    /** 日志里的来源线索。刻意只有来源、没有 token 值。 */
    private String requesterHint() {
        return "来自服务端请求";
    }
}