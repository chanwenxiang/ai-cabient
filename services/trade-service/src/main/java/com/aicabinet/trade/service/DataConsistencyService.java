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
import org.springframework.context.annotation.Lazy;
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
 *   <li>ORDER_AMOUNT 扫 {@code PAID}/{@code PARTIAL_REFUNDED}（{@code DISPUTED} 尚在人工审单，故意排除）</li>
 *   <li>PAYMENT_AMOUNT 对 PAID/PARTIAL_REFUNDED/REFUNDED 用 LEFT JOIN，无流水且应付&gt;0 也会 FAIL</li>
 *   <li>INVENTORY_MISMATCH / INVENTORY_ORPHAN_LOT 修复时以在架批次合计为准回写汇总表</li>
 *   <li>POINTS_IDENTITY 只校验 available+used+expired=total，与 POINTS_BALANCE（流水合计）互补</li>
 * </ul>
 */
@Service
public class DataConsistencyService {
    private static final String UPDATE_CABINET_ORDER_SET_TOTAL_AMOUNT_CENTS_WHERE_ORDER_ID = "UPDATE cabinet_order SET total_amount_cents = ? WHERE order_id = ?";
    private static final String INVENTORY_MISMATCH = "INVENTORY_MISMATCH";
    private static final String INVENTORY_ORPHAN_LOT = "INVENTORY_ORPHAN_LOT";
    private static final String POINTS_BALANCE = "POINTS_BALANCE";
    private static final String POINTS_IDENTITY = "POINTS_IDENTITY";
    private static final String MERCHANT_WALLET = "MERCHANT_WALLET";
    private static final String LINE_WALLET = "LINE_WALLET";
    private static final String REVENUE_SPLIT_SUM = "REVENUE_SPLIT_SUM";
    private static final String REVENUE_SPLIT_MISSING = "REVENUE_SPLIT_MISSING";
    private static final String SLOT_SKU_MISMATCH = "SLOT_SKU_MISMATCH";
    private static final String SLOT_CAPACITY = "SLOT_CAPACITY";
    private static final String SLOT_PHYSICAL = "SLOT_PHYSICAL";
    private static final String WAREHOUSE_NEGATIVE = "WAREHOUSE_NEGATIVE";
    private static final String COUPON_OVER_QUOTA = "COUPON_OVER_QUOTA";
    private static final String TOTAL_AMOUNT_CENTS = "total_amount_cents";
    private static final String DATA_CONSISTENCY = "data-consistency";
    private static final String COUPON_USED_LINK = "COUPON_USED_LINK";
    private static final String COUPON_DISCOUNT = "coupon_discount";
    private static final String PAYMENT_AMOUNT = "PAYMENT_AMOUNT";
    private static final String ORDER_LINE_SUM = "ORDER_LINE_SUM";
    private static final String WALLET_BALANCE = "WALLET_BALANCE";
    private static final String REFUND_AMOUNT = "REFUND_AMOUNT";
    private static final String ORDER_AMOUNT = "ORDER_AMOUNT";
    /** 关联投影漂移（扣库/退款库存/争议/订单归属）；仅巡检，不自动修。 */
    private static final String CROSS_LINK = "CROSS_LINK";
    private static final String EXPECTED = "expected";
    private static final String ORDER_ID = "order_id";
    private static final String ACTUAL = "actual";
    private static final String LOT_ON_SALE_STATUSES =
            "UPPER(COALESCE(l.status, '')) IN ('ON_SALE', 'NEAR_EXPIRY')";

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

    private final DataChangeLogMapper changeLogRepository;
    private final DataConsistencyRecordMapper consistencyRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ScheduledTaskService taskService;
    private final DistributedLockService distributedLockService;
    private final CouponService couponService;
    private final CabinetOrderMapper cabinetOrderRepository;
    private final OrderPaymentService orderPaymentService;
    private final DataConsistencyService self;

    public DataConsistencyService(DataChangeLogMapper changeLogRepository,
                                  DataConsistencyRecordMapper consistencyRepository,
                                  JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper,
                                  ScheduledTaskService taskService,
                                  DistributedLockService distributedLockService,
                                  CouponService couponService,
                                  CabinetOrderMapper cabinetOrderRepository,
                                  OrderPaymentService orderPaymentService,
                                  @Lazy DataConsistencyService self) {
        this.changeLogRepository = changeLogRepository;
        this.consistencyRepository = consistencyRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.distributedLockService = distributedLockService;
        this.couponService = couponService;
        this.cabinetOrderRepository = cabinetOrderRepository;
        this.orderPaymentService = orderPaymentService;
        this.self = self;
    }

    private JdbcTemplate requireJdbc() {
        return java.util.Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    private int queryInt(String sql, Object... args) {
        Integer value = requireJdbc().query(sql, rs -> rs.next() ? rs.getInt(1) : 0, args);
        return value != null ? value : 0;
    }

    private Integer queryNullableInt(String sql, Object... args) {
        return requireJdbc().query(sql, rs -> rs.next() ? rs.getInt(1) : null, args);
    }

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
        if (!taskService.tryBegin(DATA_CONSISTENCY, 900)) {
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
            taskService.finish(DATA_CONSISTENCY, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(DATA_CONSISTENCY, "SUCCESS", summary, start);
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
            checkInventoryOrphanLotConsistency();
            checkPointsConsistency();
            checkPointsIdentityConsistency();
            checkCouponIssuedConsistency();
            checkCouponOverQuotaConsistency();
            checkWalletBalanceConsistency();
            checkMerchantWalletConsistency();
            checkLineWalletConsistency();
            checkRefundAmountConsistency();
            checkOrderLineSumConsistency();
            checkCouponUsedLinkConsistency();
            checkRevenueSplitSumConsistency();
            checkRevenueSplitMissingConsistency();
            checkSlotSkuMismatchConsistency();
            checkSlotCapacityConsistency();
            checkSlotPhysicalConsistency();
            checkWarehouseNegativeConsistency();
            checkCrossLinkConsistency();
            log.info("数据一致性巡检结束");
        } catch (Exception e) {
            log.error("数据一致性巡检中断", e);
        }
        List<DataConsistencyRecord> failed = getFailedChecks();
        return failed == null ? 0 : failed.size();
    }

    /** PAID/PARTIAL_REFUNDED 订单头金额 vs 明细折后合计（明细 − 券/会员折扣；不含 DISPUTED）。 */
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
                + "WHERE o.status IN ('PAID', 'PARTIAL_REFUNDED') "
                + "GROUP BY o.order_id, o.total_amount_cents, o.coupon_discount_cents, o.member_discount_cents "
                + "HAVING o.total_amount_cents <> COALESCE(SUM(ol.line_amount_cents), 0) "
                + "- COALESCE(o.coupon_discount_cents, 0) - COALESCE(o.member_discount_cents, 0) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String orderId = String.valueOf(row.get(ORDER_ID));
            failing.add(orderId);
            String header = String.valueOf(row.get(TOTAL_AMOUNT_CENTS));
            String payable = String.valueOf(row.get("payable_from_lines"));
            recordInconsistency(ORDER_AMOUNT, "cabinet_order",
                    orderId,
                    header,
                    payable,
                    buildOrderAmountErrorMessage(row));
        }
        resolveStaleFailuresIfComplete(ORDER_AMOUNT, failing, rows.size());
    }

    static String buildOrderAmountErrorMessage(Map<String, Object> row) {
        int header = toInt(row.get(TOTAL_AMOUNT_CENTS));
        int lineSubtotal = toInt(row.get("line_subtotal"));
        int couponDiscount = toInt(row.get(COUPON_DISCOUNT));
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
            String orderId = String.valueOf(row.get(ORDER_ID));
            failing.add(orderId);
            String expected = String.valueOf(row.get("expected_cents"));
            String actual = String.valueOf(row.get("net_paid"));
            recordInconsistency(PAYMENT_AMOUNT, "payment_operation",
                    orderId,
                    expected,
                    actual,
                    "期望净入账 " + expected + " ≠ 实际净入账 " + actual + "（请走退款/调账）");
        }
        resolveStaleFailuresIfComplete(PAYMENT_AMOUNT, failing, rows.size());
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
                + "AND " + LOT_ON_SALE_STATUSES + " "
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
            recordInconsistency(INVENTORY_MISMATCH, "device_sku_inventory",
                    key,
                    expected,
                    actual,
                    "汇总库存 " + expected + " ≠ 在架批次合计 " + actual);
        }
        resolveStaleFailuresIfComplete(INVENTORY_MISMATCH, failing, rows.size());
    }

    /**
     * 在架批次有库存，但缺失 device_sku_inventory 汇总行（补货写批后漏同步）。
     */
    void checkInventoryOrphanLotConsistency() {
        String sql = "SELECT l.device_id, l.sku_id, COALESCE(SUM(l.quantity), 0) AS lot_qty "
                + "FROM device_sku_lot l "
                + "WHERE " + LOT_ON_SALE_STATUSES + " AND l.quantity > 0 "
                + "AND NOT EXISTS ( "
                + "  SELECT 1 FROM device_sku_inventory i "
                + "  WHERE i.device_id = l.device_id AND i.sku_id = l.sku_id) "
                + "GROUP BY l.device_id, l.sku_id "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String key = row.get("device_id") + "|" + row.get("sku_id");
            failing.add(key);
            String lotQty = String.valueOf(row.get("lot_qty"));
            recordInconsistency(INVENTORY_ORPHAN_LOT, "device_sku_lot",
                    key, "0", lotQty,
                    "有在架批次合计 " + lotQty + " 但无汇总库存行");
        }
        resolveStaleFailuresIfComplete(INVENTORY_ORPHAN_LOT, failing, rows.size());
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
            String expected = String.valueOf(row.get(EXPECTED));
            String actual = String.valueOf(row.get("calculated"));
            recordInconsistency(POINTS_BALANCE, "member",
                    memberId, expected, actual,
                    "可用积分 " + expected + " ≠ 积分日志汇总 " + actual);
        }
        resolveStaleFailuresIfComplete(POINTS_BALANCE, failing, rows.size());
    }

    /**
     * 会员积分三角恒等式：available + used + expired = total。
     */
    void checkPointsIdentityConsistency() {
        String sql = "SELECT m.member_id, "
                + "COALESCE(m.total_points, 0) AS expected, "
                + "COALESCE(m.available_points, 0) + COALESCE(m.used_points, 0) "
                + "+ COALESCE(m.expired_points, 0) AS calculated "
                + "FROM member m "
                + "WHERE COALESCE(m.total_points, 0) <> COALESCE(m.available_points, 0) "
                + "+ COALESCE(m.used_points, 0) + COALESCE(m.expired_points, 0) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String memberId = String.valueOf(row.get("member_id"));
            failing.add(memberId);
            String expected = String.valueOf(row.get(EXPECTED));
            String actual = String.valueOf(row.get("calculated"));
            recordInconsistency(POINTS_IDENTITY, "member",
                    memberId, expected, actual,
                    "累计积分 " + expected + " ≠ available+used+expired " + actual);
        }
        resolveStaleFailuresIfComplete(POINTS_IDENTITY, failing, rows.size());
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
            String expected = String.valueOf(row.get(EXPECTED));
            String actual = String.valueOf(row.get(ACTUAL));
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
            recordInconsistency(WALLET_BALANCE, "user_account",
                    userId,
                    String.valueOf(row.get(EXPECTED)),
                    String.valueOf(row.get(ACTUAL)),
                    "账户余额 " + row.get(EXPECTED) + " ≠ 最近流水余额 "
                            + row.get(ACTUAL) + "（请人工核对充值/退款/调账）");
        }
        resolveStaleFailuresIfComplete(WALLET_BALANCE, failing, rows.size());
    }

    /**
     * 资金退款场景：
     * <ul>
     *   <li>购物单：订单已退字段 vs 已完成 REFUND 流水合计</li>
     *   <li>充值单：已完成 RECHARGE_REFUND 合计不得超过原单金额；已标 REFUNDED 须有退款流水</li>
     * </ul>
     */
    void checkRefundAmountConsistency() {
        Set<String> failing = new HashSet<>();
        int foundCount = 0;

        String orderSql = "SELECT o.order_id, COALESCE(o.refunded_cents, 0) AS expected, "
                + "COALESCE(SUM(po.amount_cents), 0) AS actual "
                + "FROM cabinet_order o "
                + "LEFT JOIN payment_operation po ON po.order_id = o.order_id "
                + "AND po.operation_type = 'REFUND' AND po.status = 'COMPLETED' "
                + "WHERE o.status IN ('REFUNDED', 'PARTIAL_REFUNDED') "
                + "GROUP BY o.order_id, o.refunded_cents "
                + "HAVING COALESCE(o.refunded_cents, 0) <> COALESCE(SUM(po.amount_cents), 0) "
                + "LIMIT " + CHECK_BATCH;

        List<Map<String, Object>> orderRows = jdbcTemplate.queryForList(orderSql);
        foundCount += orderRows.size();
        for (Map<String, Object> row : orderRows) {
            String orderId = String.valueOf(row.get(ORDER_ID));
            failing.add(orderId);
            recordInconsistency(REFUND_AMOUNT, "cabinet_order",
                    orderId,
                    String.valueOf(row.get(EXPECTED)),
                    String.valueOf(row.get(ACTUAL)),
                    "订单已退字段 " + row.get(EXPECTED) + " ≠ 退款流水合计 "
                            + row.get(ACTUAL) + "（请走退款/调账）");
        }

        // 充值净额场景：退款合计不可超过原单；REFUNDED 须有退款流水
        String rechargeSql = "SELECT r.order_id, r.amount_cents AS expected, "
                + "COALESCE(rf.refunded, 0) AS actual, r.status AS recharge_status "
                + "FROM recharge_order r "
                + "LEFT JOIN LATERAL ( "
                + "  SELECT SUM(po.amount_cents) AS refunded "
                + "  FROM payment_operation po "
                + "  WHERE po.status = 'COMPLETED' AND po.operation_type = 'RECHARGE_REFUND' "
                + "  AND (po.order_id = r.order_id "
                + "    OR po.idempotency_key ILIKE '%' || r.order_id || '%') "
                + ") rf ON true "
                + "WHERE r.status IN ('PAID', 'REFUNDED') "
                + "AND (COALESCE(rf.refunded, 0) > r.amount_cents "
                + "  OR (r.status = 'REFUNDED' AND COALESCE(rf.refunded, 0) = 0)) "
                + "LIMIT " + CHECK_BATCH;

        List<Map<String, Object>> rechargeRows = jdbcTemplate.queryForList(rechargeSql);
        foundCount += rechargeRows.size();
        for (Map<String, Object> row : rechargeRows) {
            String orderId = String.valueOf(row.get(ORDER_ID));
            String key = "RCH|" + orderId;
            failing.add(key);
            int expected = toInt(row.get(EXPECTED));
            int actual = toInt(row.get(ACTUAL));
            String status = String.valueOf(row.get("recharge_status"));
            String msg = actual > expected
                    ? "充值退款合计 " + actual + " 超过原单 " + expected
                    : "充值单状态 REFUNDED 但无 RECHARGE_REFUND 流水";
            recordInconsistency(REFUND_AMOUNT, "recharge_order",
                    key,
                    String.valueOf(expected),
                    String.valueOf(actual),
                    msg + "（status=" + status + "）");
        }

        resolveStaleFailuresIfComplete(REFUND_AMOUNT, failing, foundCount);
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
            recordInconsistency(ORDER_LINE_SUM, "cabinet_order_line",
                    key,
                    String.valueOf(row.get(EXPECTED)),
                    String.valueOf(row.get(ACTUAL)),
                    "行金额 " + row.get(EXPECTED) + " ≠ 单价×数量 " + row.get(ACTUAL));
        }
        resolveStaleFailuresIfComplete(ORDER_LINE_SUM, failing, rows.size());
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
            String orderId = String.valueOf(row.get(ORDER_ID));
            failing.add(orderId);
            recordInconsistency(COUPON_USED_LINK, "user_coupon",
                    orderId,
                    String.valueOf(row.get("order_discount")),
                    String.valueOf(row.get(COUPON_DISCOUNT)),
                    "订单券抵扣 " + row.get("order_discount") + " / 券ID "
                            + row.get("order_coupon_id") + " ≠ 核销券 "
                            + row.get(COUPON_DISCOUNT) + " / ID " + row.get("user_coupon_id"));
        }
        resolveStaleFailuresIfComplete(COUPON_USED_LINK, failing, rows.size());
    }

    /** 券定义已发数超过 max_issue_count（配额&gt;0 时）。 */
    void checkCouponOverQuotaConsistency() {
        String sql = "SELECT d.coupon_def_id, d.max_issue_count AS expected, d.issued_count AS actual "
                + "FROM coupon_definition d "
                + "WHERE COALESCE(d.max_issue_count, 0) > 0 "
                + "AND COALESCE(d.issued_count, 0) > d.max_issue_count "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String defId = String.valueOf(row.get("coupon_def_id"));
            failing.add(defId);
            recordInconsistency(COUPON_OVER_QUOTA, "coupon_definition",
                    defId,
                    String.valueOf(row.get(EXPECTED)),
                    String.valueOf(row.get(ACTUAL)),
                    "已发数 " + row.get(ACTUAL) + " 超过发放上限 " + row.get(EXPECTED));
        }
        resolveStaleFailuresIfComplete(COUPON_OVER_QUOTA, failing, rows.size());
    }

    /** 商户钱包余额 vs 最近一条流水 balance_after。 */
    void checkMerchantWalletConsistency() {
        String sql = "SELECT a.merchant_id, a.balance_cents AS expected, "
                + "latest.balance_after AS actual "
                + "FROM merchant_wallet_account a "
                + "JOIN LATERAL ( "
                + "  SELECT l.balance_after "
                + "  FROM merchant_wallet_ledger l "
                + "  WHERE l.merchant_id = a.merchant_id "
                + "  ORDER BY l.created_at DESC, l.ledger_id DESC "
                + "  LIMIT 1 "
                + ") latest ON true "
                + "WHERE a.balance_cents IS DISTINCT FROM latest.balance_after "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String merchantId = String.valueOf(row.get("merchant_id"));
            failing.add(merchantId);
            recordInconsistency(MERCHANT_WALLET, "merchant_wallet_account",
                    merchantId,
                    String.valueOf(row.get(EXPECTED)),
                    String.valueOf(row.get(ACTUAL)),
                    "商户钱包 " + row.get(EXPECTED) + " ≠ 最近流水余额 " + row.get(ACTUAL));
        }
        resolveStaleFailuresIfComplete(MERCHANT_WALLET, failing, rows.size());
    }

    /** 线路钱包余额 vs 最近一条流水 balance_after。 */
    void checkLineWalletConsistency() {
        String sql = "SELECT a.manager_id, a.balance_cents AS expected, "
                + "latest.balance_after AS actual "
                + "FROM line_wallet_account a "
                + "JOIN LATERAL ( "
                + "  SELECT l.balance_after "
                + "  FROM line_wallet_ledger l "
                + "  WHERE l.manager_id = a.manager_id "
                + "  ORDER BY l.created_at DESC, l.ledger_id DESC "
                + "  LIMIT 1 "
                + ") latest ON true "
                + "WHERE a.balance_cents IS DISTINCT FROM latest.balance_after "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String managerId = String.valueOf(row.get("manager_id"));
            failing.add(managerId);
            recordInconsistency(LINE_WALLET, "line_wallet_account",
                    managerId,
                    String.valueOf(row.get(EXPECTED)),
                    String.valueOf(row.get(ACTUAL)),
                    "线路钱包 " + row.get(EXPECTED) + " ≠ 最近流水余额 " + row.get(ACTUAL));
        }
        resolveStaleFailuresIfComplete(LINE_WALLET, failing, rows.size());
    }

    /** 分账行金额闭合：platform + merchant = gross（排除 VOIDED）。 */
    void checkRevenueSplitSumConsistency() {
        String sql = "SELECT s.split_id, s.order_id, s.gross_cents AS expected, "
                + "COALESCE(s.platform_cents, 0) + COALESCE(s.merchant_cents, 0) AS actual "
                + "FROM order_revenue_split s "
                + "WHERE UPPER(COALESCE(s.status, '')) <> 'VOIDED' "
                + "AND COALESCE(s.gross_cents, 0) <> COALESCE(s.platform_cents, 0) "
                + "+ COALESCE(s.merchant_cents, 0) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String key = row.get("order_id") + "|" + row.get("split_id");
            failing.add(key);
            recordInconsistency(REVENUE_SPLIT_SUM, "order_revenue_split",
                    key,
                    String.valueOf(row.get(EXPECTED)),
                    String.valueOf(row.get(ACTUAL)),
                    "分账毛额 " + row.get(EXPECTED) + " ≠ 平台+商户 " + row.get(ACTUAL));
        }
        resolveStaleFailuresIfComplete(REVENUE_SPLIT_SUM, failing, rows.size());
    }

    /** 已付/部分退订单缺少有效分账记录。 */
    void checkRevenueSplitMissingConsistency() {
        String sql = "SELECT o.order_id, o.total_amount_cents AS expected, 0 AS actual "
                + "FROM cabinet_order o "
                + "WHERE o.status IN ('PAID', 'PARTIAL_REFUNDED') "
                + "AND NOT EXISTS ( "
                + "  SELECT 1 FROM order_revenue_split s "
                + "  WHERE s.order_id = o.order_id "
                + "  AND UPPER(COALESCE(s.status, '')) <> 'VOIDED') "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String orderId = String.valueOf(row.get(ORDER_ID));
            failing.add(orderId);
            recordInconsistency(REVENUE_SPLIT_MISSING, "cabinet_order",
                    orderId,
                    String.valueOf(row.get(EXPECTED)),
                    "0",
                    "已付订单缺少有效分账记录");
        }
        resolveStaleFailuresIfComplete(REVENUE_SPLIT_MISSING, failing, rows.size());
    }

    /** 货道绑定 SKU 与在架批次 SKU 不一致。 */
    void checkSlotSkuMismatchConsistency() {
        String sql = "SELECT l.device_id, l.slot_id AS slot_code, "
                + "s.assigned_sku_id AS expected, l.sku_id AS actual, "
                + "COALESCE(SUM(l.quantity), 0) AS lot_qty "
                + "FROM device_sku_lot l "
                + "JOIN device_slot s ON s.device_id = l.device_id AND s.slot_code = l.slot_id "
                + "WHERE l.slot_id IS NOT NULL AND s.assigned_sku_id IS NOT NULL "
                + "AND " + LOT_ON_SALE_STATUSES + " AND l.quantity > 0 "
                + "AND l.sku_id <> s.assigned_sku_id "
                + "GROUP BY l.device_id, l.slot_id, s.assigned_sku_id, l.sku_id "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String key = row.get("device_id") + "|" + row.get("slot_code");
            failing.add(key);
            recordInconsistency(SLOT_SKU_MISMATCH, "device_slot",
                    key,
                    String.valueOf(row.get(EXPECTED)),
                    String.valueOf(row.get(ACTUAL)),
                    "货道绑定 " + row.get(EXPECTED) + " ≠ 在架批 SKU " + row.get(ACTUAL)
                            + "（qty=" + row.get("lot_qty") + "）");
        }
        resolveStaleFailuresIfComplete(SLOT_SKU_MISMATCH, failing, rows.size());
    }

    /** 货道在架批合计超过 max_level。 */
    void checkSlotCapacityConsistency() {
        String sql = "SELECT s.device_id, s.slot_code, s.max_level AS expected, "
                + "COALESCE(SUM(l.quantity), 0) AS actual "
                + "FROM device_slot s "
                + "LEFT JOIN device_sku_lot l ON l.device_id = s.device_id AND l.slot_id = s.slot_code "
                + "AND " + LOT_ON_SALE_STATUSES + " "
                + "WHERE s.max_level > 0 "
                + "GROUP BY s.device_id, s.slot_code, s.max_level "
                + "HAVING COALESCE(SUM(l.quantity), 0) > s.max_level "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String key = row.get("device_id") + "|" + row.get("slot_code");
            failing.add(key);
            recordInconsistency(SLOT_CAPACITY, "device_slot",
                    key,
                    String.valueOf(row.get(EXPECTED)),
                    String.valueOf(row.get(ACTUAL)),
                    "在架 " + row.get(ACTUAL) + " 超过货道容量 " + row.get(EXPECTED));
        }
        resolveStaleFailuresIfComplete(SLOT_CAPACITY, failing, rows.size());
    }

    /**
     * 近 30 天物理盘点数量 vs 货道在架批合计。
     * 仅对有 last_physical_qty 且盘点时间在窗口内的货道告警。
     */
    void checkSlotPhysicalConsistency() {
        String sql = "SELECT s.device_id, s.slot_code, s.last_physical_qty AS expected, "
                + "COALESCE(SUM(l.quantity), 0) AS actual "
                + "FROM device_slot s "
                + "LEFT JOIN device_sku_lot l ON l.device_id = s.device_id AND l.slot_id = s.slot_code "
                + "AND " + LOT_ON_SALE_STATUSES + " "
                + "WHERE s.last_physical_qty IS NOT NULL "
                + "AND s.last_physical_at IS NOT NULL "
                + "AND s.last_physical_at >= (NOW() - INTERVAL '30 days') "
                + "GROUP BY s.device_id, s.slot_code, s.last_physical_qty "
                + "HAVING s.last_physical_qty IS DISTINCT FROM COALESCE(SUM(l.quantity), 0) "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String key = row.get("device_id") + "|" + row.get("slot_code");
            failing.add(key);
            recordInconsistency(SLOT_PHYSICAL, "device_slot",
                    key,
                    String.valueOf(row.get(EXPECTED)),
                    String.valueOf(row.get(ACTUAL)),
                    "近30天盘点 " + row.get(EXPECTED) + " ≠ 在架批 " + row.get(ACTUAL));
        }
        resolveStaleFailuresIfComplete(SLOT_PHYSICAL, failing, rows.size());
    }

    /** 仓存数量为负（明显账实异常）。 */
    void checkWarehouseNegativeConsistency() {
        String sql = "SELECT i.warehouse_id, i.sku_id, COALESCE(i.batch_no, '') AS batch_no, "
                + "0 AS expected, i.quantity AS actual "
                + "FROM warehouse_inventory i "
                + "WHERE i.quantity < 0 "
                + "LIMIT " + CHECK_BATCH;

        Set<String> failing = new HashSet<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        for (Map<String, Object> row : rows) {
            String key = row.get("warehouse_id") + "|" + row.get("sku_id") + "|" + row.get("batch_no");
            failing.add(key);
            recordInconsistency(WAREHOUSE_NEGATIVE, "warehouse_inventory",
                    key,
                    "0",
                    String.valueOf(row.get(ACTUAL)),
                    "仓存数量为负 " + row.get(ACTUAL));
        }
        resolveStaleFailuresIfComplete(WAREHOUSE_NEGATIVE, failing, rows.size());
    }

    /**
     * 关联投影漂移：已付缺扣库标记/SALE、退款缺库存审计、争议终态关联、订单 merchant_id 与柜机归属不一致。
     * 全部记入 CROSS_LINK，仅人工处理。
     */
    void checkCrossLinkConsistency() {
        Set<String> failing = new HashSet<>();
        failing.addAll(checkCrossLinkMissingSale());
        failing.addAll(checkCrossLinkRefundInventory());
        failing.addAll(checkCrossLinkDispute());
        failing.addAll(checkCrossLinkOrderMerchant());
        resolveStaleFailuresIfComplete(CROSS_LINK, failing, failing.size());
    }

    /** A：已付有货但未扣库（inventory_deducted=false），或柜机有批次账本却无 SALE 流水。 */
    private Set<String> checkCrossLinkMissingSale() {
        String sql = "SELECT o.order_id, "
                + "CASE WHEN o.inventory_deducted = FALSE THEN 'INVENTORY_FLAG' ELSE 'MISSING_SALE' END AS reason "
                + "FROM cabinet_order o "
                + "WHERE o.status IN ('PAID', 'PARTIAL_REFUNDED') "
                + "AND EXISTS (SELECT 1 FROM cabinet_order_line ol "
                + "  WHERE ol.order_id = o.order_id AND ol.quantity > 0) "
                + "AND ("
                + "  o.inventory_deducted = FALSE "
                + "  OR ("
                + "    EXISTS (SELECT 1 FROM device_sku_lot l WHERE l.device_id = o.device_id) "
                + "    AND NOT EXISTS ("
                + "      SELECT 1 FROM inventory_movement m "
                + "      WHERE m.movement_type = 'SALE' AND m.ref_type = 'ORDER' AND m.ref_id = o.order_id"
                + "    )"
                + "  )"
                + ") "
                + "LIMIT " + CHECK_BATCH;
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            String orderId = String.valueOf(row.get(ORDER_ID));
            String key = "SALE|" + orderId;
            keys.add(key);
            String reason = String.valueOf(row.get("reason"));
            String msg = "INVENTORY_FLAG".equals(reason)
                    ? "已付有货但 inventory_deducted=false"
                    : "已付有货且柜机有批次账本，但缺少 SALE+ORDER 库存流水";
            recordInconsistency(CROSS_LINK, "cabinet_order", key, "SALE_OK", reason, msg);
        }
        return keys;
    }

    /** B：存在已完成购物退款，但无 REFUND / REFUND_KEPT 库存审计。 */
    private Set<String> checkCrossLinkRefundInventory() {
        String sql = "SELECT DISTINCT po.order_id "
                + "FROM payment_operation po "
                + "JOIN cabinet_order o ON o.order_id = po.order_id "
                + "WHERE po.operation_type = 'REFUND' AND po.status = 'COMPLETED' "
                + "AND o.status IN ('REFUNDED', 'PARTIAL_REFUNDED', 'PAID') "
                + "AND NOT EXISTS ("
                + "  SELECT 1 FROM inventory_movement m "
                + "  WHERE m.movement_type IN ('REFUND', 'REFUND_KEPT') "
                + "  AND (m.ref_id = po.order_id OR m.ref_id LIKE po.order_id || ':%')"
                + ") "
                + "LIMIT " + CHECK_BATCH;
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            String orderId = String.valueOf(row.get(ORDER_ID));
            String key = "RFINV|" + orderId;
            keys.add(key);
            recordInconsistency(CROSS_LINK, "inventory_movement", key,
                    "REFUND_OR_KEPT", "MISSING",
                    "购物退款已完成，但缺少 REFUND/REFUND_KEPT 库存审计流水");
        }
        return keys;
    }

    /** C：争议结案缺订单，或仍 OPEN 但订单已 PAID 终态。 */
    private Set<String> checkCrossLinkDispute() {
        String sql = "SELECT d.ticket_id, d.status AS ticket_status, s.order_id, o.status AS order_status "
                + "FROM dispute_ticket d "
                + "LEFT JOIN shopping_session s ON s.session_id = d.session_id "
                + "LEFT JOIN cabinet_order o ON o.order_id = s.order_id "
                + "WHERE ("
                + "  (UPPER(COALESCE(d.status,'')) IN ('RESOLVED','CLOSED') "
                + "    AND (s.order_id IS NULL OR s.order_id = '' OR o.order_id IS NULL)) "
                + "  OR (UPPER(COALESCE(d.status,'')) = 'OPEN' "
                + "    AND UPPER(COALESCE(o.status,'')) = 'PAID')"
                + ") "
                + "LIMIT " + CHECK_BATCH;
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            String ticketId = String.valueOf(row.get("ticket_id"));
            String key = "DSP|" + ticketId;
            keys.add(key);
            String ticketStatus = String.valueOf(row.get("ticket_status"));
            String orderStatus = row.get("order_status") == null ? "null" : String.valueOf(row.get("order_status"));
            String msg = "OPEN".equalsIgnoreCase(ticketStatus)
                    ? "争议仍 OPEN 但订单已 PAID（可能结案遗漏）"
                    : "争议已结案但缺少关联订单";
            recordInconsistency(CROSS_LINK, "dispute_ticket", key,
                    "LINKED", ticketStatus + "/" + orderStatus, msg);
        }
        return keys;
    }

    /**
     * D：订单快照 merchant_id 与当前柜机归属不一致（鉴权字段漂移；device_name 允许历史快照差异，不检）。
     */
    private Set<String> checkCrossLinkOrderMerchant() {
        String sql = "SELECT o.order_id, o.merchant_id AS order_merchant, d.merchant_id AS device_merchant "
                + "FROM cabinet_order o "
                + "JOIN device_info d ON d.device_id = o.device_id "
                + "WHERE o.merchant_id IS NOT NULL AND btrim(o.merchant_id) <> '' "
                + "AND d.merchant_id IS NOT NULL AND btrim(d.merchant_id) <> '' "
                + "AND o.merchant_id IS DISTINCT FROM d.merchant_id "
                + "LIMIT " + CHECK_BATCH;
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            String orderId = String.valueOf(row.get(ORDER_ID));
            String key = "ODV|" + orderId;
            keys.add(key);
            recordInconsistency(CROSS_LINK, "cabinet_order", key,
                    String.valueOf(row.get("device_merchant")),
                    String.valueOf(row.get("order_merchant")),
                    "订单快照 merchant_id 与柜机当前归属不一致（可能转租/易主）");
        }
        return keys;
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
            for (DataConsistencyRecord consistencyRecord : open) {
                if (stillFailing != null && stillFailing.contains(consistencyRecord.getCheckKey())) {
                    continue;
                }
                consistencyRecord.setStatus(STATUS_FIXED);
                consistencyRecord.setFixedAt(now);
                consistencyRepository.save(consistencyRecord);
                log.info("一致性误报已自动关闭 type={} key={} recordId={}",
                        checkType, consistencyRecord.getCheckKey(), consistencyRecord.getId());
            }
        } catch (Exception e) {
            log.error("关闭过期一致性 FAIL 失败 type={}", checkType, e);
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
                DataConsistencyRecord consistencyRecord = new DataConsistencyRecord();
                consistencyRecord.setCheckType(checkType);
                consistencyRecord.setTableName(tableName);
                consistencyRecord.setCheckKey(checkKey);
                consistencyRecord.setExpectedValue(expected);
                consistencyRecord.setActualValue(actual);
                consistencyRecord.setErrorMessage(errorMessage);
                consistencyRecord.setStatus(STATUS_FAIL);
                consistencyRecord.setCheckedAt(Instant.now());
                consistencyRepository.save(consistencyRecord);
            }
        } catch (Exception e) {
            log.error("持久化一致性记录失败 type={} key={}", checkType, checkKey, e);
        }

        log.warn("发现数据不一致 type={} key={} expected={} actual={}",
                checkType, checkKey, expected, actual);
    }

    static String consistencyCheckLockKey(String checkType, String checkKey) {
        return "data-consistency:check:" + checkType + ":" + checkKey;
    }

    /** 人工修复入口：ORDER_AMOUNT / INVENTORY_* / POINTS_IDENTITY / 行金额 / 券核销 / 支付多收 可修。 */
    @Transactional
    public boolean fixInconsistency(Long recordId) {
        return self.fixInconsistencyDetailed(recordId).fixed();
    }

    /** 带说明的修复入口（运营控制台）。 */
    @Transactional
    public FixOutcome fixInconsistencyDetailed(Long recordId) {
        return runWithConsistencyRecordLock(recordId, () -> doFixInconsistencyDetailed(recordId));
    }

    private FixOutcome doFixInconsistencyDetailed(Long recordId) {
        DataConsistencyRecord consistencyRecord = consistencyRepository.findByIdForUpdate(recordId).orElse(null);
        if (consistencyRecord == null) {
            return FixOutcome.fail("记录不存在");
        }

        try {
            FixOutcome outcome = applyFix(consistencyRecord);
            if (outcome.fixed()) {
                consistencyRecord.setStatus(STATUS_FIXED);
                consistencyRecord.setFixedAt(Instant.now());
                if (outcome.message() != null) {
                    consistencyRecord.setErrorMessage(outcome.message());
                }
                consistencyRepository.save(consistencyRecord);
                log.info("已修复一致性问题 recordId={} msg={}", recordId, outcome.message());
            }
            return outcome;
        } catch (Exception e) {
            log.error("修复一致性问题失败 recordId={}", recordId, e);
            return FixOutcome.fail("修复异常: " + e.getMessage());
        }
    }

    static String recordLockKey(Long recordId) {
        return "data-consistency:record:" + recordId;
    }

    private <T> T runWithConsistencyRecordLock(Long recordId, java.util.function.Supplier<T> action) {
        String key = recordLockKey(recordId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "一致性记录处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private FixOutcome applyFix(DataConsistencyRecord consistencyRecord) {
        return switch (consistencyRecord.getCheckType()) {
            case ORDER_AMOUNT -> fixOrderAmount(consistencyRecord);
            case INVENTORY_MISMATCH -> fixInventoryMismatch(consistencyRecord);
            case INVENTORY_ORPHAN_LOT -> fixInventoryOrphanLot(consistencyRecord);
            case POINTS_IDENTITY -> fixPointsIdentity(consistencyRecord);
            case ORDER_LINE_SUM -> fixOrderLineSum(consistencyRecord);
            case COUPON_USED_LINK -> fixCouponUsedLink(consistencyRecord);
            case PAYMENT_AMOUNT -> fixPaymentAmount(consistencyRecord);
            case REFUND_AMOUNT, WALLET_BALANCE, MERCHANT_WALLET, LINE_WALLET,
                 REVENUE_SPLIT_SUM, REVENUE_SPLIT_MISSING, SLOT_SKU_MISMATCH,
                 SLOT_CAPACITY, SLOT_PHYSICAL, WAREHOUSE_NEGATIVE, COUPON_OVER_QUOTA,
                 POINTS_BALANCE, CROSS_LINK ->
                    FixOutcome.fail("该类仅巡检记录，请人工核对处理");
            default -> FixOutcome.fail("不支持自动修复的类型: " + consistencyRecord.getCheckType());
        };
    }

    private FixOutcome fixOrderAmount(DataConsistencyRecord consistencyRecord) {
        String orderId = consistencyRecord.getCheckKey();
        OrderAmountSnapshot snap = loadOrderAmountSnapshot(orderId);
        if (snap == null) {
            return FixOutcome.fail("订单不存在");
        }
        if (snap.header() == snap.payableFromLines()) {
            return FixOutcome.ok("头金额已与明细折后一致");
        }
        FixOutcome couponFix = tryClearStaleCouponMismatch(orderId, snap);
        if (couponFix != null) {
            return couponFix;
        }
        FixOutcome paidEqualsHeader = tryFixWhenPaidEqualsHeader(orderId, snap);
        if (paidEqualsHeader != null) {
            return paidEqualsHeader;
        }
        return tryAlignHeaderToPayable(orderId, snap);
    }

    private OrderAmountSnapshot loadOrderAmountSnapshot(String orderId) {
        Integer header = queryNullableInt(
                "SELECT total_amount_cents FROM cabinet_order WHERE order_id = ?",
                orderId);
        if (header == null) {
            return null;
        }
        int paid = queryInt(
                "SELECT COALESCE(SUM(CASE "
                        + "WHEN operation_type IN ('CHARGE', 'ADJUST_CHARGE') THEN amount_cents "
                        + "WHEN operation_type = 'REFUND' THEN -amount_cents "
                        + "ELSE 0 END), 0) "
                        + "FROM payment_operation "
                        + "WHERE order_id = ? AND status = 'COMPLETED' "
                        + "AND operation_type IN ('CHARGE', 'ADJUST_CHARGE', 'REFUND')",
                orderId);
        int lineSum = queryInt(
                "SELECT COALESCE(SUM(line_amount_cents), 0) FROM cabinet_order_line WHERE order_id = ?",
                orderId);
        int couponDiscountCents = queryInt(
                "SELECT COALESCE(coupon_discount_cents, 0) FROM cabinet_order WHERE order_id = ?",
                orderId);
        int memberDiscountCents = queryInt(
                "SELECT COALESCE(member_discount_cents, 0) FROM cabinet_order WHERE order_id = ?",
                orderId);
        int lineCount = queryInt(
                "SELECT COUNT(*) FROM cabinet_order_line WHERE order_id = ?",
                orderId);
        return new OrderAmountSnapshot(header, paid, lineSum, couponDiscountCents, memberDiscountCents,
                lineSum - couponDiscountCents - memberDiscountCents, lineCount);
    }

    private FixOutcome tryClearStaleCouponMismatch(String orderId, OrderAmountSnapshot snap) {
        if (snap.paid() == snap.header() && snap.paid() == snap.lineSum()
                && (snap.couponDiscountCents() > 0 || snap.memberDiscountCents() > 0)
                && snap.payableFromLines() != snap.header()) {
            return clearStaleCouponFields(orderId, snap.couponDiscountCents(), snap.memberDiscountCents());
        }
        return null;
    }

    private FixOutcome tryFixWhenPaidEqualsHeader(String orderId, OrderAmountSnapshot snap) {
        if (snap.paid() != snap.header()) {
            return null;
        }
        if (snap.lineCount() == 1 && snap.couponDiscountCents() == 0 && snap.memberDiscountCents() == 0
                && snap.lineSum() != snap.header()) {
            return alignSingleLineToHeader(orderId, snap.header());
        }
        if (snap.lineCount() == 0) {
            return FixOutcome.fail("无明细行，无法自动补 SKU，请人工补行");
        }
        return FixOutcome.fail("多行明细与头金额不一致，需人工拆分/改价");
    }

    private FixOutcome tryAlignHeaderToPayable(String orderId, OrderAmountSnapshot snap) {
        if (snap.lineSum() > 0 && snap.paid() == 0) {
            jdbcTemplate.update(
                    UPDATE_CABINET_ORDER_SET_TOTAL_AMOUNT_CENTS_WHERE_ORDER_ID,
                    snap.payableFromLines(), orderId);
            return FixOutcome.ok("无匹配入账流水，已把头金额改为明细折后 " + snap.payableFromLines());
        }
        if (snap.lineSum() > 0 && snap.paid() == snap.payableFromLines() && snap.paid() != snap.header()) {
            jdbcTemplate.update(
                    UPDATE_CABINET_ORDER_SET_TOTAL_AMOUNT_CENTS_WHERE_ORDER_ID,
                    snap.payableFromLines(), orderId);
            return FixOutcome.ok("实付已与折后一致，已同步订单头为 " + snap.payableFromLines());
        }
        return FixOutcome.fail("明细折后与入账不一致，无法自动修复（请走退款/调账或人工改券）");
    }

    private record OrderAmountSnapshot(int header, int paid, int lineSum, int couponDiscountCents,
                                       int memberDiscountCents, int payableFromLines, int lineCount) {}

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
                    UPDATE_CABINET_ORDER_SET_TOTAL_AMOUNT_CENTS_WHERE_ORDER_ID,
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

    private FixOutcome fixOrderLineSum(DataConsistencyRecord consistencyRecord) {
        String[] parts = consistencyRecord.getCheckKey().split("\\|", 2);
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
        int header = ((Number) row.get(TOTAL_AMOUNT_CENTS)).intValue();
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
                    UPDATE_CABINET_ORDER_SET_TOTAL_AMOUNT_CENTS_WHERE_ORDER_ID,
                    alignedLine, orderId);
            String refundMsg = refundOverchargeIfNeeded(orderId, alignedLine);
            return FixOutcome.ok("已对齐行金额 " + alignedLine + "（原 " + line + "），并同步订单头" + refundMsg);
        }
        return FixOutcome.ok("已对齐行金额 " + alignedLine + "（原 " + line + "）");
    }

    /** 实付大于订单头时退多收差额（常见于修行金额后遗留尾差）。 */
    private FixOutcome fixPaymentAmount(DataConsistencyRecord consistencyRecord) {
        String orderId = consistencyRecord.getCheckKey();
        int expected = parseRecordCents(consistencyRecord.getExpectedValue());
        int actual = parseRecordCents(consistencyRecord.getActualValue());
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

    private FixOutcome fixCouponUsedLink(DataConsistencyRecord consistencyRecord) {
        String orderId = consistencyRecord.getCheckKey();
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

    private FixOutcome fixInventoryMismatch(DataConsistencyRecord consistencyRecord) {
        String[] parts = consistencyRecord.getCheckKey().split("\\|", 2);
        if (parts.length < 2) {
            return FixOutcome.fail("库存键格式无效，期望 deviceId|skuId");
        }
        String sql = "UPDATE device_sku_inventory SET quantity = CAST(? AS INT) "
                + "WHERE device_id = ? AND sku_id = ?";
        int updated = jdbcTemplate.update(sql, consistencyRecord.getActualValue(), parts[0], parts[1]);
        if (updated <= 0) {
            return FixOutcome.fail("未更新到库存行");
        }
        return FixOutcome.ok("已将汇总库存改为在架批次合计 " + consistencyRecord.getActualValue());
    }

    /** 按在架批次合计补建/回写缺失的汇总库存行。 */
    private FixOutcome fixInventoryOrphanLot(DataConsistencyRecord consistencyRecord) {
        String[] parts = consistencyRecord.getCheckKey().split("\\|", 2);
        if (parts.length < 2) {
            return FixOutcome.fail("库存键格式无效，期望 deviceId|skuId");
        }
        String deviceId = parts[0];
        String skuId = parts[1];
        int lotQty = queryInt(
                "SELECT COALESCE(SUM(quantity), 0) FROM device_sku_lot "
                        + "WHERE device_id = ? AND sku_id = ? "
                        + "AND UPPER(COALESCE(status, '')) IN ('ON_SALE', 'NEAR_EXPIRY')",
                deviceId, skuId);
        int updated = jdbcTemplate.update(
                "INSERT INTO device_sku_inventory (device_id, sku_id, quantity, updated_at) "
                        + "VALUES (?, ?, ?, NOW()) "
                        + "ON CONFLICT (device_id, sku_id) DO UPDATE "
                        + "SET quantity = EXCLUDED.quantity, updated_at = NOW()",
                deviceId, skuId, lotQty);
        if (updated <= 0) {
            return FixOutcome.fail("未能写入汇总库存行");
        }
        return FixOutcome.ok("已按在架批次合计 " + lotQty + " 补齐汇总库存");
    }

    /** 以 available+used+expired 为准回写 total_points。 */
    private FixOutcome fixPointsIdentity(DataConsistencyRecord consistencyRecord) {
        String memberId = consistencyRecord.getCheckKey();
        int updated = jdbcTemplate.update(
                "UPDATE member SET total_points = COALESCE(available_points, 0) "
                        + "+ COALESCE(used_points, 0) + COALESCE(expired_points, 0), "
                        + "updated_at = NOW() "
                        + "WHERE member_id = CAST(? AS BIGINT) "
                        + "AND COALESCE(total_points, 0) <> COALESCE(available_points, 0) "
                        + "+ COALESCE(used_points, 0) + COALESCE(expired_points, 0)",
                memberId);
        if (updated <= 0) {
            return FixOutcome.fail("会员不存在或已一致");
        }
        return FixOutcome.ok("已按 available+used+expired 回写累计积分");
    }

    public List<DataConsistencyRecord> getFailedChecks() {
        return consistencyRepository.findByStatus(STATUS_FAIL);
    }

    public List<DataChangeLog> getUnverifiedChanges() {
        return changeLogRepository.findByVerifiedFalse();
    }
}
