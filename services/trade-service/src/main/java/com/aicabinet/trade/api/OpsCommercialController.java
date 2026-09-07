package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.CommercialFlowRunRequest;
import com.aicabinet.common.dto.CommercialFlowRunResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.CommercialFlowService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营商业聚合余量入口。主域已拆至 {@link OpsOrgController}、{@link OpsRbacController}、
 * {@link OpsWarehouseController}、{@link OpsReplenishmentController}、{@link OpsAdController}、
 * {@link OpsProcurementController}、{@link OpsRiskController}、{@link OpsOtaController}、
 * {@link OpsFinanceController}、{@link OpsDeviceEnvController}、{@link OpsAnalyticsController}。
 * <p>
 * 本类仅保留演示用 commercial-flow 一键跑通。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsCommercialController {

    private final CommercialFlowService commercialFlowService;

    public OpsCommercialController(CommercialFlowService commercialFlowService) {
        this.commercialFlowService = commercialFlowService;
    }

    @RequiresPermissions("ops:admin")
    @PostMapping("/commercial-flow/run")
    public ApiResponse<CommercialFlowRunResult> runCommercialFlow(
            HttpServletRequest request,
            @RequestBody(required = false) CommercialFlowRunRequest body) {
        return ApiResponse.ok(commercialFlowService.runFullFlow(operatorId(request), body));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
