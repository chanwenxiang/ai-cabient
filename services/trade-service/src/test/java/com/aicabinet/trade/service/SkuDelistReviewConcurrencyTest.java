package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuDelistReviewMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkuDelistReviewConcurrencyTest {

    @Mock private CabinetOrderLineMapper lineRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private SkuDelistReviewMapper reviewRepository;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private DistributedLockService distributedLockService;

    private SkuDelistReviewService service;

    @BeforeEach
    void setUp() {
        service = new SkuDelistReviewService(lineRepository, inventoryRepository,
                skuCatalogRepository, reviewRepository, inventoryLotService, distributedLockService);
    }

    @Test
    void runReview_whenBatchLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(SkuDelistReviewService.reviewBatchLockKey()), eq(120L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.runReview(30));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void decide_whenSkuLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(SkuDelistReviewService.skuReviewLockKey("SKU-A")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.decide("SKU-A", "KEEP", null, null, 1L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void decide_whenReviewNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(SkuDelistReviewService.skuReviewLockKey("SKU-B")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(reviewRepository.findBySkuIdForUpdate("SKU-B")).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.decide("SKU-B", "KEEP", null, null, 1L));

        verify(distributedLockService).unlock(SkuDelistReviewService.skuReviewLockKey("SKU-B"));
    }
}
