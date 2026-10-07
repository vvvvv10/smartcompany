package com.example.user;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 集成凭据的存取（表 integration_settings）。
 *
 * <p><b>凭据的唯一存放处</b>：管理台「集成配置」页保存进来，所有消费方
 * （钉钉/企微 provider、注册期的组织绑定 OrganizationService）都从这里读。
 * 不再读服务器环境变量——改凭据只走页面，保存即热生效，没有第二种配置口径。</p>
 *
 * <p>四个键是常量而不是散落的字符串：写入、读取、删除三方共用，
 * 打错一个字母就是「配置保存了但没生效」的哑故障。</p>
 */
@Service
@RequiredArgsConstructor
public class IntegrationSettingsService {

    public static final String DINGTALK_APP_KEY = "dingtalk.app_key";
    public static final String DINGTALK_APP_SECRET = "dingtalk.app_secret";
    public static final String WECOM_CORP_ID = "wecom.corp_id";
    public static final String WECOM_CORP_SECRET = "wecom.corp_secret";

    private final NamedParameterJdbcTemplate jdbc;

    /** 原始值：empty = 没存过（= 未配置）；present("") = 存过但被显式清空（同样未配置） */
    public Optional<String> raw(String key) {
        java.util.List<String> rows = jdbc.query(
                "select setting_value from integration_settings where setting_key = :k",
                new MapSqlParameterSource("k", key),
                (rs, rowNum) -> rs.getString(1));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** 生效值：存过就用库值，没存过 = 空串 = 未配置（页面亮「未接入」引导填写） */
    public String get(String key) {
        return raw(key).orElse("");
    }

    /** 非空才写：留空的字段表示「不修改」，避免一次保存把没填的那半截清掉 */
    public void putIfPresent(String key, String value, String operatorId) {
        if (value == null || value.isBlank()) {
            return;
        }
        jdbc.update("""
                insert into integration_settings (setting_key, setting_value, updated_by)
                values (:k, :v, :u)
                on duplicate key update setting_value = values(setting_value), updated_by = values(updated_by)
                """,
                new MapSqlParameterSource()
                        .addValue("k", key)
                        .addValue("v", value.trim())
                        .addValue("u", operatorId == null ? "" : operatorId));
    }

    /** 删行 = 清除凭据（页面「清除凭据」按钮，该平台回到未接入状态） */
    public void deleteKeys(String... keys) {
        jdbc.update("delete from integration_settings where setting_key in (:keys)",
                new MapSqlParameterSource("keys", java.util.Arrays.asList(keys)));
    }

    /** 公开标识（AppKey/CorpID）脱敏：留头 4 留尾 4，够辨认是哪把钥匙又拼不回全串 */
    public static String mask(String v) {
        if (v == null || v.isBlank()) {
            return "未设置";
        }
        String s = v.trim();
        if (s.length() <= 8) {
            return "****";
        }
        return s.substring(0, 4) + "…" + s.substring(s.length() - 4);
    }

    /** 密钥（Secret）脱敏：只露长度不露任何字符——头尾都不给，避免小长度被爆破拼凑 */
    public static String maskSecret(String v) {
        if (v == null || v.isBlank()) {
            return "未设置";
        }
        return "••••••（" + v.trim().length() + " 位）";
    }
}
