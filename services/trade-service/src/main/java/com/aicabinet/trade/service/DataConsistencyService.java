package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DataChangeLog;
import com.aicabinet.trade.domain.DataConsistencyRecord;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
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
 *   <li>PAYMENT_AMOUNT 对 PAID/PARTIAL_REFUNDED/REFUNDED 用 LEFT JOIN，无流水且应付&gt;0 也会 FAIL</li>
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

@Autowired
private CouponService couponService;

@Autowired
private CabinetOrderMapper cabinetOrderRepository;

@Autowired
private OrderPaymentService orderPaymentService;

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
            checkWalletBalanceConsistency();
            checkRefundAmountConsistency();
            checkOrderLineSumConsistency();
            checkCouponUsedLinkConsistency();
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
                + "- COALESCE(o.coupon_discount_cents, 0) - COALESCE(o.member_discount_cents, 0) AS payable_from_lines, "
                + "COALESCE((SELECT SUM(CASE "
                + "  WHEN po.operation_type IN ('CHARGE', 'ADJUST_CHARGE') THEN po.amount_cents "
                + "  WHEN po.operation_type = 'REFUND' THEN -po.amount_cents "
                + "  ELSE 0 END) FROM payment_operation po "
                + "  WHERE po.order_id = o.order_id AND po.status = 'COMPLETED' "
                + "  AND po.operation_type IN ('CHARGE', 'ADJUST_CHARGE', 'REFUND')), 0) AS net_paid "
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
            String header = String.valueOf(row.get("total_amount_cents"));
            String payable = String.valueOf(row.get("payable_from_lines"));
            recordInconsistency("ORDER_AMOUNT", "cabinet_order",
                    orderId,
                    header,
                    payable,
                    buildOrderAmountErrorMessage(row));
        }
        resolveStaleFailuresIfComplete("ORDER_AMOUNT", failing, rows.size());
    }

    static String buildOrderAmountErrorMessage(Map<String, Object> row) {
        int header = toInt(row.get("total_amount_cents"));
        int lineSubtotal = toInt(row.get("line_subtotal"));
        int couponDiscount = toInt(row.get("coupon_discount"));
        int memberDiscount = toInt(row.get("member_discount"));
        int payable = toInt(row.get("payable_from_lines"));
        int netPaid = toInt(row.get("net_paid"));
        StringBuilder msg = new StringBuilder();
        msg.append("订单头 ").append(header).append(" ≠ 按明细应收 ").append(payable)
                .append("（明细 ").append(lineSubtotal)
                .append("，券 ").append(couponDiscount)
                .append("，会员 ").append(memberDiscount).append("）");
        if (couponDiscount > lineSubtotal) {
            msg.append("；券抵扣超过明细，疑似未封顶");
        }
        if (netPaid == header && header == lineSubtotal && payable != header) {
            msg.append("；实付与明细原价一致，券字段未生效（可尝试「修复」清除脏券）");
        } else if (netPaid == payable && payable != header) {
            msg.append("；实付已按折后入账，订单头未同步");
        } else if (netPaid != header && netPaid != payable) {
            msg.append("；实付 ").append(netPaid).append(" 与订单头/折后均不符，需人工核对");
        }
        return msg.toString();
    }

    private static int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 净入账（COMPLETED CHARGE/ADJUST_CHARGE − REFUND）vs 订单应付。
     * PAID / PARTIAL_REFUNDED 比对订单头；REFUNDED 期望净额为 0（全额退）。LEFT JOIN 可检出「已付状态却无流水」。
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
                + "WHERE o.status IN ('PAID', 'REFUNDED', 'PARTIAL_REFUNDED') "
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
     * 余额账户 vs 最近一条余额渠道流水的 balance_after（仅有 BALANCE 流水时比对）。
     */
    void checkWalletBalanceConsistency() {
        String sql = "SELECT ua.user_id, ua.balance_cents AS expected, "
                + "latest.balance_after_cents AS actual "
                + "FROM user_account ua "
                + "JOIN LATERAL ( "
                + "  SELECT po.balance_after_cents "
                + "  FROM payment_operation po "
                + "  WHERE po.user_id = ua.user_id AND po.channel = 'BALANCE' "
                + "  AND po.status = 'COMPLETED' AND po.balance_after_cents IS NOT NULL "
                + "  ORDER BY po.created_at DESC, po.operation_id DESC "
                + "  LIMIT 1 "
                + ") latest ON true "
                + "WHERE ua.balance_cents <> latest.balance_after_cents "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String userId = String.valueOf(row.get("user_id"));
            failing.add(userId);
            recordInconsistency("WALLET_BALANCE", "user_account",
                    userId,
                    String.valueOf(row.get("expected")),
                    String.valueOf(row.get("actual")),
                    "账户余额 " + row.get("expected") + " ≠ 最近流水余额 "
                            + row.get("actual") + "（请人工核对充值/退款/调账）");
        }
        resolveStaleFailuresIfComplete("WALLET_BALANCE", failing, rows.size());
    }

    /**
     * 订单已退金额字段 vs 已完成 REFUND 流水合计（PARTIAL_REFUNDED / REFUNDED）。
     */
    void checkRefundAmountConsistency() {
        String sql = "SELECT o.order_id, COALESCE(o.refunded_cents, 0) AS expected, "
                + "COALESCE(SUM(po.amount_cents), 0) AS actual "
                + "FROM cabinet_order o "
                + "LEFT JOIN payment_operation po ON po.order_id = o.order_id "
                + "AND po.operation_type = 'REFUND' AND po.status = 'COMPLETED' "
                + "WHERE o.status IN ('REFUNDED', 'PARTIAL_REFUNDED') "
                + "GROUP BY o.order_id, o.refunded_cents "
                + "HAVING COALESCE(o.refunded_cents, 0) <> COALESCE(SUM(po.amount_cents), 0) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String orderId = String.valueOf(row.get("order_id"));
            failing.add(orderId);
            recordInconsistency("REFUND_AMOUNT", "cabinet_order",
                    orderId,
                    String.valueOf(row.get("expected")),
                    String.valueOf(row.get("actual")),
                    "订单已退字段 " + row.get("expected") + " ≠ 退款流水合计 "
                            + row.get("actual") + "（请走退款/调账）");
        }
        resolveStaleFailuresIfComplete("REFUND_AMOUNT", failing, rows.size());
    }

    /**
     * 订单行金额 vs 单价×数量（PAID/PENDING/PARTIAL_REFUNDED/DISPUTED）。
     */
    void checkOrderLineSumConsistency() {
        String sql = "SELECT ol.order_id || '|' || ol.sku_id AS line_key, "
                + "ol.line_amount_cents AS expected, "
                + "(ol.unit_price_cents * ol.quantity) AS actual "
                + "FROM cabinet_order_line ol "
                + "JOIN cabinet_order o ON o.order_id = ol.order_id "
                + "WHERE o.status IN ('PAID', 'PENDING', 'PARTIAL_REFUNDED', 'DISPUTED') "
                + "AND ol.line_amount_cents <> (ol.unit_price_cents * ol.quantity) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("line_key"));
            failing.add(key);
            recordInconsistency("ORDER_LINE_SUM", "cabinet_order_line",
                    key,
                    String.valueOf(row.get("expected")),
                    String.valueOf(row.get("actual")),
                    "行金额 " + row.get("expected") + " ≠ 单价×数量 " + row.get("actual"));
        }
        resolveStaleFailuresIfComplete("ORDER_LINE_SUM", failing, rows.size());
    }

    /**
     * 已核销 user_coupon 与订单券字段不一致（coupon_id / discount_cents）。
     */
    void checkCouponUsedLinkConsistency() {
        String sql = "SELECT uc.order_id, "
                + "COALESCE(o.coupon_discount_cents, 0) AS order_discount, "
                + "COALESCE(uc.discount_cents, 0) AS coupon_discount, "
                + "COALESCE(CAST(o.coupon_id AS VARCHAR), '') AS order_coupon_id, "
                + "CAST(uc.coupon_id AS VARCHAR) AS user_coupon_id "
                + "FROM user_coupon uc "
                + "JOIN cabinet_order o ON o.order_id = uc.order_id "
                + "WHERE uc.status = 'USED' AND uc.order_id IS NOT NULL "
                + "AND (o.coupon_id IS DISTINCT FROM uc.coupon_id "
                + "OR COALESCE(o.coupon_discount_cents, 0) <> COALESCE(uc.discount_cents, 0)) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String orderId = String.valueOf(row.get("order_id"));
            failing.add(orderId);
            recordInconsistency("COUPON_USED_LINK", "user_coupon",
                    orderId,
                    String.valueOf(row.get("order_discount")),
                    String.valueOf(row.get("coupon_discount")),
                    "订单券抵扣 " + row.get("order_discount") + " / 券ID "
                            + row.get("order_coupon_id") + " ≠ 核销券 "
                            + row.get("coupon_discount") + " / ID " + row.get("user_coupon_id"));
        }
        resolveStaleFailuresIfComplete("COUPON_USED_LINK", failing, rows.size());
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
            case "ORDER_LINE_SUM" -> fixOrderLineSum(record);
            case "COUPON_USED_LINK" -> fixCouponUsedLink(record);
            case "PAYMENT_AMOUNT" -> fixPaymentAmount(record);
            case "REFUND_AMOUNT", "WALLET_BALANCE" ->
                    FixOutcome.fail("该类仅巡检记录，请人工核对处理");
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
        int couponDiscountCents = couponDiscount != null ? couponDiscount : 0;
        int memberDiscountCents = memberDiscount != null ? memberDiscount : 0;
        int payableFromLines = lineSum - couponDiscountCents - memberDiscountCents;
        int lineCount = jdbcTemplate.query(
                "SELECT COUNT(*) FROM cabinet_order_line WHERE order_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                orderId);

        if (header == payableFromLines) {
            return FixOutcome.ok("头金额已与明细折后一致");
        }

        if (paid != null && paid.equals(header) && lineSum != null && paid.equals(lineSum)
                && (couponDiscountCents > 0 || memberDiscountCents > 0) && payableFromLines != header) {
            return clearStaleCouponFields(orderId, couponDiscountCents, memberDiscountCents);
        }

        if (paid != null && paid.equals(header)) {
            if (lineCount == 1 && lineSum != null
                    && couponDiscountCents == 0 && memberDiscountCents == 0
                    && lineSum != header) {
                return alignSingleLineToHeader(orderId, header);
            }
            if (lineCount == 0) {
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
        if (lineSum != null && lineSum > 0 && paid != null && paid.equals(payableFromLines) && !paid.equals(header)) {
            jdbcTemplate.update(
                    "UPDATE cabinet_order SET total_amount_cents = ? WHERE order_id = ?",
                    payableFromLines, orderId);
            return FixOutcome.ok("实付已与折后一致，已同步订单头为 " + payableFromLines);
        }
        return FixOutcome.fail("明细折后与入账不一致，无法自动修复（请走退款/调账或人工改券）");
    }

    /** 实付=明细原价但券/会员字段未生效：清除脏元数据并尝试退还券占用。 */
    private FixOutcome clearStaleCouponFields(String orderId, int couponDiscount, int memberDiscount) {
        jdbcTemplate.update(
                "UPDATE cabinet_order SET coupon_id = NULL, coupon_discount_cents = 0, "
                        + "member_discount_cents = 0 WHERE order_id = ?",
                orderId);
        int released = couponService.releaseStaleUsedCouponsForOrder(orderId);
        return FixOutcome.ok("实付与明细一致，已清除未生效的券/折扣字段"
                + "（券 " + couponDiscount + "，会员 " + memberDiscount
                + "；释放错绑核销券 " + released + " 张）");
    }

    /** 单行订单按单价×数量对齐；头金额不能整除数量时同步头金额为折后行合计。 */
    private FixOutcome alignSingleLineToHeader(String orderId, int header) {
        Integer quantity = jdbcTemplate.query(
                "SELECT quantity FROM cabinet_order_line WHERE order_id = ? LIMIT 1",
                rs -> rs.next() ? rs.getInt(1) : null,
                orderId);
        int qty = quantity != null && quantity > 0 ? quantity : 1;
        int unit = header / qty;
        int line = unit * qty;
        jdbcTemplate.update(
                "UPDATE cabinet_order_line SET line_amount_cents = ?, unit_price_cents = ? WHERE order_id = ?",
                line, unit, orderId);
        if (line != header) {
            jdbcTemplate.update(
                    "UPDATE cabinet_order SET total_amount_cents = ? WHERE order_id = ?",
                    line, orderId);
            String refundMsg = refundOverchargeIfNeeded(orderId, line);
            return FixOutcome.ok("已按单价×数量对齐明细 " + line + "（原头金额 " + header
                    + " 含 " + (header - line) + " 分尾差）" + refundMsg);
        }
        return FixOutcome.ok("已按入账金额对齐单行明细");
    }

    /** 订单头下调后，若净入账仍高于新头金额则退多收差额。 */
    private String refundOverchargeIfNeeded(String orderId, int newHeaderCents) {
        Integer netPaid = queryNetCompletedCents(orderId);
        if (netPaid == null || netPaid <= newHeaderCents) {
            return "";
        }
        CabinetOrder order = cabinetOrderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return "";
        }
        int over = netPaid - newHeaderCents;
        orderPaymentService.refundOrder(order, over, "一致性修复退多收");
        return "，已退多收 " + over + " 分";
    }

    private Integer queryNetCompletedCents(String orderId) {
        return jdbcTemplate.query(
                "SELECT COALESCE(SUM(CASE "
                        + "WHEN operation_type IN ('CHARGE', 'ADJUST_CHARGE') THEN amount_cents "
                        + "WHEN operation_type = 'REFUND' THEN -amount_cents "
                        + "ELSE 0 END), 0) "
                        + "FROM payment_operation "
                        + "WHERE order_id = ? AND status = 'COMPLETED' "
                        + "AND operation_type IN ('CHARGE', 'ADJUST_CHARGE', 'REFUND')",
                rs -> rs.next() ? rs.getInt(1) : null,
                orderId);
    }

    private FixOutcome fixOrderLineSum(DataConsistencyRecord record) {
        String[] parts = record.getCheckKey().split("\\|", 2);
        if (parts.length < 2) {
            return FixOutcome.fail("行键格式无效，期望 orderId|skuId");
        }
        String orderId = parts[0];
        String skuId = parts[1];
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ol.quantity, ol.unit_price_cents, ol.line_amount_cents, o.total_amount_cents "
                        + "FROM cabinet_order_line ol "
                        + "JOIN cabinet_order o ON o.order_id = ol.order_id "
                        + "WHERE ol.order_id = ? AND ol.sku_id = ?",
                orderId, skuId);
        if (rows.isEmpty()) {
            return FixOutcome.fail("订单行不存在");
        }
        Map<String, Object> row = rows.get(0);
        int qty = ((Number) row.get("quantity")).intValue();
        if (qty <= 0) {
            return FixOutcome.fail("行数量无效");
        }
        int unit = ((Number) row.get("unit_price_cents")).intValue();
        int line = ((Number) row.get("line_amount_cents")).intValue();
        int header = ((Number) row.get("total_amount_cents")).intValue();
        int computed = unit * qty;
        if (line == computed) {
            return FixOutcome.ok("行金额已与单价×数量一致");
        }
        int alignedLine;
        int alignedUnit;
        if (line % qty == 0) {
            alignedUnit = line / qty;
            alignedLine = line;
        } else {
            alignedUnit = unit;
            alignedLine = computed;
        }
        jdbcTemplate.update(
                "UPDATE cabinet_order_line SET line_amount_cents = ?, unit_price_cents = ? "
                        + "WHERE order_id = ? AND sku_id = ?",
                alignedLine, alignedUnit, orderId, skuId);
        Integer lineCount = jdbcTemplate.query(
                "SELECT COUNT(*) FROM cabinet_order_line WHERE order_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                orderId);
        if (lineCount != null && lineCount == 1 && header != alignedLine) {
            jdbcTemplate.update(
                    "UPDATE cabinet_order SET total_amount_cents = ? WHERE order_id = ?",
                    alignedLine, orderId);
            String refundMsg = refundOverchargeIfNeeded(orderId, alignedLine);
            return FixOutcome.ok("已对齐行金额 " + alignedLine + "（原 " + line + "），并同步订单头" + refundMsg);
        }
        return FixOutcome.ok("已对齐行金额 " + alignedLine + "（原 " + line + "）");
    }

    /** 实付大于订单头时退多收差额（常见于修行金额后遗留尾差）。 */
    private FixOutcome fixPaymentAmount(DataConsistencyRecord record) {
        String orderId = record.getCheckKey();
        int expected = parseRecordCents(record.getExpectedValue());
        int actual = parseRecordCents(record.getActualValue());
        if (actual <= expected) {
            return FixOutcome.fail("仅支持实付大于订单头的多收场景，少收请走补扣/调账");
        }
        CabinetOrder order = cabinetOrderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return FixOutcome.fail("订单不存在");
        }
        String status = order.getStatus() == null ? "" : order.getStatus();
        if (!Set.of("PAID", "PARTIAL_REFUNDED").contains(status)) {
            return FixOutcome.fail("订单状态 " + status + " 不支持自动退多收");
        }
        int over = actual - expected;
        orderPaymentService.refundOrder(order, over, "一致性修复退多收");
        return FixOutcome.ok("已退多收 " + over + " 分，净入账对齐订单头 " + expected);
    }

    private static int parseRecordCents(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private FixOutcome fixCouponUsedLink(DataConsistencyRecord record) {
        String orderId = record.getCheckKey();
        Integer orderDiscount = jdbcTemplate.query(
                "SELECT COALESCE(coupon_discount_cents, 0) FROM cabinet_order WHERE order_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                orderId);
        Long orderCouponId = jdbcTemplate.query(
                "SELECT coupon_id FROM cabinet_order WHERE order_id = ?",
                rs -> rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null,
                orderId);
        if (orderCouponId != null && orderDiscount != null && orderDiscount > 0) {
            return FixOutcome.fail("订单已绑券且抵扣生效，请人工核对券字段与核销记录");
        }
        int released = couponService.releaseStaleUsedCouponsForOrder(orderId);
        if (released <= 0) {
            return FixOutcome.fail("未找到可释放的错绑核销券");
        }
        jdbcTemplate.update(
                "UPDATE cabinet_order SET coupon_id = NULL, coupon_discount_cents = 0 "
                        + "WHERE order_id = ? AND coupon_id IS NOT NULL",
                orderId);
        return FixOutcome.ok("已释放 " + released + " 张错绑核销券");
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
