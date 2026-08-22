package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DataChangeLog;
import com.aicabinet.trade.domain.DataConsistencyRecord;
import com.aicabinet.trade.mapper.DataChangeLogMapper;
import com.aicabinet.trade.mapper.DataConsistencyRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定时资金/库存一致性巡检（BE-004）。
 * <p>
 * 默认只记录 FAIL、不自动改数；修复须经 {@link #fixInconsistency(Long)} /
 * {@link #fixInconsistencyDetailed(Long)} 显式触发。
 * 源文件请保持 UTF-8。
 * <p>
 * 口径说明：
 * <ul>
 *   <li>ORDER_AMOUNT 只扫 {@code PAID}（{@code DISPUTED} 尚在人工审单，故意排除）</li>
 *   <li>PAYMENT_AMOUNT 对 PAID/REFUNDED 用 LEFT JOIN，无流水且应付&gt;0 也会 FAIL</li>
 *   <li>INVENTORY_MISMATCH 修复时以 ON_SALE 批次合计为准回写汇总表</li>
 * </ul>
 */
@Service
public class DataConsistencyService {
    private static final Logger log = LoggerFactory.getLogger(DataConsistencyService.class);

    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_FIXED = "FIXED";

    /** 单轮巡检最多落库的不一致条数，避免全表扫描撑爆内存。 */
    private static final int CHECK_BATCH = 200;

    /** 显式修复结果（供运营 API 回传 message）。 */
    public record FixOutcome(boolean fixed, String message) {
        public static FixOutcome ok(String message) {
            return new FixOutcome(true, message);
        }

        public static FixOutcome fail(String message) {
            return new FixOutcome(false, message);
        }
    }

    @Autowired
    private DataChangeLogMapper changeLogRepository;

    @Autowired
    private DataConsistencyRecordMapper consistencyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

@Autowired
private ObjectMapper objectMapper;

@Autowired
private ScheduledTaskService taskService;

@Autowired
private DistributedLockService distributedLockService;

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
        long start = System.nanoTime();
        if (!taskService.tryBegin("data-consistency", 900)) {
            return;
        }
        boolean failed = false;
        String summary = "巡检完成，无不一致";
        try {
            int failCount = runConsistencyCheck();
            summary = failCount <= 0
                    ? "巡检通过，无不一致"
                    : "巡检完成，仍有不一致 " + failCount + " 条";
        } catch (Exception e) {
            failed = true;
            taskService.finish("data-consistency", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("data-consistency", "SUCCESS", summary, start);
            }
        }
    }

    /** 立即巡检（运营手动触发 / 联调）。返回当前仍为 FAIL 的条数。 */
    public int runConsistencyCheck() {
        try {
            log.info("开始数据一致性巡检");
            checkOrderConsistency();
            checkPaymentConsistency();
            checkInventoryConsistency();
            checkPointsConsistency();
            checkCouponIssuedConsistency();
            log.info("数据一致性巡检结束");
        } catch (Exception e) {
            log.error("数据一致性巡检中断: {}", e.getMessage());
        }
        List<DataConsistencyRecord> failed = getFailedChecks();
        return failed == null ? 0 : failed.size();
    }

    /** PAID 订单头金额 vs 明细折后合计（明细 − 券/会员折扣；不含 DISPUTED）。 */
    void checkOrderConsistency() {
        String sql = "SELECT o.order_id, o.total_amount_cents, "
                + "COALESCE(SUM(ol.line_amount_cents), 0) AS line_subtotal, "
                + "COALESCE(o.coupon_discount_cents, 0) AS coupon_discount, "
                + "COALESCE(o.member_discount_cents, 0) AS member_discount, "
                + "COALESCE(SUM(ol.line_amount_cents), 0) "
                + "- COALESCE(o.coupon_discount_cents, 0) - COALESCE(o.member_discount_cents, 0) AS payable_from_lines "
                + "FROM cabinet_order o LEFT JOIN cabinet_order_line ol ON o.order_id = ol.order_id "
                + "WHERE o.status = 'PAID' "
                + "GROUP BY o.order_id, o.total_amount_cents, o.coupon_discount_cents, o.member_discount_cents "
                + "HAVING o.total_amount_cents <> COALESCE(SUM(ol.line_amount_cents), 0) "
                + "- COALESCE(o.coupon_discount_cents, 0) - COALESCE(o.member_discount_cents, 0) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String orderId = String.valueOf(row.get("order_id"));
            failing.add(orderId);
            String expected = String.valueOf(row.get("total_amount_cents"));
            String actual = String.valueOf(row.get("payable_from_lines"));
            String lineSubtotal = String.valueOf(row.get("line_subtotal"));
            recordInconsistency("ORDER_AMOUNT", "cabinet_order",
                    orderId,
                    expected,
                    actual,
                    "订单头 " + expected + " ≠ 明细折后 " + actual
                            + "（明细 " + lineSubtotal + "，券 " + row.get("coupon_discount")
                            + "，会员 " + row.get("member_discount") + "）");
        }
        resolveStaleFailuresIfComplete("ORDER_AMOUNT", failing, rows.size());
    }

    /**
     * 净入账（COMPLETED CHARGE/ADJUST_CHARGE − REFUND）vs 订单应付。
     * PAID 比对订单头；REFUNDED 期望净额为 0（全额退）。LEFT JOIN 可检出「已付状态却无流水」。
     */
    void checkPaymentConsistency() {
        String sql = "SELECT o.order_id, "
                + "CASE WHEN o.status = 'REFUNDED' THEN 0 ELSE o.total_amount_cents END AS expected_cents, "
                + "COALESCE(SUM(CASE "
                + "  WHEN po.operation_type IN ('CHARGE', 'ADJUST_CHARGE') THEN po.amount_cents "
                + "  WHEN po.operation_type = 'REFUND' THEN -po.amount_cents "
                + "  ELSE 0 END), 0) AS net_paid "
                + "FROM cabinet_order o "
                + "LEFT JOIN payment_operation po ON po.order_id = o.order_id "
                + "AND po.status = 'COMPLETED' "
                + "AND po.operation_type IN ('CHARGE', 'ADJUST_CHARGE', 'REFUND') "
                + "WHERE o.status IN ('PAID', 'REFUNDED') "
                + "GROUP BY o.order_id, o.status, o.total_amount_cents "
                + "HAVING CASE WHEN o.status = 'REFUNDED' THEN 0 ELSE o.total_amount_cents END "
                + "<> COALESCE(SUM(CASE "
                + "  WHEN po.operation_type IN ('CHARGE', 'ADJUST_CHARGE') THEN po.amount_cents "
                + "  WHEN po.operation_type = 'REFUND' THEN -po.amount_cents "
                + "  ELSE 0 END), 0) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String orderId = String.valueOf(row.get("order_id"));
            failing.add(orderId);
            String expected = String.valueOf(row.get("expected_cents"));
            String actual = String.valueOf(row.get("net_paid"));
            recordInconsistency("PAYMENT_AMOUNT", "payment_operation",
                    orderId,
                    expected,
                    actual,
                    "期望净入账 " + expected + " ≠ 实际净入账 " + actual + "（请走退款/调账）");
        }
        resolveStaleFailuresIfComplete("PAYMENT_AMOUNT", failing, rows.size());
    }

    /**
     * 柜机 SKU 汇总库存 vs 在架批次合计（ON_SALE + NEAR_EXPIRY；临期仍占位可售）。
     * 只记录，不自动改库存（避免误伤 FEFO 批次）。
     */
    void checkInventoryConsistency() {
        String sql = "SELECT i.device_id, i.sku_id, i.quantity AS expected_qty, "
                + "COALESCE(SUM(l.quantity), 0) AS lot_qty "
                + "FROM device_sku_inventory i "
                + "LEFT JOIN device_sku_lot l ON l.device_id = i.device_id AND l.sku_id = i.sku_id "
                + "AND UPPER(COALESCE(l.status, '')) IN ('ON_SALE', 'NEAR_EXPIRY') "
                + "GROUP BY i.device_id, i.sku_id, i.quantity "
                + "HAVING i.quantity <> COALESCE(SUM(l.quantity), 0) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String key = row.get("device_id") + "|" + row.get("sku_id");
            failing.add(key);
            String expected = String.valueOf(row.get("expected_qty"));
            String actual = String.valueOf(row.get("lot_qty"));
            recordInconsistency("INVENTORY_MISMATCH", "device_sku_inventory",
                    key,
                    expected,
                    actual,
                    "汇总库存 " + expected + " ≠ 在架批次合计 " + actual);
        }
        resolveStaleFailuresIfComplete("INVENTORY_MISMATCH", failing, rows.size());
    }

    /**
     * 会员可用积分 vs 积分流水 points 字段合计（EARN 为正、USE/EXPIRE 为负）。
     */
    void checkPointsConsistency() {
        String sql = "SELECT m.member_id, m.available_points AS expected, "
                + "COALESCE((SELECT SUM(l.points) "
                + "  FROM member_points_log l WHERE l.member_id = m.member_id), 0) AS calculated "
                + "FROM member m "
                + "WHERE m.available_points <> COALESCE((SELECT SUM(l.points) "
                + "  FROM member_points_log l WHERE l.member_id = m.member_id), 0) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String memberId = String.valueOf(row.get("member_id"));
            failing.add(memberId);
            String expected = String.valueOf(row.get("expected"));
            String actual = String.valueOf(row.get("calculated"));
            recordInconsistency("POINTS_BALANCE", "member",
                    memberId, expected, actual,
                    "可用积分 " + expected + " ≠ 积分日志汇总 " + actual);
        }
        resolveStaleFailuresIfComplete("POINTS_BALANCE", failing, rows.size());
    }

    /**
     * 券定义已发数 vs user_coupon 实际发放数。
     */
    void checkCouponIssuedConsistency() {
        String sql = "SELECT d.coupon_def_id, d.issued_count AS expected, "
                + "(SELECT COUNT(*) FROM user_coupon uc WHERE uc.coupon_def_id = d.coupon_def_id) AS actual "
                + "FROM coupon_definition d "
                + "WHERE d.issued_count <> (SELECT COUNT(*) FROM user_coupon uc WHERE uc.coupon_def_id = d.coupon_def_id) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String defId = String.valueOf(row.get("coupon_def_id"));
            failing.add(defId);
            String expected = String.valueOf(row.get("expected"));
            String actual = String.valueOf(row.get("actual"));
            recordInconsistency("COUPON_ISSUED", "coupon_definition",
                    defId, expected, actual,
                    "券定义已发数 " + expected + " ≠ 实际发放 " + actual);
        }
        resolveStaleFailuresIfComplete("COUPON_ISSUED", failing, rows.size());
    }

    /**
     * 仅在本轮未触达批次上限时关闭误报；触顶说明可能还有未扫到的 FAIL，避免误标 FIXED。
     */
    void resolveStaleFailuresIfComplete(String checkType, Set<String> stillFailing, int foundCount) {
        if (foundCount >= CHECK_BATCH) {
            log.warn("一致性巡检触顶 type={} found={} batch={}，跳过误报自动关闭",
                    checkType, foundCount, CHECK_BATCH);
            return;
        }
        resolveStaleFailures(checkType, stillFailing);
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
        recordInconsistency(checkType, tableName, checkKey, expected, actual, null);
    }

    void recordInconsistency(String checkType, String tableName,
                             String checkKey, String expected, String actual, String errorMessage) {
        String lockKey = consistencyCheckLockKey(checkType, checkKey);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            log.warn("consistency check lock busy type={} key={}", checkType, checkKey);
            return;
        }
        try {
            doRecordInconsistency(checkType, tableName, checkKey, expected, actual, errorMessage);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private void doRecordInconsistency(String checkType, String tableName,
                                       String checkKey, String expected, String actual, String errorMessage) {
        try {
            List<DataConsistencyRecord> open = consistencyRepository
                    .findByCheckTypeAndCheckKeyAndStatus(checkType, checkKey, STATUS_FAIL);
            if (open != null && !open.isEmpty()) {
                DataConsistencyRecord existing = consistencyRepository.findByIdForUpdate(open.get(0).getId())
                        .orElse(open.get(0));
                existing.setExpectedValue(expected);
                existing.setActualValue(actual);
                if (errorMessage != null && !errorMessage.isBlank()) {
                    existing.setErrorMessage(errorMessage);
                }
                existing.setCheckedAt(Instant.now());
                consistencyRepository.save(existing);
            } else {
                DataConsistencyRecord record = new DataConsistencyRecord();
                record.setCheckType(checkType);
                record.setTableName(tableName);
                record.setCheckKey(checkKey);
                record.setExpectedValue(expected);
                record.setActualValue(actual);
                record.setErrorMessage(errorMessage);
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

    static String consistencyCheckLockKey(String checkType, String checkKey) {
        return "data-consistency:check:" + checkType + ":" + checkKey;
    }

    /** 人工修复入口：默认仅 ORDER_AMOUNT / INVENTORY_MISMATCH 可修。 */
    @Transactional
    public boolean fixInconsistency(Long recordId) {
        return fixInconsistencyDetailed(recordId).fixed();
    }

    /** 带说明的修复入口（运营控制台）。 */
    @Transactional
    public FixOutcome fixInconsistencyDetailed(Long recordId) {
        return runWithConsistencyRecordLock(recordId, () -> doFixInconsistencyDetailed(recordId));
    }

    private FixOutcome doFixInconsistencyDetailed(Long recordId) {
        DataConsistencyRecord record = consistencyRepository.findByIdForUpdate(recordId).orElse(null);
        if (record == null) {
            return FixOutcome.fail("记录不存在");
        }

        try {
            FixOutcome outcome = applyFix(record);
            if (outcome.fixed()) {
                record.setStatus(STATUS_FIXED);
                record.setFixedAt(Instant.now());
                if (outcome.message() != null) {
                    record.setErrorMessage(outcome.message());
                }
                consistencyRepository.save(record);
                log.info("已修复一致性问题 recordId={} msg={}", recordId, outcome.message());
            }
            return outcome;
        } catch (Exception e) {
            log.error("修复一致性问题失败 recordId={}", recordId, e);
            return FixOutcome.fail("修复异常: " + e.getMessage());
        }
    }

    static String consistencyRecordLockKey(Long recordId) {
        return "data-consistency:record:" + recordId;
    }

    private <T> T runWithConsistencyRecordLock(Long recordId, java.util.function.Supplier<T> action) {
        String key = consistencyRecordLockKey(recordId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "一致性记录处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private FixOutcome applyFix(DataConsistencyRecord record) {
        return switch (record.getCheckType()) {
            case "ORDER_AMOUNT" -> fixOrderAmount(record);
            case "INVENTORY_MISMATCH" -> fixInventoryMismatch(record);
            case "PAYMENT_AMOUNT" -> FixOutcome.fail("支付净额偏差不可自动修复，请走退款/调账");
            default -> FixOutcome.fail("不支持自动修复的类型: " + record.getCheckType());
        };
    }

    private FixOutcome fixOrderAmount(DataConsistencyRecord record) {
        String orderId = record.getCheckKey();
        Integer header = jdbcTemplate.query(
                "SELECT total_amount_cents FROM cabinet_order WHERE order_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                orderId);
        if (header == null) {
            return FixOutcome.fail("订单不存在");
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
        Integer couponDiscount = jdbcTemplate.query(
                "SELECT COALESCE(coupon_discount_cents, 0) FROM cabinet_order WHERE order_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                orderId);
        Integer memberDiscount = jdbcTemplate.query(
                "SELECT COALESCE(member_discount_cents, 0) FROM cabinet_order WHERE order_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                orderId);
        int payableFromLines = lineSum - couponDiscount - memberDiscount;
        Integer lineCount = jdbcTemplate.query(
                "SELECT COUNT(*) FROM cabinet_order_line WHERE order_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                orderId);

        if (header == payableFromLines) {
            return FixOutcome.ok("头金额已与明细折后一致");
        }

        if (paid != null && paid.equals(header)) {
            if (lineCount != null && lineCount == 1 && lineSum != null && lineSum != header + couponDiscount + memberDiscount) {
                int targetLine = header + couponDiscount + memberDiscount;
                jdbcTemplate.update(
                        "UPDATE cabinet_order_line SET line_amount_cents = ?, "
                                + "unit_price_cents = CASE WHEN quantity > 0 THEN ? / quantity ELSE ? END "
                                + "WHERE order_id = ?",
                        targetLine, targetLine, targetLine, orderId);
                return FixOutcome.ok("已按入账金额对齐单行明细（含折扣）");
            }
            if (lineCount != null && lineCount == 0) {
                return FixOutcome.fail("无明细行，无法自动补 SKU，请人工补行");
            }
            return FixOutcome.fail("多行明细与头金额不一致，需人工拆分/改价");
        }

        if (lineSum != null && lineSum > 0 && paid != null && paid == 0) {
            jdbcTemplate.update(
                    "UPDATE cabinet_order SET total_amount_cents = ? WHERE order_id = ?",
                    payableFromLines, orderId);
            return FixOutcome.ok("无匹配入账流水，已把头金额改为明细折后 " + payableFromLines);
        }
        return FixOutcome.fail("明细折后为 0 或与入账不一致，无法自动修复");
    }

    private FixOutcome fixInventoryMismatch(DataConsistencyRecord record) {
        String[] parts = record.getCheckKey().split("\\|", 2);
        if (parts.length < 2) {
            return FixOutcome.fail("库存键格式无效，期望 deviceId|skuId");
        }
        String sql = "UPDATE device_sku_inventory SET quantity = CAST(? AS INT) "
                + "WHERE device_id = ? AND sku_id = ?";
        int updated = jdbcTemplate.update(sql, record.getActualValue(), parts[0], parts[1]);
        if (updated <= 0) {
            return FixOutcome.fail("未更新到库存行");
        }
        return FixOutcome.ok("已将汇总库存改为在架批次合计 " + record.getActualValue());
    }

    public List<DataConsistencyRecord> getFailedChecks() {
        return consistencyRepository.findByStatus(STATUS_FAIL);
    }

    public List<DataChangeLog> getUnverifiedChanges() {
        return changeLogRepository.findByVerifiedFalse();
    }
}
