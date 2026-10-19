package com.aicabinet.jiangyi.ws;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.tracker.CommandTracker;
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
 * addRecognitionGoodsToOrder 等），WS 上行预期只有心跳/回执类消息——心跳按 V16 §4.3.1
 * 回发 PONG 并节流转报 trade 维持在线态；其余宽松解析记 debug 不做业务处理
 * （铁律：未知消息宁可忽略也不误当业务数据）。建连/断开同步 trade 设备在线状态。</p>
 */
@Component
public class DeviceWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceWebSocketHandler.class);

    public static final String MSG_TYPE_OPEN_DOOR = "openDoor";
    /** 补货开门（V16 §4.3.2.4，收尾E）：msgContent=补货单号（=trade 补货会话 id）。 */
    public static final String MSG_TYPE_SEND_OPEN_STOCK_DOOR = "sendOpenStockDoor";
    public static final String MSG_TYPE_UPDATE_MODEL = "updateModel";
    /** 设备模型下发完成回执。V16 §4.2.5 PDF 原件核对：主通道是设备 HTTP POST
     * /deviceInfo/downloadModelNotify（DeviceInfoController）；本 WS 分支为宽容兜底。 */
    public static final String MSG_TYPE_DOWNLOAD_MODEL_NOTIFY = "downloadModelNotify";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final TradeInternalClient tradeInternalClient;

    private final CommandTracker commandTracker;

    /** identifier → 在线 WS 会话（单设备单连接；新连接顶替旧连接）。 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** identifier → 上次 wsOnline 上报时刻（ms）。心跳桥接节流：60s 内不重复打 trade。 */
    private final Map<String, Long> lastOnlineReportAt = new ConcurrentHashMap<>();

    /** 心跳上报节流窗口：trade 巡检 2 分钟无 updated_at 刷新即判离线（DevicePresenceService），
     *  60s 上报一次留足冗余；将邑真机心跳周期见 V16 §4.3.1。 */
    private static final long ONLINE_REPORT_INTERVAL_MS = 60_000L;

    public DeviceWebSocketHandler(TradeInternalClient tradeInternalClient, CommandTracker commandTracker) {
        this.tradeInternalClient = tradeInternalClient;
        this.commandTracker = commandTracker;
    }

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
        // 2026-10-09 联调缺陷⑥：建连即上报 trade（此前 wsOnline 是死代码，trade 侧
        // device_info.online_status 恒 OFFLINE → 开门 409「设备离线」）。失败不断连。
        reportOnline(identifier, attrDeviceId(session));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String identifier = attrIdentifier(session);
        if (identifier == null) {
            return;
        }
        // 只移除自己登记的那条（顶替语义下旧连接关闭不动新连接的登记）。
        // AU-190（2026-10-18 批次 5）：remove 双参判定成功 = 本连接仍是当前登记会话才报离线；
        // 旧实现无条件 reportOffline ⇒ 新连接顶替后旧连接关闭把仍在线的设备误报离线。
        boolean wasCurrent = sessions.remove(identifier, session);
        lastOnlineReportAt.remove(identifier);
        log.info("jiangyi ws disconnected identifier={} session={} status={} wasCurrent={} onlineDevices={}",
                identifier, session.getId(), status, wasCurrent, sessions.size());
        if (wasCurrent) {
            reportOffline(identifier, attrDeviceId(session));
        } else {
            log.info("jiangyi ws stale close ignored (replaced by newer session) identifier={}", identifier);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String identifier = attrIdentifier(session);
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            // 心跳契约（2026-10-19 log3 修订）：真机发 {msgType:"heartBeat",msgContent:"PING"}，
            // 将邑云固定回 {status:200,msgType:"heartBeat",msgContent:"PONG"}——固件日志对
            // heartBeat 帧统一记「收到心跳消息」，msgContent 值比对行为未定义；按真云行为
            // 固定回 PONG（不回显 PING），status 恒带（真云全带）。
            if ("heartBeat".equalsIgnoreCase(node.path("msgType").asText())) {
                // 心跳桥接：V16「心跳保持则机器在线」→ trade 巡检按 device_info.updated_at
                // 判活（2 分钟），节流转发心跳维持在线态（缺陷⑥ 的持续侧；断开侧见 closed）。
                reportOnlineThrottled(identifier, attrDeviceId(session));
                send(session, identifier, Map.of(
                        "status", 200,
                        "msgType", "heartBeat",
                        "msgContent", "PONG"));
                return;
            }
            // 真机回执分发（2026-10-19 log3 实证，V15/V16 文档均无 *Sure 帧）：
            // sendOpenStockDoorSure msgContent=下单发出去的单号原样回 → 撤销 OPEN 15s 超时登记。
            if ("sendOpenStockDoorSure".equalsIgnoreCase(node.path("msgType").asText())
                    || "openDoorSure".equalsIgnoreCase(node.path("msgType").asText())) {
                String ackNo = node.path("msgContent").asText("");
                String deviceId = attrDeviceId(session);
                if (deviceId != null && !ackNo.isBlank()) {
                    boolean done = commandTracker.complete(CommandTracker.Kind.OPEN, ackNo, deviceId);
                    log.info("jiangyi ws door ack identifier={} no={} completed={}", identifier, ackNo, done);
                }
                return;
            }
            // updateModelSure msgContent 为空串（log3 实证）→ 按 deviceId 撤销 MODEL 登记全部条目。
            if ("updateModelSure".equalsIgnoreCase(node.path("msgType").asText())) {
                String deviceId = attrDeviceId(session);
                if (deviceId != null) {
                    int done = commandTracker.completeByDevice(CommandTracker.Kind.MODEL, deviceId);
                    log.info("jiangyi ws model ack identifier={} completed={}", identifier, done);
                }
                return;
            }
            // 模型下发完成回执（V16 §4.2.5）：宽容解析——只取 modelName，真实 msgContent
            // 形态待真机实锤（对象/字符串都容忍），缺失记 debug 不崩（CB-023 台账局限 2）。
            if (MSG_TYPE_DOWNLOAD_MODEL_NOTIFY.equalsIgnoreCase(node.path("msgType").asText())) {
                String deviceId = attrDeviceId(session);
                JsonNode content = node.path("msgContent");
                String modelName = content.isObject()
                        ? content.path("modelName").asText(null)
                        : (content.isTextual() ? content.asText() : null);
                if (deviceId != null && modelName != null && !modelName.isBlank()) {
                    try {
                        tradeInternalClient.modelConfirmed(deviceId, modelName);
                        log.info("jiangyi ws model confirmed deviceId={} modelName={}", deviceId, modelName);
                    } catch (Exception e) {
                        log.warn("jiangyi ws model-confirmed report failed deviceId={}: {}", deviceId, e.getMessage());
                    }
                } else {
                    log.debug("jiangyi ws downloadModelNotify without modelName identifier={} payload={}",
                            identifier, message.getPayload());
                }
                return;
            }
            // 模式一业务上报全走 HTTP 面；WS 上行其余消息忽略（铁律：未知消息宁可忽略也不误当业务数据）
            log.debug("jiangyi ws upstream identifier={} payload={}", identifier, message.getPayload());
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
    /**
     * 下发补货开门指令（msgContent=补货单号）。回执与购买开门同通道
     * （uploadLockState/uploadDoorState，orderNo=补货单号）。
     */
    public boolean sendOpenStockDoor(String identifier, String stockNo) {
        WebSocketSession session = sessions.get(identifier);
        if (session == null || !session.isOpen()) {
            log.warn("jiangyi ws stock-door skipped: device offline identifier={} stockNo={}",
                    identifier, stockNo);
            return false;
        }
        return send(session, identifier, Map.of(
                "status", 200,
                "msgType", MSG_TYPE_SEND_OPEN_STOCK_DOOR,
                "msgContent", stockNo));
    }

    public boolean sendOpenDoor(String identifier, String sessionId) {
        WebSocketSession session = sessions.get(identifier);
        if (session == null || !session.isOpen()) {
            log.warn("jiangyi ws open-door skipped: device offline identifier={} sessionId={}",
                    identifier, sessionId);
            return false;
        }
        return send(session, identifier, Map.of(
                "status", 200,
                "msgType", MSG_TYPE_OPEN_DOOR,
                "msgContent", sessionId));
    }

    /**
     * 下发模型更新指令（CB-023，V16 §4.3.2.9）：
     * {@code {msgType:"updateModel", msgContent:{quantity,modelUrl,textUrl,modelName}}}。
     * 发出 ≠ 设备已应用：回执主通道为 HTTP POST /deviceInfo/downloadModelNotify
     * （§4.2.5 PDF 原件核对，DeviceInfoController）；此处 WS 分支为宽容兜底。
     */
    public boolean sendModelUpdate(String identifier, String modelName, String modelUrl,
                                   String textUrl, int quantity) {
        WebSocketSession session = sessions.get(identifier);
        if (session == null || !session.isOpen()) {
            log.warn("jiangyi ws model-push skipped: device offline identifier={} modelName={}",
                    identifier, modelName);
            return false;
        }
        return send(session, identifier, Map.of(
                "status", 200,
                "msgType", MSG_TYPE_UPDATE_MODEL,
                "msgContent", Map.of(
                        "quantity", quantity,
                        "modelUrl", modelUrl == null ? "" : modelUrl,
                        "textUrl", textUrl == null ? "" : textUrl,
                        "modelName", modelName)));
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

    private static String attrDeviceId(WebSocketSession session) {
        return Optional.ofNullable(session.getAttributes().get(DeviceHandshakeInterceptor.ATTR_DEVICE_ID))
                .map(Object::toString)
                .orElse(null);
    }

    /** 建连/心跳 → trade ws-online；失败仅告警（WS 已建立，上报失败不应断开设备）。 */
    private void reportOnline(String identifier, String deviceId) {
        if (deviceId == null) {
            return;
        }
        try {
            tradeInternalClient.wsOnline(deviceId);
            lastOnlineReportAt.put(identifier, System.currentTimeMillis());
            log.info("jiangyi ws online reported deviceId={} identifier={}", deviceId, identifier);
        } catch (Exception e) {
            log.warn("jiangyi ws online report failed deviceId={} identifier={}: {}",
                    deviceId, identifier, e.getMessage());
        }
    }

    /** 心跳触发的节流版上报：窗口内不重复打 trade（心跳高频，内部接口要省）。 */
    private void reportOnlineThrottled(String identifier, String deviceId) {
        if (deviceId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastOnlineReportAt.get(identifier);
        if (last != null && now - last < ONLINE_REPORT_INTERVAL_MS) {
            return;
        }
        reportOnline(identifier, deviceId);
    }

    /** 断开 → trade ws-offline；失败仅告警（trade 巡检 2 分钟兜底置离线）。 */
    private void reportOffline(String identifier, String deviceId) {
        if (deviceId == null) {
            return;
        }
        try {
            tradeInternalClient.wsOffline(deviceId);
            log.info("jiangyi ws offline reported deviceId={} identifier={}", deviceId, identifier);
        } catch (Exception e) {
            log.warn("jiangyi ws offline report failed deviceId={} identifier={}: {}",
                    deviceId, identifier, e.getMessage());
        }
    }
}
