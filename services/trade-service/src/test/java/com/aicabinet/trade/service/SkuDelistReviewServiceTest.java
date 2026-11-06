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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkuDelistReviewServiceTest {

    @Mock private CabinetOrderLineMapper lineRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private SkuDelistReviewMapper reviewRepository;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private AdminAuditService auditService;

    private SkuDelistReviewService service() {
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        SkuDelistReviewService svc = new SkuDelistReviewService(lineRepository, inventoryRepository,
                skuCatalogRepository, reviewRepository, inventoryLotService, distributedLockService,
                auditService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "self", svc);
        return svc;
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

        List<SkuDelistReviewDto> result = service.runReview(1L, 30);

        assertEquals(0, result.size()); // list() 返回空（mock 未持久化）；重点验证 insert 被调用
        verify(reviewRepository, org.mockito.Mockito.times(2)).insert(any(SkuDelistReview.class));
    }

    @Test
    void runReview_shouldSkipInactiveSkus() {
        SkuDelistReviewService service = service();
        when(lineRepository.skuBreakdownSince(any())).thenReturn(List.<Object[]>of());
        when(inventoryRepository.findAllLimit(5000)).thenReturn(List.of());
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "可乐", "ACTIVE"), sku("SKU-B", "果汁", "INACTIVE")));
        when(reviewRepository.findBySkuId(anyString())).thenReturn(Optional.empty());
        when(reviewRepository.findAll()).thenReturn(List.of());

        service.runReview(1L, 30);

        // 已下架 SKU 不建评审行：负向漂移=去掉过滤后此断言会变 2 次
        verify(reviewRepository, org.mockito.Mockito.times(1)).insert(any(SkuDelistReview.class));
        verify(reviewRepository, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.argThat(r -> r != null && "SKU-B".equals(r.getSkuId())));
    }

    /**
     * 🔴 定时任务（SkuReviewScheduler）没有登录人 ⇒ 传 null；而审计行 operator_id 是 NOT NULL + FK(user_info)，
     * 透传 null 会让整轮事务抛约束错并回滚（2026-11-06 服务器实测：SKU_DELIST_REVIEW_RUN 连续 5 条 ERROR，
     * 选品复核每天一行都没落库）⇒ 必须归一到系统操作人 0。
     * 负向漂移：把 runReview 改回直接透传 operatorId，本断言立刻红。
     */
    @Test
    void runReview_nullOperatorFromScheduler_shouldAuditAsSystemOperator() {
        SkuDelistReviewService service = service();
        when(lineRepository.skuBreakdownSince(any())).thenReturn(List.<Object[]>of());
        when(inventoryRepository.findAllLimit(5000)).thenReturn(List.of());
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "可乐", "ACTIVE")));
        when(reviewRepository.findBySkuId(anyString())).thenReturn(Optional.empty());
        when(reviewRepository.findAll()).thenReturn(List.of());

        service.runReview(null, 30);

        verify(auditService).appendLog(
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq("SKU_DELIST_REVIEW_RUN"),
                org.mockito.ArgumentMatchers.eq("SKU_DELIST_REVIEW"),
                org.mockito.ArgumentMatchers.eq("-"),
                org.mockito.ArgumentMatchers.eq("days=30,rows=0"));
    }

    @Test
    void list_shouldExcludeRowsOfInactiveSkus() {
        SkuDelistReviewService service = service();
        SkuDelistReview activeRow = new SkuDelistReview();
        activeRow.setId(1L);
        activeRow.setSkuId("SKU-A");
        SkuDelistReview inactiveRow = new SkuDelistReview();
        inactiveRow.setId(2L);
        inactiveRow.setSkuId("SKU-B");
        when(reviewRepository.findAll()).thenReturn(List.of(activeRow, inactiveRow));
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "可乐", "ACTIVE"), sku("SKU-B", "果汁", "INACTIVE")));

        List<SkuDelistReviewDto> result = service.list();

        // 负向漂移=去掉 list() 过滤后此断言会变 2
        assertEquals(1, result.size());
        assertEquals("SKU-A", result.get(0).skuId());
    }

    @Test
    void decide_shouldMarkDelistedAndDisableSku() {
        SkuDelistReviewService service = service();
        SkuDelistReview review = new SkuDelistReview();
        review.setId(1L);
        review.setSkuId("SKU-A");
        review.setReviewStatus("PENDING");
        SkuCatalog sku = sku("SKU-A", "可乐", "ACTIVE");
        when(reviewRepository.findBySkuIdForUpdate("SKU-A")).thenReturn(Optional.of(review));
        when(skuCatalogRepository.findById("SKU-A")).thenReturn(Optional.of(sku));
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc()).thenReturn(List.of(sku));

        SkuDelistReviewDto dto = service.decide("SKU-A", "DELIST", "滞销", null, 1L);

        assertEquals("DELISTED", dto.reviewStatus());
        assertEquals("INACTIVE", sku.getStatus());
        verify(reviewRepository).save(review);
        verify(skuCatalogRepository).save(sku);
        verify(auditService).appendLog(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("SKU_DELIST_DECIDE"),
                org.mockito.ArgumentMatchers.eq("SKU"),
                org.mockito.ArgumentMatchers.eq("SKU-A"),
                org.mockito.ArgumentMatchers.contains("action=DELIST"));
    }

    @Test
    void decide_shouldKeepSku() {
        SkuDelistReviewService service = service();
        SkuDelistReview review = new SkuDelistReview();
        review.setId(1L);
        review.setSkuId("SKU-A");
        review.setReviewStatus("PENDING");
        SkuCatalog sku = sku("SKU-A", "可乐", "ACTIVE");
        when(reviewRepository.findBySkuIdForUpdate("SKU-A")).thenReturn(Optional.of(review));
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc()).thenReturn(List.of(sku));

        SkuDelistReviewDto dto = service.decide("SKU-A", "KEEP", null, null, 1L);

        assertEquals("KEPT", dto.reviewStatus());
        assertEquals("ACTIVE", sku.getStatus());
    }
}
