package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.RiskEventDto;
import com.aicabinet.common.dto.UserBlacklistDto;
import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.domain.UserBlacklist;
import com.aicabinet.trade.mapper.RiskEventMapper;
import com.aicabinet.trade.mapper.UserBlacklistMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 风控事件 / 黑名单门面（含权限校验）；自原 OpsCommercialFacade 按域抽出。 */
@Service
public class OpsRiskAdminService {

    private static final String PERM_OPS_RISK_BLACKLIST = "ops:risk:blacklist";

    private final PermissionService permissionService;
    private final RiskControlService riskControlService;
    private final RiskEventMapper riskEventRepository;
    private final UserBlacklistMapper blacklistRepository;

    public OpsRiskAdminService(PermissionService permissionService,
                               RiskControlService riskControlService,
                               RiskEventMapper riskEventRepository,
                               UserBlacklistMapper blacklistRepository) {
        this.permissionService = permissionService;
        this.riskControlService = riskControlService;
        this.riskEventRepository = riskEventRepository;
        this.blacklistRepository = blacklistRepository;
    }

    public PageResult<RiskEventDto> listRiskEvents(Long operatorId, int page, int size) {
        permissionService.requirePermission(operatorId, "ops:risk:list");
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
        riskControlService.removeBlacklist(userId);
    }

    private RiskEventDto toRiskDto(RiskEvent e) {
        return new RiskEventDto(
                e.getEventId(), e.getUserId(), e.getDeviceId(),
                e.getEventType(), e.getSeverity(), e.getDetail(), e.getCreatedAt(),
                e.getDispositionStatus() == null ? "OPEN" : e.getDispositionStatus(),
                e.getDispositionAt(), e.getDispositionNote()
        );
    }

    private UserBlacklistDto toBlacklistDto(UserBlacklist b) {
        return new UserBlacklistDto(b.getUserId(), b.getReason(), b.getSource(), b.getExpiresAt(), b.getCreatedAt());
    }
}
