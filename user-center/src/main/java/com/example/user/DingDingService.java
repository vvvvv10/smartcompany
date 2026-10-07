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
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 企业通讯录 provider 之一：钉钉。凭据**只从管理台「集成配置」页读**
 * （表 integration_settings，页面保存即热生效）——不读服务器环境变量，
 * 没有第二种配置口径；没配时 isConfigured()=false，建号同步自动跳过，
 * 组织页显示「未配置」。
 *
 * <p>为什么放在 user-center：这里是「人」的归属地，组织相关的唯一权威方。</p>
 *
 * <p>可用性降级：凭证缺失/网络失败/errcode≠0 都**不能**让创建用户失败，
 * 那是把一个外部系统的故障放大成核心链路故障。调用方把结果文本透传给前端即可。</p>
 */
@Slf4j
@Service
public class DingDingService {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private final IntegrationSettingsService settings;

    public DingDingService(IntegrationSettingsService settings) {
        this.settings = settings;
    }

    private volatile String cachedToken;
    private volatile long tokenExpireAt;
    /** 取 token 时用的凭据指纹：页面改了凭据，指纹对不上就直接重取，不用重启容器 */
    private volatile String tokenFingerprint;

    private String appKey() {
        return settings.get(IntegrationSettingsService.DINGTALK_APP_KEY);
    }

    private String appSecret() {
        return settings.get(IntegrationSettingsService.DINGTALK_APP_SECRET);
    }

    /** 凭据是否已配置——组织页与建号同步都按它决定要不要走钉钉 */
    public boolean isConfigured() {
        String key = appKey();
        String secret = appSecret();
        return key != null && !key.isBlank() && secret != null && !secret.isBlank();
    }

    /** 当前 AppKey/Secret 脱敏值——明文永不出这个类，配置页只看得到掩码 */
    public String maskedKey() {
        return IntegrationSettingsService.mask(appKey());
    }

    public String maskedSecret() {
        return IntegrationSettingsService.maskSecret(appSecret());
    }

    private synchronized String accessToken() throws Exception {
        String key = appKey();
        String secret = appSecret();
        String fingerprint = key + "|" + secret;
        if (cachedToken != null && fingerprint.equals(tokenFingerprint)
                && System.currentTimeMillis() < tokenExpireAt - 60_000) {
            return cachedToken;
        }
        cachedToken = fetchToken(key, secret);
        tokenFingerprint = fingerprint;
        tokenExpireAt = System.currentTimeMillis() + 7_140_000; // 远端 expires_in 恒 7200s，留 60s 余量
        return cachedToken;
    }

    /** 强制用当前凭据取一次 token（绕过缓存）——「测试连接」要验的就是刚保存的这组值 */
    private String fetchToken(String key, String secret) throws Exception {
        String url = "https://oapi.dingtalk.com/gettoken?appkey="
                + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&appsecret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());
        if (root.path("errcode").asInt(-1) != 0) {
            throw new IllegalStateException("钉钉取 token 失败: " + root.path("errmsg").asText());
        }
        String token = root.path("access_token").asText();
        if (token.isBlank()) {
            throw new IllegalStateException("钉钉返回了空 token");
        }
        return token;
    }

    /**
     * 测试连接：不落缓存、直接拿当前凭据换一次 token。返回给人看的一句话，
     * 永不抛异常（页面按钮的回调不能因为网络挂了就炸）。
     */
    public String testConnection() {
        if (!isConfigured()) {
            return "未配置：先在管理台「集成配置」页保存 AppKey/AppSecret 再测";
        }
        try {
            String token = fetchToken(appKey(), appSecret());
            // 测通的 token 直接回填缓存，紧接着的组织树就不用再取一次
            synchronized (this) {
                cachedToken = token;
                tokenFingerprint = appKey() + "|" + appSecret();
                tokenExpireAt = System.currentTimeMillis() + 7_140_000;
            }
            return "连接正常（token 获取成功）";
        } catch (Exception e) {
            return "连接失败：" + e.getMessage();
        }
    }

    /**
     * 把「姓名 + 手机号」对应的成员加进企业（默认 1 号部门——钉钉的全企业根部）。
     * 返回给前端的结果文案，永不抛异常。
     *
     * <p>两个踩过的坑（2026-10-06 实测）：① 创建用户端点是
     * {@code /topapi/v2/user/create}，不是 {@code user/add}（后者回 errcode 22
     * 不合法 ApiName）；② {@code dept_id_list} 是逗号分隔的<b>字符串</b>，
     * 传数组会回 errcode 60003 部门不存在。</p>
     */
    public String invite(String realName, String mobile) {
        if (!isConfigured()) {
            return null;
        }
        try {
            String token = accessToken();
            String body = mapper.writeValueAsString(java.util.Map.of(
                    "name", realName, "mobile", mobile, "dept_id_list", "1"));
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create("https://oapi.dingtalk.com/topapi/v2/user/create?access_token=" + token))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            int err = root.path("errcode").asInt(-1);
            if (err == 0) {
                String userid = root.path("userid").asText();
                if (userid.isEmpty()) {
                    userid = root.path("result").path("userid").asText();
                }
                return "已同步到钉钉（userid=" + userid + "）";
            }
            // 40103：对方手机号已绑钉钉但还没进本组织，平台发邀请等其同意——
            // 这就是「拉他进企业」的常规路径，算成功
            if (err == 40103) {
                return "已向该手机号发出钉钉邀请，对方同意后即加入企业";
            }
            return "钉钉同步失败（" + err + "）: " + root.path("errmsg").asText();
        } catch (Exception e) {
            log.warn("钉钉加人失败 mobile={}", mobile, e);
            return "钉钉同步失败: " + e.getMessage();
        }
    }

    /** 组织架构=所有部门 + 每部门直属成员。返回给管理台的树形数据。 */
    public List<DirectoryTypes.DeptNode> tree() {
        try {
            String token = accessToken();
            // 根节点（1 号部门 = 全企业）自己也可能直接挂着人，单列一层免得漏
            DirectoryTypes.DeptNode root = new DirectoryTypes.DeptNode(1L, "全企业", descend(token, 1L, 0, 3), new ArrayList<>());
            JsonNode members = call(token,
                            "https://oapi.dingtalk.com/topapi/v2/user/listsimple?access_token=" + token
                                    + "&dept_id=1&cursor=0&size=100&language=zh_CN")
                    .path("result").path("list");
            if (members.isArray()) {
                for (JsonNode m : members) {
                    root.members().add(new DirectoryTypes.Member(
                            m.path("userid").asText(), m.path("name").asText(), m.path("title").asText()));
                }
            }
            return List.of(root);
        } catch (Exception e) {
            log.warn("拉取钉钉组织失败", e);
            return Collections.emptyList();
        }
    }

    private List<DirectoryTypes.DeptNode> descend(String token, long deptId, int depth, int maxDepth) throws Exception {
        List<DirectoryTypes.DeptNode> out = new ArrayList<>();
        JsonNode depts = call(token,
                "https://oapi.dingtalk.com/topapi/v2/department/listsub?access_token=" + token
                        + "&dept_id=" + deptId + "&language=zh_CN").path("result");
        if (!depts.isArray()) {
            return out;
        }
        for (JsonNode d : depts) {
            DirectoryTypes.DeptNode node = new DirectoryTypes.DeptNode(d.path("dept_id").asLong(), d.path("name").asText(), new ArrayList<>(), new ArrayList<>());
            JsonNode members = call(token,
                            "https://oapi.dingtalk.com/topapi/v2/user/listsimple?access_token=" + token
                                    + "&dept_id=" + d.path("dept_id").asLong() + "&cursor=0&size=100&language=zh_CN")
                    .path("result").path("list");
            if (members.isArray()) {
                for (JsonNode m : members) {
                    node.members().add(new DirectoryTypes.Member(
                            m.path("userid").asText(),
                            m.path("name").asText(),
                            m.path("title").asText()));
                }
            }
            if (depth + 1 < maxDepth) {
                node.children().addAll(descend(token, d.path("dept_id").asLong(), depth + 1, maxDepth));
            }
            out.add(node);
        }
        return out;
    }

    private JsonNode call(String token, String url) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }
}
