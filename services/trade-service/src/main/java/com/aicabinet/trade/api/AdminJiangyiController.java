package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.JiangyiBindingViewDto;
import com.aicabinet.common.dto.JiangyiClassMappingDto;
import com.aicabinet.common.dto.JiangyiDeviceDto;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.service.JiangyiClassMappingService;
import com.aicabinet.trade.service.JiangyiOnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 将邑接入运营后台接口（CB-022 收尾）：把此前只能手工 SQL 的登记/绑定/映射升级为
 * 设备详情页「将邑接入」卡片可直接操作。
 *
 * <p>能力面与 {@link JiangyiInternalController} 的入驻/映射面同源（同一批 service），
 * 区别只在入口：本控制器面向运营人员（RBAC + 审计），internal 面向 jiangyi-gateway
 * （INTERNAL_API_KEY）。库存不在此处——device_sku_inventory 已有运营货道管理
 * （OpsReplenishmentController），将邑柜直接复用。</p>
 *
 * <p>权限复用柜机既有权限位（ops:device:list 读 / ops:device:edit 写），
 * 不新增 RBAC 种子——将邑接入是柜机运维能力的一部分，不是独立角色面。</p>
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class AdminJiangyiController {

    private final JiangyiOnboardingService jiangyiOnboardingService;
    private final JiangyiClassMappingService jiangyiClassMappingService;

    public AdminJiangyiController(JiangyiOnboardingService jiangyiOnboardingService,
                                  JiangyiClassMappingService jiangyiClassMappingService) {
        this.jiangyiOnboardingService = jiangyiOnboardingService;
        this.jiangyiClassMappingService = jiangyiClassMappingService;
    }

    /** 绑定视图：binding=null = 未登记（前台展示「未接入」态 + 登记表单）。 */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/jiangyi")
    public ApiResponse<JiangyiBindingViewDto> view(@PathVariable("deviceId") String deviceId) {
        JiangyiDevice device = jiangyiOnboardingService.findByDeviceId(deviceId);
        List<JiangyiClassMappingDto> mappings = jiangyiClassMappingService.listByDevice(deviceId)
                .stream().map(AdminJiangyiController::toDto).toList();
        return ApiResponse.ok(new JiangyiBindingViewDto(
                device == null ? null : toDto(device), mappings));
    }

    /** 登记：登记 jiangyi_device（status=UNBOUND），幂等按 SN 判存；柜机档案必须已存在。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/register")
    public ApiResponse<JiangyiDeviceDto> register(@PathVariable("deviceId") String deviceId,
                                                  @Valid @RequestBody RegisterRequest body) {
        return ApiResponse.ok(toDto(jiangyiOnboardingService.registerForDevice(
                deviceId, body.deviceSn().trim(), blankToNull(body.modelName()))));
    }

    /**
     * 绑定：调将邑 setDomain 把设备指向我方 → 落 identifier → status=BOUND。
     * 需要部署侧已配置 JIANGYI_TENANT_ID/JIANGYI_SECRET，未配置时服务层报错前台提示。
     */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/bind")
    public ApiResponse<JiangyiDeviceDto> bind(@PathVariable("deviceId") String deviceId,
                                              @Valid @RequestBody BindRequest body) {
        return ApiResponse.ok(toDto(jiangyiOnboardingService.bindForDevice(
                deviceId, body.domain().trim(), blankToNull(body.socketUrl()))));
    }

    /** 退役：拒绝一切后续路由与上报（gateway 校验 status）。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/retire")
    public ApiResponse<JiangyiDeviceDto> retire(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(toDto(jiangyiOnboardingService.retire(deviceId)));
    }

    /** 幂等 upsert 映射（识别换算 SKU 的唯一依据；未命中即结算 fail-closed 转 DISPUTED）。 */
    @RequiresPermissions("ops:device:edit")
    @PutMapping("/devices/{deviceId}/jiangyi/class-mappings/{classId}")
    public ApiResponse<JiangyiClassMappingDto> upsertMapping(
            @PathVariable("deviceId") String deviceId,
            @PathVariable("classId") Integer classId,
            @Valid @RequestBody MappingUpsertRequest body) {
        JiangyiClassMapping mapping = new JiangyiClassMapping();
        mapping.setDeviceId(deviceId);
        mapping.setClassId(classId);
        mapping.setModelName(blankToNull(body.modelName()));
        mapping.setTextName(blankToNull(body.textName()));
        mapping.setSkuId(blankToNull(body.skuId()));
        mapping.setStatus(Boolean.TRUE.equals(body.active()) ? "ACTIVE" : "DISABLED");
        mapping.setSource("MANUAL");
        return ApiResponse.ok(toDto(jiangyiClassMappingService.upsert(mapping)));
    }

    /** 启停（停用即时生效：下次识别解析即 fail-closed）。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/class-mappings/{classId}/status")
    public ApiResponse<Integer> setMappingStatus(@PathVariable("deviceId") String deviceId,
                                                 @PathVariable("classId") Integer classId,
                                                 @Valid @RequestBody MappingStatusRequest body) {
        return ApiResponse.ok(jiangyiClassMappingService.setStatus(
                deviceId, classId, Boolean.TRUE.equals(body.active())));
    }

    // ---------- 请求体 / 装配 ----------

    record RegisterRequest(@NotBlank(message = "设备 SN 不能为空") String deviceSn,
                           String modelName) {}

    record BindRequest(@NotBlank(message = "domain 不能为空") String domain,
                       String socketUrl) {}

    record MappingUpsertRequest(@NotBlank(message = "SKU 不能为空") String skuId,
                                String textName,
                                String modelName,
                                Boolean active) {}

    record MappingStatusRequest(Boolean active) {}

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static JiangyiDeviceDto toDto(JiangyiDevice d) {
        return new JiangyiDeviceDto(d.getDeviceId(), d.getDeviceSn(), d.getIdentifier(),
                d.getModelName(), d.getClassesVersion(), d.getStatus(), d.getTokenVersion(),
                d.getTokenIssuedAt(), d.getLastWsOnlineAt(), d.getCreatedAt(), d.getUpdatedAt());
    }

    private static JiangyiClassMappingDto toDto(JiangyiClassMapping m) {
        return new JiangyiClassMappingDto(m.getId(), m.getDeviceId(), m.getClassId(),
                m.getModelName(), m.getTextName(), m.getSkuId(), m.getStatus(), m.getSource(),
                m.getCreatedAt(), m.getUpdatedAt());
    }
}
