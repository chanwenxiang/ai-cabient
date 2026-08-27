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

    private final AdminDashboardOpsSupport ops;
    private final AdminDashboardConfigSupport config;

    public AdminDashboardControllerSupport(AdminDashboardOpsSupport ops,
                                           AdminDashboardConfigSupport config) {
        this.ops = ops;
        this.config = config;
    }

    public CacheService cacheService() {
        return ops.cacheService();
    }

    public DisputeService disputeService() {
        return ops.disputeService();
    }

    public AdminDeviceOpsService deviceOpsService() {
        return ops.deviceOpsService();
    }

    public UnpaidOrderService unpaidOrderService() {
        return ops.unpaidOrderService();
    }

    public DeviceAssetService deviceAssetService() {
        return ops.deviceAssetService();
    }

    public FileAttachmentService fileAttachmentService() {
        return config.fileAttachmentService();
    }

    public DeviceQrService deviceQrService() {
        return config.deviceQrService();
    }

    public SecurityProperties securityProperties() {
        return config.securityProperties();
    }

    public SystemConfigService systemConfigService() {
        return config.systemConfigService();
    }
}
