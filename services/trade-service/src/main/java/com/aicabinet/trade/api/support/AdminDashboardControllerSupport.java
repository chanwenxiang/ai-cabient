package com.aicabinet.trade.api.support;

import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.service.AdminDeviceOpsService;
import com.aicabinet.trade.service.DeviceAssetService;
import com.aicabinet.trade.service.DeviceQrService;
import com.aicabinet.trade.service.DisputeService;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.SystemConfigService;
import com.aicabinet.trade.service.UnpaidOrderService;
import com.aicabinet.trade.support.CacheService;
import org.springframework.stereotype.Component;

/** Groups secondary dependencies for {@link com.aicabinet.trade.api.AdminDashboardController}. */
@Component
public class AdminDashboardControllerSupport {

    private final CacheService cacheService;
    private final DisputeService disputeService;
    private final AdminDeviceOpsService deviceOpsService;
    private final UnpaidOrderService unpaidOrderService;
    private final DeviceAssetService deviceAssetService;
    private final FileAttachmentService fileAttachmentService;
    private final DeviceQrService deviceQrService;
    private final SecurityProperties securityProperties;
    private final SystemConfigService systemConfigService;

    public AdminDashboardControllerSupport(CacheService cacheService,
                                           DisputeService disputeService,
                                           AdminDeviceOpsService deviceOpsService,
                                           UnpaidOrderService unpaidOrderService,
                                           DeviceAssetService deviceAssetService,
                                           FileAttachmentService fileAttachmentService,
                                           DeviceQrService deviceQrService,
                                           SecurityProperties securityProperties,
                                           SystemConfigService systemConfigService) {
        this.cacheService = cacheService;
        this.disputeService = disputeService;
        this.deviceOpsService = deviceOpsService;
        this.unpaidOrderService = unpaidOrderService;
        this.deviceAssetService = deviceAssetService;
        this.fileAttachmentService = fileAttachmentService;
        this.deviceQrService = deviceQrService;
        this.securityProperties = securityProperties;
        this.systemConfigService = systemConfigService;
    }

    public CacheService cacheService() {
        return cacheService;
    }

    public DisputeService disputeService() {
        return disputeService;
    }

    public AdminDeviceOpsService deviceOpsService() {
        return deviceOpsService;
    }

    public UnpaidOrderService unpaidOrderService() {
        return unpaidOrderService;
    }

    public DeviceAssetService deviceAssetService() {
        return deviceAssetService;
    }

    public FileAttachmentService fileAttachmentService() {
        return fileAttachmentService;
    }

    public DeviceQrService deviceQrService() {
        return deviceQrService;
    }

    public SecurityProperties securityProperties() {
        return securityProperties;
    }

    public SystemConfigService systemConfigService() {
        return systemConfigService;
    }
}
