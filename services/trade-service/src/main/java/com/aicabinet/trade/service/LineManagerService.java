package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.domain.LineManagerSettlement;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerSettlementMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class LineManagerService {
    private static final Logger log = LoggerFactory.getLogger(LineManagerService.class);
    
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    
    @Autowired
    private LineManagerMapper lineManagerRepository;
    
    @Autowired
    private LineDeviceMapper lineDeviceRepository;
    
    @Autowired
    private LineManagerSettlementMapper settlementRepository;
    
    @Transactional
    public LineManager createManager(LineManager manager) {
        manager.setStatus(STATUS_ACTIVE);
        manager.setCreatedAt(Instant.now());
        return lineManagerRepository.save(manager);
    }
    
    @Transactional
    public LineManager updateManager(Long managerId, LineManager updates) {
        LineManager manager = lineManagerRepository.findById(managerId).orElse(null);
        if (manager == null) {
            return null;
        }
        
        if (updates.getManagerName() != null) {
            manager.setManagerName(updates.getManagerName());
        }
        if (updates.getPhone() != null) {
            manager.setPhone(updates.getPhone());
        }
        if (updates.getCommissionRate() != null) {
            manager.setCommissionRate(updates.getCommissionRate());
        }
        
        manager.setUpdatedAt(Instant.now());
        return lineManagerRepository.save(manager);
    }
    
    @Transactional
    public boolean assignDevice(Long managerId, String deviceId) {
        LineDevice existing = lineDeviceRepository
            .findByDeviceIdAndStatus(deviceId, STATUS_ACTIVE)
            .orElse(null);
        
        if (existing != null) {
            log.warn("Device already assigned to a line manager: {}", deviceId);
            return false;
        }
        
        LineDevice ld = new LineDevice();
        ld.setManagerId(managerId);
        ld.setDeviceId(deviceId);
        ld.setStatus(STATUS_ACTIVE);
        ld.setAssignedAt(Instant.now());
        lineDeviceRepository.save(ld);
        
        log.info("Device assigned to line manager: managerId={}, deviceId={}", managerId, deviceId);
        return true;
    }
    
    @Transactional
    public boolean unassignDevice(String deviceId) {
        LineDevice ld = lineDeviceRepository
            .findByDeviceIdAndStatus(deviceId, STATUS_ACTIVE)
            .orElse(null);
        
        if (ld == null) {
            return false;
        }
        
        ld.setStatus(STATUS_INACTIVE);
        ld.setUnassignedAt(Instant.now());
        lineDeviceRepository.save(ld);
        
        log.info("Device unassigned from line manager: deviceId={}", deviceId);
        return true;
    }
    
    public List<LineDevice> getManagerDevices(Long managerId) {
        return lineDeviceRepository.findByManagerId(managerId);
    }
    
    @Transactional
    public LineManagerSettlement createSettlement(Long managerId, String period) {
        LineManager manager = lineManagerRepository.findById(managerId).orElse(null);
        if (manager == null) {
            return null;
        }
        
        LineManagerSettlement existing = settlementRepository
            .findByManagerIdAndSettlementPeriod(managerId, period)
            .orElse(null);
        
        if (existing != null) {
            log.warn("Settlement already exists: managerId={}, period={}", managerId, period);
            return null;
        }
        
        BigDecimal grossRevenue = calculateGrossRevenue(managerId, period);
        BigDecimal commissionRate = manager.getCommissionRate() != null 
            ? manager.getCommissionRate() : BigDecimal.ZERO;
        BigDecimal commissionAmount = grossRevenue.multiply(commissionRate)
            .divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
        BigDecimal netAmount = grossRevenue.subtract(commissionAmount);
        
        LineManagerSettlement settlement = new LineManagerSettlement();
        settlement.setManagerId(managerId);
        settlement.setSettlementPeriod(period);
        settlement.setGrossRevenue(grossRevenue);
        settlement.setCommissionAmount(commissionAmount);
        settlement.setNetAmount(netAmount);
        settlement.setStatus("PENDING");
        settlement.setCreatedAt(Instant.now());
        
        return settlementRepository.save(settlement);
    }
    
    @Transactional
    public boolean confirmSettlement(Long settlementId) {
        LineManagerSettlement settlement = settlementRepository.findById(settlementId).orElse(null);
        if (settlement == null) {
            return false;
        }
        
        settlement.setStatus("SETTLED");
        settlement.setSettledAt(Instant.now());
        settlementRepository.save(settlement);
        
        log.info("Line manager settlement confirmed: settlementId={}", settlementId);
        return true;
    }
    
    private BigDecimal calculateGrossRevenue(Long managerId, String period) {
        return BigDecimal.ZERO;
    }
    
    public Optional<LineManager> getManager(Long managerId) {
        return lineManagerRepository.findById(managerId);
    }
    
    public List<LineManager> getManagersByFranchise(Long franchiseId) {
        return lineManagerRepository.findByFranchiseId(franchiseId);
    }
}
