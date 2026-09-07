package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.OpsCsvExportService;
import com.aicabinet.trade.service.OpsRiskAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsRiskControllerTest {

    @Mock OpsRiskAdminService riskAdminService;
    @Mock OpsCsvExportService csvExportService;
    @Mock HttpServletRequest request;

    private OpsRiskController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsRiskController(riskAdminService, csvExportService);
    }

    @Test
    void classKeepsSameAdminBasePath() {
        RequestMapping mapping = OpsRiskController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertEquals("/api/v2/ops/admin", mapping.value()[0]);
        assertNotNull(OpsRiskController.class.getAnnotation(RestController.class));
    }

    @Test
    void riskEvents_keepsPermissionAndDelegates() throws Exception {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(5L);
        Method method = OpsRiskController.class.getMethod(
                "riskEvents", HttpServletRequest.class, int.class, int.class);
        assertEquals("ops:risk:list", method.getAnnotation(RequiresPermissions.class).value()[0]);
        assertEquals("/risk/events", method.getAnnotation(GetMapping.class).value()[0]);

        when(riskAdminService.listRiskEvents(5L, 0, 20)).thenReturn(new PageResult<>(List.of(), 0, 20, 0));
        ApiResponse<?> response = controller.riskEvents(request, 0, 20);
        assertEquals(0, response.code());
        verify(riskAdminService).listRiskEvents(5L, 0, 20);
    }

    @Test
    void commercialController_noLongerOwnsRisk() {
        for (Method method : OpsCommercialController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get == null) {
                continue;
            }
            for (String path : get.value()) {
                assertTrue(!path.startsWith("/risk/"),
                        () -> "risk path still on OpsCommercialController: " + path);
            }
        }
    }
}
