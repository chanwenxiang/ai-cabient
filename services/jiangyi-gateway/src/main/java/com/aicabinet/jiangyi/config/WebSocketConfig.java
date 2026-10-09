package com.aicabinet.jiangyi.config;

import com.aicabinet.jiangyi.ws.DeviceHandshakeInterceptor;
import com.aicabinet.jiangyi.ws.DeviceWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

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

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deviceWebSocketHandler, "/websocket/device/{identifier}")
                .addInterceptors(deviceHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
