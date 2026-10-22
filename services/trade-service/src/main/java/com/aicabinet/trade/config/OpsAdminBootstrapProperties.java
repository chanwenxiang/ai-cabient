package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 首次部署引导运营超管。仅当 phone 与 password 同时配置时生效。
 * 密码只从环境变量读取（AICABINET_OPS_ADMIN_PASSWORD），不入仓库、不入日志。
 */
@ConfigurationProperties(prefix = "aicabinet.bootstrap.ops-admin")
public record OpsAdminBootstrapProperties(String phone, String password, String name) {

    public boolean configured() {
        return phone != null && !phone.isBlank()
                && password != null && !password.isBlank();
    }
}
