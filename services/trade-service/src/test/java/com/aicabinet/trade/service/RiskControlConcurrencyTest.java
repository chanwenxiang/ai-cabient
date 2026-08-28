package com.aicabinet.trade.service;

import com.aicabinet.trade.config.RiskControlProperties;
import com.aicabinet.trade.mapper.RiskEventMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskControlConcurrencyTest {

    @Mock private UserBlacklistMapper blacklistRepository;
    @Mock private RiskEventMapper riskEventRepository;
    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private DistributedLockService distributedLockService;

    private RiskControlService service;

    @BeforeEach
    void setUp() {
        service = new RiskControlService(
                new RiskControlProperties(true, 10, 3),
                blacklistRepository, riskEventRepository, sessionRepository,
                new ObjectMapper(), distributedLockService);
    }

    @Test
    void addBlacklist_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                RiskControlService.blacklistLockKey(10001L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.addBlacklist(1L, 10001L, "test", Instant.now().plusSeconds(3600)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void removeBlacklist_whenLockAcquired_deletesRow() {
        when(distributedLockService.tryLock(
                RiskControlService.blacklistLockKey(10002L), 60L, 5L))
                .thenReturn(true);
        com.aicabinet.trade.domain.UserBlacklist bl = new com.aicabinet.trade.domain.UserBlacklist();
        bl.setUserId(10002L);
        when(blacklistRepository.findByIdForUpdate(10002L)).thenReturn(java.util.Optional.of(bl));

        service.removeBlacklist(10002L);

        verify(blacklistRepository).delete(bl);
        verify(distributedLockService).unlock(RiskControlService.blacklistLockKey(10002L));
    }
}
