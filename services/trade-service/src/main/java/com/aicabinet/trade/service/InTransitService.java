package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.WarehouseInTransit;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.mapper.WarehouseInTransitMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class InTransitService {

    private static final Logger log = LoggerFactory.getLogger(InTransitService.class);
    private static final String STATUS_IN_TRANSIT = "IN_TRANSIT";
    private static final String STATUS_RECEIVED = "RECEIVED";

    private final WarehouseInTransitMapper transitRepository;
    private final DistributedLockService distributedLockService;
    private final DisplaySnapshotHelper displaySnapshotHelper;
    private final InTransitService self;

    public InTransitService(WarehouseInTransitMapper transitRepository,
                            DistributedLockService distributedLockService,
                            DisplaySnapshotHelper displaySnapshotHelper,
                            @Lazy InTransitService self) {
        this.transitRepository = transitRepository;
        this.distributedLockService = distributedLockService;
        this.displaySnapshotHelper = displaySnapshotHelper;
        this.self = self;
    }

    @Transactional
    public void recordFromOutbound(Long outboundId, List<WarehouseOutboundLine> lines) {
        if (outboundId == null || lines == null || lines.isEmpty()) {
            return;
        }
        Set<String> deviceIds = new HashSet<>();
        for (WarehouseOutboundLine line : lines) {
            if (line.getDeviceId() != null && !line.getDeviceId().isBlank() && line.getQuantity() > 0) {
                deviceIds.add(line.getDeviceId().trim());
            }
        }
        for (String deviceId : deviceIds) {
            runWithInTransitLock(outboundId, deviceId, () -> {
                doRecordFromOutboundForDevice(outboundId, deviceId, lines);
                return null;
            });
        }
    }

    private void doRecordFromOutboundForDevice(Long outboundId, String deviceId, List<WarehouseOutboundLine> lines) {
        for (WarehouseOutboundLine line : lines) {
            if (line.getDeviceId() != null && !line.getDeviceId().isBlank()
                    && line.getQuantity() > 0
                    && deviceId.equals(line.getDeviceId().trim())) {
                WarehouseInTransit transit = new WarehouseInTransit();
                transit.setOutboundId(outboundId);
                transit.setDeviceId(deviceId);
                transit.setSkuId(line.getSkuId());
                transit.setBatchNo(line.getBatchNo());
                transit.setQuantity(line.getQuantity());
                transit.setStatus(STATUS_IN_TRANSIT);
                transitRepository.save(transit);
            }
        }
        log.info("in-transit recorded outboundId={} deviceId={}", outboundId, deviceId);
    }

    @Transactional
    public int receiveForDevice(Long outboundId, String deviceId) {
        if (outboundId == null || deviceId == null || deviceId.isBlank()) {
            return 0;
        }
        return runWithInTransitLock(outboundId, deviceId.trim(),
                () -> doReceiveForDevice(outboundId, deviceId.trim()));
    }

    private int doReceiveForDevice(Long outboundId, String deviceId) {
        List<WarehouseInTransit> rows = transitRepository.findByOutboundIdAndDeviceIdAndStatusForUpdate(
                outboundId, deviceId, STATUS_IN_TRANSIT);
        Instant now = Instant.now();
        for (WarehouseInTransit row : rows) {
            row.setStatus(STATUS_RECEIVED);
            row.setReceivedAt(now);
            transitRepository.save(row);
        }
        if (!rows.isEmpty()) {
            log.info("in-transit received outboundId={} deviceId={} rows={}", outboundId, deviceId, rows.size());
        }
        return rows.size();
    }

    @Transactional(readOnly = true)
    public boolean hasOpenForDevice(Long outboundId, String deviceId) {
        if (outboundId == null || deviceId == null || deviceId.isBlank()) {
            return false;
        }
        return transitRepository.existsByOutboundIdAndDeviceIdAndStatus(
                outboundId, deviceId.trim(), STATUS_IN_TRANSIT);
    }

    /** 取消未签收的在途行（空任务/未签到清理用），不回写柜机库存。 */
    @Transactional
    public int cancelOpenForDevice(Long outboundId, String deviceId) {
        if (outboundId == null || deviceId == null || deviceId.isBlank()) {
            return 0;
        }
        return runWithInTransitLock(outboundId, deviceId.trim(),
                () -> doCancelOpenForDevice(outboundId, deviceId.trim()));
    }

    private int doCancelOpenForDevice(Long outboundId, String deviceId) {
        List<WarehouseInTransit> rows = transitRepository.findByOutboundIdAndDeviceIdAndStatusForUpdate(
                outboundId, deviceId, STATUS_IN_TRANSIT);
        for (WarehouseInTransit row : rows) {
            row.setStatus("CANCELLED");
            transitRepository.save(row);
        }
        if (!rows.isEmpty()) {
            log.info("in-transit cancelled outboundId={} deviceId={} rows={}", outboundId, deviceId, rows.size());
        }
        return rows.size();
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> qtyBySkuForDevice(String deviceId) {
        Map<String, Integer> bySku = new HashMap<>();
        if (deviceId == null || deviceId.isBlank()) {
            return bySku;
        }
        for (WarehouseInTransit row : transitRepository.findByDeviceIdAndStatus(deviceId.trim(), STATUS_IN_TRANSIT)) {
            bySku.merge(row.getSkuId(), row.getQuantity(), Integer::sum);
        }
        return bySku;
    }

    @Transactional(readOnly = true)
    public List<com.aicabinet.common.dto.WarehouseInTransitDto> listInTransit(String deviceId) {
        return self.listInTransitPage(deviceId, 0, 500).items();
    }

    @Transactional(readOnly = true)
    public PageResult<com.aicabinet.common.dto.WarehouseInTransitDto> listInTransitPage(
            String deviceId, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = transitRepository.searchPage(deviceId, STATUS_IN_TRANSIT, p, s);
        List<com.aicabinet.common.dto.WarehouseInTransitDto> items = result.getRecords().stream()
                .map(this::toDto)
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    static String inTransitLockKey(Long outboundId, String deviceId) {
        return "in-transit:" + outboundId + ":" + deviceId;
    }

    private <T> T runWithInTransitLock(Long outboundId, String deviceId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(inTransitLockKey(outboundId, deviceId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "在途库存处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(inTransitLockKey(outboundId, deviceId));
        }
    }

    private com.aicabinet.common.dto.WarehouseInTransitDto toDto(WarehouseInTransit row) {
        return new com.aicabinet.common.dto.WarehouseInTransitDto(
                row.getTransitId(), row.getOutboundId(), row.getDeviceId(), row.getSkuId(),
                row.getBatchNo(), row.getQuantity(), row.getStatus(),
                row.getCreatedAt(), row.getReceivedAt(),
                displaySnapshotHelper.resolveDeviceName(row.getDeviceId())
        );
    }
}
