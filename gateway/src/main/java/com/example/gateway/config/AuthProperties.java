package com.example.gateway.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.security")
public class AuthProperties {

    private List<String> whitelist = new ArrayList<>();

    private List<String> trustedHeaders = new ArrayList<>();

    private Jwt jwt = new Jwt();

    @Data
    public static class Jwt {

        private String secret;

        private String issuer;

        /**
         * 允许的时钟偏差，跨容器部署时几秒误差很常见。
         */
        private long allowedClockSkewSeconds = 30;
    }
}
