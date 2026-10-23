package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiOrderVideo;
import com.aicabinet.trade.domain.JiangyiRecognitionReport;
import com.aicabinet.trade.service.DevicePresenceService;
import com.aicabinet.trade.service.JiangyiClassMappingService;
import com.aicabinet.trade.service.JiangyiDeviceDirectory;
import com.aicabinet.trade.service.JiangyiFloorGoodsService;
import com.aicabinet.trade.service.JiangyiGatherService;
import com.aicabinet.trade.service.JiangyiMappingReviewService;
import com.aicabinet.trade.service.JiangyiModelSyncService;
import com.aicabinet.trade.service.JiangyiOnboardingService;
import com.aicabinet.trade.service.JiangyiOrderVideoService;
import com.aicabinet.trade.service.JiangyiRecognitionReportService;
import com.aicabinet.trade.service.JiangyiRecognitionTimeoutService;
import com.aicabinet.trade.service.SkuJiangyiLinkService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 将邑开门柜内部接口（CB-022）：jiangyi-gateway 与运维侧调用 trade 的入口。
 * <p>路径前缀 {@code /internal/v1/jiangyi/**} 自动被 InternalApiAuthInterceptor 覆盖
 * （WebConfig 拦 {@code /internal/**}，无需改动）。</p>
 *
 * <p>职责面：</p>
 * <ul>
 *   <li>设备：详情/按 SN 查（token 签发主体校验）、token 签发回执、WS 在线回执；</li>
 *   <li>映射：class_id→SKU 解析（gateway 识别上报链路；data=null 即未命中 fail-closed）、
 *       管理 upsert/启停；</li>
 *   <li>超时：映射未命中 → 会话即时转 DISPUTED（不等 10 分钟扫描）；</li>
 *   <li>入驻：register / bindDomain / retire（将邑商户云调用见 JiangyiOnboardingService）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/v1/jiangyi")
public class JiangyiInternalController {

    private final JiangyiDeviceDirectory jiangyiDeviceDirectory;
    private final JiangyiClassMappingService jiangyiClassMappingService;
    private final JiangyiRecognitionTimeoutService jiangyiRecognitionTimeoutService;
    private final JiangyiOnboardingService jiangyiOnboardingService;
    private final DevicePresenceService devicePresenceService;
    private final JiangyiModelSyncService jiangyiModelSyncService;
    private final JiangyiGatherService jiangyiGatherService;
    private final JiangyiOrderVideoService jiangyiOrderVideoService;
    private final SkuJiangyiLinkService skuJiangyiLinkService;
    private final JiangyiFloorGoodsService jiangyiFloorGoodsService;
    private final JiangyiRecognitionReportService jiangyiRecognitionReportService;
    private final JiangyiMappingReviewService jiangyiMappingReviewService;

    public JiangyiInternalController(JiangyiDeviceDirectory jiangyiDeviceDirectory,
                                     JiangyiClassMappingService jiangyiClassMappingService,
                                     JiangyiRecognitionTimeoutService jiangyiRecognitionTimeoutService,
                                     JiangyiOnboardingService jiangyiOnboardingService,
                                     DevicePresenceService devicePresenceService,
                                     JiangyiModelSyncService jiangyiModelSyncService,
                                     JiangyiGatherService jiangyiGatherService,
                                     JiangyiOrderVideoService jiangyiOrderVideoService,
                                     SkuJiangyiLinkService skuJiangyiLinkService,
                                     JiangyiFloorGoodsService jiangyiFloorGoodsService,
                                     JiangyiRecognitionReportService jiangyiRecognitionReportService,
                                     JiangyiMappingReviewService jiangyiMappingReviewService) {
        this.jiangyiDeviceDirectory = jiangyiDeviceDirectory;
        this.jiangyiClassMappingService = jiangyiClassMappingService;
        this.jiangyiRecognitionTimeoutService = jiangyiRecognitionTimeoutService;
        this.jiangyiOnboardingService = jiangyiOnboardingService;
        this.devicePresenceService = devicePresenceService;
        this.jiangyiModelSyncService = jiangyiModelSyncService;
        this.jiangyiGatherService = jiangyiGatherService;
        this.jiangyiOrderVideoService = jiangyiOrderVideoService;
        this.skuJiangyiLinkService = skuJiangyiLinkService;
        this.jiangyiFloorGoodsService = jiangyiFloorGoodsService;
        this.jiangyiRecognitionReportService = jiangyiRecognitionReportService;
        this.jiangyiMappingReviewService = jiangyiMappingReviewService;
    }

    // ---------- 设备面 ----------

    /** 设备详情（gateway 校验 status=BOUND 后才服务该设备）。 */
    @GetMapping("/devices/{deviceId}")
    public ApiResponse<JiangyiDevice> device(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiDeviceDirectory.findDevice(deviceId));
    }

    /** 按 SN 查设备（gateway /jiangyi/api/token 签发前校验主体）。 */
    @GetMapping("/devices/by-sn/{deviceSn}")
    public ApiResponse<JiangyiDevice> deviceBySn(@PathVariable("deviceSn") String deviceSn) {
        return ApiResponse.ok(jiangyiDeviceDirectory.findByDeviceSn(deviceSn));
    }

    /** token 签发回执：只记签发时间（吊销走 /token-revoke，签发不 bump，方案 §3）。 */
    @PostMapping("/devices/{deviceId}/token-issued")
    public ApiResponse<Integer> tokenIssued(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiDeviceDirectory.markTokenIssued(deviceId));
    }

    /** 吊销：token_version+1，该设备所有旧 token 即刻失效（审计由网关侧记录）。 */
    @PostMapping("/devices/{deviceId}/token-revoke")
    public ApiResponse<Integer> tokenRevoke(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiDeviceDirectory.bumpTokenVersion(deviceId));
    }

    /**
     * WS 在线回执（gateway 建连/心跳节流时调用）。除 jiangyi_device.last_ws_online_at 外，
     * 同步置 device_info.online_status=ONLINE 并刷新 updated_at——开门校验
     * ensureDeviceOnline 查的是后者，离线巡检按 updated_at 判活（2 分钟）。
     * 2026-10-09 联调缺陷⑥：此前只写 jiangyi_device，开门恒 409「设备离线」。
     */
    @PostMapping("/devices/{deviceId}/ws-online")
    @Transactional
    public ApiResponse<Integer> wsOnline(@PathVariable("deviceId") String deviceId) {
        int rows = jiangyiDeviceDirectory.markWsOnline(deviceId);
        devicePresenceService.setWsPresence(deviceId, true);
        return ApiResponse.ok(rows);
    }

    /** WS 断开回执：即时置 device_info 离线（不等巡检 2 分钟；巡检作为断开上报失败时的兜底）。 */
    @PostMapping("/devices/{deviceId}/ws-offline")
    @Transactional
    public ApiResponse<Integer> wsOffline(@PathVariable("deviceId") String deviceId) {
        devicePresenceService.setWsPresence(deviceId, false);
        return ApiResponse.ok(1);
    }

    /**
     * 将邑信号强度上报转发（V363，图2 对照）：gateway 收到 §4.2.15
     * deviceInfo/getDeviceSimInfo（signalStrength 0-4）后转至此落 device_info。
     */
    @PostMapping("/devices/{deviceId}/signal")
    public ApiResponse<Integer> signal(@PathVariable("deviceId") String deviceId,
                                       @RequestBody SignalRequest body) {
        devicePresenceService.updateSignal(deviceId, body.signalStrength());
        return ApiResponse.ok(1);
    }

    /** 路由判定（供诊断/联调核查，DeviceServiceClient 侧走本地 Directory）。 */
    @GetMapping("/devices/{deviceId}/routable")
    public ApiResponse<Boolean> routable(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiDeviceDirectory.isJiangyi(deviceId));
    }

    // ---------- 模型同步面（CB-023） ----------

    /**
     * 设备模型下发完成回执（V16 §4.2.5 downloadModelNotify，gateway WS 分支转发）：
     * 回填 jiangyi_device.model_name/classes_version + deployment CONFIRMED；
     * 无进行中 SENT 记录 → 409（疑似串包/重放，调用方记 warn 不重试）。
     */
    @PostMapping("/devices/{deviceId}/model-confirmed")
    public ApiResponse<Void> modelConfirmed(@PathVariable("deviceId") String deviceId,
                                            @RequestBody ModelConfirmedRequest body) {
        jiangyiModelSyncService.confirmModel(deviceId, body.modelName());
        return ApiResponse.ok(null);
    }

    /** 模型下发超时（watchdog MODEL kind）：deployment SENT → FAILED（幂等）。 */
    @PostMapping("/model-deployments/{deploymentId}/push-timeout")
    public ApiResponse<Void> modelPushTimeout(@PathVariable("deploymentId") long deploymentId,
                                              @RequestBody ModelPushTimeoutRequest body) {
        jiangyiModelSyncService.markPushTimeout(deploymentId, body.reason());
        return ApiResponse.ok(null);
    }

    /**
     * 将邑云学习完成回调（gateway 公开面转发）：finishNotifyId 一次性凭据防伪造 +
     * 将邑侧反查交叉验证；未知/非 PENDING 凭据静默忽略（无鉴权面不回错误详情）。
     */
    @PostMapping("/gather-finish-notify")
    public ApiResponse<Void> gatherFinishNotify(@RequestBody GatherFinishNotifyRequest body) {
        jiangyiGatherService.handleFinishNotify(body.finishNotifyId(), body.msg());
        return ApiResponse.ok(null);
    }

    /**
     * 设备视频上报落库（CB-024 §4.2.14，gateway 转发）：
     * uk(order_no, serial_num) upsert 幂等——设备重试重报以最后一次为准。
     */
    @PostMapping("/order-video-report")
    public ApiResponse<Boolean> orderVideoReport(@RequestBody OrderVideoReportRequest body) {
        return ApiResponse.ok(jiangyiOrderVideoService.upsertReport(
                body.orderNo(), body.deviceId(), body.serialNum(),
                body.videoQuantity(), body.videoUrls()));
    }

    /** admin 视频复核：按订单查全部分片（serial 升序，失败片空串在列）。 */
    @GetMapping("/order-videos/by-order")
    public ApiResponse<List<JiangyiOrderVideo>> orderVideosByOrder(
            @RequestParam("orderNo") String orderNo) {
        return ApiResponse.ok(jiangyiOrderVideoService.findByOrderNo(orderNo));
    }

    /** admin 视频复核：按设备查最近上报（设备详情卡片入口）。 */
    @GetMapping("/order-videos/by-device")
    public ApiResponse<List<JiangyiOrderVideo>> orderVideosByDevice(
            @RequestParam("deviceId") String deviceId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(jiangyiOrderVideoService.findRecentByDevice(deviceId, limit));
    }

    // ---------- 映射面 ----------

    /**
     * ACTIVE 映射点查：data=null 即未命中（gateway 据此触发 recognize-timeout）。
     * <p>V400 复核闸口：未按柜机当前 classes 版本人工复核时一律回 null——即便某行仍是 ACTIVE，
     * 也不能拿旧编号的映射去结算（重训平移后同一编号是别的商品）。网关侧另有整单预检，
     * 这里兜住 thirdPid 等绕过预检的入口。</p>
     */
    @GetMapping("/class-mappings/{deviceId}/{classId}")
    public ApiResponse<JiangyiClassMapping> resolveMapping(
            @PathVariable("deviceId") String deviceId,
            @PathVariable("classId") Integer classId) {
        if (!jiangyiMappingReviewService.isVerified(deviceId)) {
            return ApiResponse.ok(null);
        }
        return ApiResponse.ok(jiangyiClassMappingService.resolveActive(deviceId, classId).orElse(null));
    }

    /**
     * 上报 {@code thirdPid}（将邑标准商品 id）→ 我方 skuId（挂接关系反查，2026-10-21）。
     * data=null 即未挂接/已解挂，gateway 回落 classId 映射。
     */
    @GetMapping("/skus/by-product/{productId}")
    public ApiResponse<String> skuByProduct(@PathVariable("productId") String productId) {
        return ApiResponse.ok(skuJiangyiLinkService.resolveSkuIdByProductId(productId).orElse(null));
    }

    /**
     * 设备补货/在售商品表（gateway {@code floor/getFloorByDevice} 转发，2026-10-21）。
     * 固件按 classId 从此表回填识别上报的 thirdPid/price/productName，并以此做识别歧义消解，
     * 故必须给出现行售价（与结算同价）与货道库存。
     */
    @GetMapping("/devices/{deviceId}/floor-goods")
    public ApiResponse<List<JiangyiFloorGoodsService.FloorGoods>> floorGoods(
            @PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiFloorGoodsService.listFloorGoods(deviceId));
    }

    /**
     * 映射复核态（V400）：gateway 识别归一化前必查——未复核 ⇔ 当前 classes 版本没有人工
     * 审过的映射，整单 fail-closed 转人工。未登记设备返回 verified=false（不 404）。
     */
    @GetMapping("/devices/{deviceId}/mapping-status")
    public ApiResponse<JiangyiMappingReviewService.MappingStatus> mappingStatus(
            @PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiMappingReviewService.status(deviceId));
    }

    /** 全量映射（含 DISABLED）；负编号是重算腾位哨兵（V400），不对外暴露。 */
    @GetMapping("/class-mappings")
    public ApiResponse<List<JiangyiClassMapping>> mappings(
            @RequestParam("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiClassMappingService.listByDevice(deviceId).stream()
                .filter(m -> m.getClassId() != null && m.getClassId() >= 0)
                .toList());
    }

    /** 幂等 upsert（admin 人工建立；二期 MODEL_SYNC 预生成共用）。 */
    @PostMapping("/class-mappings")
    public ApiResponse<JiangyiClassMapping> upsertMapping(@RequestBody MappingUpsertRequest body) {
        JiangyiClassMapping mapping = new JiangyiClassMapping();
        mapping.setDeviceId(body.deviceId());
        mapping.setClassId(body.classId());
        mapping.setModelName(body.modelName());
        mapping.setTextName(body.textName());
        mapping.setSkuId(body.skuId());
        mapping.setStatus(body.status() == null || body.status().isBlank() ? "DISABLED" : body.status());
        mapping.setSource(body.source() == null || body.source().isBlank() ? "MANUAL" : body.source());
        return ApiResponse.ok(jiangyiClassMappingService.upsert(mapping));
    }

    /** 启停（MODEL_SYNC 预生成行人工确认后激活）。 */
    @PostMapping("/class-mappings/{deviceId}/{classId}/status")
    public ApiResponse<Integer> setMappingStatus(@PathVariable("deviceId") String deviceId,
                                                 @PathVariable("classId") Integer classId,
                                                 @RequestBody MappingStatusRequest body) {
        return ApiResponse.ok(jiangyiClassMappingService.setStatus(
                deviceId, classId, Boolean.TRUE.equals(body.active())));
    }

    // ---------- 超时面 ----------

    /**
     * 识别未成功 → 即时转人工审核（对齐 C09：释放冻结 → DISPUTED → 争议单）。
     * 返回 false 表示会话不在此状态（已结算/已取消等），幂等不重复处置。
     * cause/vendorDetail 由 gateway 透传；缺省按「超时未回传」旧口径处置（兼容未升级的 gateway）。
     */
    @PostMapping("/sessions/{sessionId}/recognize-timeout")
    public ApiResponse<Boolean> recognizeTimeout(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestParam(value = "cause", required = false) String cause,
            @RequestParam(value = "vendorDetail", required = false) String vendorDetail) {
        return ApiResponse.ok(jiangyiRecognitionTimeoutService.markRecognitionTimeout(
                sessionId, deviceId, cause, vendorDetail));
    }

    // ---------- 留档面（CB-062） ----------

    /**
     * 识别上报原样留档（V396 {@code jiangyi_recognition_report}）：gateway 每次到达落一行，
     * 含整段 forms 原文与我方归一化结果并排。🔴 与结算解耦——厂商 price 合计只存档不采信，
     * 本端点写入失败也不影响结算（gateway 侧 fire-and-forget）。
     */
    @PostMapping("/recognition-reports")
    public ApiResponse<Long> recognitionReport(@RequestBody RecognitionReportRequest body) {
        JiangyiRecognitionReport row = new JiangyiRecognitionReport();
        row.setDeviceId(body.deviceId());
        row.setOrderNo(body.orderNo());
        row.setTransactionId(body.transactionId());
        row.setPayType(body.payType());
        row.setFormsJson(body.formsJson());
        row.setItemsJson(body.itemsJson());
        row.setFormCount(body.formCount());
        row.setItemCount(body.itemCount());
        row.setVendorAmountCents(body.vendorAmountCents());
        row.setOutcome(body.outcome());
        row.setReportedAt(body.reportedAt());
        return ApiResponse.ok(jiangyiRecognitionReportService.archive(row));
    }

    // ---------- 入驻面 ----------

    @PostMapping("/onboarding/register")
    public ApiResponse<JiangyiDevice> register(@RequestBody RegisterRequest body) {
        return ApiResponse.ok(jiangyiOnboardingService.register(
                body.deviceId(), body.deviceSn(), body.modelName()));
    }

    @PostMapping("/onboarding/bind-domain")
    public ApiResponse<JiangyiDevice> bindDomain(@RequestBody BindDomainRequest body) {
        return ApiResponse.ok(jiangyiOnboardingService.bindDomain(
                body.deviceSn(), body.domain(), body.socketUrl()));
    }

    @PostMapping("/onboarding/{deviceId}/retire")
    public ApiResponse<JiangyiDevice> retire(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiOnboardingService.retire(deviceId));
    }

    // ---------- 请求体 ----------

    record MappingUpsertRequest(String deviceId, Integer classId, String modelName,
                                String textName, String skuId, String status, String source) {}
    record MappingStatusRequest(Boolean active) {}
    /** 与 gateway TradeInternalClient.RecognitionReportRequest 对齐（formsJson/itemsJson 为原文 JSON 串）。 */
    record RecognitionReportRequest(String deviceId, String orderNo, String transactionId,
                                    String payType, String formsJson, String itemsJson,
                                    Integer formCount, Integer itemCount, Long vendorAmountCents,
                                    String outcome, Instant reportedAt) {}
    record RegisterRequest(String deviceId, String deviceSn, String modelName) {}
    record BindDomainRequest(String deviceSn, String domain, String socketUrl) {}
    record ModelConfirmedRequest(String modelName) {}
    record ModelPushTimeoutRequest(String reason) {}
    record GatherFinishNotifyRequest(String finishNotifyId, String msg) {}
    /** 与 gateway TradeInternalClient.OrderVideoReportRequest 对齐（videoUrls 可空=该片失败留痕）。 */
    record OrderVideoReportRequest(String deviceId, String orderNo, Integer serialNum,
                                   Integer videoQuantity, List<String> videoUrls) {}
    /** 与 gateway TradeInternalClient.DeviceSignalRequest 对齐（§4.2.15 signalStrength 0-4）。 */
    record SignalRequest(Integer signalStrength) {}
}
