package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
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

    private final PromotionActivityMapper repository;

    public PromotionService(PromotionActivityMapper repository) {
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
    public PromotionActivityDto update(Long activityId, CreatePromotionRequest request) {
        PromotionActivity a = repository.findById(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在"));
        if ("ACTIVE".equals(a.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "进行中的活动请先停止再编辑");
        }
        if (request.endTime() != null && request.startTime() != null
                && !request.endTime().isAfter(request.startTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束时间需晚于开始时间");
        }
        a.setActivityName(request.activityName());
        a.setActivityType(request.activityType());
        a.setStartTime(request.startTime());
        a.setEndTime(request.endTime());
        a.setBudgetCents(request.budgetCents());
        a.setUserLimit(request.userLimit());
        if (request.deviceScope() != null) {
            a.setDeviceScope(request.deviceScope());
        }
        if (request.ruleConfig() != null) {
            a.setRuleConfig(request.ruleConfig());
        }
        a.setDescription(request.description());
        repository.save(a);
        log.info("promotion updated id={}", activityId);
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
