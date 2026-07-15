package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Franchise;
import com.aicabinet.trade.domain.FranchiseDevice;
import com.aicabinet.trade.domain.FranchiseSettlement;
import com.aicabinet.trade.mapper.FranchiseMapper;
import com.aicabinet.trade.mapper.FranchiseDeviceMapper;
import com.aicabinet.trade.mapper.FranchiseSettlementMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class FranchiseService {
    private static final Logger log = LoggerFactory.getLogger(FranchiseService.class);
    
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    
    @Autowired
    private FranchiseMapper franchiseRepository;
    
    @Autowired
    private FranchiseDeviceMapper franchiseDeviceRepository;
    
    @Autowired
    private FranchiseSettlementMapper settlementRepository;
    
    @Transactional
    public Franchise createFranchise(Franchise franchise) {
        franchise.setFranchiseCode(generateFranchiseCode());
        franchise.setStatus(STATUS_ACTIVE);
        franchise.setCreatedAt(Instant.now());
        return franchiseRepository.save(franchise);
    }
    
    @Transactional
    public Franchise updateFranchise(Long franchiseId, Franchise updates) {
        Franchise franchise = franchiseRepository.findById(franchiseId).orElse(null);
        if (franchise == null) {
            return null;
        }
        
        if (updates.getFranchiseName() != null) {
            franchise.setFranchiseName(updates.getFranchiseName());
        }
        if (updates.getContactName() != null) {
            franchise.setContactName(updates.getContactName());
        }
        if (updates.getContactPhone() != null) {
            franchise.setContactPhone(updates.getContactPhone());
        }
        if (updates.getCommissionRate() != null) {
            franchise.setCommissionRate(updates.getCommissionRate());
        }
        
        franchise.setUpdatedAt(Instant.now());
        return franchiseRepository.save(franchise);
    }
    
    @Transactional
    public boolean assignDevice(Long franchiseId, String deviceId) {
        FranchiseDevice existing = franchiseDeviceRepository
            .findByDeviceIdAndStatus(deviceId, STATUS_ACTIVE)
            .orElse(null);
        
        if (existing != null) {
            log.warn("Device already assigned: {}", deviceId);
            return false;
        }
        
        FranchiseDevice fd = new FranchiseDevice();
        fd.setFranchiseId(franchiseId);
        fd.setDeviceId(deviceId);
        fd.setStatus(STATUS_ACTIVE);
        fd.setAssignedAt(Instant.now());
        franchiseDeviceRepository.save(fd);
        
        log.info("Device assigned: franchiseId={}, deviceId={}", franchiseId, deviceId);
        return true;
    }
    
    @Transactional
    public boolean unassignDevice(String deviceId) {
        FranchiseDevice fd = franchiseDeviceRepository
            .findByDeviceIdAndStatus(deviceId, STATUS_ACTIVE)
            .orElse(null);
        
        if (fd == null) {
            return false;
        }
        
        fd.setStatus(STATUS_INACTIVE);
        fd.setUnassignedAt(Instant.now());
        franchiseDeviceRepository.save(fd);
        
        log.info("Device unassigned: deviceId={}", deviceId);
        return true;
    }
    
    public List<FranchiseDevice> getFranchiseDevices(Long franchiseId) {
        return franchiseDeviceRepository.findByFranchiseId(franchiseId);
    }
    
    @Transactional
    public FranchiseSettlement createSettlement(Long franchiseId, String period) {
        Franchise franchise = franchiseRepository.findById(franchiseId).orElse(null);
        if (franchise == null) {
            return null;
        }
        
        FranchiseSettlement existing = settlementRepository
            .findByFranchiseIdAndSettlementPeriod(franchiseId, period)
            .orElse(null);
        
        if (existing != null) {
            log.warn("Settlement already exists: franchiseId={}, period={}", franchiseId, period);
            return null;
        }
        
        BigDecimal grossRevenue = calculateGrossRevenue(franchiseId, period);
        BigDecimal commissionRate = franchise.getCommissionRate() != null 
            ? franchise.getCommissionRate() : BigDecimal.ZERO;
        BigDecimal commissionAmount = grossRevenue.multiply(commissionRate)
            .divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
        BigDecimal netAmount = grossRevenue.subtract(commissionAmount);
        
        FranchiseSettlement settlement = new FranchiseSettlement();
        settlement.setFranchiseId(franchiseId);
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
        FranchiseSettlement settlement = settlementRepository.findById(settlementId).orElse(null);
        if (settlement == null) {
            return false;
        }
        
        settlement.setStatus("SETTLED");
        settlement.setSettledAt(Instant.now());
        settlementRepository.save(settlement);
        
        log.info("Settlement confirmed: settlementId={}", settlementId);
        return true;
    }
    
    private BigDecimal calculateGrossRevenue(Long franchiseId, String period) {
        return BigDecimal.ZERO;
    }
    
    private String generateFranchiseCode() {
        return "FR" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    public Optional<Franchise> getFranchise(Long franchiseId) {
        return franchiseRepository.findById(franchiseId);
    }
    
    public List<Franchise> getAllActive() {
        return franchiseRepository.findByStatus(STATUS_ACTIVE);
    }
}
