package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PromotionPlacementDto;
import com.aicabinet.common.dto.UpsertPromotionPlacementRequest;
import com.aicabinet.trade.config.PromotionPlacementProperties;
import com.aicabinet.trade.domain.MediaAsset;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.domain.PromotionPlacement;
import com.aicabinet.trade.mapper.MediaAssetMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.aicabinet.trade.mapper.PromotionPlacementMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionPlacementServiceTest {

    @Mock private PromotionActivityMapper activityRepository;
    @Mock private PromotionPlacementMapper placementRepository;
    @Mock private MediaAssetMapper assetRepository;

    private PromotionPlacementService service;

    @BeforeEach
    void setUp() {
        service = new PromotionPlacementService(activityRepository, placementRepository, assetRepository,
                new PromotionPlacementProperties(null, null), org.mockito.Mockito.mock(com.aicabinet.trade.service.AdminAuditService.class));
        PromotionActivity a = new PromotionActivity();
        a.setActivityId(1L);
        a.setStatus("DRAFT");
        lenient().when(activityRepository.findById(1L)).thenReturn(Optional.of(a));
    }

    private MediaAsset image(long id, int width, int height) {
        MediaAsset asset = new MediaAsset();
        asset.setAssetId(id);
        asset.setAssetType("IMAGE");
        asset.setStatus("ACTIVE");
        asset.setImageWidth(width);
        asset.setImageHeight(height);
        return asset;
    }

    @Test
    void replace_requiresBanner() {
        when(assetRepository.findById(2L)).thenReturn(Optional.of(image(2, 750, 1000)));
        List<UpsertPromotionPlacementRequest> items = List.of(
                new UpsertPromotionPlacementRequest("POPUP_BEFORE_OPEN", 2L, null));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.replace(9001L, 1L, items));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    void replace_rejectsTwoPopups() {
        when(assetRepository.findById(2L)).thenReturn(Optional.of(image(2, 750, 300)));
        when(assetRepository.findById(3L)).thenReturn(Optional.of(image(3, 750, 1000)));
        List<UpsertPromotionPlacementRequest> items = List.of(
                new UpsertPromotionPlacementRequest("BANNER", 2L, null),
                new UpsertPromotionPlacementRequest("POPUP_BEFORE_OPEN", 3L, null),
                new UpsertPromotionPlacementRequest("POPUP_AFTER_CLOSE", 3L, null));
        assertThrows(ResponseStatusException.class, () -> service.replace(9001L, 1L, items));
        verify(placementRepository, never())
                .delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void replace_rejectsBadBannerAspect() {
        when(assetRepository.findById(2L)).thenReturn(Optional.of(image(2, 750, 750)));
        List<UpsertPromotionPlacementRequest> items = List.of(
                new UpsertPromotionPlacementRequest("BANNER", 2L, null));
        assertThrows(ResponseStatusException.class, () -> service.replace(9001L, 1L, items));
    }

    @Test
    void replace_acceptsBannerOnly() {
        when(assetRepository.findById(2L)).thenReturn(Optional.of(image(2, 750, 300)));
        when(placementRepository.findByActivityId(1L)).thenReturn(List.of());
        assertDoesNotThrowReplace(List.of(new UpsertPromotionPlacementRequest("BANNER", 2L, null)));
    }

    @Test
    void replace_rejectsVideoAsset() {
        MediaAsset video = image(5, 750, 300);
        video.setAssetType("VIDEO");
        when(assetRepository.findById(5L)).thenReturn(Optional.of(video));
        assertThrows(ResponseStatusException.class, () -> service.replace(9001L, 1L,
                List.of(new UpsertPromotionPlacementRequest("BANNER", 5L, null))));
    }

    @Test
    void replace_activeActivity_rejected() {
        PromotionActivity active = new PromotionActivity();
        active.setActivityId(9L);
        active.setStatus("ACTIVE");
        when(activityRepository.findById(9L)).thenReturn(Optional.of(active));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.replace(9001L, 9L, List.of(new UpsertPromotionPlacementRequest("BANNER", 2L, null))));
        assertEquals(409, e.getStatusCode().value());
    }

    @Test
    void list_mapsPreviewUrl() {
        PromotionPlacement p = new PromotionPlacement();
        p.setPlacementId(10L);
        p.setActivityId(1L);
        p.setPosition("BANNER");
        p.setAssetId(2L);
        p.setImageWidth(750);
        p.setImageHeight(300);
        when(placementRepository.findByActivityId(1L)).thenReturn(List.of(p));
        List<PromotionPlacementDto> result = service.list(1L);
        assertEquals(1, result.size());
        assertEquals("/api/v2/media/ad-assets/2", result.get(0).previewUrl());
    }

    @Test
    void replace_persistsBannerSortOrderForCarouselOrdering() {
        when(assetRepository.findById(2L)).thenReturn(Optional.of(image(2, 750, 300)));
        when(placementRepository.findByActivityId(1L)).thenReturn(List.of());
        service.replace(9001L, 1L, List.of(new UpsertPromotionPlacementRequest("BANNER", 2L, 30)));
        org.mockito.ArgumentCaptor<PromotionPlacement> captor =
                org.mockito.ArgumentCaptor.forClass(PromotionPlacement.class);
        verify(placementRepository).insert(captor.capture());
        assertEquals(30, captor.getValue().getSortOrder());
    }

    private void assertDoesNotThrowReplace(List<UpsertPromotionPlacementRequest> items) {
        service.replace(9001L, 1L, items);
        verify(placementRepository).insert(any(PromotionPlacement.class));
    }
}
