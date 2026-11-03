package com.aicabinet.trade.api;

import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.DeviceRentalService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA-118 钉测：押金登记的裸 Map 数值参数解析（2026-11-03）。
 *
 * <p>原来 {@code longOrNull} 把 {@code NumberFormatException} catch 掉返回 null ⇒
 * 「填了但填错」与「没填」被折成同一件事：月退额静默丢失、{@code contractYears} 静默落到
 * 默认 5 年，而这一刻押金已经真扣了商户余额（真扣真退链路上的关键参数缩水且无 400 反馈）。
 * 现在非整数必须 400 并指名道姓；空白/缺席仍按「未填=null」放行——这条反向断言同样要钉，
 * 否则下次「顺手收紧」会把老前端的正常提交打成 400。</p>
 */
class OpsDeviceRentalControllerDepositBodyTest {

    private final DeviceRentalService rentalService = mock(DeviceRentalService.class);
    private final OpsDeviceRentalController controller = new OpsDeviceRentalController(rentalService);

    private HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(42L);
        return request;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        map.put("deviceId", "D1");
        map.put("amountCents", 50_000);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    @Test
    void nonIntegerMonthlyRefund_rejected400_andServiceNeverCalled() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerDeposit(body("monthlyRefundCents", "1O00"), request()));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("monthlyRefundCents 必须是整数"), ex.getReason());
        verify(rentalService, never()).registerDeposit(any(), any(), anyLong(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void fractionalContractYears_rejected400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerDeposit(body("contractYears", "3.5"), request()));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("contractYears"), ex.getReason());
    }

    /** 反向锁：空白串与键缺席 = 「未填」，必须照常放行并传 null，不能被一起收成 400。 */
    @Test
    void blankOrAbsentNumbers_stillPassAsNull() {
        controller.registerDeposit(body("contractYears", "  "), request());
        controller.registerDeposit(body(), request());

        verify(rentalService, org.mockito.Mockito.times(2)).registerDeposit(eq(42L), eq("D1"),
                eq(50_000L), any(), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null));
    }

    /** 显式传值（含字符串数字）必须逐字段到达服务层，锁住「按字段名取参」这件事。 */
    @Test
    void explicitNumbers_reachServiceInDeclarationOrder() {
        controller.registerDeposit(body("remark", "两年约",
                "monthlyRefundCents", "300", "contractYears", "3"), request());

        verify(rentalService).registerDeposit(eq(42L), eq("D1"), eq(50_000L), eq("两年约"),
                eq(300L), eq(null), eq(null), eq(null), eq(null), eq(3));
    }
}
