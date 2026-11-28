package com.aicabinet.trade.service;

import com.aicabinet.common.dto.RiskRuntimeConfigDto;
import com.aicabinet.common.dto.SystemConfigDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.config.RiskControlProperties;
import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.domain.SystemConfig;
import com.aicabinet.trade.domain.UserBlacklist;
import com.aicabinet.trade.mapper.RiskEventMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SystemConfigHistoryMapper;
import com.aicabinet.trade.mapper.SystemConfigMapper;
import com.aicabinet.trade.mapper.UserBlacklistMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskRuntimeConfigTest {

    @Mock UserBlacklistMapper blacklistRepository;
    @Mock RiskEventMapper riskEventRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock DistributedLockService lockService;
    @Mock SystemConfigService configService;
    @Mock SystemConfigMapper configRepository;
    @Mock SystemConfigHistoryMapper historyRepository;
    @Mock AdminAuditService auditService;
    private RiskControlService risk;

    @BeforeEach
    void setUp() {
        risk = new RiskControlService(new RiskControlProperties(true, 5, 3),
                blacklistRepository, riskEventRepository, sessionRepository,
                new ObjectMapper(), lockService);
        risk.setSystemConfigService(configService);
    }

    @Test
    void noDatabaseRow_keepsStartupFallback() {
        when(configService.getValue(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS, null))
                .thenReturn(null);
        when(configService.getPositiveLimit(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS, 3))
                .thenReturn(3);
        RiskRuntimeConfigDto dto = risk.runtimeConfig();
        assertEquals(3, dto.maxDisputesPer7Days());
        assertEquals(3, dto.defaultMaxDisputesPer7Days());
        assertEquals("STARTUP", dto.source());
    }

    @Test
    void customStartupValue_isPreservedWhenDatabaseRowMissing() {
        risk = new RiskControlService(new RiskControlProperties(true, 5, 50),
                blacklistRepository, riskEventRepository, sessionRepository,
                new ObjectMapper(), lockService);
        risk.setSystemConfigService(configService);
        when(configService.getValue(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS, null))
                .thenReturn(null);
        when(configService.getPositiveLimit(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS, 50))
                .thenReturn(50);
        RiskRuntimeConfigDto dto = risk.runtimeConfig();
        assertEquals(50, dto.maxDisputesPer7Days());
        assertEquals(50, dto.defaultMaxDisputesPer7Days());
        assertEquals("STARTUP", dto.source());
    }

    @Test
    void databaseValue_isEffectiveWithoutRestart() {
        when(configService.getValue(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS, null))
                .thenReturn("5");
        when(configService.getPositiveLimit(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS, 3))
                .thenReturn(5);
        RiskRuntimeConfigDto dto = risk.runtimeConfig();
        assertEquals(5, dto.maxDisputesPer7Days());
        assertEquals("DATABASE", dto.source());
    }

    @Test
    void invalidStoredValue_fallsBackToStartup() {
        when(configService.getValue(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS, null))
                .thenReturn("0");
        when(configService.getPositiveLimit(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS, 3))
                .thenReturn(3);
        assertEquals(3, risk.maxDisputesPer7Days());
        assertEquals("STARTUP", risk.runtimeConfig().source());
    }

    @Test
    void disputeDecision_readsRuntimeThreshold() {
        when(lockService.tryLock(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(configService.getPositiveLimit(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS, 3))
                .thenReturn(5);
        when(sessionRepository.countByUserIdAndStateAndCreatedAtAfter(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(SessionState.DISPUTED),
                any(Instant.class))).thenReturn(5L);
        when(blacklistRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        risk.onDisputeCreated(1L, "SESSION-1");

        verify(blacklistRepository).save(any(UserBlacklist.class));
        verify(riskEventRepository, org.mockito.Mockito.times(3)).save(any(RiskEvent.class));
    }

    @Test
    void update_delegatesToAuditedSystemConfig() {
        when(configService.upsert(
                org.mockito.ArgumentMatchers.eq(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS),
                org.mockito.ArgumentMatchers.eq("7"),
                anyString(), org.mockito.ArgumentMatchers.eq(9L)))
                .thenReturn(new SystemConfigDto(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS,
                        "7", "d", Instant.now()));
        when(configService.getPositiveLimit(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS, 3))
                .thenReturn(7);
        RiskRuntimeConfigDto dto = risk.updateMaxDisputesPer7Days(9L, 7);
        assertEquals(7, dto.maxDisputesPer7Days());
        assertEquals("DATABASE", dto.source());
    }

    @Test
    void systemConfigWrite_rejectsNonPositiveAndNonInteger_beforeSaveOrAudit() {
        when(lockService.tryLock(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        SystemConfigService service = new SystemConfigService(configRepository, historyRepository,
                auditService, null, null, null, null, null, null, null, null, lockService, null);
        for (String value : new String[]{"0", "-1", "abc", "1.5", ""}) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.upsert(SystemConfigService.RISK_MAX_DISPUTES_PER_7_DAYS,
                            value, "d", 9L));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode(), value);
        }
        verify(configRepository, never()).save(any(SystemConfig.class));
        verify(auditService, never()).appendLog(org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString(), anyString(), anyString());
    }
}
