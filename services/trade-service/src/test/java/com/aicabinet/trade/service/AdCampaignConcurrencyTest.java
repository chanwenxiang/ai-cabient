package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.AdCampaignDeviceMapper;
import com.aicabinet.trade.mapper.AdCampaignItemMapper;
import com.aicabinet.trade.mapper.AdCampaignMapper;
import com.aicabinet.trade.mapper.AdPlayEventMapper;
import com.aicabinet.trade.mapper.MediaAssetMapper;
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
class AdCampaignConcurrencyTest {

    @Mock private AdCampaignMapper campaignRepository;
    @Mock private AdCampaignItemMapper itemRepository;
    @Mock private AdCampaignDeviceMapper deviceRepository;
    @Mock private MediaAssetMapper assetRepository;
    @Mock private AdminAuditService auditService;
    @Mock private AdPlayEventMapper playEventRepository;
    @Mock private DistributedLockService distributedLockService;

    private AdCampaignService service;

    @BeforeEach
    void setUp() {
        service = new AdCampaignService(campaignRepository, itemRepository, deviceRepository,
                assetRepository, auditService, playEventRepository, distributedLockService);
    }

    @Test
    void launch_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(AdCampaignService.campaignLockKey(1L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.launch(1L, 1L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void stop_whenCampaignNotFound_unlocksLock() {
        when(distributedLockService.tryLock(AdCampaignService.campaignLockKey(2L), 60L, 5L))
                .thenReturn(true);
        when(campaignRepository.findByIdForUpdate(2L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.stop(1L, 2L));

        verify(distributedLockService).unlock(AdCampaignService.campaignLockKey(2L));
    }
}
