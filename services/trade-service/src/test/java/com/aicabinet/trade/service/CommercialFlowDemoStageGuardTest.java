package com.aicabinet.trade.service;

import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AU-124：commercial-flow 一键跑通会造演示供应商/路线并生成**真实订单结算+对账**，
 * prod/staging（mock-enabled 恒 false，compose/validator 双钉）必须运行期拒绝——
 * 持 ops:admin 也不该在生产库留下演示脏数据。dev（mock-enabled=true）演示编排不受影响。
 */
@ExtendWith(MockitoExtension.class)
class CommercialFlowDemoStageGuardTest {

    @Mock private PermissionService permissionService;
    @Mock private DemoDataService demoDataService;
    @Mock private WarehouseService warehouseService;
    @Mock private ProcurementService procurementService;
    @Mock private ReplenishmentService replenishmentService;
    @Mock private SessionService sessionService;
    @Mock private ReconciliationService reconciliationService;
    @Mock private DeviceInfoMapper deviceRepository;

    private CommercialFlowService build(boolean mockEnabled) {
        return new CommercialFlowService(
                permissionService,
                demoDataService,
                warehouseService,
                procurementService,
                replenishmentService,
                sessionService,
                reconciliationService,
                deviceRepository,
                new SecurityProperties(mockEnabled));
    }

    @Test
    void productionLikeStage_rejectsBeforeAnySideEffect() {
        CommercialFlowService service = build(false);
        assertThatThrownBy(() -> service.runFullFlow(1L, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("commercial-flow");
        // 拒绝必须发生在一切造数之前：权限校验之后的任何服务都不许被触碰
        verifyNoInteractions(
                demoDataService,
                warehouseService,
                procurementService,
                replenishmentService,
                sessionService,
                reconciliationService,
                deviceRepository);
    }

    @Test
    void devMockStage_passesGuard() {
        CommercialFlowService service = build(true);
        when(demoDataService.ensureDemoData()).thenThrow(new IllegalStateException("demo-entered"));
        assertThatThrownBy(() -> service.runFullFlow(1L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo-entered");
        verify(permissionService).requirePermission(1L, "ops:admin");
        verify(demoDataService).ensureDemoData();
        verify(sessionService, never()).createSessionForDevTest(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }
}
