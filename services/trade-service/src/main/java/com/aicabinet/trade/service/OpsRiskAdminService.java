package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.RiskEventDto;
import com.aicabinet.common.dto.UserBlacklistDto;
import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.domain.UserBlacklist;
import com.aicabinet.trade.mapper.RiskEventMapper;
import com.aicabinet.trade.mapper.UserBlacklistMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 风控事件 / 黑名单门面（含权限校验）；自原 OpsCommercialFacade 按域抽出。 */
@Service
public class OpsRiskAdminService {

    private static final String PERM_OPS_RISK_BLACKLIST = "ops:risk:blacklist";
    private static final Set<String> MANUAL_DISPOSITION_SEVERITIES = Set.of("INFO", "WARN", "BLOCK");

    private final PermissionService permissionService;
    private final RiskControlService riskControlService;
    private final RiskEventMapper riskEventRepository;
    private final UserBlacklistMapper blacklistRepository;
    private final AdminAuditService auditService;

    public OpsRiskAdminService(PermissionService permissionService,
                               RiskControlService riskControlService,
                               RiskEventMapper riskEventRepository,
                               UserBlacklistMapper blacklistRepository,
                               AdminAuditService auditService) {
        this.permissionService = permissionService;
        this.riskControlService = riskControlService;
        this.riskEventRepository = riskEventRepository;
        this.blacklistRepository = blacklistRepository;
        this.auditService = auditService;
    }

    public PageResult<RiskEventDto> listRiskEvents(Long operatorId, int page, int size) {
        permissionService.requirePermission(operatorId, "ops:risk:list");
        // 审计批次4：分页 size 钳到全仓统一上限（防 size=MAX_VALUE 拉全表 OOM）
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);
        var p = riskEventRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
        return new PageResult<>(
                p.getContent().stream().map(this::toRiskDto).toList(),
                page, size, p.getTotalElements()
        );
    }

    public List<UserBlacklistDto> listBlacklist(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_RISK_BLACKLIST);
        return blacklistRepository.findAll().stream().map(this::toBlacklistDto).toList();
    }

    public void addBlacklist(Long operatorId, Long userId, String reason, Instant expiresAt) {
        permissionService.requirePermission(operatorId, PERM_OPS_RISK_BLACKLIST);
        riskControlService.addBlacklist(operatorId, userId, reason, expiresAt);
    }

    public void removeBlacklist(Long operatorId, Long userId) {
        permissionService.requirePermission(operatorId, PERM_OPS_RISK_BLACKLIST);
        riskControlService.removeBlacklist(operatorId, userId);
    }

    public com.aicabinet.common.dto.RiskRuntimeConfigDto riskRuntimeConfig() {
        return riskControlService.runtimeConfig();
    }

    public com.aicabinet.common.dto.RiskRuntimeConfigDto updateRiskRuntimeConfig(
            Long operatorId, int maxDisputesPer7Days) {
        permissionService.requirePermission(operatorId, "ops:config:edit");
        return riskControlService.updateMaxDisputesPer7Days(operatorId, maxDisputesPer7Days);
    }

    /**
     * 人工确认 OPEN 的 INFO/WARN/BLOCK 事件。行锁与自动处置共用，锁内重读状态，
     * 防止自动结清/确认与人工处置互相覆盖；审计与状态更新在同一事务内。
     */
    @Transactional
    public RiskEventDto disposeRiskEvent(Long operatorId, Long eventId, String note) {
        permissionService.requirePermission(operatorId, PERM_OPS_RISK_BLACKLIST);
        if (eventId == null || eventId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件 ID 非法");
        }
        String normalizedNote = note == null ? "" : note.trim();
        if (normalizedNote.isEmpty() || normalizedNote.length() > 256) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处置备注必填且不能超过 256 个字符");
        }
        RiskEvent event = riskEventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "风控事件不存在"));
        if (!"OPEN".equals(event.getDispositionStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该事件已处置，不能重复确认");
        }
        if (event.getSeverity() == null || !MANUAL_DISPOSITION_SEVERITIES.contains(event.getSeverity())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该级别事件不支持人工确认处置");
        }
        Instant now = Instant.now();
        event.setDispositionStatus("ACKED");
        event.setDispositionAt(now);
        event.setDispositionNote(normalizedNote);
        event.setDispositionOperatorId(operatorId);
        riskEventRepository.updateById(event);
        auditService.appendLog(operatorId, "RISK_EVENT_DISPOSE", "RISK_EVENT",
                String.valueOf(eventId), "级别=" + event.getSeverity() + " 备注=" + normalizedNote);
        return toRiskDto(event);
    }

    private RiskEventDto toRiskDto(RiskEvent e) {
        return new RiskEventDto(
                e.getEventId(), e.getUserId(), e.getDeviceId(),
                e.getEventType(), e.getSeverity(), e.getDetail(), e.getCreatedAt(),
                e.getDispositionStatus() == null ? "OPEN" : e.getDispositionStatus(),
                e.getDispositionAt(), e.getDispositionNote(), e.getDispositionOperatorId()
        );
    }

    private UserBlacklistDto toBlacklistDto(UserBlacklist b) {
        return new UserBlacklistDto(b.getUserId(), b.getReason(), b.getSource(), b.getExpiresAt(), b.getCreatedAt());
    }
}
