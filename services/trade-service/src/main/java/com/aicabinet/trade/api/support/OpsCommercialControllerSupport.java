package com.aicabinet.trade.api.support;

import com.aicabinet.trade.service.AdCampaignService;
import com.aicabinet.trade.service.CommercialFlowService;
import com.aicabinet.trade.service.DeviceEnvService;
import com.aicabinet.trade.service.DeviceTempPlanService;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.FootfallAnalyticsService;
import com.aicabinet.trade.service.MediaAssetService;
import com.aicabinet.trade.service.OpsCsvExportService;
import com.aicabinet.trade.service.OpsTwoFactorService;
import com.aicabinet.trade.service.OrgService;
import com.aicabinet.trade.service.ProcurementService;
import com.aicabinet.trade.service.PurchaseSuggestionService;
import com.aicabinet.trade.service.SiteContractService;
import com.aicabinet.trade.service.SupplierPayableService;
import com.aicabinet.trade.service.WarehouseBinService;
import com.aicabinet.trade.service.WarehouseStocktakeService;
import org.springframework.stereotype.Component;

/** Groups non-facade dependencies for {@link com.aicabinet.trade.api.OpsCommercialController}. */
@Component
public class OpsCommercialControllerSupport {

    private final OpsCommercialSupplySupport supply;
    private final OpsCommercialMediaSupport media;
    private final OpsCommercialOrgSupport org;

    public OpsCommercialControllerSupport(OpsCommercialSupplySupport supply,
                                          OpsCommercialMediaSupport media,
                                          OpsCommercialOrgSupport org) {
        this.supply = supply;
        this.media = media;
        this.org = org;
    }

    public CommercialFlowService commercialFlowService() {
        return org.commercialFlowService();
    }

    public ProcurementService procurementService() {
        return supply.procurementService();
    }

    public PurchaseSuggestionService purchaseSuggestionService() {
        return supply.purchaseSuggestionService();
    }

    public SupplierPayableService supplierPayableService() {
        return supply.supplierPayableService();
    }

    public WarehouseStocktakeService warehouseStocktakeService() {
        return supply.warehouseStocktakeService();
    }

    public WarehouseBinService warehouseBinService() {
        return supply.warehouseBinService();
    }

    public OpsCsvExportService csvExportService() {
        return supply.csvExportService();
    }

    public FileAttachmentService fileAttachmentService() {
        return media.fileAttachmentService();
    }

    public OpsTwoFactorService opsTwoFactorService() {
        return media.opsTwoFactorService();
    }

    public DeviceTempPlanService deviceTempPlanService() {
        return media.deviceTempPlanService();
    }

    public DeviceEnvService deviceEnvService() {
        return media.deviceEnvService();
    }

    public MediaAssetService mediaAssetService() {
        return media.mediaAssetService();
    }

    public AdCampaignService adCampaignService() {
        return media.adCampaignService();
    }

    public FootfallAnalyticsService footfallAnalyticsService() {
        return org.footfallAnalyticsService();
    }

    public OrgService orgService() {
        return org.orgService();
    }

    public SiteContractService siteContractService() {
        return org.siteContractService();
    }
}
