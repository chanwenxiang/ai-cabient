package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FinanceReportDto;
import com.aicabinet.common.dto.FinanceStatsDto;
import com.aicabinet.common.dto.PaymentReconciliationDetailDto;
import com.aicabinet.common.dto.PaymentReconciliationDto;
import com.aicabinet.common.dto.SlaMetricsDto;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/** 对账 / 财务看板 / SLA 门面（含权限校验）；自原 OpsCommercialFacade 按域抽出。 */
@Service
public class OpsFinanceAdminService {

    private final PermissionService permissionService;
    private final ReconciliationService reconciliationService;
    private final FinanceReportService financeReportService;
    private final SlaMetricsService slaMetricsService;

    public OpsFinanceAdminService(PermissionService permissionService,
                                  ReconciliationService reconciliationService,
                                  FinanceReportService financeReportService,
                                  SlaMetricsService slaMetricsService) {
        this.permissionService = permissionService;
        this.reconciliationService = reconciliationService;
        this.financeReportService = financeReportService;
        this.slaMetricsService = slaMetricsService;
    }

    public List<PaymentReconciliationDto> listReconciliation(Long operatorId, LocalDate from,
                                                             LocalDate to, String channel) {
        permissionService.requirePermission(operatorId, "ops:reconciliation:list");
        return reconciliationService.list(operatorId, from, to, channel);
    }

    public PaymentReconciliationDto runReconciliation(Long operatorId, LocalDate date, String channel) {
        permissionService.requirePermission(operatorId, "ops:reconciliation:run");
        return reconciliationService.runDaily(operatorId, date, channel);
    }

    public PaymentReconciliationDetailDto getReconciliationDetail(Long operatorId, Long reconId) {
        permissionService.requirePermission(operatorId, "ops:reconciliation:list");
        return reconciliationService.getDetail(operatorId, reconId);
    }

    public FinanceStatsDto financeStats(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:finance:view");
        return financeReportService.stats(operatorId);
    }

    public FinanceReportDto financeReport(Long operatorId, int days) {
        permissionService.requirePermission(operatorId, "ops:finance:view");
        return financeReportService.report(operatorId, days);
    }

    public SlaMetricsDto sla(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:sla");
        return slaMetricsService.current(operatorId);
    }
}
