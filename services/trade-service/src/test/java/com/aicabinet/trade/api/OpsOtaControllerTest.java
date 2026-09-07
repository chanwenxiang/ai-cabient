package com.aicabinet.trade.api;

import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.OpsOtaAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsOtaControllerTest {

    @Mock OpsOtaAdminService otaAdminService;
    @Mock HttpServletRequest request;
    private OpsOtaController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsOtaController(otaAdminService);
    }

    @Test
    void listOta_keepsPathAndDelegates() throws Exception {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(1L);
        Method method = OpsOtaController.class.getMethod("listOta", HttpServletRequest.class);
        assertEquals("/api/v2/ops/admin", OpsOtaController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertEquals("ops:ota:list", method.getAnnotation(RequiresPermissions.class).value()[0]);
        assertEquals("/ota/releases", method.getAnnotation(GetMapping.class).value()[0]);
        when(otaAdminService.listOta(1L)).thenReturn(List.of());
        assertEquals(0, controller.listOta(request).code());
        verify(otaAdminService).listOta(1L);
    }

    @Test
    void commercialOnlyKeepsCommercialFlow() {
        boolean found = false;
        for (Method method : OpsCommercialController.class.getDeclaredMethods()) {
            if ("runCommercialFlow".equals(method.getName())) {
                found = true;
            }
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                for (String path : get.value()) {
                    assertTrue(!path.startsWith("/ota/"));
                }
            }
        }
        assertTrue(found);
    }
}
