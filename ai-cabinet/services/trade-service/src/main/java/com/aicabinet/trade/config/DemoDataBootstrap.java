package com.aicabinet.trade.config;

import com.aicabinet.trade.service.DemoDataService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** dev mock 模式下启动时补齐演示业务数据（库空或部分缺失均可）。 */
@Component
public class DemoDataBootstrap implements ApplicationRunner {

    private final SecurityProperties securityProperties;
    private final DemoDataService demoDataService;

    public DemoDataBootstrap(SecurityProperties securityProperties, DemoDataService demoDataService) {
        this.securityProperties = securityProperties;
        this.demoDataService = demoDataService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (securityProperties.mockEnabled()) {
            demoDataService.ensureDemoData();
        }
    }
}
