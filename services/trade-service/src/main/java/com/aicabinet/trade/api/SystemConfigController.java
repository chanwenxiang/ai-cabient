package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.FileAttachmentDto;
import com.aicabinet.common.dto.SystemConfigDto;
import com.aicabinet.common.dto.UpsertSystemConfigRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.OpsAlertDispatcher;
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
    private final FileAttachmentService fileAttachmentService;
    private final OpsAlertDispatcher opsAlertDispatcher;

    public SystemConfigController(SystemConfigService systemConfigService,
                                  FileAttachmentService fileAttachmentService,
                                  OpsAlertDispatcher opsAlertDispatcher) {
        this.systemConfigService = systemConfigService;
        this.fileAttachmentService = fileAttachmentService;
        this.opsAlertDispatcher = opsAlertDispatcher;
    }

    @RequiresPermissions("ops:config:list")
    @GetMapping
    public ApiResponse<List<SystemConfigDto>> list(HttpServletRequest request) {
        return ApiResponse.ok(systemConfigService.listAll());
    }

    @RequiresPermissions(value = {"ops:config:edit", "ops:config:import"}, logical = RequiresPermissions.Logical.OR)
    @PutMapping
    public ApiResponse<SystemConfigDto> upsert(
            HttpServletRequest request,
            @Valid @RequestBody UpsertSystemConfigRequest body) {
        return ApiResponse.ok(systemConfigService.upsert(body));
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
        systemConfigService.delete(configKey);
        return ApiResponse.ok(null);
    }
}
