package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceFaultReportRequest;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.mapper.DeviceFaultReportMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceFaultReportConcurrencyTest {

    @Mock private DeviceFaultReportMapper repository;
    @Mock private DeviceValidationService deviceValidationService;
    @Mock private OpsExceptionService opsExceptionService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SysDictService sysDictService;
    @Mock private ApiRateLimitService apiRateLimitService;

    private DeviceFaultReportService service;

    @BeforeEach
    void setUp() {
        service = new DeviceFaultReportService(repository, deviceValidationService,
                opsExceptionService, distributedLockService, sysDictService, apiRateLimitService);
    }

    @Test
    void report_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                DeviceFaultReportService.faultReportLockKey(700L, "CAB-007"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.report(700L, "CAB-007",
                        new DeviceFaultReportRequest("DOOR_OPEN", "门打不开")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    /** MA-158：闸在抢锁之前——被限流的重复上报不该占锁、落工单、推运营告警。 */
    @Test
    void report_whenRateLimited_rejectsBeforeLockAndWritesNothing() {
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ApiMessages.TOO_MANY_FAULT_REPORTS))
                .when(apiRateLimitService).assertFaultReportAllowed(700L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.report(700L, "CAB-007",
                        new DeviceFaultReportRequest("DOOR_OPEN", "门打不开")));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        verify(distributedLockService, never()).tryLock(anyString(), anyLong(), anyLong());
        verify(repository, never()).save(any());
        verify(opsExceptionService, never())
                .report(anyString(), anyString(), any(), anyString(), anyString());
    }

    /** 正向对照：没超限仍照常落工单并推告警，且照常释放锁。 */
    @Test
    void report_whenWithinLimit_recordsTicketAndAlertsOps() {
        when(sysDictService.isActiveDictValue(SysDictService.DEVICE_FAULT_ISSUE, "DOOR_OPEN"))
                .thenReturn(true);
        when(distributedLockService.tryLock(
                DeviceFaultReportService.faultReportLockKey(700L, "CAB-007"), 60L, 5L))
                .thenReturn(true);

        service.report(700L, "CAB-007", new DeviceFaultReportRequest("DOOR_OPEN", "门打不开"));

        verify(apiRateLimitService).assertFaultReportAllowed(700L);
        verify(repository).save(any());
        verify(opsExceptionService)
                .report(eq("DEVICE_FAULT"), eq("HIGH"), any(), anyString(), anyString());
        verify(distributedLockService).unlock(DeviceFaultReportService.faultReportLockKey(700L, "CAB-007"));
    }
}
