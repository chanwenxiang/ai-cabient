package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.repository.PromotionActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    private final PromotionActivityRepository repository;

    public PromotionService(PromotionActivityRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PromotionActivityDto create(CreatePromotionRequest request) {
        PromotionActivity a = new PromotionActivity();
        a.setActivityName(request.activityName());
        a.setActivityType(request.activityType());
        a.setStartTime(request.startTime());
        a.setEndTime(request.endTime());
        a.setBudgetCents(request.budgetCents());
        a.setUserLimit(request.userLimit());
        a.setDeviceScope(request.deviceScope());
        a.setRuleConfig(request.ruleConfig() != null ? request.ruleConfig() : "{}");
        a.setDescription(request.description());
        a.setStatus("DRAFT");
        repository.save(a);
        log.info("promotion created id={} name={}", a.getActivityId(), a.getActivityName());
        return toDto(a);
    }

    public List<PromotionActivityDto> listActive() {
        return repository.findByStatus("ACTIVE").stream().map(this::toDto).toList();
    }

    public List<PromotionActivityDto> listAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    public List<PromotionActivityDto> listCurrentlyRunning() {
        Instant now = Instant.now();
        return repository.findByStatusAndStartTimeBeforeAndEndTimeAfter("ACTIVE", now, now)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public PromotionActivityDto updateStatus(Long activityId, String status) {
        PromotionActivity a = repository.findById(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在"));
        a.setStatus(status);
        repository.save(a);
        log.info("promotion {} status={}", activityId, status);
        return toDto(a);
    }

    @Transactional
    public PromotionActivityDto launch(Long activityId) {
        return updateStatus(activityId, "ACTIVE");
    }

    @Transactional
    public PromotionActivityDto stop(Long activityId) {
        return updateStatus(activityId, "STOPPED");
    }

    private PromotionActivityDto toDto(PromotionActivity a) {
        return new PromotionActivityDto(
                a.getActivityId(), a.getActivityName(), a.getActivityType(),
                a.getStatus(), a.getStartTime(), a.getEndTime(),
                a.getBudgetCents(), a.getUsedCents(), a.getUserLimit(),
                a.getDeviceScope(), a.getDescription());
    }
}
