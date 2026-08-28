package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MemberLevelRuleDto;
import com.aicabinet.trade.mapper.MemberLevelRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberLevelAdminConcurrencyTest {

    @Mock private MemberLevelRuleMapper levelRuleRepository;
    @Mock private DistributedLockService distributedLockService;

    private MemberLevelAdminService service;

    @BeforeEach
    void setUp() {
        service = new MemberLevelAdminService(levelRuleRepository, distributedLockService);
    }

    @Test
    void upsert_whenLevelCodeLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MemberLevelAdminService.levelCodeLockKey("GOLD"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsert(new MemberLevelRuleDto(
                        null, "GOLD", "黄金", null, null, 0, null,
                        BigDecimal.ONE, BigDecimal.ZERO, 1, "ACTIVE")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void setStatus_whenLevelIdLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MemberLevelAdminService.levelIdLockKey(3L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setStatus(3L, "INACTIVE"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void setStatus_whenLevelNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                MemberLevelAdminService.levelIdLockKey(4L), 60L, 5L))
                .thenReturn(true);
        when(levelRuleRepository.findByIdForUpdate(4L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.setStatus(4L, "ACTIVE"));

        verify(distributedLockService).unlock(MemberLevelAdminService.levelIdLockKey(4L));
    }
}
