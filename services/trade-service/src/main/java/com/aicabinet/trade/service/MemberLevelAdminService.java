package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

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
    private final DistributedLockService distributedLockService;
    private final AdminAuditService auditService;

    public MemberLevelAdminService(MemberLevelRuleMapper levelRuleRepository,
                                   DistributedLockService distributedLockService,
                                   AdminAuditService auditService) {
        this.levelRuleRepository = levelRuleRepository;
        this.distributedLockService = distributedLockService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<MemberLevelRuleDto> list() {
        return levelRuleRepository.findAll().stream()
                .sorted(Comparator.comparingInt((MemberLevelRule r) -> r.getSortorder() != null ? r.getSortorder() : 0))
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public MemberLevelRuleDto create(Long operatorId, MemberLevelRuleDto dto) {
        if (dto != null && dto.id() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新建等级请勿传 id，请使用 POST");
        }
        return upsert(operatorId, dto);
    }

    @Transactional
    public MemberLevelRuleDto update(Long operatorId, MemberLevelRuleDto dto) {
        if (dto == null || dto.id() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "更新等级须指定 id，请使用 PUT");
        }
        return upsert(operatorId, dto);
    }

    @Transactional
    public MemberLevelRuleDto upsert(Long operatorId, MemberLevelRuleDto dto) {
        if (dto == null || dto.levelCode() == null || dto.levelCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "等级编码不能为空");
        }
        String code = dto.levelCode().trim().toUpperCase();
        if (dto.id() != null) {
            return runWithLevelIdLock(dto.id(), () -> doUpsert(operatorId, dto, code));
        }
        return runWithLevelCodeLock(code, () -> doUpsert(operatorId, dto, code));
    }

    private MemberLevelRuleDto doUpsert(Long operatorId, MemberLevelRuleDto dto, String code) {
        MemberLevelRule rule;
        if (dto.id() != null) {
            rule = levelRuleRepository.findByIdForUpdate(dto.id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "等级不存在"));
            // 改编码时禁止撞到其它已有等级
            levelRuleRepository.findByLevelCode(code).ifPresent(existing -> {
                if (!existing.getId().equals(dto.id())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "等级编码已存在: " + code);
                }
            });
        } else {
            if (levelRuleRepository.findByLevelCode(code).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "等级编码已存在: " + code);
            }
            rule = levelRuleRepository.findByLevelCodeForUpdate(code).orElseGet(MemberLevelRule::new);
            if (rule.getId() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "等级编码已存在: " + code);
            }
        }
        rule.setLevelCode(code);
        rule.setLevelName(dto.levelName() == null
                ? MemberService.displayLevelName(code, null)
                : dto.levelName());
        rule.setMinSpent(dto.minSpent());
        rule.setMaxSpent(dto.maxSpent());
        rule.setMinPoints(dto.minPoints());
        rule.setPointsRate(dto.pointsRate() == null ? BigDecimal.ONE : dto.pointsRate());
        rule.setPriceDiscountPct(dto.priceDiscountPct() == null ? BigDecimal.ZERO : dto.priceDiscountPct());
        rule.setSortorder(dto.sortOrder());
        rule.setStatus(dto.status() == null ? CabinetConstants.PROMOTION_STATUS_ACTIVE : dto.status());
        rule.setUpdatedAt(Instant.now());
        MemberLevelRuleDto saved = toDto(levelRuleRepository.save(rule));
        auditService.appendLog(operatorId, "MEMBER_LEVEL_UPSERT", "MEMBER_LEVEL",
                String.valueOf(saved.id()),
                "code=" + saved.levelCode() + ",name=" + saved.levelName()
                        + ",minSpent=" + saved.minSpent() + ",maxSpent=" + saved.maxSpent()
                        + ",minPoints=" + saved.minPoints() + ",pointsRate=" + saved.pointsRate()
                        + ",priceDiscountPct=" + saved.priceDiscountPct() + ",status=" + saved.status());
        return saved;
    }

    @Transactional
    public MemberLevelRuleDto setStatus(Long operatorId, Long id, String status) {
        return runWithLevelIdLock(id, () -> {
            MemberLevelRule rule = levelRuleRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "等级不存在"));
            rule.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE.equalsIgnoreCase(status) ? CabinetConstants.PROMOTION_STATUS_ACTIVE : "INACTIVE");
            rule.setUpdatedAt(Instant.now());
            MemberLevelRuleDto saved = toDto(levelRuleRepository.save(rule));
            auditService.appendLog(operatorId, "MEMBER_LEVEL_STATUS", "MEMBER_LEVEL",
                    String.valueOf(saved.id()), "code=" + saved.levelCode() + ",status=" + saved.status());
            return saved;
        });
    }

    static String levelIdLockKey(Long id) {
        return "member:level:id:" + id;
    }

    static String levelCodeLockKey(String levelCode) {
        return "member:level:" + levelCode;
    }

    private <T> T runWithLevelIdLock(Long id, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(levelIdLockKey(id), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会员等级规则处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(levelIdLockKey(id));
        }
    }

    private <T> T runWithLevelCodeLock(String levelCode, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(levelCodeLockKey(levelCode), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会员等级规则处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(levelCodeLockKey(levelCode));
        }
    }

    private MemberLevelRuleDto toDto(MemberLevelRule r) {
        return new MemberLevelRuleDto(
                r.getId(),
                r.getLevelCode(),
                r.getLevelName(),
                r.getMinSpent(),
                r.getMaxSpent(),
                r.getMinPoints() != null ? r.getMinPoints() : 0,
                r.getPointsRate() != null ? r.getPointsRate() : BigDecimal.ONE,
                r.getPriceDiscountPct() != null ? r.getPriceDiscountPct() : BigDecimal.ZERO,
                r.getSortorder() != null ? r.getSortorder() : 0,
                r.getStatus()
        );
    }
}
