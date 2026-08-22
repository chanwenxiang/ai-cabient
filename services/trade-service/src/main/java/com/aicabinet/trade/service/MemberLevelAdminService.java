package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MemberLevelRuleDto;
import com.aicabinet.trade.domain.MemberLevelRule;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** 会员等级规则后台管理：门槛 / 积分倍率 / 排序 / 启停。 */
@Service
public class MemberLevelAdminService {

    private final MemberLevelRuleMapper levelRuleRepository;

    public MemberLevelAdminService(MemberLevelRuleMapper levelRuleRepository) {
        this.levelRuleRepository = levelRuleRepository;
    }

    @Transactional(readOnly = true)
    public List<MemberLevelRuleDto> list() {
        return levelRuleRepository.findAll().stream()
                .sorted(Comparator.comparingInt((MemberLevelRule r) -> r.getSortorder() != null ? r.getSortorder() : 0))
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public MemberLevelRuleDto upsert(MemberLevelRuleDto dto) {
        if (dto == null || dto.levelCode() == null || dto.levelCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "等级编码不能为空");
        }
        String code = dto.levelCode().trim().toUpperCase();
        MemberLevelRule rule = dto.id() != null
                ? levelRuleRepository.findById(dto.id())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "等级不存在"))
                : levelRuleRepository.findByLevelCode(code).orElseGet(MemberLevelRule::new);
        rule.setLevelCode(code);
        rule.setLevelName(dto.levelName() == null ? code : dto.levelName());
        rule.setMinSpent(dto.minSpent());
        rule.setMaxSpent(dto.maxSpent());
        rule.setMinPoints(dto.minPoints());
        rule.setMaxPoints(dto.maxPoints());
        rule.setPointsRate(dto.pointsRate() == null ? BigDecimal.ONE : dto.pointsRate());
        rule.setSortorder(dto.sortOrder());
        rule.setStatus(dto.status() == null ? "ACTIVE" : dto.status());
        rule.setUpdatedAt(Instant.now());
        return toDto(levelRuleRepository.save(rule));
    }

    @Transactional
    public MemberLevelRuleDto setStatus(Long id, String status) {
        MemberLevelRule rule = levelRuleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "等级不存在"));
        rule.setStatus("ACTIVE".equalsIgnoreCase(status) ? "ACTIVE" : "INACTIVE");
        rule.setUpdatedAt(Instant.now());
        return toDto(levelRuleRepository.save(rule));
    }

    private MemberLevelRuleDto toDto(MemberLevelRule r) {
        return new MemberLevelRuleDto(
                r.getId(),
                r.getLevelCode(),
                r.getLevelName(),
                r.getMinSpent(),
                r.getMaxSpent(),
                r.getMinPoints() != null ? r.getMinPoints() : 0,
                r.getMaxPoints(),
                r.getPointsRate() != null ? r.getPointsRate() : BigDecimal.ONE,
                r.getPriceDiscountPct() != null ? r.getPriceDiscountPct() : BigDecimal.ZERO,
                r.getSortorder() != null ? r.getSortorder() : 0,
                r.getStatus()
        );
    }
}
