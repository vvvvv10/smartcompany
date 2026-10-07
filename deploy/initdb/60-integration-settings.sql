-- 60-integration-settings.sql
-- 集成配置（钉钉 / 企业微信凭据）：管理台「集成配置」页可视化维护。
--
-- 这张表是凭据的唯一存放处：不读服务器环境变量，页面保存即热生效
-- （provider 每次取凭据先查库、token 按凭据指纹失效）。
-- 空串 = 显式关闭该平台；「清除凭据」按钮 = 删掉本表对应行，回到未接入。
CREATE TABLE IF NOT EXISTS integration_settings (
    setting_key   VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '配置键：dingtalk.app_key / dingtalk.app_secret / wecom.corp_id / wecom.corp_secret',
    setting_value TEXT         NOT NULL COMMENT '配置值；空串=显式关闭',
    updated_by    VARCHAR(128) NOT NULL DEFAULT '' COMMENT '最后修改人（X-User-Id）',
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部集成凭据（页面保存即生效，token 缓存按凭据指纹失效）';
