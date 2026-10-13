package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateMerchantReplenishmentRequest;
import com.aicabinet.common.dto.CreateMerchantReplenishmentRequestLine;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.MerchantReplenishmentRequest;
import com.aicabinet.trade.domain.MerchantReplenishmentRequestLine;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantReplenishmentRequestLineMapper;
import com.aicabinet.trade.mapper.MerchantReplenishmentRequestMapper;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AU-090：要货数量上界（单行）+ 接单聚合溢出夹逼（同 SKU 总量）。
 * 上限取值=保守默认（DC-18 待拍板后可调）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantReplenishmentQtyCapsTest {

    @Mock private PermissionService permissionService;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private MerchantPortalGuard merchantPortalGuard;
    @Mock private ReplenishmentService replenishmentService;
    @Mock private WarehouseService warehouseService;
    @Mock private AdminAuditService auditService;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private MerchantMapper merchantRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private MerchantReplenishmentRequestMapper requestRepository;
    @Mock private MerchantReplenishmentRequestLineMapper requestLineRepository;
    @Mock private ReplenishmentRouteMapper routeRepository;
    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private FileAttachmentService fileAttachmentService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ApprovalWorkflowService approvalWorkflowService;

    private MerchantReplenishmentService service;

    @BeforeEach
    void setUp() {
        service = new MerchantReplenishmentService(
                permissionService, merchantFeaturePackService, merchantPortalGuard,
                replenishmentService, null, warehouseService, auditService,
                deviceRepository, merchantRepository, skuCatalogRepository, userInfoRepository,
                requestRepository, requestLineRepository, routeRepository, taskRepository,
                fileAttachmentService, distributedLockService, approvalWorkflowService,
                null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        device.setDeviceName("测试柜");
        device.setMerchantId("M1");
        when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));
        when(requestRepository.save(any())).thenAnswer(i -> {
            MerchantReplenishmentRequest r = i.getArgument(0);
            if (r.getRequestId() == null) r.setRequestId(7L);
            return r;
        });
        when(requestRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(submittedRequest()));
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-1");
        when(skuCatalogRepository.findById("SKU-1")).thenReturn(Optional.of(sku));
        when(skuCatalogRepository.findAllById(anyCollection())).thenReturn(List.of(sku));
        when(replenishmentService.suggestForDevice("CAB-1")).thenReturn(List.of());
        when(requestLineRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(routeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(warehouseService.tryCreateOutboundFromLines(any(), anyString(), any(), anyMap(), any()))
                .thenReturn(null);
    }

    @Test
    void submitRequest_rejectsLineAbovePerLineCap() {
        when(requestLineRepository.findByRequestIdOrderByLineIdAsc(7L))
                .thenReturn(List.of(requestLine(1500)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.submitRequest(1L, requestWithQty(1500)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        // 超上限的行不允许落库（HEAD 码：仅 qty<=0 判定 ⇒ 1500 会直接 save 落库 ⇒ 本断言先红）
        verify(requestLineRepository, never()).save(any());
        verify(distributedLockService).unlock(MerchantReplenishmentService.replenishmentDeviceLockKey("CAB-1"));
    }

    @Test
    void submitRequest_allowsLineAtCapBoundary() {
        when(requestLineRepository.findByRequestIdOrderByLineIdAsc(7L))
                .thenReturn(List.of(requestLine(1000)));

        service.submitRequest(1L, requestWithQty(1000));

        verify(requestLineRepository, times(1)).save(
                argThat(row -> row.getRequestedQty() == 1000));
    }

    @Test
    void acceptRequest_rejectsAggregatedSameSkuAboveCap() {
        // 两行同 SKU 各 2_000_000_000：HEAD 码 Integer::sum 溢出为负且照常建路线/出库（负量直贯仓库）
        when(requestLineRepository.findByRequestIdOrderByLineIdAsc(5L))
                .thenReturn(List.of(requestLine(2_000_000_000), requestLine(2_000_000_000)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.acceptRequest(1L, 5L));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        // 聚合越界必须在建路线/任务/出库之前拦截
        verify(routeRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
        verify(warehouseService, never()).tryCreateOutboundFromLines(any(), anyString(), any(), anyMap(), any());
    }

    @Test
    void acceptRequest_aggregatesSameSkuWithinCap() {
        when(requestLineRepository.findByRequestIdOrderByLineIdAsc(5L))
                .thenReturn(List.of(requestLine(3000), requestLine(1500)));

        service.acceptRequest(1L, 5L);

        verify(replenishmentService).seedDraftRestockLines(
                nullable(Long.class), eq("CAB-1"), eq(Map.of("SKU-1", 4500)));
    }

    private static CreateMerchantReplenishmentRequest requestWithQty(int qty) {
        return new CreateMerchantReplenishmentRequest(
                "CAB-1", null,
                List.of(new CreateMerchantReplenishmentRequestLine("SKU-1", qty)), null);
    }

    private static MerchantReplenishmentRequest submittedRequest() {
        MerchantReplenishmentRequest request = new MerchantReplenishmentRequest();
        request.setRequestId(5L);
        request.setDeviceId("CAB-1");
        request.setMerchantId("M1");
        request.setStatus("SUBMITTED");
        return request;
    }

    private static MerchantReplenishmentRequestLine requestLine(int qty) {
        MerchantReplenishmentRequestLine line = new MerchantReplenishmentRequestLine();
        line.setSkuId("SKU-1");
        line.setRequestedQty(qty);
        return line;
    }
}
