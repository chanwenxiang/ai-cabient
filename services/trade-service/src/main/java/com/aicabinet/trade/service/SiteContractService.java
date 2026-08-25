package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SiteContractDto;
import com.aicabinet.common.dto.UpsertSiteContractRequest;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.SiteContract;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.SiteContractMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 点位场地合同：每个柜机一份合同，按到期时间自动标记 EXPIRING / EXPIRED。
 */
@Service
public class SiteContractService {

    private final SiteContractMapper contractRepository;
    private final DeviceInfoMapper deviceRepository;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;

    public SiteContractService(SiteContractMapper contractRepository,
                               DeviceInfoMapper deviceRepository,
                               PermissionService permissionService,
                               AdminAuditService auditService,
                               DistributedLockService distributedLockService) {
        this.contractRepository = contractRepository;
        this.deviceRepository = deviceRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
    }

    @Transactional(readOnly = true)
    public List<SiteContractDto> list(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:device:list");
        Map<String, String> deviceNames = new HashMap<>();
        for (DeviceInfo device : deviceRepository.findAllOrderByDeviceIdAsc()) {
            deviceNames.put(device.getDeviceId(),
                    device.getDeviceName() != null ? device.getDeviceName() : device.getDeviceId());
        }
        return contractRepository.findAllOrderByUpdatedDesc().stream()
                .map(c -> toDto(c, deviceNames.getOrDefault(c.getDeviceId(), c.getDeviceId())))
                .toList();
    }

    @Transactional
    public SiteContractDto upsert(Long operatorId, String deviceId, UpsertSiteContractRequest request) {
        String id = deviceId.trim().toUpperCase();
        return runWithContractLock(id, () -> doUpsert(operatorId, id, request));
    }

    private SiteContractDto doUpsert(Long operatorId, String id, UpsertSiteContractRequest request) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        DeviceInfo device = deviceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在: " + id));
        if (request.startDate() != null && request.endDate() != null
                && request.endDate().isBefore(request.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合同结束日期不能早于开始日期");
        }
        SiteContract contract = contractRepository.findByDeviceIdForUpdate(id).orElseGet(SiteContract::new);
        boolean created = contract.getContractId() == null;
        contract.setDeviceId(id);
        contract.setSiteName(request.siteName().trim());
        contract.setAddress(request.address());
        contract.setLandlordName(request.landlordName());
        contract.setLandlordPhone(request.landlordPhone());
        contract.setStartDate(request.startDate());
        contract.setEndDate(request.endDate());
        contract.setMonthlyFeeCents(Math.max(0, request.monthlyFeeCents()));
        contract.setStatus(statusFor(request.endDate()));
        contract.setRemark(request.remark());
        contract.setUpdatedAt(Instant.now());
        if (created) {
            contract.setCreatedAt(Instant.now());
            contractRepository.insert(contract);
        } else {
            contractRepository.updateById(contract);
        }
        auditService.record(operatorId, created ? "SITE_CONTRACT_CREATE" : "SITE_CONTRACT_UPDATE",
                "SITE_CONTRACT", String.valueOf(contract.getContractId()),
                "device=" + id + " site=" + contract.getSiteName());
        String deviceName = device.getDeviceName() != null ? device.getDeviceName() : id;
        return toDto(contract, deviceName);
    }

    @Transactional
    public void delete(Long operatorId, Long contractId) {
        permissionService.requireAnyPermission(operatorId, "ops:org:edit", "ops:device:edit");
        SiteContract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "合同不存在"));
        contractRepository.deleteById(contractId);
        auditService.record(operatorId, "SITE_CONTRACT_DELETE", "SITE_CONTRACT",
                String.valueOf(contractId), "device=" + contract.getDeviceId());
    }

    static String statusFor(LocalDate endDate) {
        if (endDate == null) {
            return "ACTIVE";
        }
        LocalDate today = LocalDate.now();
        if (endDate.isBefore(today)) {
            return "EXPIRED";
        }
        if (!endDate.isAfter(today.plusDays(30))) {
            return "EXPIRING";
        }
        return "ACTIVE";
    }

    static String contractLockKey(String deviceId) {
        return "device:contract:" + deviceId;
    }

    private <T> T runWithContractLock(String deviceId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(contractLockKey(deviceId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "场地合同处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(contractLockKey(deviceId));
        }
    }

    private SiteContractDto toDto(SiteContract contract, String deviceName) {
        return new SiteContractDto(
                contract.getContractId(),
                contract.getDeviceId(),
                deviceName,
                contract.getSiteName(),
                contract.getAddress(),
                contract.getLandlordName(),
                contract.getLandlordPhone(),
                contract.getStartDate(),
                contract.getEndDate(),
                contract.getMonthlyFeeCents(),
                contract.getStatus(),
                contract.getRemark(),
                contract.getUpdatedAt());
    }
}
