package com.aicabinet.trade.api.support;

import com.aicabinet.trade.service.OpsCsvExportService;
import com.aicabinet.trade.service.ProcurementService;
import com.aicabinet.trade.service.PurchaseSuggestionService;
import com.aicabinet.trade.service.SupplierPayableService;
import com.aicabinet.trade.service.WarehouseBinService;
import com.aicabinet.trade.service.WarehouseStocktakeService;
import org.springframework.stereotype.Component;

/** Supply-chain dependencies for {@link OpsCommercialControllerSupport}. */
@Component
class OpsCommercialSupplySupport {

    private final ProcurementService procurementService;
    private final PurchaseSuggestionService purchaseSuggestionService;
    private final SupplierPayableService supplierPayableService;
    private final WarehouseStocktakeService warehouseStocktakeService;
    private final WarehouseBinService warehouseBinService;
    private final OpsCsvExportService csvExportService;

    OpsCommercialSupplySupport(ProcurementService procurementService,
                               PurchaseSuggestionService purchaseSuggestionService,
                               SupplierPayableService supplierPayableService,
                               WarehouseStocktakeService warehouseStocktakeService,
                               WarehouseBinService warehouseBinService,
                               OpsCsvExportService csvExportService) {
        this.procurementService = procurementService;
        this.purchaseSuggestionService = purchaseSuggestionService;
        this.supplierPayableService = supplierPayableService;
        this.warehouseStocktakeService = warehouseStocktakeService;
        this.warehouseBinService = warehouseBinService;
        this.csvExportService = csvExportService;
    }

    ProcurementService procurementService() {
        return procurementService;
    }

    PurchaseSuggestionService purchaseSuggestionService() {
        return purchaseSuggestionService;
    }

    SupplierPayableService supplierPayableService() {
        return supplierPayableService;
    }

    WarehouseStocktakeService warehouseStocktakeService() {
        return warehouseStocktakeService;
    }

    WarehouseBinService warehouseBinService() {
        return warehouseBinService;
    }

    OpsCsvExportService csvExportService() {
        return csvExportService;
    }
}
