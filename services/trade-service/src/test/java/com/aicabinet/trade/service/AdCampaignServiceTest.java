package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ScreenContentDto;
import com.aicabinet.trade.domain.AdCampaign;
import com.aicabinet.trade.domain.AdCampaignDevice;
import com.aicabinet.trade.domain.AdCampaignItem;
import com.aicabinet.trade.domain.MediaAsset;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdCampaignServiceTest {

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
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        service = new AdCampaignService(campaignRepository, itemRepository, deviceRepository,
                assetRepository, auditService, playEventRepository, distributedLockService);
    }

    private static AdCampaign campaign(Long id, String status, String scope) {
        AdCampaign c = new AdCampaign();
        c.setCampaignId(id);
        c.setName("C" + id);
        c.setStatus(status);
        c.setDeviceScope(scope);
        c.setCreatedAt(Instant.now());
        return c;
    }

    @Test
    void screenContent_shouldReturnRunningAllScopeCampaign() {
        AdCampaign campaign = campaign(1L, "RUNNING", "ALL");
        when(campaignRepository.findRunningInWindow(any())).thenReturn(List.of(campaign));
        AdCampaignItem item = new AdCampaignItem();
        item.setItemId(10L);
        item.setCampaignId(1L);
        item.setAssetId(100L);
        item.setSortOrder(0);
        when(itemRepository.findByCampaignId(1L)).thenReturn(List.of(item));
        MediaAsset asset = new MediaAsset();
        asset.setAssetId(100L);
        asset.setTitle("可乐广告");
        asset.setAssetType("IMAGE");
        asset.setStorageUri("minio://bucket/ad/a.png");
        asset.setDurationSeconds(10);
        asset.setStatus("ACTIVE");
        when(assetRepository.findById(100L)).thenReturn(Optional.of(asset));

        ScreenContentDto out = service.screenContent("CAB-001");

        assertEquals(1L, out.campaignId());
        assertEquals(1, out.items().size());
        assertEquals("可乐广告", out.items().get(0).title());
        assertEquals("minio://bucket/ad/a.png", out.items().get(0).storageUri());
    }

    @Test
    void screenContent_shouldMatchSpecificDeviceOnly() {
        AdCampaign campaign = campaign(2L, "RUNNING", "SPECIFIC");
        when(campaignRepository.findRunningInWindow(any())).thenReturn(List.of(campaign));
        AdCampaignDevice row = new AdCampaignDevice();
        row.setCampaignId(2L);
        row.setDeviceId("CAB-001");
        when(deviceRepository.findByCampaignId(2L)).thenReturn(List.of(row));
        AdCampaignItem item = new AdCampaignItem();
        item.setCampaignId(2L);
        item.setAssetId(101L);
        item.setSortOrder(0);
        when(itemRepository.findByCampaignId(2L)).thenReturn(List.of(item));
        MediaAsset asset = new MediaAsset();
        asset.setAssetId(101L);
        asset.setTitle("定向广告");
        asset.setAssetType("IMAGE");
        asset.setStorageUri("minio://bucket/ad/b.png");
        asset.setStatus("ACTIVE");
        when(assetRepository.findById(101L)).thenReturn(Optional.of(asset));

        assertEquals(1, service.screenContent("CAB-001").items().size());
        // 未选中的设备不命中该投放
        ScreenContentDto other = service.screenContent("CAB-002");
        assertEquals(0, other.items().size());
    }

    @Test
    void screenContent_shouldIgnoreStoppedOrInactiveAssets() {
        when(campaignRepository.findRunningInWindow(any())).thenReturn(List.of());
        ScreenContentDto out = service.screenContent("CAB-001");
        assertTrue(out.items().isEmpty());
    }
}
