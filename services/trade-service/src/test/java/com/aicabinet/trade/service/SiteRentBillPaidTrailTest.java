package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.SiteRentBill;
import com.aicabinet.trade.mapper.SiteContractMapper;
import com.aicabinet.trade.mapper.SiteRentBillMapper;
import com.aicabinet.trade.mapper.SiteRentSplitRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V309：场地租金「标记已付」的付款留痕。
 *
 * <p>🔴 <b>这个类守护的是什么</b>：原 {@code markPaid} 只写 {@code status} + {@code paidAt}，
 * 场地租金这种<b>对外付款</b>在系统里就只剩一个不可复核的「已付」——
 * 谁付的、凭什么付的、凭证在哪，全都查不到。财务审计里这等于「这笔钱说不清」。
 *
 * <p>⚠️ 断言口径：留痕必须<b>同时</b>落在业务表（{@code paidBy/paidVoucherNo/paidRemark}）
 * 与审计日志（appendLog 的 detail 里含凭证号）。只落其一都不够 ——
 * 只落业务表，将来无法自证凭证号是「付款时填的」还是「事后补的」。
 */
@ExtendWith(MockitoExtension.class)
class SiteRentBillPaidTrailTest {

    @Mock private SiteRentBillMapper billMapper;
    @Mock private SiteContractMapper contractMapper;
    @Mock private SiteRentSplitRuleMapper ruleMapper;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private FeeBillMonthResolver monthResolver;

    private SiteRentBillService service;

    @BeforeEach
    void setUp() {
        service = new SiteRentBillService(billMapper, contractMapper, ruleMapper,
                permissionService, auditService, distributedLockService, monthResolver);
        // lenient：部分负向用例（已作废/已付幂等）在状态检查阶段就返回，不会走到这些调用
        lenient().doNothing().when(permissionService).requirePermission(anyLong(), anyString());
        lenient().doNothing().when(auditService).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    private SiteRentBill bill(String status) {
        SiteRentBill b = new SiteRentBill();
        b.setBillId(9L);
        b.setContractId(3L);
        b.setBillMonth("2026-08");
        b.setAmountCents(500000);
        b.setStatus(status);
        return b;
    }

    private void stubBill(SiteRentBill b) {
        when(billMapper.selectByIdForUpdate(9L)).thenReturn(Optional.of(b));
    }

    @Test
    @DisplayName("标记已付时写入操作人、凭证号与备注")
    void markPaid_writesTrail() {
        SiteRentBill b = bill(CabinetConstants.FEE_BILL_STATUS_UNPAID);
        stubBill(b);

        service.markPaid(7L, 9L, "流水号ABC123", "2026-08 月租，招商银行");

        assertEquals(CabinetConstants.FEE_BILL_STATUS_PAID, b.getStatus());
        assertEquals(7L, b.getPaidBy());
        assertEquals("流水号ABC123", b.getPaidVoucherNo());
        assertEquals("2026-08 月租，招商银行", b.getPaidRemark());
        verify(billMapper).updateById(b);
    }

    @Test
    @DisplayName("审计日志 detail 含凭证号 —— 否则无法自证不是事后补填的")
    void markPaid_auditLogCarriesVoucherNo() {
        SiteRentBill b = bill(CabinetConstants.FEE_BILL_STATUS_UNPAID);
        stubBill(b);

        service.markPaid(7L, 9L, "流水号XYZ789", null);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).appendLog(anyLong(), anyString(), anyString(), anyString(), detail.capture());
        // 凭证号必须出现在日志里；否则「凭证号是不是事后补的」永远无法判定
        org.junit.jupiter.api.Assertions.assertTrue(
                detail.getValue().contains("流水号XYZ789"),
                "审计日志应含凭证号，实际: " + detail.getValue());
    }

    @Test
    @DisplayName("空白凭证号落为 null（不留空串噪声）")
    void markPaid_blankVoucherBecomesNull() {
        SiteRentBill b = bill(CabinetConstants.FEE_BILL_STATUS_UNPAID);
        stubBill(b);

        service.markPaid(7L, 9L, "   ", "  ");

        assertNull(b.getPaidVoucherNo());
        assertNull(b.getPaidRemark());
        // 操作人仍要写 —— 不管有没有凭证号，「谁付的」是必答题
        assertEquals(7L, b.getPaidBy());
    }

    @Test
    @DisplayName("旧签名（无请求体）仍可标记已付，且写操作人")
    void markPaid_legacyOverloadStillRecordsOperator() {
        SiteRentBill b = bill(CabinetConstants.FEE_BILL_STATUS_UNPAID);
        stubBill(b);

        service.markPaid(7L, 9L);

        assertEquals(CabinetConstants.FEE_BILL_STATUS_PAID, b.getStatus());
        assertEquals(7L, b.getPaidBy());
        assertNull(b.getPaidVoucherNo());
    }

    @Test
    @DisplayName("已付账单重复标记幂等：不覆盖原有凭证号")
    void markPaid_alreadyPaid_isIdempotentAndKeepsOldVoucher() {
        // 🔴 关键回归：先有凭证号 V1，再无参重复调用 —— 绝不能被 null 覆盖掉。
        // 覆盖掉等于「后来的运营无意间抹掉了付款凭证」，且无法恢复。
        SiteRentBill b = bill(CabinetConstants.FEE_BILL_STATUS_PAID);
        b.setPaidBy(7L);
        b.setPaidVoucherNo("原始凭证V1");
        b.setPaidRemark("原始备注");
        stubBill(b);

        service.markPaid(99L, 9L, null, null);

        assertEquals("原始凭证V1", b.getPaidVoucherNo());
        assertEquals("原始备注", b.getPaidRemark());
        assertEquals(7L, b.getPaidBy());
        verify(billMapper, never()).updateById(any(SiteRentBill.class));
    }

    @Test
    @DisplayName("已作废账单拒绝标记已付（负向）")
    void markPaid_voidBill_rejected() {
        stubBill(bill(CabinetConstants.FEE_BILL_STATUS_VOID));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.markPaid(7L, 9L, "V1", "备注"));
        assertEquals(400, e.getStatusCode().value());
        verify(billMapper, never()).updateById(any(SiteRentBill.class));
    }

    @Test
    @DisplayName("DTO 出参带出留痕三字段（否则前端拿不到，字段等于白存）")
    void toDto_exposesTrailFields() {
        SiteRentBill b = bill(CabinetConstants.FEE_BILL_STATUS_PAID);
        b.setPaidAt(java.time.Instant.parse("2026-08-31T10:00:00Z"));
        b.setPaidBy(7L);
        b.setPaidVoucherNo("V1");
        b.setPaidRemark("备注X");
        stubBill(b);

        var dto = service.markPaid(7L, 9L);

        assertEquals(7L, dto.paidBy());
        assertEquals("V1", dto.paidVoucherNo());
        assertEquals("备注X", dto.paidRemark());
    }
}