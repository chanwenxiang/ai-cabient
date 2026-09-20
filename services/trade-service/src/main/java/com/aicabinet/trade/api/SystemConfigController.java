package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.FeatureFlagCatalogDto;
import com.aicabinet.common.dto.FileAttachmentDto;
import com.aicabinet.common.dto.SystemConfigDto;
import com.aicabinet.common.dto.SystemConfigHistoryDto;
import com.aicabinet.common.dto.UpsertSystemConfigRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.FeatureFlagCatalogService;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.OpsAlertDispatcher;
import com.aicabinet.trade.service.SystemConfigAuditService;
import com.aicabinet.trade.service.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin/system-configs")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;
    private final SystemConfigAuditService systemConfigAuditService;
    private final FileAttachmentService fileAttachmentService;
    private final OpsAlertDispatcher opsAlertDispatcher;
    private final FeatureFlagCatalogService featureFlagCatalogService;

    public SystemConfigController(SystemConfigService systemConfigService,
                                  SystemConfigAuditService systemConfigAuditService,
                                  FileAttachmentService fileAttachmentService,
                                  OpsAlertDispatcher opsAlertDispatcher,
                                  FeatureFlagCatalogService featureFlagCatalogService) {
        this.systemConfigService = systemConfigService;
        this.systemConfigAuditService = systemConfigAuditService;
        this.fileAttachmentService = fileAttachmentService;
        this.opsAlertDispatcher = opsAlertDispatcher;
        this.featureFlagCatalogService = featureFlagCatalogService;
    }

    @RequiresPermissions("ops:config:list")
    @GetMapping
    public ApiResponse<List<SystemConfigDto>> list(HttpServletRequest request) {
        return ApiResponse.ok(systemConfigService.listAll());
    }

    /**
     * 功能开关注册表（权威清单）：运营台「功能开关」面板据此分组渲染控件。
     * 只读；写入仍走 {@code PUT /api/v2/ops/admin/system-configs}，所以运行期改完即时生效。
     */
    @RequiresPermissions("ops:config:list")
    @GetMapping("/feature-flags")
    public ApiResponse<FeatureFlagCatalogDto> featureFlags() {
        return ApiResponse.ok(featureFlagCatalogService.catalog());
    }

    @RequiresPermissions(value = {"ops:config:edit", "ops:config:import"}, logical = RequiresPermissions.Logical.OR)
    @PutMapping
    public ApiResponse<SystemConfigDto> upsert(
            HttpServletRequest request,
            @Valid @RequestBody UpsertSystemConfigRequest body) {
        return ApiResponse.ok(systemConfigService.upsert(body, operatorId(request)));
    }

    /** 上传品牌 Logo，返回可写入 ops.brand.logo_url 的地址。 */
    @RequiresPermissions("ops:config:edit")
    @PostMapping(value = "/brand-logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileAttachmentDto> uploadBrandLogo(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(fileAttachmentService.uploadOpsBrandLogo(operatorId, file));
    }

    /**
     * 试发一条测试告警到所有**已配置**的告警渠道，返回逐渠道的真实投递结果。
     *
     * <p>存在的理由：告警渠道（尤其飞书）的配置错误在 HTTP 层是看不出来的 ——
     * 关键词不匹配、签名密钥不对、IP 白名单未放行，平台都返回 200 + 业务码。
     * 有了这个端点，运营在页面上点一下就能确认「到底通不通」，**不需要监控栈**。</p>
     */
    @RequiresPermissions("ops:config:edit")
    @PostMapping("/alert-test")
    public ApiResponse<List<OpsAlertDispatcher.ChannelProbe>> testAlertChannels(
            HttpServletRequest request) {
        return ApiResponse.ok(opsAlertDispatcher.probeChannels(
                "ALERT_CHANNEL_TEST",
                "【测试】运营告警渠道连通性",
                "这是一条测试消息；收到即表示该渠道配置正确。"));
    }

    @RequiresPermissions("ops:config:delete")
    @DeleteMapping("/{configKey:.+}")
    public ApiResponse<Void> delete(
            HttpServletRequest request,
            @PathVariable("configKey") String configKey) {
        systemConfigService.delete(configKey, operatorId(request));
        return ApiResponse.ok(null);
    }

    /**
     * 某配置键的变更历史（F1 策略版本）。最新在前；读侧不受
     * {@code ops.config.audit.enabled} 影响 —— 关开关只是不再新增版本，不该让已有历史不可见。
     */
    @RequiresPermissions("ops:config:list")
    @GetMapping("/{configKey}/history")
    public ApiResponse<List<SystemConfigHistoryDto>> history(
            HttpServletRequest request,
            @PathVariable("configKey") String configKey) {
        return ApiResponse.ok(systemConfigAuditService.listHistory(operatorId(request), configKey));
    }

    /**
     * 回滚到指定历史版本的**变更前值**（撤销那一次变更）。本身也是一次 upsert ⇒ 会再留一条历史。
     *
     * <p>权限与写入同级（{@code ops:config:edit}）：回滚会改运行期的真实配置，不是只读操作。
     */
    @RequiresPermissions("ops:config:edit")
    @PostMapping("/{configKey}/rollback")
    public ApiResponse<SystemConfigDto> rollback(
            HttpServletRequest request,
            @PathVariable("configKey") String configKey,
            @Valid @RequestBody ConfigRollbackRequest body) {
        return ApiResponse.ok(
                systemConfigAuditService.rollback(operatorId(request), configKey, body.historyId()));
    }

    /** 回滚请求体：{@code historyId} 来自历史列表。 */
    public record ConfigRollbackRequest(long historyId) {}

    /** 取当前操作人；无鉴权上下文（理论上不会走到，端点都带 @RequiresPermissions）时按系统账号记。 */
    private static Long operatorId(HttpServletRequest request) {
        Object attr = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return attr instanceof Long id ? id : null;
    }
}
