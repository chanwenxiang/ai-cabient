package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 将邑识别未成功的成因分档（2026-10-21 真机 CQYB10720 18:11 单）：设备
 * {@code uploadOrderError} 的 errorMsg「人工复核」曾被记成「classId 无法解析到我方 SKU」+
 * {@code JIANGYI_CLASS_MAPPING_MISS}，运营看不到厂商到底回了什么。现由 gateway 透传
 * cause/vendorDetail，成因文案、异常类型、设备原文三处同时对齐。
 *
 * <p>钉两条不可动的口径：① 各档 reason/fail_reason 都以「识别超时」开头——自动免单门控
 * {@code DisputeTicketMapper.likeRight(reason,"识别超时")} 按前缀筛单；② 资金语义不变
 * （仍释放预授权 + DISPUTED + 争议单，无订单无扣款）。</p>
 */
@ExtendWith(MockitoExtension.class)
class JiangyiRecognitionTimeoutCauseTest {

    private static final String SESSION = "S-CAUSE";
    private static final String DEVICE = "JY-OWNER";

    @Mock private ShoppingSessionMapper repository;
    @Mock private SessionService sessionService;
    @Mock private DisputeService disputeService;
    @Mock private ConsumerPreauthService consumerPreauthService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private OpsExceptionService opsExceptionService;
    @Mock private JiangyiDeviceDirectory jiangyiDeviceDirectory;

    @Captor private ArgumentCaptor<String> reasonCaptor;
    @Captor private ArgumentCaptor<String> typeCaptor;
    @Captor private ArgumentCaptor<String> titleCaptor;
    @Captor private ArgumentCaptor<String> detailCaptor;

    private JiangyiRecognitionTimeoutService timeoutService;

    @BeforeEach
    void setUp() {
        timeoutService = new JiangyiRecognitionTimeoutService(repository, sessionService,
                disputeService, consumerPreauthService, distributedLockService, opsExceptionService,
                jiangyiDeviceDirectory);
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    private ShoppingSession recognizing() {
        ShoppingSession s = new ShoppingSession();
        s.setSessionId(SESSION);
        s.setDeviceId(DEVICE);
        s.setUserId(200_000_001L);
        s.setState(SessionState.RECOGNIZING);
        when(repository.findByIdForUpdate(SESSION)).thenReturn(Optional.of(s));
        return s;
    }

    private ShoppingSession escalate(String cause, String vendorDetail) {
        ShoppingSession session = recognizing();
        assertTrue(timeoutService.markRecognitionTimeout(SESSION, DEVICE, cause, vendorDetail));
        verify(disputeService).createTimeoutTicket(any(ShoppingSession.class), reasonCaptor.capture());
        verify(opsExceptionService).report(typeCaptor.capture(), eq("HIGH"),
                any(OpsExceptionService.ExceptionReport.ExceptionRefs.class),
                titleCaptor.capture(), detailCaptor.capture());
        return session;
    }

    @Test
    void deviceErrorOrder_keepsVendorMsgAndRecognitionFailedType() {
        ShoppingSession session = escalate(JiangyiRecognitionTimeoutService.CAUSE_DEVICE_ERROR_ORDER, "人工复核");

        assertTrue(reasonCaptor.getValue().startsWith("识别超时"), "免单门控按前缀筛，档位不得改前缀");
        assertTrue(reasonCaptor.getValue().contains("设备上报异常订单"));
        assertTrue(reasonCaptor.getValue().contains("人工复核"), "设备原文必须进争议单，否则只能猜成因");
        assertEquals(reasonCaptor.getValue(), session.getFailReason());
        assertEquals("RECOGNITION_FAILED", typeCaptor.getValue());
        assertEquals("将邑设备上报异常订单", titleCaptor.getValue());
        assertTrue(detailCaptor.getValue().contains("人工复核"));
        verify(consumerPreauthService).releaseIfFrozen(session);
        verify(sessionService).transition(session, SessionState.DISPUTED);
    }

    @Test
    void emptyForms_hasNoVendorSuffix() {
        escalate(JiangyiRecognitionTimeoutService.CAUSE_EMPTY_FORMS, "   ");

        assertTrue(reasonCaptor.getValue().startsWith("识别超时"));
        assertTrue(reasonCaptor.getValue().contains("未识别到商品"));
        assertTrue(!reasonCaptor.getValue().contains("设备原文"), "空原文不拼「｜设备原文：」尾巴");
        assertEquals("RECOGNITION_FAILED", typeCaptor.getValue());
        assertEquals("将邑识别结果为空", titleCaptor.getValue());
    }

    @Test
    void classMappingMiss_keepsDedicatedTypeAndFormList() {
        escalate(JiangyiRecognitionTimeoutService.CAUSE_CLASS_MAPPING_MISS, "classId=29 qty=1 thirdPid=0");

        assertTrue(reasonCaptor.getValue().startsWith("识别超时"));
        assertTrue(reasonCaptor.getValue().contains("classId=29"));
        assertEquals("JIANGYI_CLASS_MAPPING_MISS", typeCaptor.getValue());
        assertEquals("将邑识别映射未命中", titleCaptor.getValue());
        assertTrue(detailCaptor.getValue().contains("classId=29"));
    }

    /** 未升级的 gateway（或内部调用）不带 cause ⇒ 落回旧「超时未回传」口径，不能变成映射文案。 */
    @Test
    void missingCause_fallsBackToLegacyTimeoutCopy() {
        escalate(null, null);

        assertEquals("识别超时（将邑端识别结果超时未回传），已转人工审核，本次暂未扣款", reasonCaptor.getValue());
        assertEquals("RECOGNITION_TIMEOUT", typeCaptor.getValue());
        assertEquals("将邑识别结果超时未回传", titleCaptor.getValue());
    }

    @Test
    void oversizedVendorDetail_truncatedWithinReasonColumn() {
        escalate(JiangyiRecognitionTimeoutService.CAUSE_DEVICE_ERROR_ORDER, "异".repeat(500));

        assertTrue(reasonCaptor.getValue().length() <= 256, "dispute_ticket.reason / fail_reason 列宽 256");
        assertTrue(detailCaptor.getValue().length() <= 1000, "ops_exception.detail 列宽 1000");
        assertTrue(reasonCaptor.getValue().endsWith("…"));
    }
}
