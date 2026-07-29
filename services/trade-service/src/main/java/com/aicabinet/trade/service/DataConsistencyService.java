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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        runConsistencyCheck();
    }

    /** 立即巡检（运营手动触发 / 联调）。返回当前仍为 FAIL 的条数。 */
    public int runConsistencyCheck() {
        try {
            log.info("开始数据一致性巡检");
            checkOrderConsistency();
            checkPaymentConsistency();
            checkInventoryConsistency();
            log.info("数据一致性巡检结束");
        } catch (Exception e) {
            log.error("数据一致性巡检中断: {}", e.getMessage());
        }
        List<DataConsistencyRecord> failed = getFailedChecks();
        return failed == null ? 0 : failed.size();
    }

    /** PAID 订单头金额 vs 明细行合计。 */
    void checkOrderConsistency() {
        String sql = "SELECT o.order_id, o.total_amount_cents, COALESCE(SUM(ol.line_amount_cents), 0) as calculated_total "
                + "FROM cabinet_order o LEFT JOIN cabinet_order_line ol ON o.order_id = ol.order_id "
                + "WHERE o.status = 'PAID' GROUP BY o.order_id, o.total_amount_cents "
                + "HAVING o.total_amount_cents != COALESCE(SUM(ol.line_amount_cents), 0)";

        Set<String> failing = new HashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            String orderId = String.valueOf(row.get("order_id"));
            failing.add(orderId);
            recordInconsistency("ORDER_AMOUNT", "cabinet_order",
                    orderId,
                    String.valueOf(row.get("total_amount_cents")),
                    String.valueOf(row.get("calculated_total")));
        }
        resolveStaleFailures("ORDER_AMOUNT", failing);
    }

    /**
     * 净入账（COMPLETED CHARGE/ADJUST_CHARGE − REFUND）vs 订单应付。
     * PAID 比对订单头；REFUNDED 期望净额为 0（全额退），避免改单退差价误报。
     */
    void checkPaymentConsistency() {
        String sql = "SELECT o.order_id, "
                + "CASE WHEN o.status = 'REFUNDED' THEN 0 ELSE o.total_amount_cents END AS expected_cents, "
                + "COALESCE(SUM(CASE "
                + "  WHEN po.operation_type IN ('CHARGE', 'ADJUST_CHARGE') THEN po.amount_cents "
                + "  WHEN po.operation_type = 'REFUND' THEN -po.amount_cents "
                + "  ELSE 0 END), 0) AS net_paid "
                + "FROM cabinet_order o "
                + "JOIN payment_operation po ON po.order_id = o.order_id "
                + "AND po.status = 'COMPLETED' "
                + "AND po.operation_type IN ('CHARGE', 'ADJUST_CHARGE', 'REFUND') "
                + "WHERE o.status IN ('PAID', 'REFUNDED') "
                + "GROUP BY o.order_id, o.status, o.total_amount_cents "
                + "HAVING CASE WHEN o.status = 'REFUNDED' THEN 0 ELSE o.total_amount_cents END "
                + "<> COALESCE(SUM(CASE "
                + "  WHEN po.operation_type IN ('CHARGE', 'ADJUST_CHARGE') THEN po.amount_cents "
                + "  WHEN po.operation_type = 'REFUND' THEN -po.amount_cents "
                + "  ELSE 0 END), 0)";

        Set<String> failing = new HashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            String orderId = String.valueOf(row.get("order_id"));
            failing.add(orderId);
            recordInconsistency("PAYMENT_AMOUNT", "payment_operation",
                    orderId,
                    String.valueOf(row.get("expected_cents")),
                    String.valueOf(row.get("net_paid")));
        }
        resolveStaleFailures("PAYMENT_AMOUNT", failing);
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

        Set<String> failing = new HashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            String key = row.get("device_id") + "|" + row.get("sku_id");
            failing.add(key);
            recordInconsistency("INVENTORY_MISMATCH", "device_sku_inventory",
                    key,
                    String.valueOf(row.get("expected_qty")),
                    String.valueOf(row.get("lot_qty")));
        }
        resolveStaleFailures("INVENTORY_MISMATCH", failing);
    }

    /** 本轮未再检出的 FAIL 标记为 FIXED，避免历史误报常驻 failCount。 */
    void resolveStaleFailures(String checkType, Set<String> stillFailing) {
        try {
            List<DataConsistencyRecord> open = consistencyRepository
                    .findByCheckTypeAndStatus(checkType, STATUS_FAIL);
            if (open == null || open.isEmpty()) {
                return;
            }
            Instant now = Instant.now();
            for (DataConsistencyRecord record : open) {
                if (stillFailing != null && stillFailing.contains(record.getCheckKey())) {
                    continue;
                }
                record.setStatus(STATUS_FIXED);
                record.setFixedAt(now);
                consistencyRepository.save(record);
                log.info("一致性误报已自动关闭 type={} key={} recordId={}",
                        checkType, record.getCheckKey(), record.getId());
            }
        } catch (Exception e) {
            log.error("关闭过期一致性 FAIL 失败 type={}: {}", checkType, e.getMessage());
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
                // 资金口径：若已有 COMPLETED CHARGE/ADJUST_CHARGE 与订单头一致，则改明细对齐头金额；
                // 否则（无有效扣款）才把头金额改为明细合计，避免改写已入账金额导致三端/支付不一致。
                String orderId = record.getCheckKey();
                Integer header = jdbcTemplate.query(
                        "SELECT total_amount_cents FROM cabinet_order WHERE order_id = ?",
                        rs -> rs.next() ? rs.getInt(1) : null,
                        orderId);
                if (header == null) {
                    return false;
                }
                Integer paid = jdbcTemplate.query(
                        "SELECT COALESCE(SUM(CASE "
                                + "WHEN operation_type IN ('CHARGE', 'ADJUST_CHARGE') THEN amount_cents "
                                + "WHEN operation_type = 'REFUND' THEN -amount_cents "
                                + "ELSE 0 END), 0) "
                                + "FROM payment_operation "
                                + "WHERE order_id = ? AND status = 'COMPLETED' "
                                + "AND operation_type IN ('CHARGE', 'ADJUST_CHARGE', 'REFUND')",
                        rs -> rs.next() ? rs.getInt(1) : null,
                        orderId);
                Integer lineSum = jdbcTemplate.query(
                        "SELECT COALESCE(SUM(line_amount_cents), 0) FROM cabinet_order_line WHERE order_id = ?",
                        rs -> rs.next() ? rs.getInt(1) : 0,
                        orderId);
                Integer lineCount = jdbcTemplate.query(
                        "SELECT COUNT(*) FROM cabinet_order_line WHERE order_id = ?",
                        rs -> rs.next() ? rs.getInt(1) : 0,
                        orderId);

                if (paid != null && paid.equals(header)) {
                    if (lineCount != null && lineCount == 1 && lineSum != null && !lineSum.equals(header)) {
                        jdbcTemplate.update(
                                "UPDATE cabinet_order_line SET line_amount_cents = ?, "
                                        + "unit_price_cents = CASE WHEN quantity > 0 THEN ? / quantity ELSE ? END "
                                        + "WHERE order_id = ?",
                                header, header, header, orderId);
                        return true;
                    }
                    if (lineCount != null && lineCount == 0) {
                        // 无明细时不臆造 SKU，留给人工补行
                        return false;
                    }
                    return false;
                }

                if (lineSum != null && lineSum > 0) {
                    jdbcTemplate.update(
                            "UPDATE cabinet_order SET total_amount_cents = ? WHERE order_id = ?",
                            lineSum, orderId);
                    return true;
                }
                return false;
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
