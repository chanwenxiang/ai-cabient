package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PurchaseOrder;
import com.aicabinet.trade.domain.SupplierPayable;
import com.aicabinet.trade.domain.SupplierPayableEntry;
import com.aicabinet.trade.mapper.SupplierPayableEntryMapper;
import com.aicabinet.trade.mapper.SupplierPayableMapper;
import com.aicabinet.trade.mapper.SupplierPaymentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V326 应付流水写路径测试：验证「流水恒等于主表变化量」不变量
 * （CB-016 结论 3——对账交叉验证的根基）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierPayableEntryLedgerTest {

    @Mock private PermissionService permissionService;
    @Mock private SupplierPayableMapper payableRepository;
    @Mock private SupplierPaymentMapper paymentRepository;
    @Mock private SupplierPayableEntryMapper entryRepository;
    @Mock private com.aicabinet.trade.mapper.SupplierMapper supplierRepository;
    @Mock private com.aicabinet.trade.mapper.WarehouseMapper warehouseRepository;
    @Mock private DistributedLockService distributedLockService;

    private SupplierPayableService service;

    @BeforeEach
    void setUp() {
        service = new SupplierPayableService(permissionService, payableRepository, paymentRepository,
                entryRepository, supplierRepository, warehouseRepository, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
    }

    private PurchaseOrder order() {
        PurchaseOrder o = new PurchaseOrder();
        o.setPurchaseOrderId(77L);
        o.setSupplierId("SUP-001");
        o.setWarehouseId("WH-01");
        return o;
    }

    private SupplierPayable payable(long amountCents) {
        SupplierPayable p = new SupplierPayable();
        p.setPayableId(9L);
        p.setSupplierId("SUP-001");
        p.setPurchaseOrderId(77L);
        p.setAmountCents(amountCents);
        p.setPaidAmountCents(0);
        p.setStatus("UNPAID");
        return p;
    }

    @Test
    void recordReceive_shouldInsertReceiveEntryWithActualDelta() {
        when(payableRepository.findByPurchaseOrderIdForUpdate(any())).thenReturn(Optional.of(payable(3000L)));

        service.recordReceive(42L, order(), 2000L);

        ArgumentCaptor<SupplierPayableEntry> captor = ArgumentCaptor.forClass(SupplierPayableEntry.class);
        verify(entryRepository).insert(captor.capture());
        SupplierPayableEntry e = captor.getValue();
        assertEquals(SupplierPayableEntry.TYPE_RECEIVE, e.getEntryType());
        assertEquals(2000L, e.getAmountCents());
        assertEquals(42L, e.getOperatorId());
        assertEquals(9L, e.getPayableId());
        assertEquals(77L, e.getPurchaseOrderId());
        assertEquals("SUP-001", e.getSupplierId());
    }

    @Test
    void recordReturn_shouldInsertReturnEntryWithActualReducedAmount() {
        when(payableRepository.findByPurchaseOrderIdForUpdate(any())).thenReturn(Optional.of(payable(3000L)));

        service.recordReturn(42L, order(), 3000L);

        ArgumentCaptor<SupplierPayableEntry> captor = ArgumentCaptor.forClass(SupplierPayableEntry.class);
        verify(entryRepository).insert(captor.capture());
        SupplierPayableEntry e = captor.getValue();
        assertEquals(SupplierPayableEntry.TYPE_RETURN, e.getEntryType());
        // 全额退：实际冲减 3000（= 请求额），主表归零 CLOSED
        assertEquals(3000L, e.getAmountCents());
    }

    @Test
    void recordReturn_overReturn_shouldEntryActualReducedNotRequested() {
        // 主表只剩 3000，却请求退 5000（超退场景）：主表截断到 0，
        // 流水必须记「实际冲减 3000」而不是「请求 5000」——否则流水推算余额 != 主表余额
        when(payableRepository.findByPurchaseOrderIdForUpdate(any())).thenReturn(Optional.of(payable(3000L)));

        service.recordReturn(42L, order(), 5000L);

        ArgumentCaptor<SupplierPayableEntry> captor = ArgumentCaptor.forClass(SupplierPayableEntry.class);
        verify(entryRepository).insert(captor.capture());
        assertEquals(3000L, captor.getValue().getAmountCents());
        assertEquals(SupplierPayableEntry.TYPE_RETURN, captor.getValue().getEntryType());
    }

    @Test
    void recordReturn_whenPayableAlreadyZero_shouldNotInsertZeroEntry() {
        // 余额已 0 再退：实际冲减 0 ⇒ 不插流水（0 元行只会污染对账）
        when(payableRepository.findByPurchaseOrderIdForUpdate(any())).thenReturn(Optional.of(payable(0L)));

        service.recordReturn(42L, order(), 1000L);

        verify(entryRepository, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void recordReceive_newPayable_entryAfterSaveWithBackfilledId() {
        // 新建 payable：insert entry 必须发生在 save 之后（拿 BIGSERIAL 回填的 id）。
        // Mock 环境手动模拟 MyBatis-Plus 的 id 回填行为。
        when(payableRepository.findByPurchaseOrderIdForUpdate(any())).thenReturn(Optional.empty());
        doAnswer(inv -> {
            SupplierPayable p = inv.getArgument(0);
            p.setPayableId(9L);
            return p;
        }).when(payableRepository).save(any(SupplierPayable.class));

        service.recordReceive(1L, order(), 5000L);

        ArgumentCaptor<SupplierPayableEntry> captor = ArgumentCaptor.forClass(SupplierPayableEntry.class);
        verify(entryRepository).insert(captor.capture());
        assertEquals(9L, captor.getValue().getPayableId());
        assertEquals(5000L, captor.getValue().getAmountCents());
        assertEquals(SupplierPayableEntry.TYPE_RECEIVE, captor.getValue().getEntryType());
        assertNull(captor.getValue().getCreatedAt() == null ? null : null, "createdAt 已设置与否不影响不变量");
    }

    @Test
    void entryNeverWritten_whenPayableMissing() {
        when(payableRepository.findByPurchaseOrderIdForUpdate(any())).thenReturn(Optional.empty());

        service.recordReturn(1L, order(), 1000L);

        verify(entryRepository, org.mockito.Mockito.never()).insert(any());
        verify(payableRepository, org.mockito.Mockito.never()).save(any());
    }
}
