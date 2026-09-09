package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdminAuditLogDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.util.PhoneMask;
import com.aicabinet.trade.domain.AdminAuditLog;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

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

    public OpsAuditQueryService(PermissionService permissionService,
                                AdminAuditLogMapper auditLogRepository,
                                UserInfoMapper userInfoRepository) {
        this.permissionService = permissionService;
        this.auditLogRepository = auditLogRepository;
        this.userInfoRepository = userInfoRepository;
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
}
