package com.example.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 注册后可选加入钉钉 / 企业微信（邀请进企业组织）。
 *
 * <p>两种运行模式：</p>
 * <ul>
 *   <li><b>真实模式</b>：管理台「集成配置」页配了企业凭证（表 integration_settings，
 *       与通讯录 provider 同一份、同一处维护），走开放平台 gettoken + 创建成员接口，
 *       把花名同步成企业通讯录成员；</li>
 *   <li><b>mock 模式</b>：没配凭证时（默认），仅在 organization_members 落一条
 *       JOINED 记录并注明 mock —— 联调/演示环境不需要真的有企业主体。</li>
 * </ul>
 *
 * <p>幂等：uk_user_channel 唯一索引保证一个用户在同一渠道只有一条记录，
 * 重复调用直接刷新状态而不是插重复行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    /** 渠道：钉钉 */
    public static final String CHANNEL_DINGTALK = "DINGTALK";
    /** 渠道：企业微信 */
    public static final String CHANNEL_WECOM = "WECOM";

    private static final List<String> ALLOWED = List.of(CHANNEL_DINGTALK, CHANNEL_WECOM);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    /** 凭据唯一来源：管理台「集成配置」页（不读环境变量，与 DingDingService/WeComService 同口径） */
    private final IntegrationSettingsService settings;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 单个渠道的绑定记录 */
    public record Membership(String channel, String externalId, String status,
                             String detail, String updatedAt) {
    }

    /** 加入结果 */
    public record JoinResult(String channel, String status, String externalId,
                             String detail, boolean mock) {
    }

    /** 我的全部组织绑定 */
    public record MyOrganizations(List<Membership> memberships) {
    }

    public List<Membership> listMine(long userId) {
        return jdbc.query("""
                select channel, external_id, status, detail, updated_at
                  from organization_members
                 where user_id = :userId
                 order by channel
                """, new MapSqlParameterSource("userId", userId), (rs, i) -> new Membership(
                rs.getString("channel"),
                rs.getString("external_id"),
                rs.getString("status"),
                rs.getString("detail"),
                rs.getString("updated_at")));
    }

    /**
     * 加入指定渠道的企业组织。
     *
     * @param userId  网关注入的 X-User-Id，绝不取客户端参数
     * @param channel DINGTALK / WECOM
     */
    @Transactional
    public JoinResult join(long userId, String channel, String nickname) {
        if (channel == null || !ALLOWED.contains(channel.trim().toUpperCase())) {
            throw new IllegalArgumentException("不支持的渠道，可选: DINGTALK / WECOM");
        }
        String target = channel.trim().toUpperCase();
        // 花名做企业侧 userid 的兜底显示名；真实侧 userid 用稳定且不含隐私的 u+id
        String externalUserId = "u" + userId;
        String displayName = StringUtils.hasText(nickname) ? nickname.trim() : externalUserId;

        boolean mockMode = !isConfigured(target);
        String status;
        String externalId;
        String detail;

        if (mockMode) {
            status = "JOINED";
            externalId = externalUserId;
            detail = "mock 模式：未配置企业凭证，仅本地记录。管理台「集成配置」页配好后即走开放平台真实邀请";
        } else {
            try {
                String token = fetchAccessToken(target);
                String resp = createMember(target, token, externalUserId, displayName);
                status = "JOINED";
                externalId = externalUserId;
                detail = shorten("已通过开放平台创建成员: " + resp);
            } catch (Exception ex) {
                log.warn("join {} failed for user {}", target, userId, ex);
                status = "FAILED";
                externalId = "";
                detail = shorten("开放平台调用失败: " + ex.getMessage());
            }
        }

        jdbc.update("""
                insert into organization_members (user_id, channel, external_id, status, detail)
                values (:userId, :channel, :externalId, :status, :detail)
                on duplicate key update
                    external_id = values(external_id),
                    status = values(status),
                    detail = values(detail),
                    updated_at = now()
                """, new MapSqlParameterSource(Map.of(
                "userId", userId,
                "channel", target,
                "externalId", externalId,
                "status", status,
                "detail", detail)));

        return new JoinResult(target, status, externalId, detail, mockMode);
    }

    /** 解除绑定（本地记录）。真实侧的成员离职调用留给后续按需补。 */
    @Transactional
    public void leave(long userId, String channel) {
        int deleted = jdbc.update(
                "delete from organization_members where user_id = :userId and channel = :channel",
                new MapSqlParameterSource(Map.of("userId", userId,
                        "channel", channel.trim().toUpperCase())));
        if (deleted == 0) {
            throw new IllegalArgumentException("未绑定该渠道: " + channel);
        }
    }

    private boolean isConfigured(String channel) {
        return CHANNEL_DINGTALK.equals(channel)
                ? StringUtils.hasText(dingtalkAppKey()) && StringUtils.hasText(dingtalkAppSecret())
                : StringUtils.hasText(wecomCorpId()) && StringUtils.hasText(wecomCorpSecret());
    }

    // 凭据四件套：一律现查配置表——页面保存后，下一次 join 立即用新值
    private String dingtalkAppKey() {
        return settings.get(IntegrationSettingsService.DINGTALK_APP_KEY);
    }

    private String dingtalkAppSecret() {
        return settings.get(IntegrationSettingsService.DINGTALK_APP_SECRET);
    }

    private String wecomCorpId() {
        return settings.get(IntegrationSettingsService.WECOM_CORP_ID);
    }

    private String wecomCorpSecret() {
        return settings.get(IntegrationSettingsService.WECOM_CORP_SECRET);
    }

    /** 换取企业侧 access_token */
    private String fetchAccessToken(String channel) throws Exception {
        String url = CHANNEL_DINGTALK.equals(channel)
                ? "https://oapi.dingtalk.com/gettoken?corpid=" + dingtalkAppKey()
                + "&corpsecret=" + dingtalkAppSecret()
                : "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" + wecomCorpId()
                + "&corpsecret=" + wecomCorpSecret();

        JsonNode node = get(url);
        if (node.path("errcode").asInt(0) != 0) {
            throw new IllegalStateException("获取 token 失败: " + node.path("errmsg").asText());
        }
        return node.path("access_token").asText();
    }

    /**
     * 创建企业成员。
     *
     * <p>钉钉 topapi/user/create、企微 user/create 都要求外部 userid 唯一，
     * 这里用 "u{本地id}" 保证幂等（重复调用是覆盖更新，不会报重复）。</p>
     */
    private String createMember(String channel, String token, String externalUserId, String name) throws Exception {
        String url;
        String body;
        if (CHANNEL_DINGTALK.equals(channel)) {
            url = "https://oapi.dingtalk.com/topapi/user/create?access_token=" + token;
            body = objectMapper.writeValueAsString(Map.of(
                    "userid", externalUserId,
                    "name", name,
                    // 钉钉部门是数组且必填，1 = 根部门
                    "department", List.of(1)));
        } else {
            url = "https://qyapi.weixin.qq.com/cgi-bin/user/create?access_token=" + token;
            body = objectMapper.writeValueAsString(Map.of(
                    "userid", externalUserId,
                    "name", name,
                    "department", List.of(1)));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode node = objectMapper.readTree(response.body());
        if (node.path("errcode").asInt(0) != 0) {
            throw new IllegalStateException(node.path("errmsg").asText("未知错误")
                    + " (errcode=" + node.path("errcode").asInt() + ")");
        }
        return node.path("errmsg").asText("ok");
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    /** detail 列只有 255 字符，超长截断避免写库报错 */
    private static String shorten(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 255 ? value : value.substring(0, 252) + "...";
    }
}
