package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdminAuditLogDto;
import com.aicabinet.common.dto.AdminAuditSensitiveSummaryDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.util.PhoneMask;
import com.aicabinet.trade.domain.AdminAuditLog;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运营审计日志只读查询（原 AdminDashboardService 审计簇）。
 * Pass 3F PR-A：从神类拆出，门面仍可委托本类。
 */
@Service
public class OpsAuditQueryService {

    private final PermissionService permissionService;
    private final AdminAuditLogMapper auditLogRepository;
    private final UserInfoMapper userInfoRepository;
    private final JdbcTemplate jdbc;

    public OpsAuditQueryService(PermissionService permissionService,
                                AdminAuditLogMapper auditLogRepository,
                                UserInfoMapper userInfoRepository,
                                JdbcTemplate jdbc) {
        this.permissionService = permissionService;
        this.auditLogRepository = auditLogRepository;
        this.userInfoRepository = userInfoRepository;
        this.jdbc = jdbc;
    }

    public PageResult<AdminAuditLogDto> listAuditLogs(Long operatorId, int page, int size, boolean logIdAsc) {
        return listAuditLogs(operatorId, page, size, logIdAsc, null, null, false);
    }

    public PageResult<AdminAuditLogDto> listAuditLogs(
            Long operatorId,
            int page,
            int size,
            boolean logIdAsc,
            String action,
            String targetType,
            boolean mineOnly) {
        permissionService.requirePermission(operatorId, "ops:audit:list");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        Long operatorFilter = mineOnly ? operatorId : null;
        Page<AdminAuditLog> result = auditLogRepository.searchPage(
                operatorFilter, blankToNull(action), blankToNull(targetType), logIdAsc, p, s);
        return toAuditPage(result);
    }

    public List<AdminAuditLogDto> listRecentAuditLogs(Long operatorId, int size, boolean mineOnly) {
        permissionService.requireAnyPermission(operatorId, "ops:audit:recent", "ops:audit:list");
        int limit = Math.min(Math.max(size, 1), 50);
        Pageable pageable = PageRequest.of(0, limit);
        Page<AdminAuditLog> result = mineOnly
                ? auditLogRepository.findByOperatorIdOrderByCreatedAtDesc(operatorId, pageable)
                : auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        return enrichAuditLogs(result.getContent());
    }

    private PageResult<AdminAuditLogDto> toAuditPage(Page<AdminAuditLog> result) {
        return new PageResult<>(
                enrichAuditLogs(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    private List<AdminAuditLogDto> enrichAuditLogs(List<AdminAuditLog> logs) {
        if (logs.isEmpty()) {
            return List.of();
        }
        List<Long> operatorIds = logs.stream()
                .map(AdminAuditLog::getOperatorId)
                .distinct()
                .toList();
        Map<Long, UserInfo> users = userInfoRepository.findByUserIdIn(operatorIds).stream()
                .collect(Collectors.toMap(UserInfo::getUserId, u -> u));
        return logs.stream()
                .map(log -> toAuditDto(log, users.get(log.getOperatorId())))
                .toList();
    }

    private static AdminAuditLogDto toAuditDto(AdminAuditLog log, UserInfo operator) {
        Long opId = log.getOperatorId();
        String phone = operator != null ? PhoneMask.mask(operator.getPhoneNumber()) : null;
        String name = operator != null ? operator.getName() : null;
        if (opId == null || opId <= 0L) {
            name = "系统";
            phone = null;
        } else if (name == null || name.isBlank()) {
            name = phone != null && !phone.isBlank() ? phone : ("账号 " + opId);
        }
        return new AdminAuditLogDto(
                log.getLogId(), opId, phone, name, log.getAction(),
                log.getTargetType(), log.getTargetId(), log.getDetail(), log.getCreatedAt()
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    // ═══ CB-021④ 敏感操作汇总报表（对齐思迅「收银员异常操作」形态：只记录不处理）═══

    /**
     * 敏感操作 action 前缀白名单（资金/权限/凭据/数据直改族）。
     * 枚举式清单会漂移（见 {@link AdminDataManageService} 前缀教训），故本表只用
     * 「族前缀」表达；新增敏感 action 命中任一前缀即自动纳入报表，无需改这里。
     */
    static final List<String> SENSITIVE_ACTION_PREFIXES = List.of(
            "BALANCE_ADJUST", "BALANCE_REFUND", "RECHARGE_REFUND",        // 余额/充值退款
            "SPLIT_LEDGER", "PROFIT_SHARING",                              // 分账
            "SITE_RENT", "DEVICE_RENTAL", "DEVICE_DATA_FEE",               // 租金/押金/数据费
            "DEVICE_MQTT_CREDENTIAL", "DEVICE_HTTP_CREDENTIAL",            // 凭据签发/吊销
            "DEVICE_REMOTE_OPEN", "DEVICE_RESET_HARDWARE", "DEVICE_REGENERATE_ID",
            "OPS_ROLE", "OPS_PERM", "OPS_USER_ROLE", "ORG_NODE_ASSIGN",    // 角色/权限/组织
            "APPROVAL_DEF", "CONFIG_",                                     // 审批流/系统配置
            "DATA_UPDATE", "DATA_DELETE", "DATA_INSERT");                  // 数据直改

    /**
     * 近 N 天敏感操作按 action 聚合（计数 + 最近一次时间），供运营日常巡视。
     * 与明细页同权限点 {@code ops:audit:list}（CB-021③：日志可见性本身是权限点）。
     */
    public List<AdminAuditSensitiveSummaryDto> sensitiveSummary(Long operatorId, int days) {
        permissionService.requirePermission(operatorId, "ops:audit:list");
        int safeDays = Math.min(Math.max(days, 1), 90);
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < SENSITIVE_ACTION_PREFIXES.size(); i++) {
            where.append(i == 0 ? " WHERE (" : " OR ").append("action LIKE ?").append(i + 1).append(" ");
        }
        where.append(") AND created_at >= NOW() - (").append(safeDays).append(" || ' days')::interval");
        String sql = "SELECT action, COUNT(*) AS total, MAX(created_at) AS last_at"
                + " FROM admin_audit_log" + where
                + " GROUP BY action ORDER BY total DESC, action ASC";
        Object[] patterns = SENSITIVE_ACTION_PREFIXES.stream().map(p -> p + "%").toArray();
        return jdbc.query(sql, (rs, i) -> new AdminAuditSensitiveSummaryDto(
                rs.getString("action"),
                rs.getLong("total"),
                rs.getObject("last_at", Instant.class)), patterns);
    }
}
