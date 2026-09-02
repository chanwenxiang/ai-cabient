package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.DeviceDataFeeBillDto;
import com.aicabinet.common.dto.GenerateMonthlyFeeBillsRequest;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.DeviceDataFeeBill;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceDataFeeBillMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 柜机流量费月结：按 device.dataFeeCents 出账；人工标记已付，不自动扣款。
 */
@Service
public class DeviceDataFeeBillService {

    private static final Logger log = LoggerFactory.getLogger(DeviceDataFeeBillService.class);

    private final DeviceDataFeeBillMapper billMapper;
    private final DeviceInfoMapper deviceMapper;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    private final FeeBillMonthResolver monthResolver;

    public DeviceDataFeeBillService(DeviceDataFeeBillMapper billMapper,
                                    DeviceInfoMapper deviceMapper,
                                    PermissionService permissionService,
                                    AdminAuditService auditService,
                                    DistributedLockService distributedLockService,
                                    FeeBillMonthResolver monthResolver) {
        this.billMapper = billMapper;
        this.deviceMapper = deviceMapper;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.monthResolver = monthResolver;
    }

    @Transactional(readOnly = true)
    public PageResult<DeviceDataFeeBillDto> list(Long operatorId, String billMonth, String status,
                                                 String deviceId, int page, int size) {
        permissionService.requirePermission(operatorId, "ops:org:list");
        int p = monthResolver.clampPage(page);
        int s = monthResolver.clampPageSize(size);
        var result = billMapper.searchPage(blankToNull(billMonth), blankToNull(status), blankToNull(deviceId), p, s);
        return new PageResult<>(result.getRecords().stream().map(this::toDto).toList(), p, s, result.getTotal());
    }

    @Transactional
    public DeviceDataFeeBillDto generateForDevice(Long operatorId, String deviceId,
                                                  GenerateMonthlyFeeBillsRequest request) {
        String month = monthResolver.resolve(request == null ? null : request.billMonth());
        return runWithLock(deviceId, month, () -> doGenerateOne(operatorId, deviceId, month));
    }

    @Transactional
    public List<DeviceDataFeeBillDto> generateForAllCharged(Long operatorId, GenerateMonthlyFeeBillsRequest request) {
        permissionService.requirePermission(operatorId, "ops:org:edit");
        return generateAllInternal(operatorId, monthResolver.resolve(request == null ? null : request.billMonth()));
    }

    /** 定时任务入口：无操作员鉴权。 */
    @Transactional
    public List<DeviceDataFeeBillDto> autoGenerate(String billMonthOrBlank) {
        return generateAllInternal(null, monthResolver.resolve(billMonthOrBlank));
    }

    private List<DeviceDataFeeBillDto> generateAllInternal(Long operatorId, String month) {
        List<DeviceDataFeeBillDto> created = new ArrayList<>();
        for (DeviceInfo device : deviceMapper.findAllOrderByDeviceIdAsc()) {
            long fee = device.getDataFeeCents() == null ? 0L : device.getDataFeeCents();
            if (fee <= 0) {
                continue;
            }
            if (billMapper.countNonVoidByDeviceAndMonth(device.getDeviceId(), month) > 0) {
                continue;
            }
            created.add(runWithLock(device.getDeviceId(), month,
                    () -> doGenerateOne(operatorId, device.getDeviceId(), month)));
        }
        log.info("data fee bills generated month={} count={} operatorId={}", month, created.size(), operatorId);
        return created;
    }

    @Transactional
    public DeviceDataFeeBillDto markPaid(Long operatorId, Long billId) {
        permissionService.requirePermission(operatorId, "ops:org:edit");
        DeviceDataFeeBill bill = requireBill(billId);
        if (CabinetConstants.FEE_BILL_STATUS_VOID.equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已作废账单不可标记已付");
        }
        if (CabinetConstants.FEE_BILL_STATUS_PAID.equals(bill.getStatus())) {
            return toDto(bill);
        }
        Instant now = Instant.now();
        bill.setStatus(CabinetConstants.FEE_BILL_STATUS_PAID);
        bill.setPaidAt(now);
        bill.setUpdatedAt(now);
        billMapper.updateById(bill);
        auditService.appendLog(operatorId, "DEVICE_DATA_FEE_PAID", "BILL", String.valueOf(billId),
                "month=" + bill.getBillMonth() + " amount=" + bill.getAmountCents());
        return toDto(bill);
    }

    @Transactional
    public DeviceDataFeeBillDto voidBill(Long operatorId, Long billId) {
        permissionService.requirePermission(operatorId, "ops:org:edit");
        DeviceDataFeeBill bill = requireBill(billId);
        if (CabinetConstants.FEE_BILL_STATUS_PAID.equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已付账单不可作废");
        }
        if (CabinetConstants.FEE_BILL_STATUS_VOID.equals(bill.getStatus())) {
            return toDto(bill);
        }
        bill.setStatus(CabinetConstants.FEE_BILL_STATUS_VOID);
        bill.setUpdatedAt(Instant.now());
        billMapper.updateById(bill);
        auditService.appendLog(operatorId, "DEVICE_DATA_FEE_VOID", "BILL", String.valueOf(billId),
                "month=" + bill.getBillMonth());
        return toDto(bill);
    }

    private DeviceDataFeeBillDto doGenerateOne(Long operatorId, String deviceId, String month) {
        if (operatorId != null) {
            permissionService.requirePermission(operatorId, "ops:org:edit");
        }
        DeviceInfo device = deviceMapper.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        if (billMapper.countNonVoidByDeviceAndMonth(deviceId, month) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该柜机 " + month + " 流量费已出账");
        }
        long fee = device.getDataFeeCents() == null ? 0L : device.getDataFeeCents();
        if (fee <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该柜机未配置流量费或为 0，无法出账");
        }
        if (fee > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "流量费金额过大");
        }
        Instant now = Instant.now();
        DeviceDataFeeBill bill = new DeviceDataFeeBill();
        bill.setDeviceId(device.getDeviceId());
        bill.setDeviceName(device.getDeviceName());
        bill.setMerchantId(device.getMerchantId());
        bill.setBillMonth(month);
        bill.setAmountCents((int) fee);
        bill.setStatus(CabinetConstants.FEE_BILL_STATUS_UNPAID);
        bill.setRemark("由柜机流量费(分/月)快照生成；标记已付不触发自动扣款");
        bill.setCreatedAt(now);
        bill.setUpdatedAt(now);
        billMapper.insert(bill);
        if (operatorId != null) {
            auditService.appendLog(operatorId, "DEVICE_DATA_FEE_GEN", "DEVICE", deviceId,
                    "month=" + month + " amount=" + fee);
        }
        log.info("data fee bill generated deviceId={} month={} amountCents={} operatorId={}",
                deviceId, month, fee, operatorId);
        return toDto(bill);
    }

    private DeviceDataFeeBill requireBill(Long billId) {
        DeviceDataFeeBill bill = billMapper.selectById(billId);
        if (bill == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "流量费账单不存在");
        }
        return bill;
    }

    private DeviceDataFeeBillDto toDto(DeviceDataFeeBill b) {
        return new DeviceDataFeeBillDto(
                b.getBillId(), b.getDeviceId(), b.getDeviceName(), b.getMerchantId(),
                b.getBillMonth(), b.getAmountCents(), b.getStatus(),
                b.getPaidAt(), b.getRemark(), b.getCreatedAt(), b.getUpdatedAt());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String billLockKey(String deviceId, String billMonth) {
        return "device-data-fee-bill:" + deviceId + ":" + billMonth;
    }

    private <T> T runWithLock(String deviceId, String billMonth, java.util.function.Supplier<T> action) {
        String key = billLockKey(deviceId, billMonth);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "流量费账单生成中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }
}
