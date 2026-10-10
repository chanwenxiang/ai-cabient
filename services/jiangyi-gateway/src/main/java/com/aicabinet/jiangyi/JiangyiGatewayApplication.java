package com.aicabinet.jiangyi;

import com.aicabinet.common.security.InternalApiAuthInterceptor;
import com.aicabinet.common.security.InternalApiProperties;
import com.aicabinet.jiangyi.config.OssStsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 将邑开门柜接入网关（CB-022，模式一）。
 * <p>设备面协议：HTTP 上报 + WS 长连接（wss://…/websocket/device/{identifier}?token=）；
 * 业务面全部委托 trade-service（会话/结算/金额），本网关只做协议归一化、鉴权与转发。</p>
 * <p>{@code @Import}：common-core 包不在本服务组件扫描路径（com.aicabinet.jiangyi）内，
 * 与 trade-service 同模式显式导入内部 API 拦截器。</p>
 */
@SpringBootApplication
@EnableScheduling
@Import(InternalApiAuthInterceptor.class)
@EnableConfigurationProperties({InternalApiProperties.class, OssStsProperties.class})
public class JiangyiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(JiangyiGatewayApplication.class, args);
    }
}
