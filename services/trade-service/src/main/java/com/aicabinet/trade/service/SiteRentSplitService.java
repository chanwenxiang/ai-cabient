package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SiteRentSplitRuleDto;
import com.aicabinet.common.dto.UpsertSiteRentSplitRulesRequest;
import com.aicabinet.trade.domain.SiteContract;
import com.aicabinet.trade.domain.SiteRentSplitRule;
import com.aicabinet.trade.mapper.SiteContractMapper;
import com.aicabinet.trade.mapper.SiteRentSplitRuleMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class SiteRentSplitService {

    private static final Set<String> PARTIES = Set.of("LANDLORD", "PLATFORM", "MERCHANT", "FRANCHISE", "OTHER");

    private final SiteRentSplitRuleMapper ruleMapper;
    private final SiteContractMapper contractMapper;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;

    public SiteRentSplitService(SiteRentSplitRuleMapper ruleMapper,
                                SiteContractMapper contractMapper,
                                PermissionService permissionService,
                                AdminAuditService auditService) {
        this.ruleMapper = ruleMapper;
        this.contractMapper = contractMapper;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<SiteRentSplitRuleDto> listByContract(Long operatorId, Long contractId) {
        permissionService.requirePermission(operatorId, "ops:org:list");
        requireContract(contractId);
        return ruleMapper.findByContractId(contractId).stream().map(this::toDto).toList();
    }

    @Transactional
    public List<SiteRentSplitRuleDto> replaceRules(Long operatorId, Long contractId,
                                                   UpsertSiteRentSplitRulesRequest request) {
        permissionService.requirePermission(operatorId, "ops:org:edit");
        requireContract(contractId);
        int sumBps = 0;
        for (UpsertSiteRentSplitRulesRequest.Rule r : request.rules()) {
            String party = r.partyType().trim().toUpperCase();
            if (!PARTIES.contains(party)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法 partyType");
            }
            if (r.shareBps() < 0 || r.shareBps() > 10000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "shareBps 非法");
            }
            sumBps += r.shareBps();
        }
        if (sumBps != 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "租金分账份额合计须为 10000bps（100%）");
        }
        ruleMapper.deleteByContractId(contractId);
        Instant now = Instant.now();
        for (UpsertSiteRentSplitRulesRequest.Rule r : request.rules()) {
            SiteRentSplitRule row = new SiteRentSplitRule();
            row.setContractId(contractId);
            row.setPartyType(r.partyType().trim().toUpperCase());
            row.setPartyId(blankToNull(r.partyId()));
            row.setShareBps(r.shareBps());
            row.setFixedCents(Math.max(0, r.fixedCents()));
            row.setStatus(r.status() == null || r.status().isBlank() ? "ACTIVE" : r.status().trim().toUpperCase());
            row.setEffectiveFrom(r.effectiveFrom());
            row.setEffectiveTo(r.effectiveTo());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            ruleMapper.insert(row);
        }
        auditService.record(operatorId, "SITE_RENT_SPLIT", "CONTRACT", String.valueOf(contractId),
                "rules=" + request.rules().size());
        return listByContract(operatorId, contractId);
    }

    private SiteContract requireContract(Long contractId) {
        return contractMapper.findById(contractId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "场地合同不存在"));
    }

    private SiteRentSplitRuleDto toDto(SiteRentSplitRule r) {
        return new SiteRentSplitRuleDto(
                r.getRuleId(), r.getContractId(), r.getPartyType(), r.getPartyId(),
                r.getShareBps(), r.getFixedCents(), r.getStatus(),
                r.getEffectiveFrom(), r.getEffectiveTo(), r.getUpdatedAt());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
