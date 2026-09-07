package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ReplenishmentRouteDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.OpsCsvExportService;
import com.aicabinet.trade.service.OpsReplenishmentAdminService;
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

/**
 * G6 / 路由契约：补货域抽出后 URL/权限不变，编排仍经 Facade。
 */
@ExtendWith(MockitoExtension.class)
class OpsReplenishmentControllerTest {

    @Mock OpsReplenishmentAdminService replenishmentAdminService;
    @Mock OpsCsvExportService csvExportService;
    @Mock FileAttachmentService fileAttachmentService;
    @Mock HttpServletRequest request;

    private OpsReplenishmentController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsReplenishmentController(
                replenishmentAdminService, csvExportService, fileAttachmentService);
    }

    @Test
    void classKeepsSameAdminBasePath() {
        RequestMapping mapping = OpsReplenishmentController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertEquals("/api/v2/ops/admin", mapping.value()[0]);
        assertNotNull(OpsReplenishmentController.class.getAnnotation(RestController.class));
    }

    @Test
    void routes_keepsPermissionAndDelegates() throws Exception {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(3L);
        Method method = OpsReplenishmentController.class.getMethod(
                "routes", HttpServletRequest.class, String.class, int.class, int.class);
        assertEquals("ops:replenishment:list", method.getAnnotation(RequiresPermissions.class).value()[0]);
        assertEquals("/replenishment/routes", method.getAnnotation(GetMapping.class).value()[0]);

        when(replenishmentAdminService.listRoutesPage(3L, null, 0, 20)).thenReturn(new PageResult<>(List.of(), 0, 20, 0));
        ApiResponse<PageResult<ReplenishmentRouteDto>> response = controller.routes(request, null, 0, 20);
        assertEquals(0, response.code());
        verify(replenishmentAdminService).listRoutesPage(3L, null, 0, 20);
    }

    @Test
    void commercialController_noLongerOwnsReplenishmentRoutes() {
        for (Method method : OpsCommercialController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get == null) {
                continue;
            }
            for (String path : get.value()) {
                assertTrue(!path.startsWith("/replenishment/"),
                        () -> "replenishment path still on OpsCommercialController: " + path);
                assertTrue(!"/inventory".equals(path),
                        "inventory list should live on OpsReplenishmentController");
            }
        }
    }

    @Test
    void financeAndSla_liveOnOpsFinanceController() throws Exception {
        Method finance = OpsFinanceController.class.getMethod("financeStats", HttpServletRequest.class);
        assertEquals("/finance/stats", finance.getAnnotation(GetMapping.class).value()[0]);
        Method sla = OpsFinanceController.class.getMethod("sla", HttpServletRequest.class);
        assertEquals("/sla", sla.getAnnotation(GetMapping.class).value()[0]);
    }
}
