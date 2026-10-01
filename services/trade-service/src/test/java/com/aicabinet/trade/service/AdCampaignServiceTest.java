package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ScreenContentDto;
import com.aicabinet.trade.domain.AdCampaign;
import com.aicabinet.trade.domain.AdCampaignDevice;
import com.aicabinet.trade.domain.AdCampaignItem;
import com.aicabinet.trade.domain.AdPlayEvent;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
                assetRepository, auditService, playEventRepository, distributedLockService,
                new AdPlayEventDeduplicator());
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
        assertEquals("/api/v2/media/ad-assets/100", out.items().get(0).playUrl());
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

    @Test
    void recordPlayEvent_shouldRejectWhenCampaignNotRunning() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign(1L, "STOPPED", "ALL")));

        service.recordPlayEvent("CAB-001", 1L, 100L, "IMPRESSION");

        verify(playEventRepository, never()).insert(any(AdPlayEvent.class));
    }

    @Test
    void recordPlayEvent_shouldRejectWhenDeviceOutOfScope() {
        when(campaignRepository.findById(2L)).thenReturn(Optional.of(campaign(2L, "RUNNING", "SPECIFIC")));
        AdCampaignDevice row = new AdCampaignDevice();
        row.setCampaignId(2L);
        row.setDeviceId("CAB-001");
        when(deviceRepository.findByCampaignId(2L)).thenReturn(List.of(row));

        // 未在投放范围内的设备上报：静默丢弃（不抛 4xx，防接口探测）
        service.recordPlayEvent("CAB-002", 2L, 100L, "IMPRESSION");

        verify(playEventRepository, never()).insert(any(AdPlayEvent.class));
    }

    @Test
    void recordPlayEvent_shouldRecordForDeviceInScope() {
        when(campaignRepository.findById(2L)).thenReturn(Optional.of(campaign(2L, "RUNNING", "SPECIFIC")));
        AdCampaignDevice row = new AdCampaignDevice();
        row.setCampaignId(2L);
        row.setDeviceId("CAB-001");
        when(deviceRepository.findByCampaignId(2L)).thenReturn(List.of(row));

        service.recordPlayEvent("cab-001", 2L, 100L, "COMPLETE");

        verify(playEventRepository).insert(any(AdPlayEvent.class));
    }

    @Test
    void recordPlayEvent_allScopeAcceptsAnyDevice() {
        when(campaignRepository.findById(3L)).thenReturn(Optional.of(campaign(3L, "RUNNING", "ALL")));

        service.recordPlayEvent("CAB-XYZ", 3L, 100L, "IMPRESSION");

        verify(playEventRepository).insert(any(AdPlayEvent.class));
    }

    // ── 服务端去重（docs/AD_MONETIZATION_DESIGN.md §10 第 2 条）────────────────────
    //
    // 客户端去重（`impressed` / `completeTimers`）可被改包或脚本绕过，服务端是最后一道。
    // 🔴 每条都**同时**断言「该记的仍然记」——只断言 never/次数会漏掉「整块功能坏掉」的假绿。

    @Test
    void recordPlayEvent_dedupesSameEventWithinWindow() {
        when(campaignRepository.findById(3L)).thenReturn(Optional.of(campaign(3L, "RUNNING", "ALL")));

        service.recordPlayEvent("CAB-DUP", 3L, 100L, "IMPRESSION");
        service.recordPlayEvent("CAB-DUP", 3L, 100L, "IMPRESSION");
        // 大小写与空白不同，规范化后仍是同一组合 ⇒ 同样只记一次
        service.recordPlayEvent("cab-dup", 3L, 100L, " impression ");

        verify(playEventRepository, times(1)).insert(any(AdPlayEvent.class));
    }

    @Test
    void recordPlayEvent_keepsDistinctCombinations() {
        when(campaignRepository.findById(3L)).thenReturn(Optional.of(campaign(3L, "RUNNING", "ALL")));

        service.recordPlayEvent("CAB-K1", 3L, 100L, "IMPRESSION");
        service.recordPlayEvent("CAB-K1", 3L, 101L, "IMPRESSION"); // 换素材
        service.recordPlayEvent("CAB-K1", 3L, 100L, "COMPLETE");   // 换事件类型
        service.recordPlayEvent("CAB-K2", 3L, 100L, "IMPRESSION"); // 换设备

        verify(playEventRepository, times(4)).insert(any(AdPlayEvent.class));
    }

    @Test
    void recordPlayEvent_releasesSlotWhenInsertFails() {
        when(campaignRepository.findById(3L)).thenReturn(Optional.of(campaign(3L, "RUNNING", "ALL")));
        // 🔴 `insert` 返回 int（MyBatis-Plus BaseMapper），不是 void ⇒ 第二次不能写 doNothing()
        doThrow(new IllegalStateException("db down"))
                .doReturn(1)
                .when(playEventRepository).insert(any(AdPlayEvent.class));

        assertThrows(IllegalStateException.class,
                () -> service.recordPlayEvent("CAB-RL", 3L, 100L, "CLICK"));
        // 落库失败必须归还资格 ⇒ 同窗口内重试仍能落库（否则一次 DB 抖动就永久吞掉这次曝光）
        service.recordPlayEvent("CAB-RL", 3L, 100L, "CLICK");

        verify(playEventRepository, times(2)).insert(any(AdPlayEvent.class));
    }

    @Test
    void screenContent_skipsMiniProgramChannel() {
        AdCampaign campaign = campaign(9L, "RUNNING", "ALL");
        campaign.setChannel(AdCampaignService.CHANNEL_MINI_PROGRAM);
        when(campaignRepository.findRunningInWindow(any())).thenReturn(List.of(campaign));

        ScreenContentDto out = service.screenContent("CAB-001");

        assertEquals(null, out.campaignId(), "小程序渠道广告不得上柜机屏（V296/P3-6）");
        assertEquals(0, out.items().size());
    }

    @Test
    void upsert_rejectsUnknownChannel() {
        when(assetRepository.findById(100L)).thenReturn(Optional.of(activeImage(100L)));
        var request = new com.aicabinet.common.dto.UpsertAdCampaignRequest(
                "x", "ALL", "TV", null, null, null, List.of(100L), null);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.upsert(1L, null, request));
    }

    @Test
    void listMiniProgramBanners_picksFirstActiveImage_skipsCabinetChannel() {
        AdCampaign mp = campaign(7L, "RUNNING", "ALL");
        mp.setChannel(AdCampaignService.CHANNEL_MINI_PROGRAM);
        mp.setLinkUrl("/pages/coupons/coupons");
        AdCampaign cabinet = campaign(8L, "RUNNING", "ALL"); // 柜机屏渠道，不得混入
        when(campaignRepository.findRunningInWindow(any())).thenReturn(List.of(mp, cabinet));
        AdCampaignItem item = new AdCampaignItem();
        item.setItemId(70L);
        item.setCampaignId(7L);
        item.setAssetId(700L);
        item.setSortOrder(0);
        when(itemRepository.findByCampaignId(7L)).thenReturn(List.of(item));
        when(assetRepository.findById(700L)).thenReturn(Optional.of(activeImage(700L)));

        var banners = service.listMiniProgramBanners(5, null);

        assertEquals(1, banners.size());
        assertEquals(7L, banners.get(0).campaignId());
        assertEquals(700L, banners.get(0).assetId());
        assertEquals("/pages/coupons/coupons", banners.get(0).linkUrl());
    }

    @Test
    void listMiniProgramBanners_specificScopeRequiresDeviceContext() {
        AdCampaign scoped = campaign(6L, "RUNNING", "SPECIFIC");
        scoped.setChannel(AdCampaignService.CHANNEL_MINI_PROGRAM);
        when(campaignRepository.findRunningInWindow(any())).thenReturn(List.of(scoped));
        when(deviceRepository.findByCampaignId(6L)).thenReturn(List.of(deviceOf("CAB-IN")));
        AdCampaignItem item = new AdCampaignItem();
        item.setItemId(60L);
        item.setCampaignId(6L);
        item.setAssetId(600L);
        item.setSortOrder(0);
        when(itemRepository.findByCampaignId(6L)).thenReturn(List.of(item));
        when(assetRepository.findById(600L)).thenReturn(Optional.of(activeImage(600L)));

        // 无柜码上下文（落地页）→ SPECIFIC 不出
        assertEquals(0, service.listMiniProgramBanners(5, null).size());
        // 柜码不在投放范围 → 不出
        assertEquals(0, service.listMiniProgramBanners(5, "CAB-OUT").size());
        // 柜码在投放范围 → 出
        var banners = service.listMiniProgramBanners(5, "cab-in");
        assertEquals(1, banners.size());
        assertEquals(6L, banners.get(0).campaignId());
    }

    private static AdCampaignDevice deviceOf(String deviceId) {
        AdCampaignDevice d = new AdCampaignDevice();
        d.setDeviceId(deviceId);
        return d;
    }

    @Test
    void recordPlayEvent_miniProgramSkipsDeviceScope() {
        AdCampaign campaign = campaign(5L, "RUNNING", "SPECIFIC");
        campaign.setChannel(AdCampaignService.CHANNEL_MINI_PROGRAM);
        when(campaignRepository.findById(5L)).thenReturn(Optional.of(campaign));
        when(deviceRepository.findByCampaignId(5L)).thenReturn(List.of()); // 设备范围不含 MP-U1

        service.recordPlayEvent("MP-U1", 5L, 500L, "IMPRESSION");

        // 小程序渠道 deviceId=MP-U{userId} 语义，不受设备范围校验拦截（V296/P3-6）
        verify(playEventRepository, times(1)).insert(any(AdPlayEvent.class));
    }

    private static MediaAsset activeImage(long assetId) {
        MediaAsset asset = new MediaAsset();
        asset.setAssetId(assetId);
        asset.setTitle("A" + assetId);
        asset.setAssetType("IMAGE");
        asset.setStorageUri("minio://bucket/ad/" + assetId + ".png");
        asset.setDurationSeconds(10);
        asset.setStatus("ACTIVE");
        return asset;
    }
}
