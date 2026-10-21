package com.aicabinet.trade.api;

import com.aicabinet.common.dto.OpsExceptionDangerActionRequest;
import com.aicabinet.common.dto.OpsExceptionDetailDto;
import com.aicabinet.common.dto.OpsExceptionDto;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.JiangyiDeviceDirectory;
import com.aicabinet.trade.service.OpsExceptionService;
import com.aicabinet.trade.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 将邑柜的「重试识别」闸门（2026-10-21 拍板）：厂商识别结果只在将邑侧，我方重试喂的是
 * RETIRED 空结果 ⇒ 必然退回人工审核并清空 fail_reason，属空转按钮，须拒绝且不留处置流水。
 */
@ExtendWith(MockitoExtension.class)
class OpsExceptionJiangyiRetryGuardTest {

    private static final String JIANGYI_DEVICE = "EG943322621391";
    private static final String LEGACY_DEVICE = "719325384517";

    @Mock OpsExceptionService service;
    @Mock SessionService sessionService;
    @Mock JiangyiDeviceDirectory jiangyiDeviceDirectory;
    @Mock HttpServletRequest request;

    private OpsExceptionController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsExceptionController(service, sessionService, jiangyiDeviceDirectory);
    }

    private static OpsExceptionDetailDto detail(String deviceId) {
        OpsExceptionDto exception = new OpsExceptionDto(
                "EX-1", "RECOGNITION_FAILED", "HIGH", "OPEN",
                deviceId, "S-1", null, 10001L,
                "将邑识别映射未命中", "识别超时（将邑端未回传）", null, null,
                null, null, null);
        return new OpsExceptionDetailDto(exception, List.of());
    }

    private static OpsExceptionDangerActionRequest body() {
        return new OpsExceptionDangerActionRequest("KEY-1", "运营重试");
    }

    @Test
    void jiangyiSession_retryRejectedWithoutRecordingAnyAction() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(5L);
        when(service.detail(5L, "EX-1")).thenReturn(detail(JIANGYI_DEVICE));
        when(jiangyiDeviceDirectory.isJiangyi(JIANGYI_DEVICE)).thenReturn(true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.retry(request, "EX-1", body()));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("人工商品处置"),
                "拒绝理由要直接给运营出路：" + error.getReason());
        verify(service, never()).recordAction(any(), anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(sessionService);
    }

    @Test
    void nonJiangyiSession_retryStillRuns() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(5L);
        when(service.detail(5L, "EX-1")).thenReturn(detail(LEGACY_DEVICE));
        when(jiangyiDeviceDirectory.isJiangyi(LEGACY_DEVICE)).thenReturn(false);
        when(sessionService.retryForOperations("S-1")).thenReturn(new SessionDto(
                "S-1", 10001L, LEGACY_DEVICE, SessionState.DISPUTED,
                null, null, null, null, null, null));

        controller.retry(request, "EX-1", body());

        verify(service).recordAction(5L, "EX-1", "OPS_EXCEPTION_RETRY", "KEY-1", "运营重试");
        verify(sessionService).retryForOperations("S-1");
    }
}
