package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DataChangeLog;
import com.aicabinet.trade.domain.DataConsistencyRecord;
import com.aicabinet.trade.mapper.DataChangeLogMapper;
import com.aicabinet.trade.mapper.DataConsistencyRecordMapper;
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

/**
 * 定时资金/库存一致性巡检（BE-004）。
 * <p>
 * 默认只记录 FAIL、不自动改数；修复须经 {@link #fixInconsistency(Long)} 显式触发。
 * 源文件请保持 UTF-8。
 */
@Service
public class DataConsistencyService {
    private static final Logger log = LoggerFactory.getLogger(DataConsistencyService.class);

    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_FIXED = "FIXED";

    @Autowired
    private DataChangeLogMapper changeLogRepository;

    @Autowired
    private DataConsistencyRecordMapper consistencyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** 记录业务变更（可选审计）；失败不影响主流程。 */
    public void logChange(String tableName, String recordId, String operation,
                          Object oldValue, Object newValue, String changedBy) {
        try {
            DataChangeLog changeLog = new DataChangeLog();
            changeLog.setTableName(tableName);
            changeLog.setRecordId(recordId);
            changeLog.setOperation(operation);
            changeLog.setOldValue(oldValue != null ? objectMapper.writeValueAsString(oldValue) : null);
            changeLog.setNewValue(newValue != null ? objectMapper.writeValueAsString(newValue) : null);
            changeLog.setChangedBy(changedBy);
            changeLogRepository.save(changeLog);
        } catch (Exception e) {
            log.error("记录数据变更失败", e);
        }
    }

    @Scheduled(fixedDelay = 300000)
    public void performConsistencyCheck() {
        try {
            log.info("开始数据一致性巡检");
            checkOrderConsistency();
            checkPaymentConsistency();
            checkInventoryConsistency();
            log.info("数据一致性巡检结束");
        } catch (Exception e) {
            log.error("数据一致性巡检中断: {}", e.getMessage());
        }
    }

    /** PAID 订单头金额 vs 明细行合计。 */
    void checkOrderConsistency() {
        String sql = "SELECT o.order_id, o.total_amount_cents, COALESCE(SUM(ol.line_amount_cents), 0) as calculated_total "
                + "FROM cabinet_order o LEFT JOIN cabinet_order_line ol ON o.order_id = ol.order_id "
                + "WHERE o.status = 'PAID' GROUP BY o.order_id, o.total_amount_cents "
                + "HAVING o.total_amount_cents != COALESCE(SUM(ol.line_amount_cents), 0)";

        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            recordInconsistency("ORDER_AMOUNT", "cabinet_order",
                    String.valueOf(row.get("order_id")),
                    String.valueOf(row.get("total_amount_cents")),
                    String.valueOf(row.get("calculated_total")));
        }
    }

    /**
     * 成功扣款流水 vs 订单金额。
     * 仅比对 CHARGE/ADJUST_CHARGE 且 COMPLETED，避免退款流水误报。
     */
    void checkPaymentConsistency() {
        String sql = "SELECT po.order_id, po.amount_cents, o.total_amount_cents "
                + "FROM payment_operation po "
                + "JOIN cabinet_order o ON po.order_id = o.order_id "
                + "WHERE po.status = 'COMPLETED' "
                + "AND po.operation_type IN ('CHARGE', 'ADJUST_CHARGE') "
                + "AND po.amount_cents != o.total_amount_cents";

        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            recordInconsistency("PAYMENT_AMOUNT", "payment_operation",
                    String.valueOf(row.get("order_id")),
                    String.valueOf(row.get("total_amount_cents")),
                    String.valueOf(row.get("amount_cents")));
        }
    }

    /**
     * 柜机 SKU 汇总库存 vs ON_SALE 批次合计。
     * 只记录，不自动改库存（避免误伤 FEFO 批次）。
     */
    void checkInventoryConsistency() {
        String sql = "SELECT i.device_id, i.sku_id, i.quantity AS expected_qty, "
                + "COALESCE(SUM(l.quantity), 0) AS lot_qty "
                + "FROM device_sku_inventory i "
                + "LEFT JOIN device_sku_lot l ON l.device_id = i.device_id AND l.sku_id = i.sku_id "
                + "AND UPPER(COALESCE(l.status, '')) = 'ON_SALE' "
                + "GROUP BY i.device_id, i.sku_id, i.quantity "
                + "HAVING i.quantity <> COALESCE(SUM(l.quantity), 0)";

        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            String key = row.get("device_id") + "|" + row.get("sku_id");
            recordInconsistency("INVENTORY_MISMATCH", "device_sku_inventory",
                    key,
                    String.valueOf(row.get("expected_qty")),
                    String.valueOf(row.get("lot_qty")));
        }
    }

    /**
     * 写入 FAIL 记录；同一 checkType+checkKey 已有未修复 FAIL 则只刷新期望/实际值，避免每 5 分钟刷屏。
     */
    void recordInconsistency(String checkType, String tableName,
                             String checkKey, String expected, String actual) {
        try {
            List<DataConsistencyRecord> open = consistencyRepository
                    .findByCheckTypeAndCheckKeyAndStatus(checkType, checkKey, STATUS_FAIL);
            if (open != null && !open.isEmpty()) {
                DataConsistencyRecord existing = open.get(0);
                existing.setExpectedValue(expected);
                existing.setActualValue(actual);
                existing.setCheckedAt(Instant.now());
                consistencyRepository.save(existing);
            } else {
                DataConsistencyRecord record = new DataConsistencyRecord();
                record.setCheckType(checkType);
                record.setTableName(tableName);
                record.setCheckKey(checkKey);
                record.setExpectedValue(expected);
                record.setActualValue(actual);
                record.setStatus(STATUS_FAIL);
                record.setCheckedAt(Instant.now());
                consistencyRepository.save(record);
            }
        } catch (Exception e) {
            log.error("持久化一致性记录失败 type={} key={}: {}", checkType, checkKey, e.getMessage());
        }

        log.warn("发现数据不一致 type={} key={} expected={} actual={}",
                checkType, checkKey, expected, actual);
    }

    /** 人工修复入口：默认仅 ORDER_AMOUNT / INVENTORY_MISMATCH 可修。 */
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
                log.info("已修复一致性问题 recordId={}", recordId);
            }

            return fixed;
        } catch (Exception e) {
            log.error("修复一致性问题失败 recordId={}", recordId, e);
            return false;
        }
    }

    private boolean applyFix(DataConsistencyRecord record) {
        switch (record.getCheckType()) {
            case "ORDER_AMOUNT" -> {
                String sql = "UPDATE cabinet_order SET total_amount_cents = CAST(? AS INT) WHERE order_id = ?";
                jdbcTemplate.update(sql, record.getActualValue(), record.getCheckKey());
                return true;
            }
            case "INVENTORY_MISMATCH" -> {
                // checkKey = deviceId|skuId；修复为批次合计（actual）
                String[] parts = record.getCheckKey().split("\\|", 2);
                if (parts.length < 2) {
                    return false;
                }
                String sql = "UPDATE device_sku_inventory SET quantity = CAST(? AS INT) "
                        + "WHERE device_id = ? AND sku_id = ?";
                jdbcTemplate.update(sql, record.getActualValue(), parts[0], parts[1]);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public List<DataConsistencyRecord> getFailedChecks() {
        return consistencyRepository.findByStatus(STATUS_FAIL);
    }

    public List<DataChangeLog> getUnverifiedChanges() {
        return changeLogRepository.findByVerifiedFalse();
    }
}
