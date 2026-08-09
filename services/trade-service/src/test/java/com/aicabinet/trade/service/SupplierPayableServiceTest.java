package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PaySupplierRequest;
import com.aicabinet.trade.domain.PurchaseOrder;
import com.aicabinet.trade.domain.Supplier;
import com.aicabinet.trade.domain.SupplierPayable;
import com.aicabinet.trade.domain.SupplierPayment;
import com.aicabinet.trade.mapper.SupplierMapper;
import com.aicabinet.trade.mapper.SupplierPayableMapper;
import com.aicabinet.trade.mapper.SupplierPaymentMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierPayableServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock private PermissionService permissionService;
    @Mock private SupplierPayableMapper payableRepository;
    @Mock private SupplierPaymentMapper paymentRepository;
    @Mock private SupplierMapper supplierRepository;
    @Mock private WarehouseMapper warehouseRepository;

    private SupplierPayableService service;

    @BeforeEach
    void setUp() {
        service = new SupplierPayableService(permissionService, payableRepository,
                paymentRepository, supplierRepository, warehouseRepository);
    }

    @Test
    void recordReceive_shouldCreatePayableWithTermsBasedDueDate() {
        when(supplierRepository.findById("SUP-001")).thenReturn(Optional.of(supplier(30)));
        when(payableRepository.findByPurchaseOrderId(any())).thenReturn(Optional.empty());

        service.recordReceive(1L, order(), 5000L);

        ArgumentCaptor<SupplierPayable> captor = ArgumentCaptor.forClass(SupplierPayable.class);
        verify(payableRepository).save(captor.capture());
        SupplierPayable p = captor.getValue();
        assertEquals("SUP-001", p.getSupplierId());
        assertEquals(5000L, p.getAmountCents());
        assertEquals("UNPAID", p.getStatus());
        assertEquals(LocalDate.now(ZONE).plusDays(30), p.getDueDate());
    }

    @Test
    void recordReceive_shouldAccumulateOnPartialReceives() {
        SupplierPayable existing = payable(3000L, 0L, "UNPAID");
        when(payableRepository.findByPurchaseOrderId(any())).thenReturn(Optional.of(existing));

        service.recordReceive(1L, order(), 2000L);

        assertEquals(5000L, existing.getAmountCents());
        assertEquals("UNPAID", existing.getStatus());
    }

    @Test
    void recordReturn_shouldReduceAndCloseWhenZero() {
        SupplierPayable existing = payable(3000L, 0L, "UNPAID");
        when(payableRepository.findByPurchaseOrderId(any())).thenReturn(Optional.of(existing));

        service.recordReturn(1L, order(), 3000L);

        assertEquals(0L, existing.getAmountCents());
        assertEquals("CLOSED", existing.getStatus());
    }

    @Test
    void pay_shouldMarkPaidWhenFullSettlement() {
        SupplierPayable existing = payable(10000L, 0L, "UNPAID");
        when(payableRepository.findById(1L)).thenReturn(Optional.of(existing));

        service.pay(1L, 1L, new PaySupplierRequest(10000L, "对公转账"));

        assertEquals(10000L, existing.getPaidAmountCents());
        assertEquals("PAID", existing.getStatus());
        ArgumentCaptor<SupplierPayment> captor = ArgumentCaptor.forClass(SupplierPayment.class);
        verify(paymentRepository).save(captor.capture());
        assertEquals(10000L, captor.getValue().getAmountCents());
        assertEquals(1L, captor.getValue().getPayableId());
    }

    @Test
    void pay_shouldMarkPartialWhenUnderpaying() {
        SupplierPayable existing = payable(10000L, 0L, "UNPAID");
        when(payableRepository.findById(1L)).thenReturn(Optional.of(existing));

        service.pay(1L, 1L, new PaySupplierRequest(4000L, null));

        assertEquals("PARTIAL", existing.getStatus());
        assertEquals(4000L, existing.getPaidAmountCents());
    }

    @Test
    void pay_shouldRejectAmountAboveBalance() {
        SupplierPayable existing = payable(10000L, 5000L, "PARTIAL");
        when(payableRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThrows(ResponseStatusException.class,
                () -> service.pay(1L, 1L, new PaySupplierRequest(6000L, null)));
    }

    @Test
    void listPayables_shouldFlagOverdue() {
        SupplierPayable overdue = payable(2000L, 0L, "UNPAID");
        overdue.setDueDate(LocalDate.now(ZONE).minusDays(3));
        when(payableRepository.findAllByOrderByDueDateAsc()).thenReturn(List.of(overdue));
        when(supplierRepository.findById("SUP-001")).thenReturn(Optional.of(supplier(30)));

        var result = service.listPayables(1L, null, null, false);

        assertEquals(1, result.size());
        assertTrue(result.get(0).overdue());
        assertEquals(3, result.get(0).overdueDays());
        assertEquals(2000L, result.get(0).balanceCents());
    }

    @Test
    void summary_shouldAggregateTotalAndOverdueBalances() {
        SupplierPayable overdue = payable(2000L, 0L, "UNPAID");
        overdue.setDueDate(LocalDate.now(ZONE).minusDays(2));
        SupplierPayable normal = payable(3000L, 0L, "UNPAID");
        normal.setDueDate(LocalDate.now(ZONE).plusDays(10));
        when(payableRepository.findAll()).thenReturn(List.of(overdue, normal));
        when(supplierRepository.findById("SUP-001")).thenReturn(Optional.of(supplier(30)));

        var result = service.summary(1L, null);

        assertEquals(1, result.size());
        assertEquals("测试供应商", result.get(0).supplierName());
        assertEquals(2, result.get(0).payableCount());
        assertEquals(5000L, result.get(0).totalBalanceCents());
        assertEquals(2000L, result.get(0).overdueBalanceCents());
    }

    private static PurchaseOrder order() {
        PurchaseOrder o = new PurchaseOrder();
        o.setSupplierId("SUP-001");
        o.setWarehouseId("WH-001");
        return o;
    }

    private static Supplier supplier(int termsDays) {
        Supplier s = new Supplier();
        s.setSupplierId("SUP-001");
        s.setSupplierName("测试供应商");
        s.setPaymentTermsDays(termsDays);
        return s;
    }

    private static SupplierPayable payable(long amount, long paid, String status) {
        SupplierPayable p = new SupplierPayable();
        p.setPayableId(1L);
        p.setSupplierId("SUP-001");
        p.setPurchaseOrderId(100L);
        p.setWarehouseId("WH-001");
        p.setAmountCents(amount);
        p.setPaidAmountCents(paid);
        p.setStatus(status);
        p.setDueDate(LocalDate.now(ZONE).plusDays(30));
        return p;
    }
}
