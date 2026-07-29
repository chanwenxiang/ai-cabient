package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DataConsistencyRecord;
import com.aicabinet.trade.mapper.DataChangeLogMapper;
import com.aicabinet.trade.mapper.DataConsistencyRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 数据一致性巡检单测（BE-004）：去重 FAIL、支付口径、显式修复。
 */
@ExtendWith(MockitoExtension.class)
class DataConsistencyServiceTest {

    @Mock DataChangeLogMapper changeLogRepository;
    @Mock DataConsistencyRecordMapper consistencyRepository;
    @Mock JdbcTemplate jdbcTemplate;

    DataConsistencyService service;

    @BeforeEach
    void setUp() {
        service = new DataConsistencyService();
        ReflectionTestUtils.setField(service, "changeLogRepository", changeLogRepository);
        ReflectionTestUtils.setField(service, "consistencyRepository", consistencyRepository);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
    }

    @Test
    void recordInconsistency_dedupesOpenFailByTypeAndKey() {
        DataConsistencyRecord existing = new DataConsistencyRecord();
        existing.setId(7L);
        existing.setCheckType("ORDER_AMOUNT");
        existing.setCheckKey("O-1");
        existing.setStatus(DataConsistencyService.STATUS_FAIL);
        existing.setExpectedValue("100");
        existing.setActualValue("90");

        when(consistencyRepository.findByCheckTypeAndCheckKeyAndStatus(
                "ORDER_AMOUNT", "O-1", DataConsistencyService.STATUS_FAIL))
                .thenReturn(List.of(existing));

        service.recordInconsistency("ORDER_AMOUNT", "cabinet_order", "O-1", "100", "95");

        ArgumentCaptor<DataConsistencyRecord> captor = ArgumentCaptor.forClass(DataConsistencyRecord.class);
        verify(consistencyRepository).save(captor.capture());
        DataConsistencyRecord saved = captor.getValue();
        assertEquals(7L, saved.getId());
        assertEquals("95", saved.getActualValue());
        assertEquals(DataConsistencyService.STATUS_FAIL, saved.getStatus());
        assertNotNull(saved.getCheckedAt());
    }

    @Test
    void checkPaymentConsistency_usesNetPaidAgainstOrder() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(consistencyRepository.findByCheckTypeAndStatus(anyString(), anyString()))
                .thenReturn(List.of());

        service.checkPaymentConsistency();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("COMPLETED"));
        assertTrue(sql.contains("CHARGE"));
        assertTrue(sql.contains("ADJUST_CHARGE"));
        assertTrue(sql.contains("REFUND"));
        assertTrue(sql.contains("net_paid") || sql.contains("-po.amount_cents"));
        assertTrue(sql.contains("REFUNDED"));
    }

    @Test
    void resolveStaleFailures_marksMissingKeysFixed() {
        DataConsistencyRecord stale = new DataConsistencyRecord();
        stale.setId(1793L);
        stale.setCheckType("PAYMENT_AMOUNT");
        stale.setCheckKey("OLD-ORDER");
        stale.setStatus(DataConsistencyService.STATUS_FAIL);

        when(consistencyRepository.findByCheckTypeAndStatus(
                "PAYMENT_AMOUNT", DataConsistencyService.STATUS_FAIL))
                .thenReturn(List.of(stale));

        service.resolveStaleFailures("PAYMENT_AMOUNT", Set.of("OTHER"));

        ArgumentCaptor<DataConsistencyRecord> captor = ArgumentCaptor.forClass(DataConsistencyRecord.class);
        verify(consistencyRepository).save(captor.capture());
        assertEquals(DataConsistencyService.STATUS_FIXED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getFixedAt());
    }

    @Test
    void checkOrderConsistency_recordsMismatch() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("order_id", "O-9", "total_amount_cents", 100, "calculated_total", 80)
        ));
        when(consistencyRepository.findByCheckTypeAndCheckKeyAndStatus(
                anyString(), anyString(), anyString())).thenReturn(List.of());
        when(consistencyRepository.findByCheckTypeAndStatus(anyString(), anyString()))
                .thenReturn(List.of());

        service.checkOrderConsistency();

        ArgumentCaptor<DataConsistencyRecord> captor = ArgumentCaptor.forClass(DataConsistencyRecord.class);
        verify(consistencyRepository).save(captor.capture());
        assertEquals("ORDER_AMOUNT", captor.getValue().getCheckType());
        assertEquals("O-9", captor.getValue().getCheckKey());
        assertEquals("100", captor.getValue().getExpectedValue());
        assertEquals("80", captor.getValue().getActualValue());
    }

    @Test
    void fixInconsistency_whenPaidMatchesHeader_alignsSingleLine() {
        DataConsistencyRecord record = new DataConsistencyRecord();
        record.setId(3L);
        record.setCheckType("ORDER_AMOUNT");
        record.setCheckKey("O-FIX");
        record.setExpectedValue("150");
        record.setActualValue("350");
        record.setStatus(DataConsistencyService.STATUS_FAIL);

        when(consistencyRepository.findById(3L)).thenReturn(java.util.Optional.of(record));
        when(jdbcTemplate.query(startsWith("SELECT total_amount_cents"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("O-FIX")))
                .thenReturn(150);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(SUM(CASE"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("O-FIX")))
                .thenReturn(150);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(SUM(line_amount_cents)"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("O-FIX")))
                .thenReturn(350);
        when(jdbcTemplate.query(startsWith("SELECT COUNT(*)"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("O-FIX")))
                .thenReturn(1);
        when(jdbcTemplate.update(startsWith("UPDATE cabinet_order_line"), any(), any(), any(), eq("O-FIX")))
                .thenReturn(1);

        assertTrue(service.fixInconsistency(3L));
        assertEquals(DataConsistencyService.STATUS_FIXED, record.getStatus());
        assertNotNull(record.getFixedAt());
        verify(consistencyRepository).save(record);
    }

    @Test
    void fixInconsistency_withoutPayment_updatesHeaderToLineSum() {
        DataConsistencyRecord record = new DataConsistencyRecord();
        record.setId(5L);
        record.setCheckType("ORDER_AMOUNT");
        record.setCheckKey("O-NOPAY");
        record.setActualValue("120");
        record.setStatus(DataConsistencyService.STATUS_FAIL);

        when(consistencyRepository.findById(5L)).thenReturn(java.util.Optional.of(record));
        when(jdbcTemplate.query(startsWith("SELECT total_amount_cents"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("O-NOPAY")))
                .thenReturn(100);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(SUM(CASE"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("O-NOPAY")))
                .thenReturn(null);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(SUM(line_amount_cents)"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("O-NOPAY")))
                .thenReturn(120);
        when(jdbcTemplate.query(startsWith("SELECT COUNT(*)"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("O-NOPAY")))
                .thenReturn(1);
        when(jdbcTemplate.update(
                eq("UPDATE cabinet_order SET total_amount_cents = ? WHERE order_id = ?"),
                eq(120),
                eq("O-NOPAY"))).thenReturn(1);

        assertTrue(service.fixInconsistency(5L));
        assertEquals(DataConsistencyService.STATUS_FIXED, record.getStatus());
    }

    @Test
    void fixInconsistency_paymentAmountNotAutoFixed() {
        DataConsistencyRecord record = new DataConsistencyRecord();
        record.setId(4L);
        record.setCheckType("PAYMENT_AMOUNT");
        record.setCheckKey("O-PAY");
        record.setStatus(DataConsistencyService.STATUS_FAIL);

        when(consistencyRepository.findById(4L)).thenReturn(java.util.Optional.of(record));

        assertFalse(service.fixInconsistency(4L));
        assertEquals(DataConsistencyService.STATUS_FAIL, record.getStatus());
        verify(jdbcTemplate, never()).update(anyString(), any(), any());
    }
}
