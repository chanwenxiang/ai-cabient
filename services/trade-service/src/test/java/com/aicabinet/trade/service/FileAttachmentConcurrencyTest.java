package com.aicabinet.trade.service;

import com.aicabinet.trade.config.MinioProperties;
import com.aicabinet.trade.mapper.FileAttachmentMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileAttachmentConcurrencyTest {

    @Mock private FileAttachmentMapper fileAttachmentMapper;
    @Mock private MinioVideoService minioVideoService;
    @Mock private MinioProperties minioProperties;
    @Mock private DistributedLockService distributedLockService;

    private FileAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new FileAttachmentService(
                fileAttachmentMapper,
                minioVideoService,
                minioProperties,
                distributedLockService,
                "./data/attachments");
    }

    @Test
    void bindEvidenceToDispute_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(DisputeService.disputeTicketLockKey("TK-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.bindEvidenceToDispute(1L, "TK-1", List.of(10L)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void uploadReplenishmentEvidence_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(ReplenishmentService.replenishmentTaskLockKey(5L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.uploadReplenishmentEvidence(1L, 5L, null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void bindEvidenceToDispute_whenTooManyFiles_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(DisputeService.disputeTicketLockKey("TK-2")), eq(60L), eq(5L)))
                .thenReturn(true);

        assertThrows(ResponseStatusException.class,
                () -> service.bindEvidenceToDispute(1L, "TK-2", List.of(1L, 2L, 3L, 4L, 5L, 6L)));

        verify(distributedLockService).unlock(DisputeService.disputeTicketLockKey("TK-2"));
    }
}
