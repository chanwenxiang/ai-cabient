package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.WarehouseInTransit;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.mapper.WarehouseInTransitMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InTransitService {

    private static final Logger log = LoggerFactory.getLogger(InTransitService.class);
    private static final String STATUS_IN_TRANSIT = "IN_TRANSIT";
    private static final String STATUS_RECEIVED = "RECEIVED";

    private final WarehouseInTransitMapper transitRepository;

    public InTransitService(WarehouseInTransitMapper transitRepository) {
        this.transitRepository = transitRepository;
    }

    @Transactional
    public void recordFromOutbound(Long outboundId, List<WarehouseOutboundLine> lines) {
        for (WarehouseOutboundLine line : lines) {
            if (line.getDeviceId() == null || line.getDeviceId().isBlank() || line.getQuantity() <= 0) {
                continue;
            }
            WarehouseInTransit transit = new WarehouseInTransit();
            transit.setOutboundId(outboundId);
            transit.setDeviceId(line.getDeviceId().trim());
            transit.setSkuId(line.getSkuId());
            transit.setBatchNo(line.getBatchNo());
            transit.setQuantity(line.getQuantity());
            transit.setStatus(STATUS_IN_TRANSIT);
            transitRepository.save(transit);
        }
        log.info("in-transit recorded outboundId={} lines={}", outboundId, lines.size());
    }

    @Transactional
    public int receiveForDevice(Long outboundId, String deviceId) {
        if (outboundId == null || deviceId == null || deviceId.isBlank()) {
            return 0;
        }
        List<WarehouseInTransit> rows = transitRepository.findByOutboundIdAndDeviceIdAndStatus(
                outboundId, deviceId.trim(), STATUS_IN_TRANSIT);
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
        List<WarehouseInTransit> rows = transitRepository.findByOutboundIdAndDeviceIdAndStatus(
                outboundId, deviceId.trim(), STATUS_IN_TRANSIT);
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
        if (deviceId == null || deviceId.isBlank()) {
            return transitRepository.findByStatusOrderByCreatedAtAsc(STATUS_IN_TRANSIT).stream()
                    .map(this::toDto)
                    .toList();
        }
        return transitRepository.findByDeviceIdAndStatus(deviceId.trim(), STATUS_IN_TRANSIT).stream()
                .map(this::toDto)
                .toList();
    }

    private com.aicabinet.common.dto.WarehouseInTransitDto toDto(WarehouseInTransit row) {
        return new com.aicabinet.common.dto.WarehouseInTransitDto(
                row.getTransitId(), row.getOutboundId(), row.getDeviceId(), row.getSkuId(),
                row.getBatchNo(), row.getQuantity(), row.getStatus(),
                row.getCreatedAt(), row.getReceivedAt()
        );
    }
}
