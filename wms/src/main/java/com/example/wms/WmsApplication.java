package com.example.wms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WMS 服务 —— 仓库、库存、出入库记录。
 *
 * <p>身份来自网关注入的 X-User-Id / X-User-Roles，本服务不做接口级角色限制：
 * 任何登录用户都可读写（令牌验签在网关完成，服务只信清洗后的身份头）。</p>
 */
@SpringBootApplication
public class WmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(WmsApplication.class, args);
    }
}
