package com.aicabinet.jiangyi.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将邑设备 WS 通道（CB-022）：identifier → 会话登记 + 开门指令下发。
 *
 * <p>下行（商户 → 设备）：购物开门 {@code {msgType:"openDoor", msgContent:"<sessionId>"}}——
 * orderNo=我方 sessionId（方案 §4 枢纽，V16 §4.3.2.7）；空 msgContent=强制开门，
 * 本网关一律拒绝下发（一期边界，方案 §4A）。</p>
 *
 * <p>上行（设备 → 商户 WS）：模式一协议的状态/识别上报走 HTTP 面（uploadDoorState /
 * addRecognitionGoodsToOrder 等），WS 上行预期只有心跳/回执类消息——宽松解析记 debug，
 * 不做业务处理（铁律：未知消息宁可忽略也不误当业务数据）。</p>
 */
@Component
public class DeviceWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceWebSocketHandler.class);

    public static final String MSG_TYPE_OPEN_DOOR = "openDoor";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** identifier → 在线 WS 会话（单设备单连接；新连接顶替旧连接）。 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String identifier = attrIdentifier(session);
        if (identifier == null) {
            return;
        }
        WebSocketSession prev = sessions.put(identifier, session);
        if (prev != null && prev.isOpen() && !prev.getId().equals(session.getId())) {
            log.info("jiangyi ws replacing stale session identifier={} prevSession={}",
                    identifier, prev.getId());
            try {
                prev.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException e) {
                log.debug("jiangyi ws stale session close failed identifier={}", identifier, e);
            }
        }
        log.info("jiangyi ws connected identifier={} session={} onlineDevices={}",
                identifier, session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String identifier = attrIdentifier(session);
        if (identifier == null) {
            return;
        }
        // 只移除自己登记的那条（顶替语义下旧连接关闭不动新连接的登记）
        sessions.remove(identifier, session);
        log.info("jiangyi ws disconnected identifier={} session={} status={} onlineDevices={}",
                identifier, session.getId(), status, sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String identifier = attrIdentifier(session);
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            log.debug("jiangyi ws upstream identifier={} payload={}", identifier, message.getPayload());
            // 模式一业务上报全走 HTTP 面；WS 上行未知消息忽略（首行注释契约）
        } catch (Exception e) {
            log.debug("jiangyi ws upstream non-JSON identifier={} len={}",
                    identifier, message.getPayload().length());
        }
    }

    /** 设备是否在线（开门路由前的健康判断）。 */
    public boolean isOnline(String identifier) {
        WebSocketSession session = sessions.get(identifier);
        return session != null && session.isOpen();
    }

    public int onlineCount() {
        return (int) sessions.values().stream().filter(WebSocketSession::isOpen).count();
    }

    /**
     * 下发购物开门指令（msgContent=sessionId/orderNo）。
     *
     * @return true 已送达（发出≠设备已执行；执行回执走 HTTP lockStatus/doorStatus）
     */
    public boolean sendOpenDoor(String identifier, String sessionId) {
        WebSocketSession session = sessions.get(identifier);
        if (session == null || !session.isOpen()) {
            log.warn("jiangyi ws open-door skipped: device offline identifier={} sessionId={}",
                    identifier, sessionId);
            return false;
        }
        return send(session, identifier, Map.of(
                "msgType", MSG_TYPE_OPEN_DOOR,
                "msgContent", sessionId));
    }

    /** 通用下发；强制开门（空 msgContent）在调用方已拒，此处不再开洞。 */
    public boolean send(String identifier, Map<String, Object> payload) {
        WebSocketSession session = sessions.get(identifier);
        if (session == null || !session.isOpen()) {
            return false;
        }
        return send(session, identifier, payload);
    }

    private boolean send(WebSocketSession session, String identifier, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            log.info("jiangyi ws downstream identifier={} payload={}", identifier, json);
            return true;
        } catch (IOException e) {
            log.error("jiangyi ws send failed identifier={}", identifier, e);
            return false;
        }
    }

    private static String attrIdentifier(WebSocketSession session) {
        return Optional.ofNullable(session.getAttributes().get(DeviceHandshakeInterceptor.ATTR_IDENTIFIER))
                .map(Object::toString)
                .orElse(null);
    }
}
