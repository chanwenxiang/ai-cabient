package com.aicabinet.jiangyi.api;

import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.normalize.DoorStateNormalizer;
import com.aicabinet.jiangyi.normalize.RecognitionNormalizer;
import com.aicabinet.jiangyi.tracker.CommandTracker;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 将邑设备面上报端点（CB-022，方案 §4.2/§4.3 时序 2–4）。
 *
 * <p>路径<b>照抄 V16 文档</b>（设备固件按「domain+文档路径」硬拼，不可自定义），
 * {@code /jiangyi/api} 前缀 = setDomain 的 domain 尾段（nginx {@code /jiangyi/} 保留前缀转发）。
 * 响应一律将邑协议 envelope {@code {status:200, msg:"success", data:"0"}}。</p>
 *
 * <p>枢纽约定：orderNo = 我方 sessionId（方案 §4，V16 §4.3.2.7「商户服务器生成、需唯一」）。
 * 模式一<b>无关门上报</b>——识别上报到达时统一先合成 door-event CLOSED（推进 SHOPPING→
 * RECOGNIZING）再转发 edge-results；door-event 对已关门会话被 TOO_EARLY 拒收属预期幂等。</p>
 */
@RestController
public class DeviceReportController {

    private static final Logger log = LoggerFactory.getLogger(DeviceReportController.class);

    private final TradeInternalClient tradeInternalClient;
    private final RecognitionNormalizer recognitionNormalizer;
    private final CommandTracker commandTracker;

    public DeviceReportController(TradeInternalClient tradeInternalClient,
                                  RecognitionNormalizer recognitionNormalizer,
                                  CommandTracker commandTracker) {
        this.tradeInternalClient = tradeInternalClient;
        this.recognitionNormalizer = recognitionNormalizer;
        this.commandTracker = commandTracker;
    }

    // ---------- §4.2.7 开锁状态 ----------

    /**
     * V16 §4.2.7「可以根据该接口知道机器是否已经解锁」——lockStatus=success 即开门回执：
     * complete(OPEN) 撤销 watchdog 15s 开门超时登记（联调实测缺陷：漏补导致已开锁会话被
     * watchdog 误判「15秒无设备回执」腰斩成 FAILED）；fail → trade open-failed
     * （markOpenDoorFailed 幂等）。开门会话状态由 doorState（门磁）事件推进。
     */
    @PostMapping("/jiangyi/api/device/uploadLockState")
    public Map<String, Object> uploadLockState(HttpServletRequest request,
                                               @RequestBody LockStateRequest body) {
        String deviceId = device(request);
        boolean success = DoorStateNormalizer.isSuccess(body.lockStatus());
        if (success) {
            commandTracker.complete(CommandTracker.Kind.OPEN, body.orderNo());
            log.info("jiangyi lock OK deviceId={} orderNo={}", deviceId, body.orderNo());
            return ok();
        }
        log.warn("jiangyi lock FAIL deviceId={} orderNo={} — notify open-failed", deviceId, body.orderNo());
        try {
            tradeInternalClient.postOpenFailed(body.orderNo(), "将邑设备上报开锁失败");
        } catch (Exception e) {
            log.error("jiangyi open-failed notify failed orderNo={}", body.orderNo(), e);
        }
        return ok();
    }

    // ---------- §4.2.8 门磁状态 ----------

    /**
     * §4.2.8「上报用户拉门状态」：doorStatus=success = 用户拉门打开（door-event OPEN，
     * 会话 OPENING→SHOPPING）；fail = 拉门失败 → **立即** postOpenFailed 转 FAILED（幂等），
     * 不等 300s CLOSE 兜底（§4.2.8「如果开门失败，不会上报订单结果」⇒ 必无后续识别上报）。
     * 「关门」在模式一无独立上报——由识别上报到达时 gateway 统一合成 CLOSED（RECOGNIZING）。
     */
    @PostMapping("/jiangyi/api/device/uploadDoorState")
    public Map<String, Object> uploadDoorState(HttpServletRequest request,
                                               @RequestBody DoorStateRequest body) {
        String deviceId = device(request);
        if (!DoorStateNormalizer.isSuccess(body.doorStatus())) {
            // V16 §4.2.8「如果开门失败，不会上报订单结果」——拉门失败的订单必然没有后续
            // 识别上报，等 300s CLOSE 兜底纯属浪费：立即走 open-failed 转 FAILED（幂等）。
            log.warn("jiangyi door FAIL deviceId={} orderNo={} — notify open-failed", deviceId, body.orderNo());
            try {
                tradeInternalClient.postOpenFailed(body.orderNo(), "将邑设备上报拉门失败");
            } catch (Exception e) {
                log.error("jiangyi open-failed notify failed orderNo={}", body.orderNo(), e);
            }
            return ok();
        }
        try {
            tradeInternalClient.doorEvent(new DoorEventRequest(
                    body.orderNo(), deviceId, DoorState.OPEN, System.currentTimeMillis()));
            log.info("jiangyi door-event OPEN forwarded deviceId={} sessionId={}", deviceId, body.orderNo());
        } catch (Exception e) {
            log.warn("jiangyi door-event forward rejected deviceId={} sessionId={} reason={}",
                    deviceId, body.orderNo(), e.getMessage());
        }
        // 登记关门 300s 兜底（识别转发后 complete；误登记由 watchdog→trade 幂等处置消化）
        commandTracker.register(CommandTracker.Kind.CLOSE, body.orderNo(), deviceId,
                String.valueOf(request.getAttribute(DeviceAuthInterceptor.ATTR_IDENTIFIER)));
        return ok();
    }

    // ---------- §4.2.10 识别结果 ----------

    /**
     * 识别上报（含二次甄别结果复用本端点的场景）：
     * ① 先补 door-event CLOSED（模式一无关门上报，保证 SHOPPING→RECOGNIZING）；
     * ② forms → 映射归一化（任一未命中 fail-closed → recognize-timeout 转 DISPUTED）；
     * ③ 转发 edge-results 进既有结算链路（金额我方按映射 SKU 价合计，分）。
     */
    @PostMapping("/jiangyi/api/orderProduct/addRecognitionGoodsToOrder")
    public Map<String, Object> addRecognitionGoods(HttpServletRequest request,
                                                   @RequestBody RecognitionRequest body) {
        String deviceId = device(request);
        String sessionId = body.orderNo();
        forwardDoorClosed(deviceId, sessionId);

        List<VisionRecognitionResultDto.Item> items = recognitionNormalizer.normalize(deviceId, body.forms());
        if (items.isEmpty()) {
            // forms 空 = 没识别到商品（合法）；映射未命中 = fail-closed。
            // 两者对 trade 的处置相同：立即转人工审核，不等 10 分钟扫描。
            boolean isMiss = body.forms() != null && !body.forms().isEmpty();
            boolean handled = tradeInternalClient.recognizeTimeout(sessionId);
            log.warn("jiangyi recognition {} deviceId={} sessionId={} handled={}",
                    isMiss ? "MAPPING-MISS" : "EMPTY", deviceId, sessionId, handled);
            return ok();
        }
        VisionRecognitionResultDto result = new VisionRecognitionResultDto(
                sessionId,
                deviceId,
                null,
                null,
                items,
                1.0d,
                false,
                RecognitionNormalizer.modelVersion(tradeInternalClient.device(deviceId)
                        .map(TradeInternalClient.JiangyiDeviceView::modelName).orElse(null)),
                null,
                "JIANGYI",
                Instant.now());
        try {
            var response = tradeInternalClient.edgeResults(result);
            log.info("jiangyi recognition forwarded deviceId={} sessionId={} items={} outcome={}",
                    deviceId, sessionId, items.size(), response == null ? "n/a" : response.outcome());
        } catch (Exception e) {
            log.error("jiangyi edge-results forward failed deviceId={} sessionId={}", deviceId, sessionId, e);
        } finally {
            // 识别结果已到达（无论受理结论），关门兜底使命完成
            commandTracker.complete(CommandTracker.Kind.CLOSE, sessionId);
        }
        return ok();
    }

    // ---------- §4.2.11/4.2.12 异常/大模型 ----------

    /**
     * bigModel=doing（大小写不敏感，§4.2.11 bigModel / §4.2.12 BigModel）→ 保持
     * RECOGNIZING 等二次甄别重报；不传值/其他 = 异常订单 → recognize-timeout 转 DISPUTED。
     */
    @PostMapping("/jiangyi/api/device/pokerOrder/uploadOrderError")
    public Map<String, Object> uploadOrderError(HttpServletRequest request,
                                                @RequestBody OrderErrorRequest body) {
        String deviceId = device(request);
        String bigModel = body.bigModel() != null ? body.bigModel() : body.BigModel();
        if (DoorStateNormalizer.isDoing(bigModel)) {
            log.info("jiangyi order entering big-model review deviceId={} orderNo={}",
                    deviceId, body.orderNo());
            // 登记 10min 兜底：二次甄别若不来，超时转 DISPUTED
            commandTracker.register(CommandTracker.Kind.DOING, body.orderNo(), deviceId,
                    String.valueOf(request.getAttribute(DeviceAuthInterceptor.ATTR_IDENTIFIER)));
            return ok();
        }
        log.warn("jiangyi order error deviceId={} orderNo={} errorMsg={} — fail-closed",
                deviceId, body.orderNo(), body.errorMsg());
        try {
            tradeInternalClient.recognizeTimeout(body.orderNo());
        } catch (Exception e) {
            log.error("jiangyi recognize-timeout notify failed orderNo={}", body.orderNo(), e);
        }
        return ok();
    }

    // ---------- §4.2.9 双门占用（可选）/ 故障单 ----------

    @PostMapping("/jiangyi/api/order/openDoubleDoorError")
    public Map<String, Object> openDoubleDoorError(HttpServletRequest request,
                                                   @RequestBody DoubleDoorRequest body) {
        log.warn("jiangyi double-door busy deviceId={} orderNo={} doorPosition={}",
                device(request), body.orderNo(), body.doorPosition());
        return ok();
    }

    /** 故障单（§5.2.x）：一期仅审计；会话生命周期由 watchdog 兜底。 */
    @PostMapping("/jiangyi/api/order/uploadFaultOrder")
    public Map<String, Object> uploadFaultOrder(HttpServletRequest request,
                                                @RequestBody FaultOrderRequest body) {
        log.warn("jiangyi fault order deviceId={} body={}", device(request), body);
        return ok();
    }

    // ---------- 内部 ----------

    /** 合成关门事件转发（模式一：识别/关门上报前统一推进 SHOPPING→RECOGNIZING）。 */
    private void forwardDoorClosed(String deviceId, String sessionId) {
        try {
            tradeInternalClient.doorEvent(new DoorEventRequest(
                    sessionId, deviceId, DoorState.CLOSED, System.currentTimeMillis()));
            log.info("jiangyi door-event CLOSED forwarded deviceId={} sessionId={}", deviceId, sessionId);
        } catch (Exception e) {
            // TOO_EARLY（已关门）等幂等场景也在异常流里——识别转发继续，由 edge-results 侧校验
            log.warn("jiangyi door-event forward rejected deviceId={} sessionId={} reason={}",
                    deviceId, sessionId, e.getMessage());
        }
    }

    private static String device(HttpServletRequest request) {
        Object attr = request.getAttribute(DeviceAuthInterceptor.ATTR_DEVICE_ID);
        return attr == null ? "unknown" : attr.toString();
    }

    private static Map<String, Object> ok() {
        return Map.of("status", 200, "msg", "success", "data", "0");
    }

    // ---------- 请求体（V16 文档字段照抄） ----------

    record LockStateRequest(String orderNo, String lockStatus) {}
    record DoorStateRequest(String orderNo, String doorStatus) {}
    record RecognitionRequest(String orderNo, List<RecognitionNormalizer.Form> forms) {}
    /** §4.2.11 bigModel / §4.2.12 BigModel——大小写两存，运行时归一（铁律：固件不可信）。 */
    record OrderErrorRequest(String orderNo, String errorMsg, String bigModel, String BigModel) {}
    record DoubleDoorRequest(String orderNo, String doorPosition) {}
    record FaultOrderRequest(String orderNo, String errorCode, String errorMsg) {}
}
