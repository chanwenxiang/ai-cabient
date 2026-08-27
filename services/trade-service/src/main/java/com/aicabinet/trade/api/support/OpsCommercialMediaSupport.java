package com.aicabinet.trade.api.support;

import com.aicabinet.trade.service.AdCampaignService;
import com.aicabinet.trade.service.DeviceEnvService;
import com.aicabinet.trade.service.DeviceTempPlanService;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.MediaAssetService;
import com.aicabinet.trade.service.OpsTwoFactorService;
import org.springframework.stereotype.Component;

/** Media, device env and security dependencies for {@link OpsCommercialControllerSupport}. */
@Component
class OpsCommercialMediaSupport {

    private final FileAttachmentService fileAttachmentService;
    private final OpsTwoFactorService opsTwoFactorService;
    private final DeviceTempPlanService deviceTempPlanService;
    private final DeviceEnvService deviceEnvService;
    private final MediaAssetService mediaAssetService;
    private final AdCampaignService adCampaignService;

    OpsCommercialMediaSupport(FileAttachmentService fileAttachmentService,
                            OpsTwoFactorService opsTwoFactorService,
                            DeviceTempPlanService deviceTempPlanService,
                            DeviceEnvService deviceEnvService,
                            MediaAssetService mediaAssetService,
                            AdCampaignService adCampaignService) {
        this.fileAttachmentService = fileAttachmentService;
        this.opsTwoFactorService = opsTwoFactorService;
        this.deviceTempPlanService = deviceTempPlanService;
        this.deviceEnvService = deviceEnvService;
        this.mediaAssetService = mediaAssetService;
        this.adCampaignService = adCampaignService;
    }

    FileAttachmentService fileAttachmentService() {
        return fileAttachmentService;
    }

    OpsTwoFactorService opsTwoFactorService() {
        return opsTwoFactorService;
    }

    DeviceTempPlanService deviceTempPlanService() {
        return deviceTempPlanService;
    }

    DeviceEnvService deviceEnvService() {
        return deviceEnvService;
    }

    MediaAssetService mediaAssetService() {
        return mediaAssetService;
    }

    AdCampaignService adCampaignService() {
        return adCampaignService;
    }
}
