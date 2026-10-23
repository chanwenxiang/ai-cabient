package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceValidationServiceTest {

    @Mock
    private DeviceInfoMapper deviceInfoRepository;
    @Mock
    private ShoppingSessionMapper sessionRepository;
    @Mock
    private ReplenishmentTaskMapper replenishmentTaskRepository;
    @Mock
    private ConsumerPreauthService consumerPreauthService;
    @Mock
    private MerchantMapper merchantRepository;
    @Mock
    private JiangyiGatherService jiangyiGatherService;
    @Mock
    private JiangyiDeviceDirectory jiangyiDeviceDirectory;
    @Mock
    private com.aicabinet.trade.config.SecurityProperties securityProperties;

    private DeviceValidationService service;

    @BeforeEach
    void setUp() {
        service = new DeviceValidationService(
                deviceInfoRepository, sessionRepository, replenishmentTaskRepository,
                consumerPreauthService, merchantRepository, jiangyiGatherService,
                jiangyiDeviceDirectory, securityProperties);
    }

    @Test
    void ensureRestockDoorAllowed_requiresCheckIn() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(1L);
        task.setDeviceId("CAB-001");
        task.setStatus("PENDING");
        task.setAssigneeUserId(100000001L);
        when(replenishmentTaskRepository.findById(1L)).thenReturn(Optional.of(task));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.ensureRestockDoorAllowed("CAB-001", 1L, 100000001L));
        assertEquals(ApiMessages.REPLENISHMENT_CHECK_IN_REQUIRED, ex.getReason());
    }

    @Test
    void ensureRestockDoorAllowed_operatorCanBypassAssignee() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(1L);
        task.setDeviceId("CAB-001");
        task.setStatus("PENDING");
        task.setAssigneeUserId(1L);
        task.setCheckInAt(Instant.now());
        when(replenishmentTaskRepository.findById(1L)).thenReturn(Optional.of(task));
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setOnlineStatus("ONLINE");
        when(deviceInfoRepository.findById("CAB-001")).thenReturn(Optional.of(device));
        when(sessionRepository.findByDeviceIdAndStateIn(ArgumentMatchers.eq("CAB-001"), ArgumentMatchers.anyList()))
                .thenReturn(List.of());

        assertSame(task, service.ensureRestockDoorAllowed("CAB-001", 1L, 100000001L));
    }

    @Test
    void ensureRestockDoorAllowed_merchantMustMatchAssignee() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(1L);
        task.setDeviceId("CAB-001");
        task.setStatus("PENDING");
        task.setAssigneeUserId(99L);
        when(replenishmentTaskRepository.findById(1L)).thenReturn(Optional.of(task));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.ensureRestockDoorAllowed("CAB-001", 1L, 42L));
        assertEquals(ApiMessages.REPLENISHMENT_TASK_ASSIGNEE, ex.getReason());
    }

    @Test
    void ensureConsumerShoppingAllowed_blocksInactiveMerchant() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setOnlineStatus("ONLINE");
        device.setMerchantId("MCH-1");
        when(deviceInfoRepository.findById("CAB-001")).thenReturn(Optional.of(device));

        com.aicabinet.trade.domain.Merchant merchant = new com.aicabinet.trade.domain.Merchant();
        merchant.setMerchantId("MCH-1");
        merchant.setStatus("INACTIVE");
        when(merchantRepository.findById("MCH-1")).thenReturn(Optional.of(merchant));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.ensureConsumerShoppingAllowed("CAB-001"));
        assertEquals(ApiMessages.MERCHANT_NOT_ACTIVE, ex.getReason());
    }

    @Test
    void ensureConsumerShoppingAllowed_nonJiangyiBlockedWhenMockDisabled() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setOnlineStatus("ONLINE");
        when(deviceInfoRepository.findById("CAB-001")).thenReturn(Optional.of(device));
        when(securityProperties.mockEnabled()).thenReturn(false);
        when(jiangyiDeviceDirectory.isJiangyi("CAB-001")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.ensureConsumerShoppingAllowed("CAB-001"));
        assertEquals(409, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("将邑柜机"));
    }

    @Test
    void ensureConsumerShopping_allowedWhenMockEnabledEvenForNonJiangyi() {
        // mock 环境豁免的是「只认端侧识别柜」这道闸；结算绿仍须由 edge-results 挣（DC-27 批2）
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setOnlineStatus("ONLINE");
        when(deviceInfoRepository.findById("CAB-001")).thenReturn(Optional.of(device));
        when(securityProperties.mockEnabled()).thenReturn(true);
        when(jiangyiGatherService.isGatherLocked("CAB-001")).thenReturn(false);
        when(sessionRepository.findByDeviceIdAndStateIn(ArgumentMatchers.eq("CAB-001"), ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        when(replenishmentTaskRepository.findByDeviceIdAndStatusIn(ArgumentMatchers.eq("CAB-001"), ArgumentMatchers.anyList()))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> service.ensureConsumerShoppingAllowed("CAB-001"));
        verify(jiangyiDeviceDirectory, never()).isJiangyi(ArgumentMatchers.any());
    }

    @Test
    void ensureConsumerShopping_allowedWhenJiangyiBoundAndMockDisabled() {        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setOnlineStatus("ONLINE");
        when(deviceInfoRepository.findById("CAB-001")).thenReturn(Optional.of(device));
        when(securityProperties.mockEnabled()).thenReturn(false);
        when(jiangyiDeviceDirectory.isJiangyi("CAB-001")).thenReturn(true);
        when(jiangyiGatherService.isGatherLocked("CAB-001")).thenReturn(false);
        when(sessionRepository.findByDeviceIdAndStateIn(ArgumentMatchers.eq("CAB-001"), ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        when(replenishmentTaskRepository.findByDeviceIdAndStatusIn(ArgumentMatchers.eq("CAB-001"), ArgumentMatchers.anyList()))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> service.ensureConsumerShoppingAllowed("CAB-001"));
    }

    // ---------- 维护指令（reboot §4.3.2.1 / closeDoor §4.3.2.6）前置 ----------

    @Test
    void maintenanceCommand_blocksActiveConsumerSession() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-1");
        when(sessionRepository.findByDeviceIdAndStateIn(ArgumentMatchers.eq("CAB-001"), ArgumentMatchers.anyList()))
                .thenReturn(List.of(session));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.ensureMaintenanceCommandAllowed("CAB-001", false));

        assertEquals(409, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("消费者购物会话"));
    }

    @Test
    void maintenanceCommand_ignoresNonConsumerSessions() {
        ShoppingSession restock = new ShoppingSession();
        restock.setIdempotencyKey("RESTOCK:T-1");
        ShoppingSession ops = new ShoppingSession();
        ops.setIdempotencyKey("OPS_REMOTE:1");
        when(sessionRepository.findByDeviceIdAndStateIn(ArgumentMatchers.eq("CAB-001"), ArgumentMatchers.anyList()))
                .thenReturn(List.of(restock, ops));
        when(replenishmentTaskRepository.findByDeviceIdAndStatusIn(ArgumentMatchers.eq("CAB-001"), ArgumentMatchers.anyList()))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> service.ensureMaintenanceCommandAllowed("CAB-001", false));
    }

    @Test
    void maintenanceCommand_blocksCheckedInReplenishmentTask() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setStatus("IN_PROGRESS");
        task.setCheckInAt(Instant.now());
        when(sessionRepository.findByDeviceIdAndStateIn(ArgumentMatchers.eq("CAB-001"), ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        when(replenishmentTaskRepository.findByDeviceIdAndStatusIn(ArgumentMatchers.eq("CAB-001"), ArgumentMatchers.anyList()))
                .thenReturn(List.of(task));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.ensureMaintenanceCommandAllowed("CAB-001", false));

        assertEquals(409, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("补货任务"));
    }

    @Test
    void maintenanceCommand_forceSkipsBothChecks() {
        assertDoesNotThrow(() -> service.ensureMaintenanceCommandAllowed("CAB-001", true));

        verify(sessionRepository, never()).findByDeviceIdAndStateIn(ArgumentMatchers.any(), ArgumentMatchers.anyList());
        verify(replenishmentTaskRepository, never())
                .findByDeviceIdAndStatusIn(ArgumentMatchers.any(), ArgumentMatchers.anyList());
    }
}
