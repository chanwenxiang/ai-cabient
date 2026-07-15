package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CommercialFlowRunRequest;
import com.aicabinet.common.dto.CommercialFlowRunResult;
import com.aicabinet.common.dto.CommercialFlowStepDto;
import com.aicabinet.common.dto.CreatePurchaseOrderRequest;
import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.common.dto.PaymentReconciliationDto;
import com.aicabinet.common.dto.PlanRouteRequest;
import com.aicabinet.common.dto.PurchaseOrderDto;
import com.aicabinet.common.dto.PurchaseOrderLineDto;
import com.aicabinet.common.dto.ReceivePurchaseOrderRequest;
import com.aicabinet.common.dto.ReplenishmentCheckInRequest;
import com.aicabinet.common.dto.ReplenishmentRouteDto;
import com.aicabinet.common.dto.ReplenishmentTaskDto;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.dto.WarehouseOutboundDto;
import com.aicabinet.common.dto.SupplierDto;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CommercialFlowService {

    private final PermissionService permissionService;
    private final DemoDataService demoDataService;
    private final WarehouseService warehouseService;
    private final ProcurementService procurementService;
    private final ReplenishmentService replenishmentService;
    private final SessionService sessionService;
    private final ReconciliationService reconciliationService;
    private final DeviceInfoMapper deviceRepository;

    public CommercialFlowService(PermissionService permissionService,
                                 DemoDataService demoDataService,
                                 WarehouseService warehouseService,
                                 ProcurementService procurementService,
                                 ReplenishmentService replenishmentService,
                                 SessionService sessionService,
                                 ReconciliationService reconciliationService,
                                 DeviceInfoMapper deviceRepository) {
        this.permissionService = permissionService;
        this.demoDataService = demoDataService;
        this.warehouseService = warehouseService;
        this.procurementService = procurementService;
        this.replenishmentService = replenishmentService;
        this.sessionService = sessionService;
        this.reconciliationService = reconciliationService;
        this.deviceRepository = deviceRepository;
    }

    public CommercialFlowRunResult runFullFlow(Long operatorId, CommercialFlowRunRequest request) {
        permissionService.requireAnyPermission(operatorId, "ops:dashboard:view", "ops:replenishment:edit");
        List<CommercialFlowStepDto> steps = new ArrayList<>();
        DemoDataService.DemoContext demo = demoDataService.ensureDemoData();

        String deviceId = valueOrDefault(request != null ? request.deviceId() : null, demo.deviceId());
        String skuId = valueOrDefault(request != null ? request.skuId() : null, demo.fallbackSkuId());
        int inboundQty = request != null && request.inboundQty() != null && request.inboundQty() > 0
                ? request.inboundQty() : 24;
        long consumerUserId = request != null && request.consumerUserId() != null
                ? request.consumerUserId() : demo.consumerUserId();
        String channel = valueOrDefault(request != null ? request.channel() : null, "MOCK");

        mark(steps, "DEMO_CONTEXT", "DONE", "Demo catalog, device, warehouse and user are ready");

        String batchNo = "FLOW-" + LocalDate.now() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        procurementService.upsertSupplier(operatorId, new SupplierDto(
                "SUP-DEMO-001", "Demo Beverage Supplier", "Demo Buyer", "13800138001", "ACTIVE", null));
        PurchaseOrderDto purchase = procurementService.createPurchaseOrder(operatorId, new CreatePurchaseOrderRequest(
                "SUP-DEMO-001",
                DemoDataService.DEMO_WAREHOUSE_ID,
                "FLOW-PO-" + batchNo,
                "commercial full-flow rehearsal",
                List.of(new PurchaseOrderLineDto(
                        null,
                        skuId,
                        batchNo,
                        LocalDate.now().minusDays(1),
                        LocalDate.now().plusDays(180),
                        inboundQty,
                        0,
                        100
                ))
        ));
        procurementService.receivePurchaseOrder(operatorId, purchase.purchaseOrderId(), new ReceivePurchaseOrderRequest(
                purchase.lines().stream()
                        .map(l -> new PurchaseOrderLineDto(
                                l.lineId(), l.skuId(), l.batchNo(), l.productionDate(), l.expiryDate(),
                                l.orderedQty(), l.orderedQty(), l.unitCostCents()))
                        .toList(),
                "flow receive"
        ));
        mark(steps, "PURCHASE_INBOUND", "DONE", "Purchase order received " + inboundQty + " units to warehouse");

        DeviceInfo device = deviceRepository.findById(deviceId).orElse(null);
        ReplenishmentRouteDto route = replenishmentService.planAndCreateRoute(operatorId, new PlanRouteRequest(
                "Full-flow route " + LocalDate.now(),
                operatorId,
                LocalDate.now(),
                List.of(deviceId),
                device != null ? device.getLatitude() : null,
                device != null ? device.getLongitude() : null
        ));
        mark(steps, "REPLENISHMENT_ROUTE", "DONE", "Route planned with " + route.tasks().size() + " task(s)");

        WarehouseOutboundDto outbound = ensureOutbound(operatorId, route.routeId());
        mark(steps, "WAREHOUSE_OUTBOUND", "DONE", "Outbound created: " + outbound.outboundId());

        outbound = warehouseService.markPicked(outbound.outboundId());
        outbound = warehouseService.shipOutbound(operatorId, outbound.outboundId());
        replenishmentService.generateLinesFromOutbound(outbound.outboundId());
        mark(steps, "WAREHOUSE_SHIP", "DONE", "Outbound shipped and in-transit recorded");

        for (ReplenishmentTaskDto task : route.tasks()) {
            replenishmentService.checkInTask(operatorId, task.taskId(), new ReplenishmentCheckInRequest(null, null));
            replenishmentService.completeTask(operatorId, task.taskId());
        }
        mark(steps, "CABINET_REPLENISHED", "DONE", "Replenishment tasks completed into cabinet inventory");

        SessionDto session = sessionService.createSessionForDevTest(consumerUserId,
                new CreateSessionRequest(deviceId, "flow-" + UUID.randomUUID()));
        mark(steps, "SHOPPING_SESSION", "DONE", "Shopping session created in dev mode: " + session.sessionId());

        SessionDto completed = sessionService.completeDevUploadRecognition(
                session.sessionId(),
                new VisionServiceClient.RecognitionResult(
                        "FLOW-" + session.sessionId(),
                        List.of(new VisionServiceClient.RecognizedItem(skuId, 1, 0.99f)),
                        0.99f,
                        false,
                        "flow-mock",
                        List.of("flow")
                ));
        mark(steps, "AI_SETTLEMENT", "DONE", "Recognition settled order: " + completed.orderId());

        PaymentReconciliationDto recon = reconciliationService.runDaily(operatorId, LocalDate.now(), channel);
        mark(steps, "RECONCILIATION", "DONE", "Reconciliation status: " + recon.status());

        mark(steps, "BUSINESS_CLOSED_LOOP", "DONE",
                "Inbound, warehouse, replenishment, shopping, settlement, inventory deduction and reconciliation completed");

        return new CommercialFlowRunResult(
                deviceId,
                skuId,
                completed.sessionId(),
                completed.orderId(),
                route.routeId(),
                outbound.outboundId(),
                recon.reconId(),
                steps
        );
    }

    private WarehouseOutboundDto ensureOutbound(Long operatorId, Long routeId) {
        return warehouseService.listOutbounds().stream()
                .filter(o -> routeId.equals(o.routeId()))
                .findFirst()
                .orElseGet(() -> warehouseService.createOutboundForRoute(
                        routeId, DemoDataService.DEMO_WAREHOUSE_ID, operatorId));
    }

    private static void mark(List<CommercialFlowStepDto> steps, String code, String status, String message) {
        steps.add(new CommercialFlowStepDto(code, status, message, Instant.now()));
    }

    private static String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}
