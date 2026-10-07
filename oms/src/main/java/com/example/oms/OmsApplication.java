package com.example.oms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OMS 服务 —— 服装行业订单管理：商品 SKU、订单（主表+明细）、统计看板。
 *
 * <p>身份来自网关注入的 X-User-Id / X-User-Roles，本服务不做接口级角色限制：
 * 任何登录用户都可读写（令牌验签在网关完成，服务只信清洗后的身份头）。</p>
 */
@SpringBootApplication
public class OmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OmsApplication.class, args);
    }
}
