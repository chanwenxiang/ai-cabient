package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentTaskDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantReplenishmentCompleteGatesTest {

    @Mock private PermissionService permissionService;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private MerchantPortalGuard merchantPortalGuard;
    @Mock private ReplenishmentService replenishmentService;
    @Mock private AdminAuditService auditService;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private FileAttachmentService fileAttachmentService;
    @Mock private ShoppingSessionMapper shoppingSessionRepository;

    private MerchantReplenishmentService service;

    @BeforeEach
    void setUp() {
        service = new MerchantReplenishmentService(
                permissionService, merchantFeaturePackService, merchantPortalGuard, replenishmentService,
                null, null, auditService, deviceRepository, null, null, null,
                null, null, null, taskRepository, fileAttachmentService,
                null, null, shoppingSessionRepository, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    private ReplenishmentTask scopedTask() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(9L);
        task.setDeviceId("CAB-001");
        task.setStatus("IN_PROGRESS");
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));
        return task;
    }

    @Test
    void completeTask_requiresDoorSession() {
        scopedTask();
        when(shoppingSessionRepository.existsByReplenishmentTaskId(9L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.completeTask(100L, 9L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(ApiMessages.REPLENISHMENT_COMPLETE_DOOR_REQUIRED, ex.getReason());
        verify(replenishmentService, never()).completeTask(anyLong(), anyLong());
    }

    @Test
    void completeTask_requiresEvidence() {
        scopedTask();
        when(shoppingSessionRepository.existsByReplenishmentTaskId(9L)).thenReturn(true);
        when(fileAttachmentService.countReplenishmentEvidence(9L)).thenReturn(0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.completeTask(100L, 9L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(ApiMessages.REPLENISHMENT_COMPLETE_EVIDENCE_REQUIRED, ex.getReason());
        verify(replenishmentService, never()).completeTask(anyLong(), anyLong());
    }

    @Test
    void completeTask_passesGates() {
        scopedTask();
        when(shoppingSessionRepository.existsByReplenishmentTaskId(9L)).thenReturn(true);
        when(fileAttachmentService.countReplenishmentEvidence(9L)).thenReturn(1);
        ReplenishmentTaskDto dto = org.mockito.Mockito.mock(ReplenishmentTaskDto.class);
        when(replenishmentService.completeTask(100L, 9L)).thenReturn(dto);

        service.completeTask(100L, 9L);
        verify(replenishmentService).completeTask(100L, 9L);
    }

    @Test
    void assertDeviceInFieldScope_mapsForbidden() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-X");
        when(deviceRepository.findById("CAB-X")).thenReturn(Optional.of(device));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED))
                .when(merchantFeaturePackService)
                .requireDevicePack(eq(100L), eq("CAB-X"), eq(MerchantFeaturePacks.FIELD));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assertDeviceInFieldScope(100L, "CAB-X"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals(ApiMessages.REPLENISHMENT_DEVICE_OUT_OF_SCOPE, ex.getReason());
    }
}
