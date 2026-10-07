package com.example.tms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TMS 服务 —— 运输订单、车辆、司机。
 *
 * <p>身份来自网关注入的 X-User-Id / X-User-Roles，国内陆运模块本服务不做接口级角色限制
 * （令牌验签在网关完成，服务只信清洗后的身份头）；国际域的写端点另有一道 ADMIN 闸
 * （见 {@code IntlCurrentUser.requireAdminForWrite}），承运商回调端点则换成 token 鉴权。</p>
 *
 * <p>{@code @EnableScheduling}（M2-5）：全仓第一个定时任务。加在这里而不是某个
 * {@code @Configuration} 里，是为了让「本服务有没有定时任务」这件事在主类上一眼可见——
 * 这类开关最怕的就是散落在各处、后来没人知道当初为什么开着。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.example.tms.intl.callback")
@EnableScheduling
public class TmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(TmsApplication.class, args);
    }
}