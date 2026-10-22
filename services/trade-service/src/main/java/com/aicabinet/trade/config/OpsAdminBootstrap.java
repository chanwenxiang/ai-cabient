package com.aicabinet.trade.config;

import com.aicabinet.trade.service.OpsAdminBootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次部署引导运营超管（环境变量驱动）。
 * 未配置即跳过；配置后创建失败会中断启动，避免「以为建好了、实际没人能登录」。
 */
@Component
public class OpsAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpsAdminBootstrap.class);

    private final OpsAdminBootstrapProperties properties;
    private final OpsAdminBootstrapService service;

    public OpsAdminBootstrap(OpsAdminBootstrapProperties properties, OpsAdminBootstrapService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.configured()) {
            log.info("初始运营超管未配置（AICABINET_OPS_ADMIN_PHONE/PASSWORD 为空），跳过");
            return;
        }
        try {
            service.ensureInitialAdmin(properties.phone(), properties.password(), properties.name());
        } catch (RuntimeException e) {
            throw new IllegalStateException("初始运营超管引导失败：" + e.getMessage(), e);
        }
    }
}
