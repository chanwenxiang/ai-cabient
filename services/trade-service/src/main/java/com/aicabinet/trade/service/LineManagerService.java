package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LineManagerDto;
import com.aicabinet.common.dto.LineWalletLedgerDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineWalletAccount;
import com.aicabinet.trade.domain.LineWalletLedger;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineWalletLedgerMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class LineManagerService {
    private static final String PERM_OPS_LINE_MANAGER_EDIT = "ops:line-manager:edit";
    private static final String PERM_OPS_LINE_MANAGER_LIST = "ops:line-manager:list";
    private static final String COMMISSIONFIXEDCENTS = "commissionFixedCents";
    private static final String COMMISSIONRATEBPS = "commissionRateBps";
    private static final String PERM_OPS_FINANCE_VIEW = "ops:finance:view";
    private static final String MANAGERNAME = "managerName";
    private static final String WXOPENID = "wxOpenid";
    private static final String ORGNAME = "orgName";
    private static final String USERID = "userId";
    private static final String PHONE = "phone";


    public static final String STATUS_ACTIVE = "ACTIVE";

    private final LineManagerMapper managerMapper;
    private final LineDeviceMapper deviceMapper;
    private final LineWalletLedgerMapper ledgerMapper;
    private final DeviceInfoMapper deviceInfoMapper;
    private final LineWalletService lineWalletService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    /** �?Spring 代理调用本类 @Transactional 方法，避免自调用失效�?*/
    private final LineManagerService self;

    public LineManagerService(LineManagerMapper managerMapper,
                              LineDeviceMapper deviceMapper,
                              LineWalletLedgerMapper ledgerMapper,
                              DeviceInfoMapper deviceInfoMapper,
                              LineWalletService lineWalletService,
                              PermissionService permissionService,
                              AdminAuditService auditService,
                              DistributedLockService distributedLockService, @Lazy LineManagerService self) {
        this.managerMapper = managerMapper;
        this.deviceMapper = deviceMapper;
        this.ledgerMapper = ledgerMapper;
        this.deviceInfoMapper = deviceInfoMapper;
        this.lineWalletService = lineWalletService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public PageResult<LineManagerDto> list(Long operatorId, String status, String keyword, int page, int size) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_LINE_MANAGER_LIST, PERM_OPS_FINANCE_VIEW);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<LineManager> q = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            q.eq(LineManager::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(LineManager::getManagerName, kw).or().like(LineManager::getPhone, kw));
        }
        q.orderByDesc(LineManager::getCreatedAt);
        Page<LineManager> result = managerMapper.selectPage(new Page<>(p + 1L, s), q);
        List<LineManagerDto> items = result.getRecords().stream().map(this::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public LineManagerDto detail(Long operatorId, long managerId) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_LINE_MANAGER_LIST, PERM_OPS_FINANCE_VIEW);
        return toDto(requireManager(managerId));
    }

    @Transactional
    public LineManagerDto create(Long operatorId, Map<String, Object> body) {
        permissionService.requirePermission(operatorId, PERM_OPS_LINE_MANAGER_EDIT);
        String name = stringVal(body.get(MANAGERNAME));
        String phone = stringVal(body.get(PHONE));
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "姓名必填");
        }
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机必填");
        }
        if (managerMapper.findByPhone(phone.trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "手机号已存在");
        }
        Instant now = Instant.now();
        LineManager manager = new LineManager();
        manager.setManagerName(name.trim());
        manager.setPhone(phone.trim());
        manager.setStatus(STATUS_ACTIVE);
        manager.setWxOpenid(trim(stringVal(body.get(WXOPENID))));
        manager.setUserId(longVal(body.get(USERID)));
        manager.setOrgName(trim(stringVal(body.get(ORGNAME))));
        manager.setCommissionRateBps(intVal(body.get(COMMISSIONRATEBPS), 200));
        manager.setCommissionFixedCents(intVal(body.get(COMMISSIONFIXEDCENTS), 0));
        manager.setCreatedAt(now);
        manager.setUpdatedAt(now);
        managerMapper.insert(manager);
        lineWalletService.ensureAccount(manager.getManagerId());
        return toDto(manager);
    }

    @Transactional
    public LineManagerDto update(Long operatorId, long managerId, Map<String, Object> body) {
        permissionService.requirePermission(operatorId, PERM_OPS_LINE_MANAGER_EDIT);
        LineManager manager = requireManager(managerId);
        applyLineManagerUpdates(manager, managerId, body);
        manager.setUpdatedAt(Instant.now());
        managerMapper.updateById(manager);
        return toDto(manager);
    }

    private void applyLineManagerUpdates(LineManager manager, long managerId, Map<String, Object> body) {
        if (body.containsKey(MANAGERNAME)) {
            String name = stringVal(body.get(MANAGERNAME));
            if (name != null && !name.isBlank()) {
                manager.setManagerName(name.trim());
            }
        }
        if (body.containsKey(PHONE)) {
            applyLineManagerPhone(manager, managerId, stringVal(body.get(PHONE)));
        }
        if (body.containsKey(WXOPENID)) {
            manager.setWxOpenid(trim(stringVal(body.get(WXOPENID))));
        }
        if (body.containsKey(USERID)) {
            manager.setUserId(longVal(body.get(USERID)));
        }
        if (body.containsKey(ORGNAME)) {
            manager.setOrgName(trim(stringVal(body.get(ORGNAME))));
        }
        if (body.containsKey(COMMISSIONRATEBPS)) {
            manager.setCommissionRateBps(intVal(body.get(COMMISSIONRATEBPS), manager.getCommissionRateBps()));
        }
        if (body.containsKey(COMMISSIONFIXEDCENTS)) {
            manager.setCommissionFixedCents(intVal(body.get(COMMISSIONFIXEDCENTS), manager.getCommissionFixedCents()));
        }
        if (body.containsKey("status")) {
            String status = stringVal(body.get("status"));
            if (status != null && !status.isBlank()) {
                manager.setStatus(status.trim().toUpperCase(Locale.ROOT));
            }
        }
    }

    private void applyLineManagerPhone(LineManager manager, long managerId, String phone) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        managerMapper.findByPhone(phone.trim()).ifPresent(existing -> {
            if (!existing.getManagerId().equals(managerId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "手机号已存在");
            }
        });
        manager.setPhone(phone.trim());
    }

    @Transactional
    public LineManagerDto bindDevice(Long operatorId, long managerId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_LINE_MANAGER_EDIT);
        requireManager(managerId);
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备编号必填");
        }
        String dev = deviceId.trim();
        if (deviceInfoMapper.selectById(dev) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存�?);
        }
        deviceMapper.findByDeviceIdAndStatus(dev, STATUS_ACTIVE).ifPresent(existing -> {
            if (!existing.getManagerId().equals(managerId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "设备已绑定其他线�?);
            }
        });
        if (deviceMapper.findByDeviceIdAndStatus(dev, STATUS_ACTIVE).isEmpty()) {
            LineDevice ld = new LineDevice();
            ld.setManagerId(managerId);
            ld.setDeviceId(dev);
            ld.setStatus(STATUS_ACTIVE);
            ld.setAssignedAt(Instant.now());
            deviceMapper.insert(ld);
        }
        return toDto(requireManager(managerId));
    }

    @Transactional
    public LineManagerDto unbindDevice(Long operatorId, long managerId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_LINE_MANAGER_EDIT);
        requireManager(managerId);
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备编号必填");
        }
        LineDevice ld = deviceMapper.findByDeviceIdAndStatus(deviceId.trim(), STATUS_ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备未绑�?));
        if (!ld.getManagerId().equals(managerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备不属于该线长");
        }
        ld.setStatus("INACTIVE");
        ld.setUnassignedAt(Instant.now());
        deviceMapper.updateById(ld);
        return toDto(requireManager(managerId));
    }

    @Transactional
    public LineManagerDto adjust(Long operatorId, long managerId, long amountCents, String remark) {
        permissionService.requirePermission(operatorId, PERM_OPS_LINE_MANAGER_EDIT);
        requireManager(managerId);
        if (amountCents == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "调账金额不能�?0");
        }
        return runWithLineWalletLock(managerId, () -> {
            String refId = "ADJ-" + operatorId + "-" + System.currentTimeMillis();
            if (amountCents > 0) {
                lineWalletService.credit(managerId, amountCents, "ADJUST", "OPS_ADJUST", refId, remark);
            } else {
                lineWalletService.debit(managerId, -amountCents, "ADJUST", "OPS_ADJUST", refId, remark);
            }
            auditService.appendLog(operatorId, "LINE_MANAGER_ADJUST", "LINE_MANAGER",
                    String.valueOf(managerId), "金额(�?=" + amountCents + "；备�?" + remark);
            return toDto(requireManager(managerId));
        });
    }

    @Transactional(readOnly = true)
    public List<LineWalletLedgerDto> ledgers(Long operatorId, long managerId, int limit) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_LINE_MANAGER_LIST, PERM_OPS_FINANCE_VIEW);
        return self.ledgersForManager(managerId, limit);
    }

    @Transactional(readOnly = true)
    public List<LineWalletLedgerDto> ledgersForManager(long managerId, int limit) {
        requireManager(managerId);
        int lim = Math.min(Math.max(limit, 1), 200);
        return ledgerMapper.findByManagerIdOrderByCreatedAtDesc(managerId, lim).stream()
                .map(this::toLedgerDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<LineManager> findByUserId(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return managerMapper.findByUserId(userId);
    }

    public LineManagerDto toDto(LineManager manager) {
        LineWalletAccount account = lineWalletService.ensureAccount(manager.getManagerId());
        List<String> deviceIds = deviceMapper.findActiveByManagerId(manager.getManagerId()).stream()
                .map(LineDevice::getDeviceId)
                .toList();
        return new LineManagerDto(
                manager.getManagerId(),
                manager.getManagerName(),
                manager.getPhone(),
                manager.getStatus(),
                manager.getWxOpenid(),
                manager.getUserId(),
                manager.getOrgName(),
                manager.getCommissionRateBps(),
                manager.getCommissionFixedCents(),
                value(account.getBalanceCents()),
                value(account.getFrozenCents()),
                deviceIds,
                manager.getCreatedAt(),
                manager.getUpdatedAt()
        );
    }

    LineWalletLedgerDto toLedgerDto(LineWalletLedger ledger) {
        return new LineWalletLedgerDto(
                ledger.getLedgerId(),
                ledger.getManagerId(),
                ledger.getEntryType(),
                ledger.getAmountCents(),
                ledger.getBalanceAfter(),
                ledger.getFrozenAfter(),
                ledger.getRefType(),
                ledger.getRefId(),
                ledger.getRemark(),
                ledger.getCreatedAt()
        );
    }

    LineManager requireManager(long managerId) {
        return managerMapper.findById(managerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "线长不存�?));
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Long longVal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : Long.parseLong(s);
    }

    private static int intVal(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? defaultValue : Integer.parseInt(s);
    }

    private <T> T runWithLineWalletLock(long managerId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(LineWithdrawService.lineWalletLockKey(managerId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "钱包处理中，请稍后重�?);
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(LineWithdrawService.lineWalletLockKey(managerId));
        }
    }
}
