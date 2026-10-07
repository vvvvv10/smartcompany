package com.example.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 企业通讯录 provider 之二：企业微信。凭据**只从管理台「集成配置」页读**
 * （表 integration_settings，页面保存即热生效）——不读服务器环境变量；
 * 没配时 isConfigured()=false，建号同步跳过、组织页显示未配置。
 *
 * <p>接口（qyapi.weixin.qq.com，全 GET/POST + JSON）：</p>
 * <ul>
 *   <li>gettoken —— 本地缓存、按凭据指纹失效、过期前 60s 刷新</li>
 *   <li>user/create —— 直接按手机号建成员进 1 号根部门（企微建人不需要先发邀请）</li>
 *   <li>department/list?id=1 —— 一次拉全树，按 parentid 拼层级</li>
 *   <li>user/list?department_id=X&amp;fetch_child=0 —— 每部门直属成员</li>
 * </ul>
 */
@Slf4j
@Service
public class WeComService {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private final IntegrationSettingsService settings;

    public WeComService(IntegrationSettingsService settings) {
        this.settings = settings;
    }

    private volatile String cachedToken;
    private volatile long tokenExpireAt;
    /** 取 token 时用的凭据指纹：页面改了凭据，指纹对不上就直接重取，不用重启容器 */
    private volatile String tokenFingerprint;

    private String corpId() {
        return settings.get(IntegrationSettingsService.WECOM_CORP_ID);
    }

    private String corpSecret() {
        return settings.get(IntegrationSettingsService.WECOM_CORP_SECRET);
    }

    public boolean isConfigured() {
        String id = corpId();
        String secret = corpSecret();
        return id != null && !id.isBlank() && secret != null && !secret.isBlank();
    }

    /** 当前 CorpId/Secret 脱敏值——明文永不出这个类，配置页只看得到掩码 */
    public String maskedKey() {
        return IntegrationSettingsService.mask(corpId());
    }

    public String maskedSecret() {
        return IntegrationSettingsService.maskSecret(corpSecret());
    }

    private synchronized String accessToken() throws Exception {
        String id = corpId();
        String secret = corpSecret();
        String fingerprint = id + "|" + secret;
        if (cachedToken != null && fingerprint.equals(tokenFingerprint)
                && System.currentTimeMillis() < tokenExpireAt - 60_000) {
            return cachedToken;
        }
        cachedToken = fetchToken(id, secret);
        tokenFingerprint = fingerprint;
        tokenExpireAt = System.currentTimeMillis() + 7_140_000; // 远端 expires_in 恒 7200s，留 60s 余量
        return cachedToken;
    }

    /** 强制用当前凭据取一次 token（绕过缓存）——「测试连接」要验的就是刚保存的这组值 */
    private String fetchToken(String id, String secret) throws Exception {
        String url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid="
                + URLEncoder.encode(id, StandardCharsets.UTF_8)
                + "&corpsecret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());
        if (root.path("errcode").asInt(-1) != 0) {
            throw new IllegalStateException("企业微信取 token 失败: " + root.path("errmsg").asText());
        }
        String token = root.path("access_token").asText();
        if (token.isBlank()) {
            throw new IllegalStateException("企业微信返回了空 token");
        }
        return token;
    }

    /**
     * 测试连接：不落缓存、直接拿当前凭据换一次 token。返回给人看的一句话，
     * 永不抛异常（页面按钮的回调不能因为网络挂了就炸）。
     */
    public String testConnection() {
        if (!isConfigured()) {
            return "未配置：先在管理台「集成配置」页保存 CorpID/Secret 再测";
        }
        try {
            String token = fetchToken(corpId(), corpSecret());
            // 测通的 token 直接回填缓存，紧接着的组织树就不用再取一次
            synchronized (this) {
                cachedToken = token;
                tokenFingerprint = corpId() + "|" + corpSecret();
                tokenExpireAt = System.currentTimeMillis() + 7_140_000;
            }
            return "连接正常（token 获取成功）";
        } catch (Exception e) {
            return "连接失败：" + e.getMessage();
        }
    }

    /**
     * 把「姓名 + 手机号」建为企业微信成员（1 号根部门）。未配置返回 null（调用方跳过），
     * 其余情况返回结果文案，永不抛异常。
     */
    public String invite(String realName, String mobile) {
        if (!isConfigured()) {
            return null;
        }
        try {
            String token = accessToken();
            // userid 用手机号：稳定、可回查，也便于以后按手机号对账
            String body = mapper.writeValueAsString(Map.of(
                    "userid", mobile,
                    "name", realName,
                    "mobile", mobile,
                    "department", List.of(1)));
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create("https://qyapi.weixin.qq.com/cgi-bin/user/create?access_token=" + token))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            int err = root.path("errcode").asInt(-1);
            if (err == 0) {
                return "已创建成员（userid=" + mobile + "）";
            }
            String msg = root.path("errmsg").asText();
            // 幂等口径：userid 已在企业里 = 目标达成
            if (msg.contains("exist") || msg.contains("已经存在") || msg.contains("已存在")) {
                return "该成员已存在于企业";
            }
            return "同步失败（" + err + "）: " + msg;
        } catch (Exception e) {
            log.warn("企业微信加人失败 mobile={}", mobile, e);
            return "同步失败: " + e.getMessage();
        }
    }

    /** 组织架构：department/list 拉全树 + 每部门直属成员，拼成与钉钉同构的树。 */
    public List<DirectoryTypes.DeptNode> tree() {
        if (!isConfigured()) {
            return List.of();
        }
        try {
            String token = accessToken();
            JsonNode depts = call(token, "https://qyapi.weixin.qq.com/cgi-bin/department/list?access_token="
                    + token + "&id=1").path("department");
            if (!depts.isArray()) {
                return List.of();
            }
            Map<Long, DirectoryTypes.DeptNode> byId = new HashMap<>();
            // 先建节点（1 号根部门兜底补一个，企微的 department/list 通常会返回它）
            boolean hasRoot = false;
            for (JsonNode d : depts) {
                long id = d.path("id").asLong();
                if (id == 1) {
                    hasRoot = true;
                }
                byId.put(id, new DirectoryTypes.DeptNode(id, d.path("name").asText(),
                        new ArrayList<>(), new ArrayList<>()));
            }
            if (!hasRoot) {
                byId.put(1L, new DirectoryTypes.DeptNode(1L, "全企业", new ArrayList<>(), new ArrayList<>()));
                // 结果里没有 1 号根部门时才单独补一次直属成员，否则与上面的按部门循环重复
                JsonNode rootUsers = call(token, "https://qyapi.weixin.qq.com/cgi-bin/user/list?access_token="
                        + token + "&department_id=1&fetch_child=0").path("user");
                if (rootUsers.isArray()) {
                    for (JsonNode u : rootUsers) {
                        byId.get(1L).members().add(new DirectoryTypes.Member(
                                u.path("userid").asText(), u.path("name").asText(), u.path("position").asText()));
                    }
                }
            }
            // 按 parentid 挂层级；parent 不在结果里的挂根下
            DirectoryTypes.DeptNode root = byId.get(1L);
            for (JsonNode d : depts) {
                long id = d.path("id").asLong();
                if (id == 1) {
                    continue;
                }
                long parentId = d.path("parentid").asLong();
                DirectoryTypes.DeptNode parent = byId.getOrDefault(parentId, root);
                parent.children().add(byId.get(id));
            }
            // 每部门直属成员（fetch_child=0 只要直属，避免重复计人）
            for (JsonNode d : depts) {
                long id = d.path("id").asLong();
                JsonNode users = call(token, "https://qyapi.weixin.qq.com/cgi-bin/user/list?access_token="
                        + token + "&department_id=" + id + "&fetch_child=0").path("user");
                if (!users.isArray()) {
                    continue;
                }
                for (JsonNode u : users) {
                    byId.get(id).members().add(new DirectoryTypes.Member(
                            u.path("userid").asText(),
                            u.path("name").asText(),
                            u.path("position").asText()));
                }
            }
            // 根部门成员已按部门循环取过，此处不再重复
            return List.of(root);
        } catch (Exception e) {
            log.warn("拉取企业微信组织失败", e);
            return List.of();
        }
    }

    private JsonNode call(String token, String url) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }
}
