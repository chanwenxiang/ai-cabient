package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PaySupplierRequest;
import com.aicabinet.common.dto.SupplierPayableDto;
import com.aicabinet.common.dto.SupplierPayableSummaryDto;
import com.aicabinet.common.dto.SupplierPaymentDto;
import com.aicabinet.trade.domain.PurchaseOrder;
import com.aicabinet.trade.domain.SupplierPayable;
import com.aicabinet.trade.domain.SupplierPayment;
import com.aicabinet.trade.mapper.SupplierMapper;
import com.aicabinet.trade.mapper.SupplierPayableMapper;
import com.aicabinet.trade.mapper.SupplierPaymentMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商应付账款：采购收货自动生成/累加应付，退货冲减，付款核销，
 * 并按账期给出逾期与欠款汇总。
 */
@Service
public class SupplierPayableService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_TERMS_DAYS = 30;

    private final PermissionService permissionService;
    private final SupplierPayableMapper payableRepository;
    private final SupplierPaymentMapper paymentRepository;
    private final SupplierMapper supplierRepository;
    private final WarehouseMapper warehouseRepository;
    private final DistributedLockService distributedLockService;

    public SupplierPayableService(PermissionService permissionService,
                                  SupplierPayableMapper payableRepository,
                                  SupplierPaymentMapper paymentRepository,
                                  SupplierMapper supplierRepository,
                                  WarehouseMapper warehouseRepository,
                                  DistributedLockService distributedLockService) {
        this.permissionService = permissionService;
        this.payableRepository = payableRepository;
        this.paymentRepository = paymentRepository;
        this.supplierRepository = supplierRepository;
        this.warehouseRepository = warehouseRepository;
        this.distributedLockService = distributedLockService;
    }

    @Transactional(readOnly = true)
    public List<SupplierPayableDto> listPayables(Long operatorId, String supplierId,
                                                 String status, boolean overdueOnly) {
        permissionService.requirePermission(operatorId, "ops:procurement:list");
        return payableRepository.findAllByOrderByDueDateAsc().stream()
                .filter(p -> supplierId == null || supplierId.isBlank()
                        || supplierId.trim().equals(p.getSupplierId()))
                .filter(p -> status == null || status.isBlank()
                        || status.trim().equalsIgnoreCase(p.getStatus()))
                .filter(p -> !overdueOnly || isOverdue(p))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SupplierPayableSummaryDto> summary(Long operatorId, String supplierId) {
        permissionService.requirePermission(operatorId, "ops:procurement:list");
        Map<String, long[]> agg = new LinkedHashMap<>(); // supplierId -> [count, balance, overdueBalance]
        for (SupplierPayable p : payableRepository.findAll()) {
            if (supplierId != null && !supplierId.isBlank()
                    && !supplierId.trim().equals(p.getSupplierId())) {
                continue;
            }
            long balance = balance(p);
            if (balance <= 0) {
                continue;
            }
            long[] row = agg.computeIfAbsent(p.getSupplierId(), k -> new long[3]);
            row[0]++;
            row[1] += balance;
            if (isOverdue(p)) {
                row[2] += balance;
            }
        }
        List<SupplierPayableSummaryDto> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            long[] row = e.getValue();
            out.add(new SupplierPayableSummaryDto(
                    e.getKey(),
                    supplierName(e.getKey()),
                    (int) row[0],
                    row[1],
                    row[2]));
        }
        out.sort(Comparator.comparingLong(SupplierPayableSummaryDto::overdueBalanceCents).reversed()
                .thenComparingLong(SupplierPayableSummaryDto::totalBalanceCents).reversed());
        return out;
    }

    @Transactional
    public SupplierPayableDto pay(Long operatorId, Long payableId, PaySupplierRequest request) {
        permissionService.requirePermission(operatorId, "ops:procurement:edit");
        if (request.amountCents() == null || request.amountCents() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountCents must be positive");
        }
        String idemKey = normalizeIdempotencyKey(request.idempotencyKey());
        if (idemKey != null) {
            var existingPayment = paymentRepository.findByIdempotencyKey(idemKey);
            if (existingPayment.isPresent()) {
                SupplierPayable payable = payableRepository.findById(existingPayment.get().getPayableId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payable not found"));
                return toDto(payable);
            }
        }
        return runWithPayableLock(payableId, () -> doPay(operatorId, payableId, request, idemKey));
    }

    private SupplierPayableDto doPay(Long operatorId, Long payableId, PaySupplierRequest request, String idemKey) {
        SupplierPayable payable = payableRepository.findByIdForUpdate(payableId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payable not found"));
        long balance = balance(payable);
        if (balance <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "payable has no outstanding balance");
        }
        if (request.amountCents() > balance) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount exceeds payable balance");
        }

        SupplierPayment payment = new SupplierPayment();
        payment.setSupplierId(payable.getSupplierId());
        payment.setPayableId(payableId);
        payment.setAmountCents(request.amountCents());
        payment.setOperatorId(operatorId);
        payment.setNotes(trimToNull(request.notes()));
        payment.setIdempotencyKey(idemKey);
        payment.setCreatedAt(Instant.now());
        try {
            paymentRepository.save(payment);
        } catch (DuplicateKeyException e) {
            if (idemKey != null) {
                return paymentRepository.findByIdempotencyKey(idemKey)
                        .map(p -> payableRepository.findById(p.getPayableId()).orElse(payable))
                        .map(this::toDto)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "付款幂等冲突"));
            }
            throw e;
        }

        payable.setPaidAmountCents(payable.getPaidAmountCents() + request.amountCents());
        long remaining = balance(payable);
        if (remaining <= 0) {
            payable.setStatus("PAID");
            payable.setPaidAt(Instant.now());
        } else {
            payable.setStatus("PARTIAL");
        }
        payable.setUpdatedAt(Instant.now());
        payableRepository.save(payable);
        return toDto(payable);
    }

    /** 采购收货后累加应付金额，首次收货按供应商账期生成到期日。 */
    @Transactional
    public void recordReceive(Long operatorId, PurchaseOrder order, long receivedValueCents) {
        if (receivedValueCents <= 0 || order == null || order.getPurchaseOrderId() == null) {
            return;
        }
        runWithPurchaseOrderLock(order.getPurchaseOrderId(),
                () -> doRecordReceive(order, receivedValueCents));
    }

    private void doRecordReceive(PurchaseOrder order, long receivedValueCents) {
        SupplierPayable payable = payableRepository.findByPurchaseOrderIdForUpdate(order.getPurchaseOrderId())
                .orElse(null);
        if (payable == null) {
            payable = new SupplierPayable();
            payable.setSupplierId(order.getSupplierId());
            payable.setPurchaseOrderId(order.getPurchaseOrderId());
            payable.setWarehouseId(order.getWarehouseId());
            payable.setAmountCents(0);
            payable.setPaidAmountCents(0);
            payable.setStatus("UNPAID");
            payable.setDueDate(LocalDate.now(ZONE).plusDays(termsDays(order.getSupplierId())));
            payable.setCreatedAt(Instant.now());
        }
        payable.setAmountCents(payable.getAmountCents() + receivedValueCents);
        refreshStatus(payable);
        payable.setUpdatedAt(Instant.now());
        payableRepository.save(payable);
    }

    /** 采购退货冲减应付金额；金额归零后关闭应付单。 */
    @Transactional
    public void recordReturn(Long operatorId, PurchaseOrder order, long returnedValueCents) {
        if (returnedValueCents <= 0 || order == null || order.getPurchaseOrderId() == null) {
            return;
        }
        runWithPurchaseOrderLock(order.getPurchaseOrderId(),
                () -> doRecordReturn(order, returnedValueCents));
    }

    private void doRecordReturn(PurchaseOrder order, long returnedValueCents) {
        SupplierPayable payable = payableRepository.findByPurchaseOrderIdForUpdate(order.getPurchaseOrderId())
                .orElse(null);
        if (payable == null) {
            return;
        }
        payable.setAmountCents(Math.max(0, payable.getAmountCents() - returnedValueCents));
        if (payable.getAmountCents() <= 0) {
            payable.setStatus("CLOSED");
            payable.setPaidAt(Instant.now());
        } else {
            refreshStatus(payable);
        }
        payable.setUpdatedAt(Instant.now());
        payableRepository.save(payable);
    }

    static String payableLockKey(Long payableId) {
        return "supplier:payable:" + payableId;
    }

    static String purchaseOrderPayableLockKey(Long purchaseOrderId) {
        return "supplier:payable:po:" + purchaseOrderId;
    }

    private <T> T runWithPayableLock(Long payableId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(payableLockKey(payableId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "应付账款处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(payableLockKey(payableId));
        }
    }

    private void runWithPurchaseOrderLock(Long purchaseOrderId, Runnable action) {
        runWithPurchaseOrderLock(purchaseOrderId, () -> {
            action.run();
            return null;
        });
    }

    private <T> T runWithPurchaseOrderLock(Long purchaseOrderId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(purchaseOrderPayableLockKey(purchaseOrderId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "应付账款处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(purchaseOrderPayableLockKey(purchaseOrderId));
        }
    }

    private static String normalizeIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    private void refreshStatus(SupplierPayable payable) {
        long remaining = balance(payable);
        if (remaining <= 0) {
            payable.setStatus("PAID");
            payable.setPaidAt(Instant.now());
        } else if (payable.getPaidAmountCents() > 0) {
            payable.setStatus("PARTIAL");
        } else {
            payable.setStatus("UNPAID");
        }
    }

    private int termsDays(String supplierId) {
        return supplierRepository.findById(supplierId)
                .map(s -> s.getPaymentTermsDays() > 0 ? s.getPaymentTermsDays() : DEFAULT_TERMS_DAYS)
                .orElse(DEFAULT_TERMS_DAYS);
    }

    private static long balance(SupplierPayable p) {
        return Math.max(0, p.getAmountCents() - p.getPaidAmountCents());
    }

    private boolean isOverdue(SupplierPayable p) {
        return balance(p) > 0 && p.getDueDate() != null && p.getDueDate().isBefore(LocalDate.now(ZONE));
    }

    private SupplierPayableDto toDto(SupplierPayable p) {
        long balance = balance(p);
        boolean overdue = isOverdue(p);
        int overdueDays = overdue
                ? (int) ChronoUnit.DAYS.between(p.getDueDate(), LocalDate.now(ZONE))
                : 0;
        List<SupplierPaymentDto> payments = paymentRepository.findByPayableIdOrderByCreatedAtAsc(p.getPayableId())
                .stream()
                .map(pm -> new SupplierPaymentDto(
                        pm.getPaymentId(),
                        pm.getSupplierId(),
                        pm.getPayableId(),
                        pm.getAmountCents(),
                        pm.getOperatorId(),
                        pm.getNotes(),
                        pm.getCreatedAt()))
                .toList();
        return new SupplierPayableDto(
                p.getPayableId(),
                p.getSupplierId(),
                supplierName(p.getSupplierId()),
                p.getPurchaseOrderId(),
                p.getWarehouseId(),
                warehouseName(p.getWarehouseId()),
                p.getAmountCents(),
                p.getPaidAmountCents(),
                balance,
                p.getStatus(),
                p.getDueDate(),
                overdue,
                overdueDays,
                p.getNotes(),
                p.getCreatedAt(),
                payments);
    }

    private String supplierName(String supplierId) {
        return supplierRepository.findById(supplierId)
                .map(s -> s.getSupplierName())
                .orElse(supplierId);
    }

    private String warehouseName(String warehouseId) {
        if (warehouseId == null || warehouseId.isBlank()) {
            return "—";
        }
        return warehouseRepository.findById(warehouseId)
                .map(w -> w.getWarehouseName())
                .orElse(warehouseId);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
