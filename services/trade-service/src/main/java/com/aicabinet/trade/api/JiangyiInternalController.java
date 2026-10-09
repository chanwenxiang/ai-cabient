package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.service.JiangyiClassMappingService;
import com.aicabinet.trade.service.JiangyiDeviceDirectory;
import com.aicabinet.trade.service.JiangyiOnboardingService;
import com.aicabinet.trade.service.JiangyiRecognitionTimeoutService;
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
    private final com.aicabinet.trade.mapper.JiangyiDeviceMapper jiangyiDeviceMapper;

    public JiangyiInternalController(JiangyiDeviceDirectory jiangyiDeviceDirectory,
                                     JiangyiClassMappingService jiangyiClassMappingService,
                                     JiangyiRecognitionTimeoutService jiangyiRecognitionTimeoutService,
                                     JiangyiOnboardingService jiangyiOnboardingService,
                                     com.aicabinet.trade.mapper.JiangyiDeviceMapper jiangyiDeviceMapper) {
        this.jiangyiDeviceDirectory = jiangyiDeviceDirectory;
        this.jiangyiClassMappingService = jiangyiClassMappingService;
        this.jiangyiRecognitionTimeoutService = jiangyiRecognitionTimeoutService;
        this.jiangyiOnboardingService = jiangyiOnboardingService;
        this.jiangyiDeviceMapper = jiangyiDeviceMapper;
    }

    // ---------- 设备面 ----------

    /** 设备详情（gateway 校验 status=BOUND 后才服务该设备）。 */
    @GetMapping("/devices/{deviceId}")
    public ApiResponse<JiangyiDevice> device(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiDeviceMapper.selectById(deviceId));
    }

    /** 按 SN 查设备（gateway /jiangyi/api/token 签发前校验主体）。 */
    @GetMapping("/devices/by-sn/{deviceSn}")
    public ApiResponse<JiangyiDevice> deviceBySn(@PathVariable("deviceSn") String deviceSn) {
        return ApiResponse.ok(jiangyiDeviceMapper.findByDeviceSn(deviceSn));
    }

    /** token 签发回执：只记签发时间（吊销走 /token-revoke，签发不 bump，方案 §3）。 */
    @PostMapping("/devices/{deviceId}/token-issued")
    public ApiResponse<Integer> tokenIssued(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiDeviceMapper.markTokenIssued(deviceId, Instant.now()));
    }

    /** 吊销：token_version+1，该设备所有旧 token 即刻失效（审计由网关侧记录）。 */
    @PostMapping("/devices/{deviceId}/token-revoke")
    public ApiResponse<Integer> tokenRevoke(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiDeviceMapper.bumpTokenVersion(deviceId, Instant.now()));
    }

    /** WS 在线回执（gateway onConnect/心跳时调用）。 */
    @PostMapping("/devices/{deviceId}/ws-online")
    public ApiResponse<Integer> wsOnline(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiDeviceMapper.markWsOnline(deviceId, Instant.now()));
    }

    /** 路由判定（供诊断/联调核查，DeviceServiceClient 侧走本地 Directory）。 */
    @GetMapping("/devices/{deviceId}/routable")
    public ApiResponse<Boolean> routable(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiDeviceDirectory.isJiangyi(deviceId));
    }

    // ---------- 映射面 ----------

    /** ACTIVE 映射点查：data=null 即未命中（gateway 据此触发 recognize-timeout）。 */
    @GetMapping("/class-mappings/{deviceId}/{classId}")
    public ApiResponse<JiangyiClassMapping> resolveMapping(
            @PathVariable("deviceId") String deviceId,
            @PathVariable("classId") Integer classId) {
        return ApiResponse.ok(jiangyiClassMappingService.resolveActive(deviceId, classId).orElse(null));
    }

    @GetMapping("/class-mappings")
    public ApiResponse<List<JiangyiClassMapping>> mappings(
            @RequestParam("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiClassMappingService.listByDevice(deviceId));
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
     * 识别映射未命中 → 即时转人工审核（对齐 C09：释放冻结 → DISPUTED → 争议单）。
     * 返回 false 表示会话不在此状态（已结算/已取消等），幂等不重复处置。
     */
    @PostMapping("/sessions/{sessionId}/recognize-timeout")
    public ApiResponse<Boolean> recognizeTimeout(@PathVariable("sessionId") String sessionId) {
        return ApiResponse.ok(jiangyiRecognitionTimeoutService.markRecognitionTimeout(sessionId));
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
    record RegisterRequest(String deviceId, String deviceSn, String modelName) {}
    record BindDomainRequest(String deviceSn, String domain, String socketUrl) {}
}
