package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DataConsistencyRecord;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DataChangeLogMapper;
import com.aicabinet.trade.mapper.DataConsistencyRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
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
@MockitoSettings(strictness = Strictness.LENIENT)
class DataConsistencyServiceTest {

    @Mock DataChangeLogMapper changeLogRepository;
    @Mock DataConsistencyRecordMapper consistencyRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock DistributedLockService distributedLockService;
    @Mock ScheduledTaskService taskService;
    @Mock CouponService couponService;
    @Mock CabinetOrderMapper cabinetOrderRepository;
    @Mock OrderPaymentService orderPaymentService;

    DataConsistencyService service;

    private static ResultSetExtractor<Integer> anyIntExtractor() {
        return org.mockito.ArgumentMatchers.<ResultSetExtractor<Integer>>any();
    }

    @BeforeEach
    void setUp() {
        service = new DataConsistencyService(changeLogRepository, consistencyRepository, jdbcTemplate,
                new ObjectMapper(), taskService, distributedLockService, couponService,
                cabinetOrderRepository, orderPaymentService, null);
        ReflectionTestUtils.setField(service, "self", service);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
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
        assertTrue(sql.contains("LEFT JOIN payment_operation") || sql.contains("LEFT JOIN"));
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
    void checkOrderConsistency_consistentRowNotReturnedBySql() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(consistencyRepository.findByCheckTypeAndStatus(anyString(), anyString()))
                .thenReturn(List.of());

        service.checkOrderConsistency();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("coupon_discount_cents"));
        assertTrue(sql.contains("member_discount_cents"));
        assertTrue(sql.contains("payable_from_lines"));
        verify(consistencyRepository, never()).save(any());
    }

    @Test
    void checkOrderConsistency_recordsTrueMismatch() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("order_id", "O-9", "total_amount_cents", 100, "line_subtotal", 120,
                        "coupon_discount", 0, "member_discount", 0, "payable_from_lines", 120)
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
        assertEquals("120", captor.getValue().getActualValue());
    }

    @Test
    void fixInconsistency_whenPaidMatchesHeader_alignsSingleLine() {
        DataConsistencyRecord consistencyRecord = new DataConsistencyRecord();
        consistencyRecord.setId(3L);
        consistencyRecord.setCheckType("ORDER_AMOUNT");
        consistencyRecord.setCheckKey("O-FIX");
        consistencyRecord.setExpectedValue("150");
        consistencyRecord.setActualValue("350");
        consistencyRecord.setStatus(DataConsistencyService.STATUS_FAIL);

        when(consistencyRepository.findByIdForUpdate(3L)).thenReturn(java.util.Optional.of(consistencyRecord));
        when(jdbcTemplate.query(startsWith("SELECT total_amount_cents"), anyIntExtractor(), eq("O-FIX")))
                .thenReturn(150);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(SUM(CASE"), anyIntExtractor(), eq("O-FIX")))
                .thenReturn(150);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(SUM(line_amount_cents)"), anyIntExtractor(), eq("O-FIX")))
                .thenReturn(350);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(coupon_discount_cents"), anyIntExtractor(), eq("O-FIX")))
                .thenReturn(0);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(member_discount_cents"), anyIntExtractor(), eq("O-FIX")))
                .thenReturn(0);
        when(jdbcTemplate.query(startsWith("SELECT COUNT(*)"), anyIntExtractor(), eq("O-FIX")))
                .thenReturn(1);
        when(jdbcTemplate.query(startsWith("SELECT quantity"), anyIntExtractor(), eq("O-FIX")))
                .thenReturn(1);
        when(jdbcTemplate.update(startsWith("UPDATE cabinet_order_line SET line_amount_cents = ?, unit_price_cents"),
                eq(150), eq(150), eq("O-FIX")))
                .thenReturn(1);

        assertTrue(service.fixInconsistency(3L));
        assertEquals(DataConsistencyService.STATUS_FIXED, consistencyRecord.getStatus());
        assertNotNull(consistencyRecord.getFixedAt());
        verify(consistencyRepository).save(consistencyRecord);
    }

    @Test
    void fixInconsistency_clearsStaleCouponWhenPaidMatchesLineSubtotal() {
        DataConsistencyRecord consistencyRecord = new DataConsistencyRecord();
        consistencyRecord.setId(9L);
        consistencyRecord.setCheckType("ORDER_AMOUNT");
        consistencyRecord.setCheckKey("O-STALE");
        consistencyRecord.setStatus(DataConsistencyService.STATUS_FAIL);

        when(consistencyRepository.findByIdForUpdate(9L)).thenReturn(java.util.Optional.of(consistencyRecord));
        when(jdbcTemplate.query(startsWith("SELECT total_amount_cents"), anyIntExtractor(), eq("O-STALE")))
                .thenReturn(150);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(SUM(CASE"), anyIntExtractor(), eq("O-STALE")))
                .thenReturn(150);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(SUM(line_amount_cents)"), anyIntExtractor(), eq("O-STALE")))
                .thenReturn(150);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(coupon_discount_cents"), anyIntExtractor(), eq("O-STALE")))
                .thenReturn(200);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(member_discount_cents"), anyIntExtractor(), eq("O-STALE")))
                .thenReturn(0);
        when(jdbcTemplate.query(startsWith("SELECT COUNT(*)"), anyIntExtractor(), eq("O-STALE")))
                .thenReturn(1);
        when(jdbcTemplate.update(startsWith("UPDATE cabinet_order SET coupon_id"), eq("O-STALE")))
                .thenReturn(1);
        when(couponService.releaseStaleUsedCouponsForOrder("O-STALE")).thenReturn(1);

        DataConsistencyService.FixOutcome outcome = service.fixInconsistencyDetailed(9L);
        assertTrue(outcome.fixed());
        assertTrue(outcome.message().contains("清除未生效"));
        assertEquals(DataConsistencyService.STATUS_FIXED, consistencyRecord.getStatus());
    }

    @Test
    void buildOrderAmountErrorMessage_flagsStaleCouponHint() {
        String msg = DataConsistencyService.buildOrderAmountErrorMessage(Map.of(
                "total_amount_cents", 150,
                "line_subtotal", 150,
                "coupon_discount", 200,
                "member_discount", 0,
                "payable_from_lines", -50,
                "net_paid", 150));
        assertTrue(msg.contains("券抵扣超过明细"));
        assertTrue(msg.contains("券字段未生效"));
    }

    @Test
    void fixInconsistency_withoutPayment_updatesHeaderToLineSum() {
        DataConsistencyRecord consistencyRecord = new DataConsistencyRecord();
        consistencyRecord.setId(5L);
        consistencyRecord.setCheckType("ORDER_AMOUNT");
        consistencyRecord.setCheckKey("O-NOPAY");
        consistencyRecord.setActualValue("120");
        consistencyRecord.setStatus(DataConsistencyService.STATUS_FAIL);

        when(consistencyRepository.findByIdForUpdate(5L)).thenReturn(java.util.Optional.of(consistencyRecord));
        when(jdbcTemplate.query(startsWith("SELECT total_amount_cents"), anyIntExtractor(), eq("O-NOPAY")))
                .thenReturn(100);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(SUM(CASE"), anyIntExtractor(), eq("O-NOPAY")))
                .thenReturn(0);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(SUM(line_amount_cents)"), anyIntExtractor(), eq("O-NOPAY")))
                .thenReturn(120);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(coupon_discount_cents"), anyIntExtractor(), eq("O-NOPAY")))
                .thenReturn(0);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(member_discount_cents"), anyIntExtractor(), eq("O-NOPAY")))
                .thenReturn(0);
        when(jdbcTemplate.query(startsWith("SELECT COUNT(*)"), anyIntExtractor(), eq("O-NOPAY")))
                .thenReturn(1);
        when(jdbcTemplate.update(
                "UPDATE cabinet_order SET total_amount_cents = ? WHERE order_id = ?",
                120,
                "O-NOPAY")).thenReturn(1);

        assertTrue(service.fixInconsistency(5L));
        assertEquals(DataConsistencyService.STATUS_FIXED, consistencyRecord.getStatus());
    }

    @Test
    void fixInconsistency_paymentAmount_rejectsWhenUnderpaid() {
        DataConsistencyRecord consistencyRecord = new DataConsistencyRecord();
        consistencyRecord.setId(4L);
        consistencyRecord.setCheckType("PAYMENT_AMOUNT");
        consistencyRecord.setCheckKey("O-PAY");
        consistencyRecord.setExpectedValue("100");
        consistencyRecord.setActualValue("50");
        consistencyRecord.setStatus(DataConsistencyService.STATUS_FAIL);

        when(consistencyRepository.findByIdForUpdate(4L)).thenReturn(java.util.Optional.of(consistencyRecord));

        assertFalse(service.fixInconsistency(4L));
        assertEquals(DataConsistencyService.STATUS_FAIL, consistencyRecord.getStatus());
        verify(orderPaymentService, never()).refundOrder(any(), anyInt(), anyString());

        DataConsistencyService.FixOutcome outcome = service.fixInconsistencyDetailed(4L);
        assertFalse(outcome.fixed());
        assertTrue(outcome.message().contains("补扣") || outcome.message().contains("调账"));
    }

    @Test
    void fixInconsistency_paymentAmount_refundsOvercharge() {
        DataConsistencyRecord consistencyRecord = new DataConsistencyRecord();
        consistencyRecord.setId(12L);
        consistencyRecord.setCheckType("PAYMENT_AMOUNT");
        consistencyRecord.setCheckKey("O-SNACK");
        consistencyRecord.setExpectedValue("1503");
        consistencyRecord.setActualValue("1504");
        consistencyRecord.setStatus(DataConsistencyService.STATUS_FAIL);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-SNACK");
        order.setStatus("PAID");

        when(consistencyRepository.findByIdForUpdate(12L)).thenReturn(java.util.Optional.of(consistencyRecord));
        when(cabinetOrderRepository.findByIdForUpdate("O-SNACK")).thenReturn(java.util.Optional.of(order));

        DataConsistencyService.FixOutcome outcome = service.fixInconsistencyDetailed(12L);
        assertTrue(outcome.fixed());
        assertTrue(outcome.message().contains("1"));
        verify(orderPaymentService).refundOrder(order, 1, "一致性修复退多收");
        assertEquals(DataConsistencyService.STATUS_FIXED, consistencyRecord.getStatus());
    }

    @Test
    void fixInconsistencyDetailed_missingRecord() {
        when(consistencyRepository.findByIdForUpdate(99L)).thenReturn(java.util.Optional.empty());
        DataConsistencyService.FixOutcome outcome = service.fixInconsistencyDetailed(99L);
        assertFalse(outcome.fixed());
        assertEquals("记录不存在", outcome.message());
    }

    @Test
    void resolveStaleFailuresIfComplete_skipsAutoCloseWhenBatchCapHit() {
        service.resolveStaleFailuresIfComplete("POINTS_BALANCE", Set.of("M-1"), 200);
        verify(consistencyRepository, never()).findByCheckTypeAndStatus(anyString(), anyString());
    }

    @Test
    void checkPointsConsistency_usesLedgerSum() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(consistencyRepository.findByCheckTypeAndStatus(anyString(), anyString()))
                .thenReturn(List.of());

        service.checkPointsConsistency();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("SUM(l.points)"));
        assertTrue(sql.contains("member_points_log"));
    }

    @Test
    void checkCouponIssuedConsistency_recordsMismatch() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("coupon_def_id", "CD-1", "expected", 5, "actual", 3)
        ));
        when(consistencyRepository.findByCheckTypeAndCheckKeyAndStatus(
                anyString(), anyString(), anyString())).thenReturn(List.of());
        when(consistencyRepository.findByCheckTypeAndStatus(anyString(), anyString()))
                .thenReturn(List.of());

        service.checkCouponIssuedConsistency();

        ArgumentCaptor<DataConsistencyRecord> captor = ArgumentCaptor.forClass(DataConsistencyRecord.class);
        verify(consistencyRepository).save(captor.capture());
        assertEquals("COUPON_ISSUED", captor.getValue().getCheckType());
        assertEquals("CD-1", captor.getValue().getCheckKey());
        assertEquals("5", captor.getValue().getExpectedValue());
        assertEquals("3", captor.getValue().getActualValue());
    }

    @Test
    void fixInconsistency_inventoryMismatch_updatesSummaryQty() {
        DataConsistencyRecord consistencyRecord = new DataConsistencyRecord();
        consistencyRecord.setId(8L);
        consistencyRecord.setCheckType("INVENTORY_MISMATCH");
        consistencyRecord.setCheckKey("DEV-1|SKU-9");
        consistencyRecord.setActualValue("12");
        consistencyRecord.setStatus(DataConsistencyService.STATUS_FAIL);

        when(consistencyRepository.findByIdForUpdate(8L)).thenReturn(java.util.Optional.of(consistencyRecord));
        when(jdbcTemplate.update(startsWith("UPDATE device_sku_inventory"), eq("12"), eq("DEV-1"), eq("SKU-9")))
                .thenReturn(1);

        DataConsistencyService.FixOutcome outcome = service.fixInconsistencyDetailed(8L);

        assertTrue(outcome.fixed());
        assertEquals(DataConsistencyService.STATUS_FIXED, consistencyRecord.getStatus());
        assertTrue(outcome.message().contains("12"));
    }

    @Test
    void checkWalletBalanceConsistency_usesLatestLedger() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(consistencyRepository.findByCheckTypeAndStatus(anyString(), anyString()))
                .thenReturn(List.of());

        service.checkWalletBalanceConsistency();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("user_account"));
        assertTrue(sql.contains("balance_after_cents"));
        assertTrue(sql.contains("WALLET_BALANCE") || sql.contains("BALANCE"));
    }

    @Test
    void checkRefundAmountConsistency_recordsMismatch() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("order_id", "O-R1", "expected", 100, "actual", 80)
        ));
        when(consistencyRepository.findByCheckTypeAndCheckKeyAndStatus(
                anyString(), anyString(), anyString())).thenReturn(List.of());
        when(consistencyRepository.findByCheckTypeAndStatus(anyString(), anyString()))
                .thenReturn(List.of());

        service.checkRefundAmountConsistency();

        ArgumentCaptor<DataConsistencyRecord> captor = ArgumentCaptor.forClass(DataConsistencyRecord.class);
        verify(consistencyRepository).save(captor.capture());
        assertEquals("REFUND_AMOUNT", captor.getValue().getCheckType());
        assertEquals("O-R1", captor.getValue().getCheckKey());
    }

    @Test
    void checkOrderLineSumConsistency_recordsMismatch() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("line_key", "O-1|SKU-A", "expected", 150, "actual", 140)
        ));
        when(consistencyRepository.findByCheckTypeAndCheckKeyAndStatus(
                anyString(), anyString(), anyString())).thenReturn(List.of());
        when(consistencyRepository.findByCheckTypeAndStatus(anyString(), anyString()))
                .thenReturn(List.of());

        service.checkOrderLineSumConsistency();

        ArgumentCaptor<DataConsistencyRecord> captor = ArgumentCaptor.forClass(DataConsistencyRecord.class);
        verify(consistencyRepository).save(captor.capture());
        assertEquals("ORDER_LINE_SUM", captor.getValue().getCheckType());
    }

    @Test
    void checkCouponUsedLinkConsistency_recordsMismatch() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("order_id", "O-C1", "order_discount", 0, "coupon_discount", 200,
                        "order_coupon_id", "", "user_coupon_id", "44")
        ));
        when(consistencyRepository.findByCheckTypeAndCheckKeyAndStatus(
                anyString(), anyString(), anyString())).thenReturn(List.of());
        when(consistencyRepository.findByCheckTypeAndStatus(anyString(), anyString()))
                .thenReturn(List.of());

        service.checkCouponUsedLinkConsistency();

        ArgumentCaptor<DataConsistencyRecord> captor = ArgumentCaptor.forClass(DataConsistencyRecord.class);
        verify(consistencyRepository).save(captor.capture());
        assertEquals("COUPON_USED_LINK", captor.getValue().getCheckType());
    }

    @Test
    void fixInconsistency_orderLineSum_alignsLineToUnitTimesQty() {
        DataConsistencyRecord consistencyRecord = new DataConsistencyRecord();
        consistencyRecord.setId(10L);
        consistencyRecord.setCheckType("ORDER_LINE_SUM");
        consistencyRecord.setCheckKey("O-L1|SKU-A");
        consistencyRecord.setStatus(DataConsistencyService.STATUS_FAIL);

        when(consistencyRepository.findByIdForUpdate(10L)).thenReturn(java.util.Optional.of(consistencyRecord));
        when(jdbcTemplate.queryForList(anyString(), eq("O-L1"), eq("SKU-A")))
                .thenReturn(List.of(Map.of(
                        "quantity", 3,
                        "unit_price_cents", 501,
                        "line_amount_cents", 1504,
                        "total_amount_cents", 1504)));
        when(jdbcTemplate.update(startsWith("UPDATE cabinet_order_line SET line_amount_cents"),
                eq(1503), eq(501), eq("O-L1"), eq("SKU-A")))
                .thenReturn(1);
        when(jdbcTemplate.query(startsWith("SELECT COUNT(*)"), anyIntExtractor(), eq("O-L1")))
                .thenReturn(1);
        when(jdbcTemplate.update(startsWith("UPDATE cabinet_order SET total_amount_cents"),
                eq(1503), eq("O-L1")))
                .thenReturn(1);
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(SUM(CASE"), anyIntExtractor(), eq("O-L1")))
                .thenReturn(1504);
        CabinetOrder paidOrder = new CabinetOrder();
        paidOrder.setOrderId("O-L1");
        paidOrder.setStatus("PAID");
        when(cabinetOrderRepository.findByIdForUpdate("O-L1")).thenReturn(java.util.Optional.of(paidOrder));

        DataConsistencyService.FixOutcome outcome = service.fixInconsistencyDetailed(10L);
        assertTrue(outcome.fixed());
        assertTrue(outcome.message().contains("1503"));
        assertEquals(DataConsistencyService.STATUS_FIXED, consistencyRecord.getStatus());
    }

    @Test
    void fixInconsistency_couponUsedLink_releasesStaleCoupons() {
        DataConsistencyRecord consistencyRecord = new DataConsistencyRecord();
        consistencyRecord.setId(11L);
        consistencyRecord.setCheckType("COUPON_USED_LINK");
        consistencyRecord.setCheckKey("O-COUP");
        consistencyRecord.setStatus(DataConsistencyService.STATUS_FAIL);

        when(consistencyRepository.findByIdForUpdate(11L)).thenReturn(java.util.Optional.of(consistencyRecord));
        when(jdbcTemplate.query(startsWith("SELECT COALESCE(coupon_discount_cents"), anyIntExtractor(), eq("O-COUP")))
                .thenReturn(0);
        when(jdbcTemplate.query(startsWith("SELECT coupon_id"), anyIntExtractor(), eq("O-COUP")))
                .thenReturn(null);
        when(couponService.releaseStaleUsedCouponsForOrder("O-COUP")).thenReturn(6);
        when(jdbcTemplate.update(startsWith("UPDATE cabinet_order SET coupon_id = NULL"), eq("O-COUP")))
                .thenReturn(0);

        DataConsistencyService.FixOutcome outcome = service.fixInconsistencyDetailed(11L);
        assertTrue(outcome.fixed());
        assertTrue(outcome.message().contains("6"));
        assertEquals(DataConsistencyService.STATUS_FIXED, consistencyRecord.getStatus());
    }
}
