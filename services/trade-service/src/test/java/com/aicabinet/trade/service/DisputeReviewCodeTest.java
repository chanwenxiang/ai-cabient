package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DisputeMessageMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeReviewCodeTest {

    @Mock DisputeTicketMapper disputeRepository;
    @Mock DisputeMessageMapper disputeMessageRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock SettlementService settlementService;
    @Mock MinioVideoService minioVideoService;
    @Mock JiangyiOrderVideoService jiangyiOrderVideoService;
    @Mock AdminAuditService auditService;
    @Mock RiskControlService riskControlService;
    @Mock PermissionService permissionService;
    @Mock MerchantScopeService merchantScopeService;
    @Mock MerchantPortalGuard merchantPortalGuard;
    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock UserInfoMapper userInfoRepository;
    @Mock OpsExceptionService opsExceptionService;
    @Mock FileAttachmentService fileAttachmentService;
    @Mock SystemConfigService systemConfigService;
    @Mock VideoArchiveService videoArchiveService;
    @Mock OrderPaymentService orderPaymentService;
    @Mock DistributedLockService distributedLockService;
    @Mock CabinetMetrics metrics;

    private DisputeService service;

    @BeforeEach
    void setUp() {
        service = new DisputeService(disputeRepository, disputeMessageRepository, sessionRepository, orderRepository,
                settlementService, new ObjectMapper(), minioVideoService, jiangyiOrderVideoService, auditService,
                riskControlService,
                permissionService, merchantScopeService, null, merchantPortalGuard, skuCatalogRepository,
                new DisputeSlaProperties(48, 12, null, false), userInfoRepository, opsExceptionService,
                fileAttachmentService, null, videoArchiveService, orderPaymentService, distributedLockService,
                systemConfigService, null, null, metrics, null);
        lenient().when(systemConfigService.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
    }

    @Test
    void reviewCodeFor_mockModel_yieldsMock() {
        var recognition = new VisionServiceClient.RecognitionResult(
                "T1", List.of(), 0.1f, true, "mock-v1", List.of());
        assertEquals("MOCK", DisputeService.reviewCodeFor(recognition, "人工复核"));
    }

    @Test
    void reviewCodeFor_gravityMismatch_yieldsGravityMismatch() {
        var recognition = new VisionServiceClient.RecognitionResult(
                "T2", List.of(), 0.2f, true, "gravity-mismatch", List.of());
        assertEquals("GRAVITY_MISMATCH", DisputeService.reviewCodeFor(recognition, "视觉与重力不一致"));
    }

    @Test
    void reviewCodeFor_gravityFill_yieldsGravityFill() {
        var recognition = new VisionServiceClient.RecognitionResult(
                "T3", List.of(), 0.2f, true, "gravity-fill", List.of());
        assertEquals("GRAVITY_FILL", DisputeService.reviewCodeFor(recognition, "仅有重力信号"));
    }

    @Test
    void createTicket_persistsReviewCodeFromRecognition() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-REV-001");
        session.setUserId(10001L);
        session.setState(SessionState.DISPUTED);

        when(disputeRepository.findBySessionId("S-REV-001")).thenReturn(Optional.empty());
        when(disputeRepository.save(any(DisputeTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.findById("S-REV-001")).thenReturn(Optional.of(session));
        when(orderRepository.findBySessionId("S-REV-001")).thenReturn(Optional.empty());
        when(minioVideoService.presignPlaybackUrl(any())).thenReturn(Optional.empty());
        when(disputeMessageRepository.findByTicketIdOrderByCreatedAtAsc(anyString())).thenReturn(List.of());
        when(fileAttachmentService.listDisputeEvidence(anyString())).thenReturn(List.of());

        var recognition = new VisionServiceClient.RecognitionResult(
                "TASK-MOCK", List.of(), 0.05f, true, "mock-force-need-review", List.of("unknown"));

        var dto = service.createTicket(session, recognition, "模拟识别需人工复核（非生产精度）");

        ArgumentCaptor<DisputeTicket> captor = ArgumentCaptor.forClass(DisputeTicket.class);
        verify(disputeRepository).save(captor.capture());
        assertEquals("MOCK", captor.getValue().getReviewCode());
        assertEquals("RECOGNITION", captor.getValue().getCategory());
        assertEquals("OPEN", captor.getValue().getStatus());
        assertEquals("MOCK", dto.reviewCode());
    }

    /**
     * CB-030 一致性：将邑柜机不写 shopping_session.video_uri，只按会话列签发地址时争议页
     * 永远看不到录像 —— 必须回退到 jiangyi_order_video 台账（order_no 即 sessionId）。
     * 同时断言「失败片被跳过、取第一条可播放片」，避免把失败位当成可播地址给出。
     */
    @Test
    void createTicket_fallsBackToJiangyiLedgerWhenSessionHasNoEdgeVideo() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-REV-JY");
        session.setUserId(10002L);
        session.setState(SessionState.DISPUTED);

        when(disputeRepository.findBySessionId("S-REV-JY")).thenReturn(Optional.empty());
        when(disputeRepository.save(any(DisputeTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.findById("S-REV-JY")).thenReturn(Optional.of(session));
        when(orderRepository.findBySessionId("S-REV-JY")).thenReturn(Optional.empty());
        // 边缘链路：会话没写 video_uri ⇒ 预签名必为空
        when(minioVideoService.presignPlaybackUrl(any())).thenReturn(Optional.empty());
        when(disputeMessageRepository.findByTicketIdOrderByCreatedAtAsc(anyString())).thenReturn(List.of());
        when(fileAttachmentService.listDisputeEvidence(anyString())).thenReturn(List.of());
        // 将邑台账：第 1 片失败、第 2 片可播 —— 期望取到第 2 片签名地址
        when(jiangyiOrderVideoService.playByOrderNo("S-REV-JY")).thenReturn(List.of(
                new JiangyiOrderVideoService.PlayUrlView(7L, "S-REV-JY", "DEV-JY", 1, 2, Instant.now(),
                        List.of(
                                new JiangyiOrderVideoService.PlayUrlItem(1, null, false, false, "该片生成或上传失败"),
                                new JiangyiOrderVideoService.PlayUrlItem(
                                        2, "https://oss.example/signed-2.mp4", true, true, null)))));

        var dto = service.createTicket(session, null, "用户申诉");

        assertEquals("https://oss.example/signed-2.mp4", dto.videoPreviewUrl());
    }
}
