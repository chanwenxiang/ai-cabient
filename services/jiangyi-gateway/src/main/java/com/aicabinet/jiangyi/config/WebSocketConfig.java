package com.aicabinet.jiangyi.config;

import com.aicabinet.jiangyi.ws.DeviceHandshakeInterceptor;
import com.aicabinet.jiangyi.ws.DeviceWebSocketHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 将邑设备 WS 注册（CB-022）：{@code /websocket/device/{identifier}?token=}。
 * <p>设备为非浏览器直连客户端：放开 origin 限制（鉴权由 DeviceHandshakeInterceptor
 * 的 query token 完全承担，放开 origin 不引入额外暴露面）；nginx 侧
 * {@code /websocket/} 路径转发见部署配置（方案 §8）。</p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DeviceWebSocketHandler deviceWebSocketHandler;
    private final DeviceHandshakeInterceptor deviceHandshakeInterceptor;

    public WebSocketConfig(DeviceWebSocketHandler deviceWebSocketHandler,
                           DeviceHandshakeInterceptor deviceHandshakeInterceptor) {
        this.deviceWebSocketHandler = deviceWebSocketHandler;
        this.deviceHandshakeInterceptor = deviceHandshakeInterceptor;
    }

    /**
     * AU-190（2026-10-18 批次 5）：WS 容器空闲超时 90s——4G 柜机 NAT 半开 TCP 下
     * sessions 条目永不清理、isOnline 恒 true（ghost-online，开门放行打进虚空）。
     * 需配合设备端心跳周期 &lt; 90s（V16 心跳协议）让容器踢死链触发 afterConnectionClosed。
     * <p>🔴 属性闸（2026-10-18 CI 实证）：{@code ServletServerContainerFactoryBean} 要求
     * ServletContext 带 {@code jakarta.websocket.server.ServerContainer} 属性——真实 servlet
     * 环境由容器提供，MockMvc 测试上下文没有 ⇒ {@code DeviceAuthIT} 上下文加载全灭
     * （AU-178 把 jiangyi IT 首次纳入 CI 才暴露）。生产 matchIfMissing=true 行为不变。</p>
     */
    @Bean
    @ConditionalOnProperty(name = "aicabinet.jiangyi.ws-container.enabled", havingValue = "true", matchIfMissing = true)
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxSessionIdleTimeout(90_000L);
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deviceWebSocketHandler, "/websocket/device/{identifier}")
                .addInterceptors(deviceHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
