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
        return handleLockState(request, body.orderNo(), body.lockStatus());
    }

    /**
     * 真机实路径（V15/V16 均未记载，2026-10-19 log3 实证：POST /order/uploadOrderLockStatus，
     * body {@code {"orderNo":"","lockStatus":"success"}}）——与 §4.2.7 uploadLockState 同语义，
     * 文档路径与固件路径并存兼容。orderNo 允许为空（真机实发空串）。
     */
    @PostMapping("/jiangyi/api/order/uploadOrderLockStatus")
    public Map<String, Object> uploadOrderLockStatus(HttpServletRequest request,
                                                     @RequestBody LockStateRequest body) {
        return handleLockState(request, body.orderNo(), body.lockStatus());
    }

    private Map<String, Object> handleLockState(HttpServletRequest request, String orderNo, String lockStatus) {
        String deviceId = device(request);
        Boolean success = DoorStateNormalizer.normalize(lockStatus);
        if (success == null) {
            // 未知词汇/缺字段：只告警不动会话——fail-close 会误杀进行中的订单（第三轮审计 P0-3）
            log.warn("jiangyi lock status UNKNOWN deviceId={} orderNo={} raw={} — ignore (fail-open)",
                    deviceId, orderNo, lockStatus);
            return ok();
        }
        if (success) {
            if (isBlank(orderNo)) {
                // 真机实锤 {"orderNo":"","lockStatus":"success"}：空单号无会话可完成，只审计
                log.info("jiangyi lock OK (blank orderNo, audit only) deviceId={}", deviceId);
                return ok();
            }
            commandTracker.complete(CommandTracker.Kind.OPEN, orderNo, deviceId);
            log.info("jiangyi lock OK deviceId={} orderNo={}", deviceId, orderNo);
            return ok();
        }
        if (isBlank(orderNo)) {
            log.warn("jiangyi lock FAIL with blank orderNo deviceId={} — nothing to fail", deviceId);
            return ok();
        }
        log.warn("jiangyi lock FAIL deviceId={} orderNo={} — notify open-failed", deviceId, orderNo);
        notifyOpenFailed(orderNo, deviceId, "将邑设备上报开锁失败");
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
        return handleDoorState(request, body.orderNo(), body.doorStatus());
    }

    /**
     * 真机实路径（V15/V16 均未记载，log3 实证 30 次：POST /deviceInfo/uploadDeviceOpenStatus，
     * body {@code {"openDoorStatus":"yes"}}，多数无 orderNo）——设备物理开门回报。
     * 带 orderNo 时按门开事件推进会话；不带只审计（无法关联会话，靠 §4.2.10 识别上报推进）。
     */
    @PostMapping("/jiangyi/api/deviceInfo/uploadDeviceOpenStatus")
    public Map<String, Object> uploadDeviceOpenStatus(HttpServletRequest request,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        String deviceId = device(request);
        String status = body == null ? null : String.valueOf(body.getOrDefault("openDoorStatus", "")).trim();
        String orderNo = body == null || body.get("orderNo") == null ? null : body.get("orderNo").toString();
        log.info("jiangyi device open-status deviceId={} openDoorStatus={} orderNo={}",
                deviceId, status, orderNo);
        Boolean open = DoorStateNormalizer.normalize(status);
        if (Boolean.TRUE.equals(open) && !isBlank(orderNo)) {
            forwardDoorOpen(deviceId, orderNo);
            return ok();
        }
        return ok();
    }

    private Map<String, Object> handleDoorState(HttpServletRequest request, String orderNo, String doorStatus) {
        String deviceId = device(request);
        Boolean success = DoorStateNormalizer.normalize(doorStatus);
        if (success == null) {
            // 未知词汇/缺字段：只告警不动会话（第三轮审计 P0-3 fail-open 修订）
            log.warn("jiangyi door status UNKNOWN deviceId={} orderNo={} raw={} — ignore (fail-open)",
                    deviceId, orderNo, doorStatus);
            return ok();
        }
        if (!success) {
            if (isBlank(orderNo)) {
                log.warn("jiangyi door FAIL with blank orderNo deviceId={} — nothing to fail", deviceId);
                return ok();
            }
            // V16 §4.2.8「如果开门失败，不会上报订单结果」——明确失败才立即 open-failed（幂等）
            log.warn("jiangyi door FAIL deviceId={} orderNo={} — notify open-failed", deviceId, orderNo);
            notifyOpenFailed(orderNo, deviceId, "将邑设备上报拉门失败");
            return ok();
        }
        if (isBlank(orderNo)) {
            log.info("jiangyi door OPEN (blank orderNo, audit only) deviceId={}", deviceId);
            return ok();
        }
        forwardDoorOpen(deviceId, orderNo);
        // 登记关门 300s 兜底（识别转发后 complete；误登记由 watchdog→trade 幂等处置消化）
        commandTracker.register(CommandTracker.Kind.CLOSE, orderNo, deviceId,
                String.valueOf(request.getAttribute(DeviceAuthInterceptor.ATTR_IDENTIFIER)));
        return ok();
    }

    private void forwardDoorOpen(String deviceId, String orderNo) {
        try {
            tradeInternalClient.doorEvent(new DoorEventRequest(
                    orderNo, deviceId, DoorState.OPEN, System.currentTimeMillis()));
            log.info("jiangyi door-event OPEN forwarded deviceId={} sessionId={}", deviceId, orderNo);
        } catch (Exception e) {
            log.warn("jiangyi door-event forward rejected deviceId={} sessionId={} reason={}",
                    deviceId, orderNo, e.getMessage());
        }
    }

    /** open-failed 统一出口：orderNo 空守卫（真机实发 ""——拼 URL 会产出 /sessions//open-failed）。 */
    private void notifyOpenFailed(String orderNo, String deviceId, String reason) {
        if (isBlank(orderNo)) {
            log.warn("jiangyi open-failed skipped (blank orderNo) deviceId={} reason={}", deviceId, reason);
            return;
        }
        try {
            tradeInternalClient.postOpenFailed(orderNo, reason, deviceId);
        } catch (Exception e) {
            log.error("jiangyi open-failed notify failed orderNo={}", orderNo, e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
        // 真机报文存在 orderNo 为空的形态（log3 实证）——空单号识别无法关联会话，只审计
        if (isBlank(sessionId)) {
            log.warn("jiangyi recognition with blank orderNo deviceId={} forms={} — audit only",
                    deviceId, body.forms() == null ? 0 : body.forms().size());
            return ok();
        }
        forwardDoorClosed(deviceId, sessionId);

        List<VisionRecognitionResultDto.Item> items = recognitionNormalizer.normalize(deviceId, body.forms());
        if (items.isEmpty()) {
            // forms 空 = 没识别到商品（合法）；映射未命中 = fail-closed。
            // 两者对 trade 的处置相同：立即转人工审核，不等 10 分钟扫描。
            boolean isMiss = body.forms() != null && !body.forms().isEmpty();
            boolean handled = tradeInternalClient.recognizeTimeout(sessionId, deviceId);
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
        boolean forwarded = false;
        try {
            var response = tradeInternalClient.edgeResults(result);
            forwarded = response != null;
            log.info("jiangyi recognition forwarded deviceId={} sessionId={} items={} outcome={}",
                    deviceId, sessionId, items.size(), response == null ? "n/a" : response.outcome());
        } catch (Exception e) {
            log.error("jiangyi edge-results forward failed deviceId={} sessionId={}", deviceId, sessionId, e);
        }
        if (forwarded) {
            // 受理结论已回：关门兜底使命完成
            commandTracker.complete(CommandTracker.Kind.CLOSE, sessionId, deviceId);
        } else {
            // AU-063（批次 11）：转发失败不撤销关门兜底——旧实现 finally 无条件 complete，
            // trade 不可达时识别数据丢失且 300s watchdog 不再触发（仅靠 trade 侧扫描）。
            // 保留兜底：watchdog 到期 open-failed → recognize-timeout → DISPUTED（fail-safe），
            // trade 恢复后自动收口；设备侧已回 200 不重报属既有取舍（防重试风暴，注释留痕）。
            log.warn("jiangyi edge-results forward incomplete, CLOSE watchdog retained deviceId={} sessionId={}",
                    deviceId, sessionId);
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
        if (isBlank(body.orderNo())) {
            log.warn("jiangyi order error with blank orderNo deviceId={} — nothing to notify", deviceId);
            return ok();
        }
        try {
            tradeInternalClient.recognizeTimeout(body.orderNo(), deviceId);
        } catch (Exception e) {
            log.error("jiangyi recognize-timeout notify failed orderNo={}", body.orderNo(), e);
        }
        return ok();
    }

    // ---------- 真机实路径 stub（V15/V16 均未记载，log3 实证高频调用） ----------

    /**
     * 运营模式同步（log3 实证 110 次：POST /operationMode/sync，开机及运行期周期调用）。
     * 契约未文档化——宽容收下记日志回 ok（设备按成功处理）；模式语义待厂商答复（汇报文件问题 7）。
     */
    @PostMapping("/jiangyi/api/operationMode/sync")
    public Map<String, Object> operationModeSync(HttpServletRequest request,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        log.info("jiangyi operationMode sync deviceId={} body={}", device(request), body);
        return ok();
    }

    /**
     * 视频自动上传模式查询（log3 实证 34 次：POST /deviceInfo/isAutoUploadVideo）。
     * 与 §4.2.13 getArtificialCheckByIdentifier 同族：默认 data=false（只上传异常视频，
     * 全量上传属商户配置暂不开放）。
     */
    @PostMapping("/jiangyi/api/deviceInfo/isAutoUploadVideo")
    public Map<String, Object> isAutoUploadVideo(HttpServletRequest request) {
        log.info("jiangyi isAutoUploadVideo deviceId={} → false（默认只传异常视频）", device(request));
        return Map.of("status", 200, "msg", "success", "data", false);
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

    // ---------- §4.2.15 上报设备信息 ----------

    /**
     * 设备信息上报（V16 §4.2.15 devicelnfo/getDeviceSimlnfo，可选接口）：
     * 机器启动默认上报一次；可下发 getDeviceInfo（§4.3.2.13）实时拉取。
     * 当前只取 signalStrength 0-4（M 必报；simNum/temperature/location 文档标注"暂无法获取"），
     * 转发 trade device_info.signal_strength（V363，图2 对照信号强度展示）。
     */
    @PostMapping("/jiangyi/api/deviceInfo/getDeviceSimInfo")
    public Map<String, Object> getDeviceSimInfo(HttpServletRequest request,
                                                @RequestBody DeviceSimInfoRequest body) {
        String deviceId = device(request);
        Integer signal = parseSignalStrength(body.signalStrength());
        if (signal == null) {
            log.debug("jiangyi sim-info without signal deviceId={} body={}", deviceId, body);
            return ok();
        }
        try {
            tradeInternalClient.deviceSignal(deviceId, signal);
            log.info("jiangyi signal reported deviceId={} signal={}", deviceId, signal);
        } catch (Exception e) {
            log.warn("jiangyi signal forward failed deviceId={} signal={}: {}",
                    deviceId, signal, e.getMessage());
        }
        return ok();
    }

    /** 文档 signalStrength 为 String 型 0-4；宽容解析（固件口径不可信，铁律）。 */
    private static Integer parseSignalStrength(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v >= 0 && v <= 4 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- §4.2.16-4.2.18 可选上报（2026-10-19 补齐：真机固件可能调用，缺端点会 404） ----------

    /**
     * 门磁实时状态上报（V16 §4.2.16 deviceInfo/getDoorInfo，可选）：
     * 运行过程下发命令后设备回报实时门/锁状态。当前仅审计日志——
     * 门锁状态主链路走 §4.2.7 uploadLockState / §4.2.8 uploadDoorState（带 orderNo）。
     */
    @PostMapping("/jiangyi/api/deviceInfo/getDoorInfo")
    public Map<String, Object> getDoorInfo(HttpServletRequest request,
                                           @RequestBody DoorInfoRequest body) {
        log.info("jiangyi door-magnet report deviceId={} doorState={} lockState={}",
                device(request), body.doorState(), body.lockState());
        return ok();
    }

    /** Wi-Fi 连接状态上报（V16 §4.2.17 deviceInfo/changeWifi，可选）：服务器下发连网指令后回报。 */
    @PostMapping("/jiangyi/api/deviceInfo/changeWifi")
    public Map<String, Object> changeWifi(HttpServletRequest request,
                                          @RequestBody NetworkStateRequest body) {
        log.info("jiangyi wifi-state report deviceId={} network={}", device(request), body.network());
        return ok();
    }

    /** 机器端在线状态上报（V16 §4.2.18 deviceInfo/queryNetwork，可选）：下发查询后回报。 */
    @PostMapping("/jiangyi/api/deviceInfo/queryNetwork")
    public Map<String, Object> queryNetwork(HttpServletRequest request,
                                            @RequestBody NetworkStateRequest body) {
        log.info("jiangyi network-state report deviceId={} network={}", device(request), body.network());
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
    /** §4.2.15 字段照抄文档（signalStrength M 必报 0-4，String 型）。 */
    record DeviceSimInfoRequest(String simNum, String temperature, String signalStrength,
                                String location, String isMove) {}
    /** §4.2.16 门磁：doorState/lockState ∈ open|close（文档 String 型）。 */
    record DoorInfoRequest(String doorState, String lockState) {}
    /** §4.2.17/18 网络状态：network ∈ online|offline。 */
    record NetworkStateRequest(String network) {}
}
