package com.example.user;

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

/**
 * 集成配置（管理台「集成配置」页，role:manage）：钉钉 / 企业微信凭据的可视化维护。
 *
 * <p>凭据的<b>唯一</b>存放处就是 integration_settings 表——页面保存即热生效
 * （provider 每次取凭据都先查库、token 按凭据指纹失效），不读服务器环境变量，
 * 没有第二种配置口径。</p>
 *
 * <p>安全边界：明文凭据只进不出（写入时 trim 落库，读取只回脱敏值）；
 * 「清除凭据」= 删行，该平台回到未接入状态。测试连接强制现取一次 token，
 * 验的就是刚保存的值。</p>
 */
@RestController
@RequestMapping("/api/admin/integrations")
@RequiredArgsConstructor
public class IntegrationsController {

    private static final String HEADER_ROLES = "X-User-Roles";
    private static final String PERMISSION_MANAGE = "role:manage";

    private final PermissionService permissions;
    private final IntegrationSettingsService settings;
    private final DingDingService dingding;
    private final WeComService wecom;

    /**
     * 单个 provider 的配置态。source：db=页面存的库值、none=没配过
     * （凭据只走页面，不再有 env 口径）。
     * keyLabel/secretLabel 由后端给：钉钉叫 AppKey/AppSecret、企微叫 CorpID/CorpSecret，
     * 前端不猜口径。
     */
    public record ProviderConfig(String code, String name, boolean configured, String source,
                                 String keyLabel, String secretLabel,
                                 String maskedKey, String maskedSecret) {
    }

    /** 保存入参：字段留空 = 不修改该项（避免一次保存把没填的那半截清掉） */
    public record ProviderEdit(String key, String secret) {
    }

    /** 测试结果：ok 决定页面提示的颜色，message 是给人看的一句话 */
    public record TestResult(boolean ok, String message) {
    }

    @GetMapping
    public List<ProviderConfig> list(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles) {
        requireManage(roles);
        return List.of(dingdingConfig(), wecomConfig());
    }

    @PutMapping("/{code}")
    public ProviderConfig save(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable String code,
            @RequestBody ProviderEdit edit) {
        requireManage(roles);
        String operator = "console"; // updated_by 只用于审计溯源；网关没传操作者 id 时先记来源
        switch (normalize(code)) {
            case "dingding" -> {
                if (isBlank(edit.key()) && isBlank(edit.secret())) {
                    throw new IllegalArgumentException("至少填写一项（AppKey 或 AppSecret）");
                }
                settings.putIfPresent(IntegrationSettingsService.DINGTALK_APP_KEY, edit.key(), operator);
                settings.putIfPresent(IntegrationSettingsService.DINGTALK_APP_SECRET, edit.secret(), operator);
                return dingdingConfig();
            }
            case "wecom" -> {
                if (isBlank(edit.key()) && isBlank(edit.secret())) {
                    throw new IllegalArgumentException("至少填写一项（CorpID 或 CorpSecret）");
                }
                settings.putIfPresent(IntegrationSettingsService.WECOM_CORP_ID, edit.key(), operator);
                settings.putIfPresent(IntegrationSettingsService.WECOM_CORP_SECRET, edit.secret(), operator);
                return wecomConfig();
            }
            default -> throw new IllegalArgumentException("未知集成: " + code);
        }
    }

    /** 清除凭据（页面「清除凭据」按钮）：删行，该平台回到未接入状态 */
    @DeleteMapping("/{code}")
    public ProviderConfig reset(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable String code) {
        requireManage(roles);
        switch (normalize(code)) {
            case "dingding" -> settings.deleteKeys(
                    IntegrationSettingsService.DINGTALK_APP_KEY, IntegrationSettingsService.DINGTALK_APP_SECRET);
            case "wecom" -> settings.deleteKeys(
                    IntegrationSettingsService.WECOM_CORP_ID, IntegrationSettingsService.WECOM_CORP_SECRET);
            default -> throw new IllegalArgumentException("未知集成: " + code);
        }
        return "dingding".equals(normalize(code)) ? dingdingConfig() : wecomConfig();
    }

    @PostMapping("/{code}/test")
    public TestResult test(
            @RequestHeader(value = HEADER_ROLES, required = false) String roles,
            @PathVariable String code) {
        requireManage(roles);
        String message = switch (normalize(code)) {
            case "dingding" -> dingding.testConnection();
            case "wecom" -> wecom.testConnection();
            default -> throw new IllegalArgumentException("未知集成: " + code);
        };
        // 两个 service 的测试文案都以「连接正常」开头，其余（未配置/连接失败）都按失败展示
        return new TestResult(message.startsWith("连接正常"), message);
    }

    private ProviderConfig dingdingConfig() {
        return new ProviderConfig("dingding", "钉钉", dingding.isConfigured(), source("dingding"),
                "AppKey", "AppSecret", dingding.maskedKey(), dingding.maskedSecret());
    }

    private ProviderConfig wecomConfig() {
        return new ProviderConfig("wecom", "企业微信", wecom.isConfigured(), source("wecom"),
                "CorpID", "CorpSecret", wecom.maskedKey(), wecom.maskedSecret());
    }

    private String source(String code) {
        boolean hasDbRow = switch (code) {
            case "dingding" -> settings.raw(IntegrationSettingsService.DINGTALK_APP_KEY).isPresent()
                    || settings.raw(IntegrationSettingsService.DINGTALK_APP_SECRET).isPresent();
            case "wecom" -> settings.raw(IntegrationSettingsService.WECOM_CORP_ID).isPresent()
                    || settings.raw(IntegrationSettingsService.WECOM_CORP_SECRET).isPresent();
            default -> false;
        };
        if (hasDbRow) {
            return "db";
        }
        return "none";
    }

    private String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase();
    }

    private boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private void requireManage(String roles) {
        String missing = permissions.firstMissing(roles, PERMISSION_MANAGE);
        if (missing != null) {
            throw new ForbiddenException("缺少权限点: " + missing + "，请联系管理员在「权限配置」中授予");
        }
    }
}
