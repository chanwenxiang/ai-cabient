package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.SiteRentBill;
import com.aicabinet.trade.mapper.SiteContractMapper;
import com.aicabinet.trade.mapper.SiteRentBillMapper;
import com.aicabinet.trade.mapper.SiteRentSplitRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H30(a)：markPaid/void 持行锁（selectByIdForUpdate）后再做状态迁移。
 */
@ExtendWith(MockitoExtension.class)
class SiteRentBillRowLockTest {

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
        doNothing().when(permissionService).requirePermission(anyLong(), anyString());
        doNothing().when(auditService).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    private SiteRentBill bill(String status) {
        SiteRentBill b = new SiteRentBill();
        b.setBillId(9L);
        b.setBillMonth("2026-08");
        b.setAmountCents(500000);
        b.setStatus(status);
        return b;
    }

    @Test
    void markPaid_locksBillRowForUpdate() {
        when(billMapper.selectByIdForUpdate(9L)).thenReturn(Optional.of(bill("UNPAID")));
        when(billMapper.updateById(any())).thenReturn(1);

        service.markPaid(1L, 9L);

        verify(billMapper).selectByIdForUpdate(9L);
        verify(billMapper).updateById(any(SiteRentBill.class));
    }

    @Test
    void voidBill_locksBillRowForUpdate() {
        when(billMapper.selectByIdForUpdate(9L)).thenReturn(Optional.of(bill(CabinetConstants.FEE_BILL_STATUS_UNPAID)));
        when(billMapper.updateById(any())).thenReturn(1);

        service.voidBill(1L, 9L);

        verify(billMapper).selectByIdForUpdate(9L);
        verify(billMapper).updateById(any(SiteRentBill.class));
    }
}
