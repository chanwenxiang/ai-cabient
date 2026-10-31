package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsExceptionDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.service.support.OpsExceptionServiceSupport;
import com.aicabinet.trade.storage.MinioVideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收尾D 实锤：列表路径既不该逐行签发录像（MinIO statObject + 将邑台账查询），
 * 也不该逐行查会话；orderId/userId 回填改走一次 selectBatchIds。
 */
@ExtendWith(MockitoExtension.class)
class OpsExceptionListVideoTest {

    @Mock OpsExceptionMapper repository;
    @Mock PermissionService permissionService;
    @Mock MerchantScopeService merchantScopeService;
    @Mock AdminAuditService auditService;
    @Mock AdminAuditLogMapper auditRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock SettlementService settlementService;
    @Mock DisputeService disputeService;
    @Mock RepairTicketService repairTicketService;
    @Mock DistributedLockService distributedLockService;
    @Mock SessionService sessionService;
    @Mock MinioVideoService minioVideoService;
    @Mock JiangyiOrderVideoService jiangyiOrderVideoService;

    private OpsExceptionService service;

    @BeforeEach
    void setUp() {
        OpsExceptionServiceSupport support = new OpsExceptionServiceSupport(
                auditService, auditRepository, sessionRepository, settlementService, disputeService,
                repairTicketService, sessionService, minioVideoService, jiangyiOrderVideoService);
        service = new OpsExceptionService(repository, permissionService, merchantScopeService, support,
                distributedLockService, null);
        org.mockito.Mockito.lenient().when(merchantScopeService.allowedDeviceIds(10001L)).thenReturn(null);
        org.mockito.Mockito.lenient().when(merchantScopeService.isGlobalScope(10001L)).thenReturn(true);
    }

    @Test
    void list_doesNotSignVideo_andPrefetchesSessionsInOneBatch() {
        OpsException item = openRecognitionException("EX-LV-1", "S-LV-1");
        item.setOrderId(null);
        item.setUserId(null);
        ShoppingSession session = session("S-LV-1", "ORD-LV-1", 10001L);
        session.setVideoUri("minio://videos/S-LV-1.mp4");
        when(repository.findFiltered(any(), any(), anyBoolean(), any(), any(),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));
        when(sessionRepository.findAllById(anyCollection())).thenReturn(List.of(session));

        PageResult<OpsExceptionDto> page = service.list(10001L, null, null, false, null, 0, 20);

        OpsExceptionDto dto = page.items().get(0);
        assertEquals("ORD-LV-1", dto.orderId(), "列表仍从会话回填 orderId");
        assertEquals(10001L, dto.userId());
        assertNull(dto.videoPreviewUrl(), "列表不再签发录像地址");
        assertTrue(dto.videoPreviewUrls() == null || dto.videoPreviewUrls().isEmpty());
        verify(minioVideoService, never()).presignPlaybackUrl(any());
        verify(jiangyiOrderVideoService, never()).playByOrderNo(anyString());
        verify(sessionRepository, never()).findById(anyString());
        verify(sessionRepository).findAllById(anyCollection());
    }

    @Test
    void detail_stillSignsVideoOnDemand() {
        OpsException item = openRecognitionException("EX-LV-2", "S-LV-2");
        item.setStatus("RESOLVED");
        ShoppingSession session = session("S-LV-2", "ORD-LV-2", 10001L);
        session.setVideoUri("minio://videos/S-LV-2.mp4");
        when(repository.findById("EX-LV-2")).thenReturn(Optional.of(item));
        when(auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc("OPS_EXCEPTION", "EX-LV-2"))
                .thenReturn(List.of());
        when(sessionRepository.findById("S-LV-2")).thenReturn(Optional.of(session));
        when(minioVideoService.presignPlaybackUrl("minio://videos/S-LV-2.mp4"))
                .thenReturn(Optional.of("https://signed/S-LV-2.mp4"));

        OpsExceptionDto dto = service.detail(10001L, "EX-LV-2").exception();

        assertEquals("https://signed/S-LV-2.mp4", dto.videoPreviewUrl(), "详情点播才现签");
    }

    private static OpsException openRecognitionException(String exceptionId, String sessionId) {
        OpsException item = new OpsException();
        item.setExceptionId(exceptionId);
        item.setExceptionType("RECOGNITION_FAILED");
        item.setStatus("OPEN");
        item.setSessionId(sessionId);
        item.setSeverity("HIGH");
        item.setTitle("test");
        return item;
    }

    private static ShoppingSession session(String sessionId, String orderId, Long userId) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId(sessionId);
        session.setState(SessionState.DISPUTED);
        session.setOrderId(orderId);
        session.setUserId(userId);
        return session;
    }
}
