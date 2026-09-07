package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.FootfallAnalyticsDto;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.DeviceEnvService;
import com.aicabinet.trade.service.DeviceTempPlanService;
import com.aicabinet.trade.service.FootfallAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsAnalyticsAndDeviceEnvControllerTest {

    @Mock FootfallAnalyticsService footfallAnalyticsService;
    @Mock DeviceTempPlanService deviceTempPlanService;
    @Mock DeviceEnvService deviceEnvService;

    @Test
    void analytics_keepsPathAndDelegates() throws Exception {
        OpsAnalyticsController controller = new OpsAnalyticsController(footfallAnalyticsService);
        Method method = OpsAnalyticsController.class.getMethod(
                "footfallAnalytics", HttpServletRequest.class, int.class, int.class, int.class);
        assertEquals("/api/v2/ops/admin",
                OpsAnalyticsController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertEquals("ops:analytics:footfall:view", method.getAnnotation(RequiresPermissions.class).value()[0]);
        assertEquals("/analytics/footfall", method.getAnnotation(GetMapping.class).value()[0]);
        when(footfallAnalyticsService.analytics(7, 50, 20)).thenReturn(null);
        ApiResponse<FootfallAnalyticsDto> response = controller.footfallAnalytics(null, 7, 50, 20);
        assertEquals(0, response.code());
        verify(footfallAnalyticsService).analytics(7, 50, 20);
    }

    @Test
    void deviceEnv_keepsTempPlanPath() throws Exception {
        assertEquals("/api/v2/ops/admin",
                OpsDeviceEnvController.class.getAnnotation(RequestMapping.class).value()[0]);
        Method method = OpsDeviceEnvController.class.getMethod("tempPlan", HttpServletRequest.class, String.class);
        assertEquals("/devices/{deviceId}/temp-plan", method.getAnnotation(GetMapping.class).value()[0]);
        assertNotNullCtor();
    }

    private void assertNotNullCtor() throws Exception {
        assertTrue(OpsDeviceEnvController.class.getConstructor(
                DeviceTempPlanService.class, DeviceEnvService.class) != null);
        new OpsDeviceEnvController(deviceTempPlanService, deviceEnvService);
    }
}
