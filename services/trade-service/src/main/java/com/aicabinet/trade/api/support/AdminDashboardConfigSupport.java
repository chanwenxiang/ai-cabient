package com.aicabinet.trade.api.support;

import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.service.DeviceQrService;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.SystemConfigService;
import org.springframework.stereotype.Component;

/** Config and asset helpers for {@link AdminDashboardControllerSupport}. */
@Component
class AdminDashboardConfigSupport {

    private final FileAttachmentService fileAttachmentService;
    private final DeviceQrService deviceQrService;
    private final SecurityProperties securityProperties;
    private final SystemConfigService systemConfigService;

    AdminDashboardConfigSupport(FileAttachmentService fileAttachmentService,
                                DeviceQrService deviceQrService,
                                SecurityProperties securityProperties,
                                SystemConfigService systemConfigService) {
        this.fileAttachmentService = fileAttachmentService;
        this.deviceQrService = deviceQrService;
        this.securityProperties = securityProperties;
        this.systemConfigService = systemConfigService;
    }

    FileAttachmentService fileAttachmentService() {
        return fileAttachmentService;
    }

    DeviceQrService deviceQrService() {
        return deviceQrService;
    }

    SecurityProperties securityProperties() {
        return securityProperties;
    }

    SystemConfigService systemConfigService() {
        return systemConfigService;
    }
}
