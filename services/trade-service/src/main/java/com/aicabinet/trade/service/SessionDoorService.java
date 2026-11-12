package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.SessionLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/**
 * 门事件与关门路径：开门/关门状态落库，再按会话类型编排补货快照或结算。
 * <p>状态迁移、DTO、会话锁与结算委托 {@link SessionService}，避免行为漂移。</p>
 */
@Service
public class SessionDoorService {
    private static final String DEVICEID = "deviceId";
    private static final Logger log = LoggerFactory.getLogger(SessionDoorService.class);

    private final ShoppingSessionMapper repository;
    private final GravitySettlementHelper gravityHelper;
    private final SessionRestockService sessionRestockService;
    private final CabinetMetrics cabinetMetrics;
    private final DomainEventPublisher domainEventPublisher;
    private final SessionService sessionService;
    private final SessionDoorService self;
    private final com.aicabinet.trade.service.JiangyiDeviceDirectory jiangyiDeviceDirectory;

    public SessionDoorService(ShoppingSessionMapper repository,
                              GravitySettlementHelper gravityHelper,
                              SessionRestockService sessionRestockService,
                              CabinetMetrics cabinetMetrics,
                              DomainEventPublisher domainEventPublisher,
                              @Lazy SessionService sessionService,
                              @Lazy SessionDoorService self,
                              com.aicabinet.trade.service.JiangyiDeviceDirectory jiangyiDeviceDirectory) {
        this.repository = repository;
        this.gravityHelper = gravityHelper;
        this.sessionRestockService = sessionRestockService;
        this.cabinetMetrics = cabinetMetrics;
        this.domainEventPublisher = domainEventPublisher;
        this.sessionService = sessionService;
        this.self = self;
        this.jiangyiDeviceDirectory = jiangyiDeviceDirectory;
    }

    /**
     * 门事件入口。关门状态先单独提交，再结算，避免 vision/扣款失败把「已关门」回滚掉，
     * 导致会话卡在 SHOPPING、设备无法再次开门。
     */
    public SessionDto handleDoorEvent(DoorEventRequest event) {
        SessionDto afterDoor = self.applyDoorEvent(event);
        if (event.doorState() == DoorState.CLOSED && afterDoor.state() != SessionState.WAITING_UPLOAD) {
            ShoppingSession session = repository.findById(event.sessionId()).orElse(null);
            if (session != null && DeviceValidationService.isOpsRemoteSession(session)) {
                return afterDoor;
            }
            // 将邑（CB-022 模式一）：gateway 在识别上报到达时先合成 CLOSED 推进到 RECOGNIZING，
            // 随后立刻转发 edge-results 结算。若此处走 settleAfterClose 会「空车结算」——
            // 识别数据尚未入会话即完成结案，真正的 edge-results 到达时已终态被拒（ALREADY_HANDLED，
            // 2026-10-09 联调实测）。将邑会话关门后停在 RECOGNIZING 等识别结果，与 ops-remote 同豁免模式。
            if (session != null && jiangyiDeviceDirectory.isJiangyi(session.getDeviceId())) {
                return afterDoor;
            }
            if (session != null && DeviceValidationService.isRestockSession(session)) {
                if (afterDoor.state() == SessionState.RECOGNIZING) {
                    return sessionRestockService.finishRestockSnapshot(event.sessionId());
                }
                return afterDoor;
            }
            return sessionService.settleAfterClose(event.sessionId());
        }
        return afterDoor;
    }

    @Transactional
    public SessionDto applyDoorEvent(DoorEventRequest event) {
        return sessionService.runWithSessionLifeLock(event.sessionId(), () -> doApplyDoorEvent(event));
    }

    /**
     * 将邑补货关门上报（uploadAddGoodsCloseStatus，2026-10-27 EG943322621391 实锤：
     * 真机补货链路唯一的关门信号，上报 body 无会话号）：按设备找最新活跃补货会话，
     * 走与模拟器完全同一条 {@link #handleDoorEvent}(CLOSED) 路径——OPENING 孤儿关门
     * 转 SHOPPING，SHOPPING 落 closeTime + 快照 + COMPLETED。
     *
     * @return true=找到活跃补货会话并推进；false=无（幂等 no-op，含会话已被
     *         90s 超时清扫的竞态——此时门事件落库为无害 no-op）
     */
    @Transactional
    public boolean applyRestockDoorClosed(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        ShoppingSession session = repository.findLatestActiveRestockByDeviceId(
                deviceId, java.util.List.of(SessionState.OPENING, SessionState.SHOPPING)).orElse(null);
        if (session == null) {
            return false;
        }
        SessionDto after = handleDoorEvent(new DoorEventRequest(session.getSessionId(), deviceId,
                DoorState.CLOSED, System.currentTimeMillis()));
        log.info("restock door-close report applied session={} device={} state={}",
                session.getSessionId(), deviceId, after.state());
        return true;
    }

    /**
     * 将邑开门回执推进（WS sendOpenStockDoorSure/openDoorSure，2026-10-30）：网关只按
     * sessionId 转发，是否放行由本方法判定——tracker 的 stock 标记不可靠，watchdog 15s
     * open-failed 会先把登记清掉，回执稍慢就丢。
     *
     * <p>2026-11-12 真机事故（会话 1794452035347297629828）放开消费会话：弱网下设备
     * HTTP uploadLockState 迟到 2m13s（花生壳 1Mbps 隧道被视频上传挤占），10s
     * OPEN_TIMEOUT 清扫误杀真开门；openDoorSure 回执迟到 14.3s 却是唯一能在清扫前
     * 到达的设备响应信号。回执=设备已执行解锁指令，与 HTTP 门事件同一
     * {@link #onDoorOpened} 语义（OPENING→SHOPPING；已 SHOPPING 幂等）。终态
     * （CANCELLED/FAILED/COMPLETED 等）仍拒绝——救会话只发生在清扫尚未杀掉时，
     * 已杀会话的兜账走迟到识别上报的运维异常路径，不在此复活。</p>
     *
     * @return true=设备一致的活跃会话（OPENING/SHOPPING）且已投递 {@link DoorState#OPEN}
     *         （已开为幂等 no-op）；false=无此会话/设备不符/会话已终态
     */
    @Transactional
    public boolean applyRestockDoorOpened(String deviceId, String sessionId) {
        if (deviceId == null || deviceId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        ShoppingSession session = repository.findById(sessionId).orElse(null);
        if (session == null || !deviceId.equals(session.getDeviceId())
                || (session.getState() != SessionState.OPENING
                && session.getState() != SessionState.SHOPPING)) {
            return false;
        }
        SessionDto after = handleDoorEvent(new DoorEventRequest(sessionId, deviceId,
                DoorState.OPEN, System.currentTimeMillis()));
        log.info("restock door-open ack applied session={} device={} state={}",
                sessionId, deviceId, after.state());
        return true;
    }

    private SessionDto doApplyDoorEvent(DoorEventRequest event) {
        ShoppingSession session = repository.findByIdForUpdate(event.sessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));

        if (!session.getDeviceId().equals(event.deviceId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DEVICE_MISMATCH);
        }

        if (event.gravityDeltasJson() != null && !event.gravityDeltasJson().isBlank()) {
            session.setGravityDeltas(gravityHelper.mergeGravityJson(session.getGravityDeltas(), event.gravityDeltasJson()));
        }
        if (event.gravityDeltasJson() != null && !event.gravityDeltasJson().isBlank()) {
            repository.save(session);
        }

        return switch (event.doorState()) {
            case OPEN -> onDoorOpened(session);
            case CLOSED -> onDoorClosed(session);
            default -> sessionService.toDto(session);
        };
    }

    private SessionDto onDoorOpened(ShoppingSession session) {
        if (session.getState() == SessionState.OPENING) {
            session.setOpenTime(Instant.now());
            sessionService.transition(session, SessionState.SHOPPING);
            cabinetMetrics.recordDoorOpen(true);
            // Map.of 拒 null（铁律 24-27）：运维/盘点等会话可能无 userId，缺了降级为不带该键而非 NPE
            Map<String, Object> payload = session.getUserId() != null
                    ? Map.of(DEVICEID, session.getDeviceId(), "userId", session.getUserId())
                    : Map.of(DEVICEID, session.getDeviceId());
            domainEventPublisher.publish("DoorOpened", session.getSessionId(), payload);
            log.info("door opened {}", SessionLogContext.of(session));
        }
        return sessionService.toDto(session);
    }

    private SessionDto onDoorClosed(ShoppingSession session) {
        boolean orphanClose = false;
        if (session.getState() == SessionState.OPENING) {
            session.setOpenTime(Instant.now());
            sessionService.transition(session, SessionState.SHOPPING);
            orphanClose = true;
            log.warn("door closed while opening, treat as shopping {}", SessionLogContext.of(session));
        }
        if (session.getState() != SessionState.SHOPPING) {
            return sessionService.toDto(session);
        }
        // 关门完整率的分子：只在「从 SHOPPING 正常走到这里」时计一次
        // （重复关门/非 SHOPPING 已在上方 return ⇒ 不重复计数）。
        cabinetMetrics.recordDoorClose(orphanClose);
        session.setCloseTime(Instant.now());
        repository.save(session);

        if (DeviceValidationService.isOpsRemoteSession(session)) {
            // 运维开门：关门即完成，不识别、不结算；有录像则保留供审计
            sessionService.transition(session, SessionState.COMPLETED);
            log.info("ops remote door closed {}", SessionLogContext.of(session));
            return sessionService.toDto(session);
        }

        if (DeviceValidationService.isStocktakeSession(session)) {
            // 盘点开门（2026-10-28）：关门即完成，不识别、不结算；
            // 库存修正由盘点提交接口按实盘落账（InventoryLotService.stocktakeAdjust），
            // 这里只收会话，避免识别链路把清点动作当购物。
            sessionService.transition(session, SessionState.COMPLETED);
            log.info("stocktake door closed {}", SessionLogContext.of(session));
            return sessionService.toDto(session);
        }

        if (DeviceValidationService.isRestockSession(session)) {
            sessionService.transition(session, SessionState.COMPLETED);
            log.info("restock door closed with gravity snapshot {}", SessionLogContext.of(session));
            return sessionService.toDto(session);
        }

        sessionService.transition(session, SessionState.RECOGNIZING);
        log.info("door closed, recognizing {}", SessionLogContext.of(session));
        return sessionService.toDto(session);
    }
}
