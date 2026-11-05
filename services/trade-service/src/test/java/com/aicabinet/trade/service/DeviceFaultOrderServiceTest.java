package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.DeviceFaultOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 故障单「商品识别参数未就绪」旁路告警（2026-11-05 拍板 A 档）。
 *
 * <p>钉三件事：① 只有该原因才报；② 会话状态机与资金一律不碰（本类没有这些依赖）；
 * ③ 落库/告警任一失败都不得把设备侧 POST 打成 5xx（固件无退避重试）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DeviceFaultOrderServiceTest {

    @Mock private DeviceFaultOrderMapper mapper;
    @Mock private OpsExceptionService opsExceptionService;

    private DeviceFaultOrderService service;

    @BeforeEach
    void setUp() {
        service = new DeviceFaultOrderService(mapper, opsExceptionService);
    }

    @Test
    void paramsNotReadyRaisesDedicatedAlertWithOrderNo() {
        service.recordFault("dev-1", "CQYB1", "order-9", "RECOGNITION", "商品识别参数未就绪");

        ArgumentCaptor<OpsExceptionService.ExceptionReport.ExceptionRefs> refs =
                ArgumentCaptor.forClass(OpsExceptionService.ExceptionReport.ExceptionRefs.class);
        verify(opsExceptionService).report(eq("JIANGYI_PARAMS_NOT_READY"), eq("MEDIUM"),
                refs.capture(), eq("商品识别参数未就绪"), anyString());
        assertEquals("order-9", refs.getValue().orderId());
        assertEquals("dev-1", refs.getValue().deviceId());
    }

    @Test
    void otherFaultDoesNotRaiseAlert() {
        service.recordFault("dev-1", "CQYB1", "order-9", "CAMERA", "摄像头打开失败");

        verify(opsExceptionService, never()).report(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void insertFailureStillAlertsAndNeverThrows() {
        doThrow(new IllegalStateException("db down")).when(mapper)
                .insertFault(anyString(), anyString(), anyString(), anyString(), anyString(), any());

        assertDoesNotThrow(() -> service.recordFault(
                "dev-1", "CQYB1", "order-9", "RECOGNITION", "商品识别参数未就绪"));

        verify(opsExceptionService).report(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void alertFailureNeverBreaksDeviceReport() {
        doThrow(new IllegalStateException("lock busy")).when(opsExceptionService)
                .report(anyString(), anyString(), any(), anyString(), anyString());

        assertDoesNotThrow(() -> service.recordFault(
                "dev-1", "CQYB1", "order-9", "RECOGNITION", "商品识别参数未就绪"));
    }

    @Test
    void messageMatchIsSubstringOnly() {
        assertTrue(DeviceFaultOrderService.isParamsNotReady("商品识别参数未就绪"));
        assertTrue(DeviceFaultOrderService.isParamsNotReady("商品识别参数未就绪（V16）"));
        assertFalse(DeviceFaultOrderService.isParamsNotReady("摄像头打开失败"));
        assertFalse(DeviceFaultOrderService.isParamsNotReady(null));
    }
}
