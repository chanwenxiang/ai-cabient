package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplyFeedbackRequest;
import com.aicabinet.trade.mapper.UserFeedbackMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserFeedbackConcurrencyTest {

    @Mock private UserFeedbackMapper repository;
    @Mock private PermissionService permissionService;
    @Mock private DistributedLockService distributedLockService;

    private UserFeedbackService service;

    @BeforeEach
    void setUp() {
        doNothing().when(permissionService).requirePermission(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        service = new UserFeedbackService(repository, permissionService, distributedLockService);
    }

    @Test
    void reply_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(UserFeedbackService.feedbackLockKey(5L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.reply(1L, 5L, new ReplyFeedbackRequest("thanks")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void delete_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(UserFeedbackService.feedbackLockKey(6L)), eq(60L), eq(5L)))
                .thenReturn(true);
        doNothing().when(permissionService).requireAnyPermission(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        when(repository.findByIdForUpdate(6L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.delete(1L, 6L));

        verify(distributedLockService).unlock(UserFeedbackService.feedbackLockKey(6L));
    }
}
