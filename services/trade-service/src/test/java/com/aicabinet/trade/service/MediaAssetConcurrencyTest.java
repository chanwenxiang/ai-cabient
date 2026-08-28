package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertMediaAssetRequest;
import com.aicabinet.trade.mapper.AdCampaignItemMapper;
import com.aicabinet.trade.mapper.MediaAssetMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetConcurrencyTest {

    @Mock private MediaAssetMapper assetRepository;
    @Mock private AdCampaignItemMapper campaignItemMapper;
    @Mock private MinioVideoService minioVideoService;
    @Mock private DistributedLockService distributedLockService;

    private MediaAssetService service;

    @BeforeEach
    void setUp() {
        service = new MediaAssetService(assetRepository, campaignItemMapper, minioVideoService, distributedLockService);
    }

    @Test
    void update_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MediaAssetService.mediaAssetLockKey(3L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.update(3L, new UpsertMediaAssetRequest("标题", 10, "ACTIVE")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void delete_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                MediaAssetService.mediaAssetLockKey(4L), 60L, 5L))
                .thenReturn(true);
        when(assetRepository.findByIdForUpdate(4L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.delete(4L));

        verify(distributedLockService).unlock(MediaAssetService.mediaAssetLockKey(4L));
    }
}
