package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.config.RiskControlProperties;
import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.domain.UserBlacklist;
import com.aicabinet.trade.mapper.RiskEventMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserBlacklistMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
public class RiskControlService {

    private static final Logger log = LoggerFactory.getLogger(RiskControlService.class);

    private final RiskControlProperties properties;
    private final UserBlacklistMapper blacklistRepository;
    private final RiskEventMapper riskEventRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final ObjectMapper objectMapper;

    public RiskControlService(RiskControlProperties properties,
                              UserBlacklistMapper blacklistRepository,
                              RiskEventMapper riskEventRepository,
                              ShoppingSessionMapper sessionRepository,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.blacklistRepository = blacklistRepository;
        this.riskEventRepository = riskEventRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    public void validateCanOpenDoor(Long userId, String deviceId) {
        if (userId == null || !properties.enabled()) {
            return;
        }
        Instant now = Instant.now();
        blacklistRepository.findById(userId).ifPresent(bl -> {
            if (bl.getExpiresAt() == null || bl.getExpiresAt().isAfter(now)) {
                recordEvent(userId, deviceId, "BLACKLIST_HIT", "BLOCK", Map.of("reason", bl.getReason()));
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        ApiMessages.USER_BLACKLISTED + (bl.getReason() == null || bl.getReason().isBlank()
                                ? "" : "：" + bl.getReason()));
            }
        });

        Instant since1h = now.minus(1, ChronoUnit.HOURS);
        long recentOpens = sessionRepository.countByUserIdAndCreatedAtAfter(userId, since1h);
        if (recentOpens >= properties.maxOpensPerHour()) {
            recordEvent(userId, deviceId, "MALICIOUS_OPEN", "WARN",
                    Map.of("opensLastHour", recentOpens));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ApiMessages.TOO_MANY_OPENS);
        }
    }

    public void onDisputeCreated(Long userId, String sessionId) {
        recordEvent(userId, null, "DISPUTE_CREATED", "INFO", Map.of("sessionId", sessionId));
        Instant since7d = Instant.now().minus(7, ChronoUnit.DAYS);
        long disputes = sessionRepository.countByUserIdAndStateAndCreatedAtAfter(
                userId, SessionState.DISPUTED, since7d);
        if (disputes >= properties.maxDisputesPer7Days()) {
            autoBlacklist(userId, "频繁申诉 " + disputes + " 次/7天");
            recordEvent(userId, null, "FREQUENT_DISPUTE", "WARN", Map.of("count", disputes));
        }
    }

    @Transactional
    public void addBlacklist(Long operatorId, Long userId, String reason, Instant expiresAt) {
        UserBlacklist bl = new UserBlacklist();
        bl.setUserId(userId);
        bl.setReason(reason);
        bl.setSource("MANUAL");
        bl.setExpiresAt(expiresAt);
        blacklistRepository.save(bl);
        recordEvent(userId, null, "BLACKLIST_ADD", "INFO", Map.of("reason", reason, "by", operatorId));
        log.info("user blacklisted userId={} by={}", userId, operatorId);
    }

    @Transactional
    public void removeBlacklist(Long userId) {
        blacklistRepository.deleteById(userId);
    }

    private void autoBlacklist(Long userId, String reason) {
        if (blacklistRepository.existsById(userId)) {
            return;
        }
        UserBlacklist bl = new UserBlacklist();
        bl.setUserId(userId);
        bl.setReason(reason);
        bl.setSource("AUTO");
        bl.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        blacklistRepository.save(bl);
        recordEvent(userId, null, "BLACKLIST_AUTO", "BLOCK", Map.of("reason", reason));
    }

    private void recordEvent(Long userId, String deviceId, String type, String severity, Map<String, ?> detail) {
        RiskEvent event = new RiskEvent();
        event.setUserId(userId);
        event.setDeviceId(deviceId);
        event.setEventType(type);
        event.setSeverity(severity);
        try {
            event.setDetail(objectMapper.writeValueAsString(detail));
        } catch (JsonProcessingException e) {
            event.setDetail("{}");
        }
        riskEventRepository.save(event);
    }
}
