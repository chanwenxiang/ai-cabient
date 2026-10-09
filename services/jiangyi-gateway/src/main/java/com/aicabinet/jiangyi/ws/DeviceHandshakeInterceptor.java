package com.aicabinet.jiangyi.ws;

import com.aicabinet.jiangyi.auth.DeviceTokenService;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 将邑设备 WS 握手拦截（CB-022，方案 §3）：query token 校验且
 * claims.identifier 必须等于路径 identifier（防拿 A 机 token 连 B 机通道）。
 * <p>路径 {@code /websocket/device/{identifier}?token=<JWT>}（V16 §4.2.2 设备侧）。
 * 校验链与 DeviceAuthInterceptor 同款：验签+exp → status=BOUND → ver 未吊销。
 * 通过后把 deviceId/identifier 写入 handshake attributes 供 handler 使用。</p>
 */
@Component
public class DeviceHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DeviceHandshakeInterceptor.class);

    public static final String ATTR_DEVICE_ID = "jiangyi.deviceId";
    public static final String ATTR_IDENTIFIER = "jiangyi.identifier";

    private final DeviceTokenService deviceTokenService;
    private final TradeInternalClient tradeInternalClient;

    public DeviceHandshakeInterceptor(DeviceTokenService deviceTokenService,
                                      TradeInternalClient tradeInternalClient) {
        this.deviceTokenService = deviceTokenService;
        this.tradeInternalClient = tradeInternalClient;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        String identifier = path.substring(path.lastIndexOf('/') + 1);
        if (identifier.isBlank()) {
            reject(response, "bad path");
            return false;
        }
        String token = queryParam(request, "token");
        if (token == null || token.isBlank()) {
            reject(response, "missing token");
            return false;
        }
        Claims claims;
        try {
            claims = deviceTokenService.verify(token);
        } catch (JwtException e) {
            log.info("jiangyi ws handshake rejected: bad token identifier={}", identifier);
            reject(response, "bad token");
            return false;
        }
        String claimedIdentifier = claims.get(DeviceTokenService.CLAIM_IDENTIFIER, String.class);
        String deviceId = claims.getSubject();
        if (!identifier.equals(claimedIdentifier)) {
            // 铁律 fail-closed：token 与通道不匹配，拒绝
            log.info("jiangyi ws handshake rejected: identifier mismatch claimed={} path={}",
                    claimedIdentifier, identifier);
            reject(response, "identifier mismatch");
            return false;
        }
        Long verClaim = claims.get(DeviceTokenService.CLAIM_VER, Long.class);
        long tokenVer = verClaim == null ? 0L : verClaim;
        TradeInternalClient.JiangyiDeviceView device = tradeInternalClient.device(deviceId).orElse(null);
        if (device == null || !"BOUND".equals(device.status())) {
            log.info("jiangyi ws handshake rejected: device not bound deviceId={}", deviceId);
            reject(response, "device not bound");
            return false;
        }
        long dbVer = device.tokenVersion() == null ? 0L : device.tokenVersion();
        if (tokenVer < dbVer) {
            log.info("jiangyi ws handshake rejected: token revoked deviceId={}", deviceId);
            reject(response, "token revoked");
            return false;
        }
        attributes.put(ATTR_DEVICE_ID, deviceId);
        attributes.put(ATTR_IDENTIFIER, identifier);
        log.info("jiangyi ws handshake accepted deviceId={} identifier={}", deviceId, identifier);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    private static String queryParam(ServerHttpRequest request, String name) {
        if (request instanceof ServletServerHttpRequest servlet) {
            return servlet.getServletRequest().getParameter(name);
        }
        // 非 Servlet 容器兜底：手工解析 query string
        String query = request.getURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static void reject(ServerHttpResponse response, String reason) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        log.info("jiangyi ws handshake rejected: {}", reason);
    }
}
