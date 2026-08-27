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

    private final CommercialFlowService commercialFlowService;
    private final ProcurementService procurementService;
    private final PurchaseSuggestionService purchaseSuggestionService;
    private final SupplierPayableService supplierPayableService;
    private final WarehouseStocktakeService warehouseStocktakeService;
    private final WarehouseBinService warehouseBinService;
    private final OpsCsvExportService csvExportService;
    private final FileAttachmentService fileAttachmentService;
    private final OpsTwoFactorService opsTwoFactorService;
    private final DeviceTempPlanService deviceTempPlanService;
    private final DeviceEnvService deviceEnvService;
    private final MediaAssetService mediaAssetService;
    private final AdCampaignService adCampaignService;
    private final FootfallAnalyticsService footfallAnalyticsService;
    private final OrgService orgService;
    private final SiteContractService siteContractService;

    public OpsCommercialControllerSupport(CommercialFlowService commercialFlowService,
                                          ProcurementService procurementService,
                                          PurchaseSuggestionService purchaseSuggestionService,
                                          SupplierPayableService supplierPayableService,
                                          WarehouseStocktakeService warehouseStocktakeService,
                                          WarehouseBinService warehouseBinService,
                                          OpsCsvExportService csvExportService,
                                          FileAttachmentService fileAttachmentService,
                                          OpsTwoFactorService opsTwoFactorService,
                                          DeviceTempPlanService deviceTempPlanService,
                                          DeviceEnvService deviceEnvService,
                                          MediaAssetService mediaAssetService,
                                          AdCampaignService adCampaignService,
                                          FootfallAnalyticsService footfallAnalyticsService,
                                          OrgService orgService,
                                          SiteContractService siteContractService) {
        this.commercialFlowService = commercialFlowService;
        this.procurementService = procurementService;
        this.purchaseSuggestionService = purchaseSuggestionService;
        this.supplierPayableService = supplierPayableService;
        this.warehouseStocktakeService = warehouseStocktakeService;
        this.warehouseBinService = warehouseBinService;
        this.csvExportService = csvExportService;
        this.fileAttachmentService = fileAttachmentService;
        this.opsTwoFactorService = opsTwoFactorService;
        this.deviceTempPlanService = deviceTempPlanService;
        this.deviceEnvService = deviceEnvService;
        this.mediaAssetService = mediaAssetService;
        this.adCampaignService = adCampaignService;
        this.footfallAnalyticsService = footfallAnalyticsService;
        this.orgService = orgService;
        this.siteContractService = siteContractService;
    }

    public CommercialFlowService commercialFlowService() {
        return commercialFlowService;
    }

    public ProcurementService procurementService() {
        return procurementService;
    }

    public PurchaseSuggestionService purchaseSuggestionService() {
        return purchaseSuggestionService;
    }

    public SupplierPayableService supplierPayableService() {
        return supplierPayableService;
    }

    public WarehouseStocktakeService warehouseStocktakeService() {
        return warehouseStocktakeService;
    }

    public WarehouseBinService warehouseBinService() {
        return warehouseBinService;
    }

    public OpsCsvExportService csvExportService() {
        return csvExportService;
    }

    public FileAttachmentService fileAttachmentService() {
        return fileAttachmentService;
    }

    public OpsTwoFactorService opsTwoFactorService() {
        return opsTwoFactorService;
    }

    public DeviceTempPlanService deviceTempPlanService() {
        return deviceTempPlanService;
    }

    public DeviceEnvService deviceEnvService() {
        return deviceEnvService;
    }

    public MediaAssetService mediaAssetService() {
        return mediaAssetService;
    }

    public AdCampaignService adCampaignService() {
        return adCampaignService;
    }

    public FootfallAnalyticsService footfallAnalyticsService() {
        return footfallAnalyticsService;
    }

    public OrgService orgService() {
        return orgService;
    }

    public SiteContractService siteContractService() {
        return siteContractService;
    }
}
