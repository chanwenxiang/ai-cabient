package com.aicabinet.trade.api;

import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.OpsFinanceAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class OpsFinanceControllerTest {

    @Test
    void keepsAdminBaseAndFinancePaths() throws Exception {
        assertEquals("/api/v2/ops/admin",
                OpsFinanceController.class.getAnnotation(RequestMapping.class).value()[0]);
        Method stats = OpsFinanceController.class.getMethod("financeStats", HttpServletRequest.class);
        assertEquals("ops:finance:view", stats.getAnnotation(RequiresPermissions.class).value()[0]);
        assertEquals("/finance/stats", stats.getAnnotation(GetMapping.class).value()[0]);
        Method sla = OpsFinanceController.class.getMethod("sla", HttpServletRequest.class);
        assertEquals("/sla", sla.getAnnotation(GetMapping.class).value()[0]);
        assertNotNull(OpsFinanceController.class.getConstructor(OpsFinanceAdminService.class));
    }

    @Test
    void commercialNoLongerOwnsFinanceOrRecon() {
        for (Method method : OpsCommercialController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get == null) {
                continue;
            }
            for (String path : get.value()) {
                assertTrue(!path.startsWith("/finance/") && !path.startsWith("/reconciliation/")
                        && !"/sla".equals(path) && !"/reconciliation".equals(path));
            }
        }
    }
}
