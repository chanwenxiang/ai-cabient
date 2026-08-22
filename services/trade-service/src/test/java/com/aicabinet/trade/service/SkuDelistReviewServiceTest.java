package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SkuDelistReviewDto;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.SkuDelistReview;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuDelistReviewMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkuDelistReviewServiceTest {

    @Mock private CabinetOrderLineMapper lineRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private SkuDelistReviewMapper reviewRepository;
    @Mock private InventoryLotService inventoryLotService;

    private SkuDelistReviewService service() {
        return new SkuDelistReviewService(lineRepository, inventoryRepository,
                skuCatalogRepository, reviewRepository, inventoryLotService);
    }

    private static SkuCatalog sku(String id, String name, String status) {
        SkuCatalog s = new SkuCatalog();
        s.setSkuId(id);
        s.setSkuName(name);
        s.setCategory("饮料");
        s.setStatus(status);
        return s;
    }

    @Test
    void runReview_shouldCreatePendingRowsForSkus() {
        SkuDelistReviewService service = service();
        when(lineRepository.skuBreakdownSince(any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"SKU-A", "可乐", 90, 18000L, 9000L},
                        new Object[]{"SKU-B", "果汁", 1, 300L, 150L}));
        DeviceSkuInventory inv = new DeviceSkuInventory();
        inv.setDeviceId("CAB-1");
        inv.setSkuId("SKU-A");
        inv.setQuantity(12);
        inv.setCapacity(20);
        when(inventoryRepository.findAllLimit(5000)).thenReturn(List.of(inv));
        when(inventoryLotService.deviceUsesLotLedger(anyString())).thenReturn(false);
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "可乐", "ACTIVE"), sku("SKU-B", "果汁", "ACTIVE")));
        when(reviewRepository.findBySkuId(anyString())).thenReturn(Optional.empty());
        when(reviewRepository.findAll()).thenReturn(List.of());

        List<SkuDelistReviewDto> result = service.runReview(30);

        assertEquals(0, result.size()); // list() 返回空（mock 未持久化）；重点验证 insert 被调用
        verify(reviewRepository, org.mockito.Mockito.times(2)).insert(any(SkuDelistReview.class));
    }

    @Test
    void decide_shouldMarkDelistedAndDisableSku() {
        SkuDelistReviewService service = service();
        SkuDelistReview review = new SkuDelistReview();
        review.setId(1L);
        review.setSkuId("SKU-A");
        review.setReviewStatus("PENDING");
        SkuCatalog sku = sku("SKU-A", "可乐", "ACTIVE");
        when(reviewRepository.findBySkuId("SKU-A")).thenReturn(Optional.of(review));
        when(skuCatalogRepository.findById("SKU-A")).thenReturn(Optional.of(sku));
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc()).thenReturn(List.of(sku));

        SkuDelistReviewDto dto = service.decide("SKU-A", "DELIST", "滞销", null, 1L);

        assertEquals("DELISTED", dto.reviewStatus());
        assertEquals("INACTIVE", sku.getStatus());
        verify(reviewRepository).save(review);
        verify(skuCatalogRepository).save(sku);
    }

    @Test
    void decide_shouldKeepSku() {
        SkuDelistReviewService service = service();
        SkuDelistReview review = new SkuDelistReview();
        review.setId(1L);
        review.setSkuId("SKU-A");
        review.setReviewStatus("PENDING");
        SkuCatalog sku = sku("SKU-A", "可乐", "ACTIVE");
        when(reviewRepository.findBySkuId("SKU-A")).thenReturn(Optional.of(review));
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc()).thenReturn(List.of(sku));

        SkuDelistReviewDto dto = service.decide("SKU-A", "KEEP", null, null, 1L);

        assertEquals("KEPT", dto.reviewStatus());
        assertEquals("ACTIVE", sku.getStatus());
    }
}
