package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.DataChangeLogMapper;
import com.aicabinet.trade.mapper.DataConsistencyRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataConsistencyConcurrencyTest {

    @Mock private DataChangeLogMapper changeLogRepository;
    @Mock private DataConsistencyRecordMapper consistencyRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private DistributedLockService distributedLockService;

    private DataConsistencyService service;

    @BeforeEach
    void setUp() {
        service = new DataConsistencyService();
        ReflectionTestUtils.setField(service, "changeLogRepository", changeLogRepository);
        ReflectionTestUtils.setField(service, "consistencyRepository", consistencyRepository);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "distributedLockService", distributedLockService);
    }

    @Test
    void fixInconsistency_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(DataConsistencyService.consistencyRecordLockKey(5L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.fixInconsistencyDetailed(5L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void recordInconsistency_whenCheckLockBusy_skipsPersist() {
        when(distributedLockService.tryLock(
                eq(DataConsistencyService.consistencyCheckLockKey("ORDER_AMOUNT", "O-BUSY")),
                eq(60L), eq(5L)))
                .thenReturn(false);

        service.recordInconsistency("ORDER_AMOUNT", "cabinet_order", "O-BUSY", "100", "90");

        verify(consistencyRepository, never()).save(any());
    }
}
