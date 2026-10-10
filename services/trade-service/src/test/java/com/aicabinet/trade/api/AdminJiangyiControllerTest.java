package com.aicabinet.trade.api;

import com.aicabinet.common.dto.JiangyiBindingViewDto;
import com.aicabinet.common.dto.JiangyiClassMappingDto;
import com.aicabinet.common.dto.JiangyiDeviceDto;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.service.JiangyiClassMappingService;
import com.aicabinet.trade.service.JiangyiGatherService;
import com.aicabinet.trade.service.JiangyiModelSyncService;
import com.aicabinet.trade.service.JiangyiOnboardingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 将邑接入运营后台接口（CB-022 收尾）：装配/参数规整/视图组装的单测。
 * 服务层业务规则（幂等、SN 冲突、将邑凭据缺失）在 JiangyiOnboardingService 自身语义里，
 * 由集成链路覆盖；此处钉住 admin 门面的行为契约。
 */
@ExtendWith(MockitoExtension.class)
class AdminJiangyiControllerTest {

    private static final String DEVICE_ID = "100000000001";

    @Mock private JiangyiOnboardingService onboardingService;
    @Mock private JiangyiClassMappingService mappingService;
    @Mock private JiangyiModelSyncService modelSyncService;
    @Mock private JiangyiGatherService gatherService;
    @Mock private com.aicabinet.trade.mapper.JiangyiOrderVideoMapper orderVideoMapper;

    private AdminJiangyiController controller() {
        return new AdminJiangyiController(onboardingService, mappingService,
                modelSyncService, gatherService, orderVideoMapper);
    }

    private JiangyiDevice boundDevice() {
        JiangyiDevice d = new JiangyiDevice();
        d.setDeviceId(DEVICE_ID);
        d.setDeviceSn("2b26552554fb7bf9");
        d.setIdentifier("CQYB11253");
        d.setModelName("JY-DOOR-01");
        d.setClassesVersion("v7");
        d.setStatus("BOUND");
        d.setTokenVersion(3L);
        d.setTokenIssuedAt(Instant.parse("2026-10-09T12:00:00Z"));
        d.setLastWsOnlineAt(Instant.parse("2026-10-09T12:30:00Z"));
        d.setCreatedAt(Instant.parse("2026-10-01T08:00:00Z"));
        d.setUpdatedAt(Instant.parse("2026-10-09T12:30:00Z"));
        return d;
    }

    @Test
    void view_unregisteredDeviceReturnsNullBindingAndEmptyMappings() {
        when(onboardingService.findByDeviceId(DEVICE_ID)).thenReturn(null);
        when(mappingService.listByDevice(DEVICE_ID)).thenReturn(List.of());

        JiangyiBindingViewDto view = controller().view(DEVICE_ID).data();

        assertNull(view.binding());
        assertTrue(view.mappings().isEmpty());
    }

    @Test
    void view_registeredDeviceMapsAllFields() {
        when(onboardingService.findByDeviceId(DEVICE_ID)).thenReturn(boundDevice());
        when(mappingService.listByDevice(DEVICE_ID)).thenReturn(List.of());

        JiangyiDeviceDto dto = controller().view(DEVICE_ID).data().binding();

        assertEquals(DEVICE_ID, dto.deviceId());
        assertEquals("2b26552554fb7bf9", dto.deviceSn());
        assertEquals("CQYB11253", dto.identifier());
        assertEquals("BOUND", dto.status());
        assertEquals(3L, dto.tokenVersion());
        assertNotNull(dto.lastWsOnlineAt());
    }

    @Test
    void registerTrimsSnAndBlankModelNameBecomesNull() {
        when(onboardingService.registerForDevice(eq(DEVICE_ID), eq("2b26552554fb7bf9"), any()))
                .thenReturn(boundDevice());

        var body = new AdminJiangyiController.RegisterRequest(" 2b26552554fb7bf9 ", "  ");
        controller().register(DEVICE_ID, body);

        ArgumentCaptor<String> model = ArgumentCaptor.forClass(String.class);
        verify(onboardingService).registerForDevice(eq(DEVICE_ID), eq("2b26552554fb7bf9"), model.capture());
        assertNull(model.getValue());
    }

    @Test
    void bindDelegatesToDeviceScopedService() {
        when(onboardingService.bindForDevice(eq(DEVICE_ID), eq("https://api.example.com"), any()))
                .thenReturn(boundDevice());

        var body = new AdminJiangyiController.BindRequest(" https://api.example.com ", "");
        controller().bind(DEVICE_ID, body);

        ArgumentCaptor<String> socketUrl = ArgumentCaptor.forClass(String.class);
        verify(onboardingService).bindForDevice(eq(DEVICE_ID), eq("https://api.example.com"), socketUrl.capture());
        assertNull(socketUrl.getValue());
    }

    @Test
    void retireDelegates() {
        when(onboardingService.retire(DEVICE_ID)).thenReturn(boundDevice());

        JiangyiDeviceDto dto = controller().retire(DEVICE_ID).data();

        verify(onboardingService).retire(DEVICE_ID);
        assertEquals("BOUND", dto.status());
    }

    @Test
    void upsertMappingActiveTrueMeansActiveManualSource() {
        JiangyiClassMapping saved = new JiangyiClassMapping();
        saved.setId(7L);
        saved.setDeviceId(DEVICE_ID);
        saved.setClassId(101);
        saved.setSkuId("SKU-WATER-001");
        saved.setTextName("农夫山泉 550ml");
        saved.setStatus("ACTIVE");
        saved.setSource("MANUAL");
        when(mappingService.upsert(any())).thenReturn(saved);

        var body = new AdminJiangyiController.MappingUpsertRequest(
                " SKU-WATER-001 ", "农夫山泉 550ml", null, Boolean.TRUE);
        JiangyiClassMappingDto dto = controller().upsertMapping(DEVICE_ID, 101, body).data();

        ArgumentCaptor<JiangyiClassMapping> captor = ArgumentCaptor.forClass(JiangyiClassMapping.class);
        verify(mappingService).upsert(captor.capture());
        JiangyiClassMapping passed = captor.getValue();
        assertEquals(DEVICE_ID, passed.getDeviceId());
        assertEquals(101, passed.getClassId());
        assertEquals("SKU-WATER-001", passed.getSkuId());
        assertEquals("ACTIVE", passed.getStatus());
        assertEquals("MANUAL", passed.getSource());
        assertEquals(7L, dto.id());
    }

    @Test
    void upsertMappingActiveNullDefaultsToDisabled() {
        JiangyiClassMapping saved = new JiangyiClassMapping();
        saved.setDeviceId(DEVICE_ID);
        saved.setClassId(101);
        saved.setSkuId("SKU-WATER-001");
        saved.setStatus("DISABLED");
        saved.setSource("MANUAL");
        when(mappingService.upsert(any())).thenReturn(saved);

        var body = new AdminJiangyiController.MappingUpsertRequest("SKU-WATER-001", null, null, null);
        controller().upsertMapping(DEVICE_ID, 101, body);

        ArgumentCaptor<JiangyiClassMapping> captor = ArgumentCaptor.forClass(JiangyiClassMapping.class);
        verify(mappingService).upsert(captor.capture());
        assertEquals("DISABLED", captor.getValue().getStatus());
    }

    @Test
    void setMappingStatusPassesThroughBoolean() {
        when(mappingService.setStatus(DEVICE_ID, 101, false)).thenReturn(1);

        controller().setMappingStatus(DEVICE_ID, 101,
                new AdminJiangyiController.MappingStatusRequest(Boolean.FALSE));

        verify(mappingService).setStatus(DEVICE_ID, 101, false);
    }

    // ---------- 采集编排面（CB-023 二期） ----------

    @Test
    void startGather_nullBodyMeansNullDoorPosition() {
        controller().startGather(DEVICE_ID, null);
        verify(gatherService).startGather(DEVICE_ID, null);

        controller().startGather(DEVICE_ID, new AdminJiangyiController.GatherStartRequest(" 1 "));
        verify(gatherService).startGather(DEVICE_ID, "1");
    }

    @Test
    void exitGatherDelegates() {
        controller().exitGather(DEVICE_ID);
        verify(gatherService).exitGatherMode(DEVICE_ID);
    }

    @Test
    void startTrainingReturnsTicketId() {
        com.aicabinet.trade.domain.JiangyiTrainingTicket ticket =
                new com.aicabinet.trade.domain.JiangyiTrainingTicket();
        ticket.setId(77L);
        when(gatherService.startTraining(DEVICE_ID, "SKU-WATER-001", "JY-DOOR-01-model"))
                .thenReturn(ticket);

        Long id = controller().startTraining(DEVICE_ID,
                new AdminJiangyiController.TrainingRequest(" SKU-WATER-001 ", " JY-DOOR-01-model ")).data();

        assertEquals(77L, id);
    }

    @Test
    void gatherCheckPassesSkuIdThrough() {
        when(gatherService.checkList("SKU-WATER-001")).thenReturn(List.of());
        controller().gatherCheck("SKU-WATER-001");
        verify(gatherService).checkList("SKU-WATER-001");
    }
}
