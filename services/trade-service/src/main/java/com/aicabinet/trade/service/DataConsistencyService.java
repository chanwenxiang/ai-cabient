package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DataChangeLog;
import com.aicabinet.trade.domain.DataConsistencyRecord;
import com.aicabinet.trade.repository.DataChangeLogRepository;
import com.aicabinet.trade.repository.DataConsistencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class DataConsistencyService {
    private static final Logger log = LoggerFactory.getLogger(DataConsistencyService.class);
    
    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_FIXED = "FIXED";
    
    @Autowired
    private DataChangeLogRepository changeLogRepository;
    
    @Autowired
    private DataConsistencyRecordRepository consistencyRepository;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    public void logChange(String tableName, String recordId, String operation, 
                          Object oldValue, Object newValue, String changedBy) {
        try {
            DataChangeLog log = new DataChangeLog();
            log.setTableName(tableName);
            log.setRecordId(recordId);
            log.setOperation(operation);
            log.setOldValue(oldValue != null ? objectMapper.writeValueAsString(oldValue) : null);
            log.setNewValue(newValue != null ? objectMapper.writeValueAsString(newValue) : null);
            log.setChangedBy(changedBy);
            changeLogRepository.save(log);
        } catch (Exception e) {
            this.log.error("Failed to log data change", e);
        }
    }
    
    @Scheduled(fixedDelay = 300000)
    public void performConsistencyCheck() {
        log.info("Starting data consistency check");
        
        checkOrderConsistency();
        checkPaymentConsistency();
        checkInventoryConsistency();
    }
    
    private void checkOrderConsistency() {
        String sql = "SELECT o.order_id, o.total_amount, SUM(ol.line_total) as calculated_total " +
                     "FROM cabinet_order o LEFT JOIN cabinet_order_line ol ON o.order_id = ol.order_id " +
                     "WHERE o.status = 'PAID' GROUP BY o.order_id, o.total_amount " +
                     "HAVING o.total_amount != SUM(ol.line_total)";
        
        List<Map<String, Object>> mismatches = jdbcTemplate.queryForList(sql);
        
        for (Map<String, Object> row : mismatches) {
            recordInconsistency("ORDER_AMOUNT", "cabinet_order", 
                row.get("order_id").toString(),
                row.get("total_amount").toString(),
                row.get("calculated_total").toString());
        }
    }
    
    private void checkPaymentConsistency() {
        String sql = "SELECT po.order_id, po.amount, o.paid_amount " +
                     "FROM payment_operation po " +
                     "JOIN cabinet_order o ON po.order_id = o.order_id " +
                     "WHERE po.status = 'SUCCESS' AND po.amount != o.paid_amount";
        
        List<Map<String, Object>> mismatches = jdbcTemplate.queryForList(sql);
        
        for (Map<String, Object> row : mismatches) {
            recordInconsistency("PAYMENT_AMOUNT", "payment_operation",
                row.get("order_id").toString(),
                row.get("paid_amount").toString(),
                row.get("amount").toString());
        }
    }
    
    private void checkInventoryConsistency() {
        String sql = "SELECT dsi.device_id, dsi.sku_id, dsi.quantity, " +
                     "COALESCE(SUM(dsl.slot_qty), 0) as slot_total " +
                     "FROM device_sku_inventory dsi " +
                     "LEFT JOIN device_slot dsl ON dsi.device_id = dsl.device_id " +
                     "AND dsi.sku_id = dsl.sku_id " +
                     "GROUP BY dsi.device_id, dsi.sku_id, dsi.quantity " +
                     "HAVING dsi.quantity != COALESCE(SUM(dsl.slot_qty), 0)";
        
        List<Map<String, Object>> mismatches = jdbcTemplate.queryForList(sql);
        
        for (Map<String, Object> row : mismatches) {
            recordInconsistency("INVENTORY_MISMATCH", "device_sku_inventory",
                row.get("device_id") + "_" + row.get("sku_id"),
                row.get("quantity").toString(),
                row.get("slot_total").toString());
        }
    }
    
    private void recordInconsistency(String checkType, String tableName, 
                                     String checkKey, String expected, String actual) {
        DataConsistencyRecord record = new DataConsistencyRecord();
        record.setCheckType(checkType);
        record.setTableName(tableName);
        record.setCheckKey(checkKey);
        record.setExpectedValue(expected);
        record.setActualValue(actual);
        record.setStatus(STATUS_FAIL);
        consistencyRepository.save(record);
        
        log.warn("Data inconsistency found: type={}, key={}, expected={}, actual={}", 
            checkType, checkKey, expected, actual);
    }
    
    @Transactional
    public boolean fixInconsistency(Long recordId) {
        DataConsistencyRecord record = consistencyRepository.findById(recordId).orElse(null);
        if (record == null) {
            return false;
        }
        
        try {
            boolean fixed = applyFix(record);
            
            if (fixed) {
                record.setStatus(STATUS_FIXED);
                record.setFixedAt(Instant.now());
                consistencyRepository.save(record);
                log.info("Fixed inconsistency: recordId={}", recordId);
            }
            
            return fixed;
        } catch (Exception e) {
            log.error("Failed to fix inconsistency: recordId={}", recordId, e);
            return false;
        }
    }
    
    private boolean applyFix(DataConsistencyRecord record) {
        String sql = null;
        
        switch (record.getCheckType()) {
            case "ORDER_AMOUNT":
                sql = "UPDATE cabinet_order SET total_amount = CAST(? AS DECIMAL(10,2)) WHERE order_id = CAST(? AS BIGINT)";
                jdbcTemplate.update(sql, record.getActualValue(), record.getCheckKey());
                return true;
                
            case "INVENTORY_MISMATCH":
                String[] parts = record.getCheckKey().split("_");
                if (parts.length >= 2) {
                    sql = "UPDATE device_sku_inventory SET quantity = CAST(? AS INT) WHERE device_id = ? AND sku_id = CAST(? AS BIGINT)";
                    jdbcTemplate.update(sql, record.getActualValue(), parts[0], parts[1]);
                    return true;
                }
                return false;
                
            default:
                return false;
        }
    }
    
    public List<DataConsistencyRecord> getFailedChecks() {
        return consistencyRepository.findByStatus(STATUS_FAIL);
    }
    
    public List<DataChangeLog> getUnverifiedChanges() {
        return changeLogRepository.findByVerifiedFalse();
    }
}
