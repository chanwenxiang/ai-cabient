package com.aicabinet.trade.api.support;

import com.aicabinet.trade.service.AdminDeviceOpsService;
import com.aicabinet.trade.service.DeviceAssetService;
import com.aicabinet.trade.service.DisputeService;
import com.aicabinet.trade.service.UnpaidOrderService;
import com.aicabinet.trade.support.CacheService;
import org.springframework.stereotype.Component;

/** Core ops dependencies for {@link AdminDashboardControllerSupport}. */
@Component
class AdminDashboardOpsSupport {

    private final CacheService cacheService;
    private final DisputeService disputeService;
    private final AdminDeviceOpsService deviceOpsService;
    private final UnpaidOrderService unpaidOrderService;
    private final DeviceAssetService deviceAssetService;

    AdminDashboardOpsSupport(CacheService cacheService,
                             DisputeService disputeService,
                             AdminDeviceOpsService deviceOpsService,
                             UnpaidOrderService unpaidOrderService,
                             DeviceAssetService deviceAssetService) {
        this.cacheService = cacheService;
        this.disputeService = disputeService;
        this.deviceOpsService = deviceOpsService;
        this.unpaidOrderService = unpaidOrderService;
        this.deviceAssetService = deviceAssetService;
    }

    CacheService cacheService() {
        return cacheService;
    }

    DisputeService disputeService() {
        return disputeService;
    }

    AdminDeviceOpsService deviceOpsService() {
        return deviceOpsService;
    }

    UnpaidOrderService unpaidOrderService() {
        return unpaidOrderService;
    }

    DeviceAssetService deviceAssetService() {
        return deviceAssetService;
    }
}
