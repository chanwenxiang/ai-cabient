package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.JiangyiBindingViewDto;
import com.aicabinet.common.dto.JiangyiClassMappingDto;
import com.aicabinet.common.dto.JiangyiDeviceDto;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiModelDeployment;
import com.aicabinet.trade.service.JiangyiClassMappingService;
import com.aicabinet.trade.service.JiangyiDeviceOpsService;
import com.aicabinet.trade.service.JiangyiGatherService;
import com.aicabinet.trade.service.JiangyiMappingReviewService;
import com.aicabinet.trade.service.JiangyiModelSyncService;
import com.aicabinet.trade.service.JiangyiOnboardingService;
import com.aicabinet.trade.service.JiangyiOrderVideoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final JiangyiModelSyncService jiangyiModelSyncService;
    private final JiangyiGatherService jiangyiGatherService;
    private final JiangyiOrderVideoService jiangyiOrderVideoService;
    private final JiangyiDeviceOpsService jiangyiDeviceOpsService;
    private final JiangyiMappingReviewService jiangyiMappingReviewService;

    public AdminJiangyiController(JiangyiOnboardingService jiangyiOnboardingService,
                                  JiangyiClassMappingService jiangyiClassMappingService,
                                  JiangyiModelSyncService jiangyiModelSyncService,
                                  JiangyiGatherService jiangyiGatherService,
                                  JiangyiOrderVideoService jiangyiOrderVideoService,
                                  JiangyiDeviceOpsService jiangyiDeviceOpsService,
                                  JiangyiMappingReviewService jiangyiMappingReviewService) {
        this.jiangyiOnboardingService = jiangyiOnboardingService;
        this.jiangyiClassMappingService = jiangyiClassMappingService;
        this.jiangyiModelSyncService = jiangyiModelSyncService;
        this.jiangyiGatherService = jiangyiGatherService;
        this.jiangyiOrderVideoService = jiangyiOrderVideoService;
        this.jiangyiDeviceOpsService = jiangyiDeviceOpsService;
        this.jiangyiMappingReviewService = jiangyiMappingReviewService;
    }

    /**
     * 视频复核：按订单查全部分片（serial 升序；video_urls 空串=该片生成/上传失败）。
     *
     * <p>🔴 参数 {@code orderNo} 传的是<b>将邑上报的订单编号</b>，它等于我方 {@code sessionId}
     * （网关枢纽约定，V16 §4.3.2.7「商户服务器生成、需唯一」）——所以订单管理侧拿到
     * sessionId 就能直接查，不需要额外映射表。</p>
     *
     * <p>🔴 DC-11（2026-10-19 拍板）：本端点对 {@code ops:device:list} 持有者回<b>脱敏行</b>
     * （对象键 videoUrls 不出网，只回片数统计）；看视频走 {@code ops:device:video} 的
     * play-url 端点（CB-029 授权分离在此闭环）。</p>
     */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/jiangyi/order-videos/by-order")
    public ApiResponse<List<JiangyiOrderVideoService.VideoListItem>> orderVideosByOrder(
            @RequestParam("orderNo") String orderNo) {
        return ApiResponse.ok(jiangyiOrderVideoService.listByOrderRedacted(orderNo));
    }

    /** 视频复核：按补货任务查（收尾E 入口，补货视频 orderNo=补货会话 id）。DC-11：统一脱敏行。 */
    @RequiresPermissions("ops:device:video")
    @GetMapping("/jiangyi/order-videos/by-task/{taskId}")
    public ApiResponse<List<JiangyiOrderVideoService.VideoListItem>> orderVideosByTask(
            @PathVariable("taskId") long taskId) {
        return ApiResponse.ok(jiangyiOrderVideoService.listByTaskRedacted(taskId));
    }

    /** 视频复核：按设备查最近上报（设备详情「将邑接入」卡片入口）。DC-11：统一脱敏行。 */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/jiangyi/order-videos/by-device")
    public ApiResponse<List<JiangyiOrderVideoService.VideoListItem>> orderVideosByDevice(
            @RequestParam("deviceId") String deviceId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(jiangyiOrderVideoService.listRecentByDeviceRedacted(deviceId, limit));
    }

    /**
     * 视频复核：取该条上报的可播放地址（CB-029 读路径）。
     *
     * <p>🔴 <b>独立权限点</b> {@code ops:device:video}，刻意不复用 {@code ops:device:list}：
     * 行业团标《智能柜用户交易视频隐私保护规范》把交易视频单列为受约束对象，
     * 查看交易视频应与「看设备列表」分开授权（默认只给超级管理员与运营人员）。</p>
     */
    @RequiresPermissions("ops:device:video")
    @GetMapping("/jiangyi/order-videos/{id}/play-url")
    public ApiResponse<JiangyiOrderVideoService.PlayUrlView> orderVideoPlayUrl(
            @PathVariable("id") long id) {
        return ApiResponse.ok(jiangyiOrderVideoService.playUrls(id));
    }

    /**
     * 按单拉取柜内视频（V16 §4.3.2.11，方案 A 按需拉取）：orderNo=我方 sessionId。
     * 正常单视频默认只留柜内（isAutoUploadVideo=false 拍板不变），运营在此按需下发
     * 拉取指令 → 设备查 /sdcard/{单号}-*.mp4，有则传 OSS 回调入库，无则 WS 上报「无文件」。
     * 与查看视频同为 ops:device:video 授权；柜机需在线，无同步回执，稍后刷新视频列表看结果。
     */
    @RequiresPermissions("ops:device:video")
    @PostMapping("/jiangyi/order-videos/pull")
    public ApiResponse<Void> pullOrderVideo(HttpServletRequest request,
                                            @Valid @RequestBody PullVideoRequest body) {
        jiangyiDeviceOpsService.pullOrderVideo(operatorId(request), body.orderNo().trim());
        return ApiResponse.ok(null);
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

    /**
     * 租户级 setDomain（2026-10-20，系统参数设置「将邑接入」区块）。
     *
     * <p><b>作用域是租户不是设备</b>：/platform/tenant/setDomain 一次覆盖租户下全部柜子，
     * 与 {@code /devices/{id}/jiangyi/bind} 调的是同一个厂商接口；本端点存在的理由是
     * BOUND 设备的卡片不显示接入表单（v-if=UNBOUND），且全局配置不该埋在单设备页。
     * socketUrl 留空按 domain 自动推导（wss://host/websocket/device/）。</p>
     *
     * <p>⚠️ 对厂商云共享状态的写：租户下所有设备的上报/WS 目标都会随之切换。</p>
     */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/jiangyi/tenant-domain")
    public ApiResponse<JiangyiOnboardingService.TenantDomainView> setTenantDomain(
            @Valid @RequestBody TenantDomainRequest body) {
        return ApiResponse.ok(jiangyiOnboardingService.setTenantDomain(
                body.domain().trim(), blankToNull(body.socketUrl())));
    }

    /** 退役：拒绝一切后续路由与上报（gateway 校验 status）。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/retire")
    public ApiResponse<JiangyiDeviceDto> retire(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(toDto(jiangyiOnboardingService.retire(deviceId)));
    }

    /** 修改 SN（仅 UNBOUND）：登记抄错的纠正入口；SN 全局唯一，BOUND 须退役重登记。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/update-sn")
    public ApiResponse<JiangyiDeviceDto> updateSn(@PathVariable("deviceId") String deviceId,
                                                  @Valid @RequestBody UpdateSnRequest body) {
        return ApiResponse.ok(toDto(jiangyiOnboardingService.updateDeviceSn(deviceId, body.deviceSn())));
    }

    /**
     * 登记工控机机型（将邑原值 "76"/"88"）：模型预生成/下发按与厂商值字符串相等校验，
     * 未登记时 model-sync 与 model-push 均拒绝。
     */
    @RequiresPermissions("ops:device:edit")
    @PutMapping("/devices/{deviceId}/jiangyi/industrial-control-model")
    public ApiResponse<JiangyiDeviceDto> updateIndustrialControlModel(
            @PathVariable("deviceId") String deviceId,
            @Valid @RequestBody IndustrialControlModelRequest body) {
        return ApiResponse.ok(toDto(jiangyiOnboardingService.setIndustrialControlModel(
                deviceId, body.industrialControlModel())));
    }

    /** 将邑侧激活（V16 §5.4.10 confirmActivate）：出厂未入库设备用铭牌设备号入库。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/confirm-activate")
    public ApiResponse<JiangyiDeviceDto> confirmActivate(@PathVariable("deviceId") String deviceId,
                                                         @Valid @RequestBody ConfirmActivateRequest body) {
        return ApiResponse.ok(toDto(jiangyiOnboardingService.activateAtJiangyi(
                deviceId, blankToNull(body.identifier()), body.deviceName(),
                body.deviceAddress(), Boolean.TRUE.equals(body.screen()))));
    }

    /**
     * 激活：允许该设备凭 SN 领取 token（审计 A1/P0-3 激活制，2026-10-15 拍板）。
     * SN 为出厂可枚举标识，绑定≠在场——设备到场通电前由运营逐台激活（预授权）。
     */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/activate")
    public ApiResponse<JiangyiDeviceDto> activate(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(toDto(jiangyiOnboardingService.setDeviceAuthEnabled(deviceId, true)));
    }

    /** 停用：关闭开关并同步吊销存量 token（token_version+1，已发 token 即刻失效）。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/deactivate")
    public ApiResponse<JiangyiDeviceDto> deactivate(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(toDto(jiangyiOnboardingService.setDeviceAuthEnabled(deviceId, false)));
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

    // ---------- 设备控制面（V363，图2 对照，2026-10-15） ----------

    /** 音量设置（§4.3.2.16/17）：switchOn/volume 至少一项，volume 0=大/1=中/2=小。 */
    @RequiresPermissions("ops:device:edit")
    @PutMapping("/devices/{deviceId}/jiangyi/sound")
    public ApiResponse<Void> setSound(HttpServletRequest request,
                                      @PathVariable("deviceId") String deviceId,
                                      @Valid @RequestBody SoundRequest body) {
        jiangyiDeviceOpsService.setSound(operatorId(request), deviceId, body.switchOn(), body.volume());
        return ApiResponse.ok(null);
    }

    /** 实时拉取设备信息（§4.3.2.13）：回执异步落库，随后 GET signal 可读。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/device-info")
    public ApiResponse<Void> requestDeviceInfo(HttpServletRequest request,
                                               @PathVariable("deviceId") String deviceId) {
        jiangyiDeviceOpsService.requestDeviceInfo(operatorId(request), deviceId);
        return ApiResponse.ok(null);
    }

    /**
     * 关门/回锁（§4.3.2.6 可选指令 closeDoor）。
     * <p>有进行中的消费者会话/补货任务时 409；{@code force=true} 为运营二次确认后的强制下发。</p>
     */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/close-door")
    public ApiResponse<Void> closeDoor(HttpServletRequest request,
                                       @PathVariable("deviceId") String deviceId,
                                       @RequestParam(name = "force", defaultValue = "false") boolean force) {
        jiangyiDeviceOpsService.closeDoor(operatorId(request), deviceId, force);
        return ApiResponse.ok(null);
    }

    /** 信号强度视图（0-4 档；非将邑柜/未上报 signalStrength=null）。 */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/jiangyi/signal")
    public ApiResponse<JiangyiDeviceOpsService.SignalView> signal(
            @PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiDeviceOpsService.signal(deviceId));
    }

    /** 机器重启（§4.3.2.1 reboot）：无回执，设备重启期间 WS 断开；有进行中业务时 409，force 可越过。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/reboot")
    public ApiResponse<Void> reboot(HttpServletRequest request,
                                    @PathVariable("deviceId") String deviceId,
                                    @RequestParam(name = "force", defaultValue = "false") boolean force) {
        jiangyiDeviceOpsService.reboot(operatorId(request), deviceId, force);
        return ApiResponse.ok(null);
    }

    /** 下发门磁查询（§4.3.2.12 getDoorState）：回执经 §4.2.16 异步落库，随后 GET door-state 可读。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/door-state")
    public ApiResponse<Void> requestDoorState(HttpServletRequest request,
                                              @PathVariable("deviceId") String deviceId) {
        jiangyiDeviceOpsService.requestDoorState(operatorId(request), deviceId);
        return ApiResponse.ok(null);
    }

    /** 门磁快照视图（最近一次 §4.2.16 上报；未查询过全 null）。 */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/jiangyi/door-state")
    public ApiResponse<JiangyiDeviceOpsService.DoorStateView> doorState(
            @PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiDeviceOpsService.doorState(deviceId));
    }

    // ---------- 模型同步面（CB-023 二期） ----------

    /** 将邑模型列表 + classes 解析预览（classId↔textName 对照表；rejectReason 非空=该模型解析被拒）。 */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/jiangyi/models")
    public ApiResponse<List<JiangyiModelSyncService.ModelPreview>> models(
            @RequestParam(value = "classIdBase", defaultValue = "0") int classIdBase) {
        return ApiResponse.ok(jiangyiModelSyncService.previewModels(classIdBase));
    }

    /** 映射预生成：模型 classes → 该设备 MODEL_SYNC/DISABLED 行（sku_id 保留既有）。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/model-sync")
    public ApiResponse<Integer> modelSync(@PathVariable("deviceId") String deviceId,
                                          @Valid @RequestBody ModelSyncRequest body) {
        return ApiResponse.ok(jiangyiModelSyncService.pregenerateForDevice(
                deviceId, body.modelName().trim(), body.classIdBase() == null ? 0 : body.classIdBase()));
    }

    /** 批量激活某设备某模型的预生成行（人工确认对照表后一次性生效）。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/class-mappings/activate")
    public ApiResponse<Integer> activatePregenerated(@PathVariable("deviceId") String deviceId,
                                                     @Valid @RequestBody ModelSyncRequest body) {
        return ApiResponse.ok(jiangyiModelSyncService.activatePregenerated(
                deviceId, body.modelName().trim()));
    }

    /** 下发模型到设备（BOUND+机型匹配+无未决 SENT；updateModel 经 gateway WS 下发）。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/model-push")
    public ApiResponse<Long> modelPush(@PathVariable("deviceId") String deviceId,
                                       @Valid @RequestBody ModelSyncRequest body) {
        return ApiResponse.ok(jiangyiModelSyncService.pushModel(deviceId, body.modelName().trim()));
    }

    /**
     * 显式 URL 生成对照表（台账 G-14）：§4.4.4 回空数组时按操作人给的 classes 地址生成，
     * 仍落 DISABLED 等人工激活。机型这一步校验不了（显式载荷没有厂商型号字段），由操作人负责。
     */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/model-sync-explicit")
    public ApiResponse<Integer> modelSyncExplicit(@PathVariable("deviceId") String deviceId,
                                                  @RequestBody ExplicitModelRequest body) {
        return ApiResponse.ok(jiangyiModelSyncService.pregenerateForDeviceByUrl(
                deviceId, body.toSpec(), body.classIdBase() == null ? 0 : body.classIdBase()));
    }

    /** 显式 URL 下发（台账 G-14）：不查 §4.4.4，直接组 V16 §4.3.2.9 updateModel 经网关 WS 发出。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/model-push-explicit")
    public ApiResponse<Long> modelPushExplicit(@PathVariable("deviceId") String deviceId,
                                               @RequestBody ExplicitModelRequest body) {
        return ApiResponse.ok(jiangyiModelSyncService.pushModelByUrl(deviceId, body.toSpec()));
    }

    /**
     * 厂商对照解码（V16 §5.5.3，<b>只读</b>）：classes.txt 每行 → 厂商标准商品品名/条码 +
     * 本平台条码命中的候选 SKU + 该柜当前挂接情况。给运营挂接对照时看人话用，
     * 不写任何映射行（§5.5.3 回的是厂商商品，不是我方 SKU——自动挂会错扣款）。
     */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/jiangyi/class-decode")
    public ApiResponse<JiangyiModelSyncService.ClassDecodeView> classDecode(
            @PathVariable("deviceId") String deviceId,
            @RequestParam("textUrl") String textUrl,
            @RequestParam(value = "quantity", defaultValue = "0") int quantity,
            @RequestParam(value = "classIdBase", defaultValue = "0") int classIdBase) {
        return ApiResponse.ok(jiangyiModelSyncService.decodeVendorSkuByClasses(
                deviceId, textUrl, quantity, classIdBase));
    }

    /** 模型下发审计列表（SENT/CONFIRMED/FAILED + classes_version）。 */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/jiangyi/model-deployments")
    public ApiResponse<List<DeploymentDto>> modelDeployments(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiModelSyncService.listDeployments(deviceId).stream()
                .map(AdminJiangyiController::toDto).toList());
    }

    // ---------- 映射复核面（V400：重训后编号平移） ----------

    /** 复核态：verified=false 时识别整单转人工、在售面下发空表（一眼看出「为什么柜子不卖了」）。 */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/jiangyi/mapping-status")
    public ApiResponse<JiangyiMappingReviewService.MappingStatus> mappingStatus(
            @PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiMappingReviewService.status(deviceId));
    }

    /**
     * 差异预览（只读）：按 classes.txt 的识别名对照现存映射，算保留/平移/新增/失效/冲突。
     * 回包里的 classesVersion 是「确认令牌」——apply 时原样带回，防审 A 版落 B 版。
     */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/jiangyi/mapping-rebuild")
    public ApiResponse<JiangyiMappingReviewService.RebuildPlan> mappingRebuildPlan(
            @PathVariable("deviceId") String deviceId,
            @RequestParam("modelName") String modelName,
            @RequestParam("textUrl") String textUrl,
            @RequestParam("quantity") int quantity,
            @RequestParam(value = "classIdBase", defaultValue = "0") int classIdBase) {
        return ApiResponse.ok(jiangyiMappingReviewService.plan(
                deviceId, modelName, textUrl, quantity, classIdBase));
    }

    /**
     * 应用差异 = 本轮人工复核通过（同一事务写映射 + 认领/确认 classes 版本）。
     * 柜机尚无 classes_version（厂商直推设备）时在此认领当前审阅版本。
     */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/mapping-rebuild")
    public ApiResponse<JiangyiMappingReviewService.ApplyResult> mappingRebuildApply(
            HttpServletRequest request,
            @PathVariable("deviceId") String deviceId,
            @Valid @RequestBody MappingRebuildRequest body) {
        return ApiResponse.ok(jiangyiMappingReviewService.apply(
                deviceId, body.modelName().trim(), body.textUrl().trim(), body.quantity(),
                body.classIdBase() == null ? 0 : body.classIdBase(),
                body.expectedClassesVersion(), operatorId(request)));
    }

    // ---------- 采集编排面（CB-023 二期，范围 C） ----------

    /** 进入采集模式：置锁（营业开门即 409）→ 将邑侧采集开门；开门失败自动回滚锁。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/gather/start")
    public ApiResponse<Void> startGather(@PathVariable("deviceId") String deviceId,
                                         @RequestBody(required = false) GatherStartRequest body) {
        jiangyiGatherService.startGather(deviceId, body == null ? null : blankToNull(body.doorPosition()));
        return ApiResponse.ok(null);
    }

    /** 退出采集模式（恢复营业）。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/gather/exit")
    public ApiResponse<Void> exitGather(@PathVariable("deviceId") String deviceId) {
        jiangyiGatherService.exitGatherMode(deviceId);
        return ApiResponse.ok(null);
    }

    /** 采集进度聚合：采集锁状态 + 开门状态 + 学习中 + 审核列表。 */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/devices/{deviceId}/jiangyi/gather/progress")
    public ApiResponse<JiangyiGatherService.GatherProgress> gatherProgress(
            @PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(jiangyiGatherService.progress(deviceId));
    }

    /** 触发学习（幂等：同 SKU 有 PENDING 凭据直接返回既有 ticket）。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/devices/{deviceId}/jiangyi/gather/training")
    public ApiResponse<Long> startTraining(@PathVariable("deviceId") String deviceId,
                                           @Valid @RequestBody TrainingRequest body) {
        return ApiResponse.ok(jiangyiGatherService.startTraining(
                deviceId, body.skuId().trim(), body.modelName().trim()).getId());
    }

    /** 审核列表（按挂接商品反查 productIds；skuId 缺省=全部）。 */
    @RequiresPermissions("ops:device:list")
    @GetMapping("/jiangyi/gather-check")
    public ApiResponse<List<com.aicabinet.trade.dto.JiangyiGatherDtos.GatherCheckItem>> gatherCheck(
            @RequestParam(value = "skuId", required = false) String skuId) {
        return ApiResponse.ok(jiangyiGatherService.checkList(skuId));
    }

    // ---------- 请求体 / 装配 ----------

    record RegisterRequest(@NotBlank(message = "设备 SN 不能为空") String deviceSn,
                           String modelName) {}

    record BindRequest(@NotBlank(message = "domain 不能为空") String domain,
                       String socketUrl) {}

    record TenantDomainRequest(@NotBlank(message = "domain 不能为空") String domain,
                               String socketUrl) {}

    record UpdateSnRequest(@NotBlank(message = "设备 SN 不能为空") String deviceSn) {}

    record IndustrialControlModelRequest(
            @NotBlank(message = "工控机机型不能为空") String industrialControlModel) {}

    /** V16 §5.4.10 表153：identifier/deviceSn 服务端补，deviceName/deviceAddress 必填，screen 可选。 */
    record ConfirmActivateRequest(String identifier,
                                  @NotBlank(message = "设备名称不能为空") String deviceName,
                                  @NotBlank(message = "投放地址不能为空") String deviceAddress,
                                  Boolean screen) {}

    record MappingUpsertRequest(@NotBlank(message = "SKU 不能为空") String skuId,
                                String textName,
                                String modelName,
                                Boolean active) {}

    record MappingStatusRequest(Boolean active) {}

    record ModelSyncRequest(@NotBlank(message = "模型名不能为空") String modelName,
                            Integer classIdBase) {}

    /** 显式下发（G-14）：V16 §4.3.2.9 updateModel 的四字段原样；空值/数量校验统一在 service 侧。 */
    record ExplicitModelRequest(String modelName, String modelUrl, String textUrl,
                                Integer quantity, Integer classIdBase) {
        JiangyiModelSyncService.ExplicitModel toSpec() {
            return new JiangyiModelSyncService.ExplicitModel(modelName, modelUrl, textUrl, quantity);
        }
    }

    /** 复核应用入参：expectedClassesVersion = 预览回包里的 classesVersion（内容令牌，必填）。 */
    record MappingRebuildRequest(@NotBlank(message = "模型名不能为空") String modelName,
                                 @NotBlank(message = "classes 地址不能为空") String textUrl,
                                 @jakarta.validation.constraints.NotNull(
                                         message = "模型商品数量不能为空") Integer quantity,
                                 Integer classIdBase,
                                 @NotBlank(message = "缺少审阅版本令牌，请重新拉取差异")
                                 String expectedClassesVersion) {}

    record GatherStartRequest(String doorPosition) {}

    record TrainingRequest(@NotBlank(message = "SKU 不能为空") String skuId,
                           @NotBlank(message = "模型名不能为空") String modelName) {}

    private static DeploymentDto toDto(JiangyiModelDeployment d) {
        return new DeploymentDto(d.getId(), d.getDeviceId(), d.getModelName(), d.getModelUrl(),
                d.getIndustrialControlModel(), d.getClassesVersion(), d.getStatus(),
                d.getSentAt(), d.getConfirmedAt(), d.getFailReason());
    }

    /** 下发审计行（openapi inline schema）。 */
    public record DeploymentDto(Long id, String deviceId, String modelName, String modelUrl,
                                String industrialControlModel, String classesVersion, String status,
                                java.time.Instant sentAt, java.time.Instant confirmedAt,
                                String failReason) {}

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(com.aicabinet.trade.auth.AuthInterceptor.ATTR_USER_ID);
    }

    /** 音量设置请求体：switchOn/volume 至少一项（service 校验）。 */
    record SoundRequest(Boolean switchOn, Integer volume) {}

    record PullVideoRequest(@NotBlank(message = "订单号（会话 id）不能为空") String orderNo) {}

    private static JiangyiDeviceDto toDto(JiangyiDevice d) {
        return new JiangyiDeviceDto(d.getDeviceId(), d.getDeviceSn(), d.getIdentifier(),
                d.getModelName(), d.getIndustrialControlModel(), d.getClassesVersion(), d.getStatus(),
                d.getTokenVersion(), d.getTokenIssuedAt(), d.getLastWsOnlineAt(), d.getCreatedAt(),
                d.getUpdatedAt(), Boolean.TRUE.equals(d.getAuthEnabled()));
    }

    private static JiangyiClassMappingDto toDto(JiangyiClassMapping m) {
        return new JiangyiClassMappingDto(m.getId(), m.getDeviceId(), m.getClassId(),
                m.getModelName(), m.getTextName(), m.getSkuId(), m.getStatus(), m.getSource(),
                m.getCreatedAt(), m.getUpdatedAt());
    }
}
