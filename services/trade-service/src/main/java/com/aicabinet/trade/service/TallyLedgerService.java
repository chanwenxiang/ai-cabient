package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.TallyRecordRowDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * C2 理货记录台账（老系统 stock_operator_record 对位，2026-10-15 backlog 拍板项）。
 *
 * <p>老系统语义：按「柜机 × 操作员 × SKU」记每日库存更新总量。新系统补货动作已结构化在
 * replenishment_task（谁/哪个柜/何时）× replenishment_task_line（什么货/几件/上架或下架），
 * 本台账为**纯只读聚合视图**，不建新表不写数据——补货链路落库即入台账。
 * 与老系统差异（有意不同）：老系统为日汇总粒度且无批次/货道维度；新系统行粒度更细
 * （批次号/货道/上架下架类型），台账直接展示明细行。
 */
@Service
public class TallyLedgerService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;

    public TallyLedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 理货台账分页。
     *
     * <p>R23X C44：本方法自拼 SQL，旧的 WHERE 只有「时间窗 + assignee>0」，**不带设备范围**——
     * 有 {@code ops:replenishment:list} 功能码的域受限账号一页就能读到全平台理货明细。
     * 现在设备范围由调用方解析成集合下推（{@code null}=不限，空集=无可见柜机 ⇒ 直接空页），
     * 与补货路线/履约任务/在途分页同一契约（{@code MerchantScopeService#pushdownDeviceIds}）。
     *
     * <p>单值 {@code deviceId} 参数已移除：留着「只按柜号过滤」的口子，下一个调用方照样能绕过范围。
     */
    @Transactional(readOnly = true)
    public PageResult<TallyRecordRowDto> ledger(int page, int size, java.util.Collection<String> deviceIds,
                                                Long operatorUserId, int days) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int window = Math.min(Math.max(days, 1), 365);
        if (deviceIds != null && deviceIds.isEmpty()) {
            return new PageResult<>(List.of(), safePage, safeSize, 0);
        }
        Instant since = LocalDate.now(ZONE).minusDays(window - 1L).atStartOfDay(ZONE).toInstant();
        // JDBC 驱动无法推断 Instant 类型（Can't infer the SQL type），统一转 Timestamp
        java.sql.Timestamp sinceTs = java.sql.Timestamp.from(since);

        StringBuilder where = new StringBuilder(
                " WHERE t.created_at >= ? AND COALESCE(t.assignee_user_id, 0) > 0");
        List<Object> args = new ArrayList<>();
        args.add(sinceTs);
        if (deviceIds != null) {
            where.append(" AND t.device_id IN (")
                    .append(String.join(",", java.util.Collections.nCopies(deviceIds.size(), "?")))
                    .append(')');
            args.addAll(deviceIds);
        }
        if (operatorUserId != null && operatorUserId > 0) {
            where.append(" AND t.assignee_user_id = ?");
            args.add(operatorUserId);
        }

        String baseSql = " FROM replenishment_task t"
                + " JOIN replenishment_task_line l ON l.task_id = t.task_id"
                + " LEFT JOIN user_info u ON u.user_id = t.assignee_user_id"
                + " LEFT JOIN sku_catalog s ON s.sku_id = l.sku_id"
                + where;

        Long total = jdbc.queryForObject("SELECT COUNT(*)" + baseSql, Long.class, args.toArray());
        String sql = "SELECT l.line_id, t.task_id, t.device_id, t.assignee_user_id, u.name AS operator_name,"
                + " t.status AS task_status, l.line_type, l.sku_id, COALESCE(s.sku_name, l.sku_id) AS sku_name,"
                + " l.quantity, l.slot_id, l.applied, t.completed_at"
                + baseSql
                + " ORDER BY COALESCE(t.completed_at, t.created_at) DESC, t.task_id DESC, l.line_id ASC"
                + " LIMIT ? OFFSET ?";
        args.add(safeSize);
        args.add((long) safePage * safeSize);
        List<TallyRecordRowDto> rows = jdbc.query(sql, (rs, i) -> new TallyRecordRowDto(
                rs.getLong("line_id"),
                rs.getLong("task_id"),
                rs.getString("device_id"),
                (Long) rs.getObject("assignee_user_id"),
                rs.getString("operator_name"),
                rs.getString("task_status"),
                rs.getString("line_type"),
                rs.getString("sku_id"),
                rs.getString("sku_name"),
                (Integer) rs.getObject("quantity"),
                rs.getString("slot_id"),
                (Boolean) rs.getObject("applied"),
                rs.getTimestamp("completed_at") == null
                        ? null
                        : rs.getTimestamp("completed_at").toInstant()
        ), args.toArray());
        return new PageResult<>(rows, safePage, safeSize, total == null ? 0 : total);
    }
}
