package com.aicabinet.trade.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 全局 Clock bean：供需要可注入时钟的业务服务（PlatformFundService/DeviceRentalService 等）
 * 在生产环境取系统默认时区时钟；测试用 Clock.fixed 覆盖。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
