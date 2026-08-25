package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceFaultReportRequest;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceFaultReportConcurrencyTest {

    @Mock private DeviceFaultReportMapper repository;
    @Mock private DeviceValidationService deviceValidationService;
    @Mock private OpsExceptionService opsExceptionService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SysDictService sysDictService;

    private DeviceFaultReportService service;

    @BeforeEach
    void setUp() {
        service = new DeviceFaultReportService(repository, deviceValidationService,
                opsExceptionService, distributedLockService, sysDictService);
    }

    @Test
    void report_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(DeviceFaultReportService.faultReportLockKey(700L, "CAB-007")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.report(700L, "CAB-007",
                        new DeviceFaultReportRequest("DOOR_OPEN", "门打不开")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
