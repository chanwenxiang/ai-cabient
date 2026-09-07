package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.WarehouseDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.OpsCsvExportService;
import com.aicabinet.trade.service.OpsWarehouseAdminService;
import com.aicabinet.trade.service.WarehouseBinService;
import com.aicabinet.trade.service.WarehouseStocktakeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
 * G6 / 路由契约：warehouse 域抽出后 URL/权限不变；出库 ship 仍走 Facade。
 */
@ExtendWith(MockitoExtension.class)
class OpsWarehouseControllerTest {

    @Mock WarehouseStocktakeService warehouseStocktakeService;
    @Mock WarehouseBinService warehouseBinService;
    @Mock OpsCsvExportService csvExportService;
    @Mock OpsWarehouseAdminService warehouseAdminService;
    @Mock HttpServletRequest request;

    private OpsWarehouseController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsWarehouseController(
                warehouseStocktakeService, warehouseBinService, csvExportService, warehouseAdminService);
    }

    @Test
    void classKeepsSameAdminBasePath() {
        RequestMapping mapping = OpsWarehouseController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertEquals("/api/v2/ops/admin", mapping.value()[0]);
        assertNotNull(OpsWarehouseController.class.getAnnotation(RestController.class));
    }

    @Test
    void warehouses_keepsOrPermissionAndDelegatesToFacade() throws Exception {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(9L);
        Method method = OpsWarehouseController.class.getMethod(
                "warehouses", HttpServletRequest.class, String.class, int.class, int.class);
        RequiresPermissions perm = method.getAnnotation(RequiresPermissions.class);
        assertEquals(RequiresPermissions.Logical.OR, perm.logical());
        assertTrue(List.of(perm.value()).contains("ops:warehouse:list"));
        assertEquals("/warehouse/list", method.getAnnotation(GetMapping.class).value()[0]);

        when(warehouseAdminService.listWarehousesPage(9L, null, 0, 20)).thenReturn(new PageResult<>(List.of(), 0, 20, 0));
        ApiResponse<PageResult<WarehouseDto>> response = controller.warehouses(request, null, 0, 20);
        assertEquals(0, response.code());
        verify(warehouseAdminService).listWarehousesPage(9L, null, 0, 20);
    }

    @Test
    void stocktakes_delegatesToStocktakeService() throws Exception {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(9L);
        Method method = OpsWarehouseController.class.getMethod(
                "stocktakes", HttpServletRequest.class, String.class, String.class, int.class, int.class);
        assertEquals("ops:warehouse:list", method.getAnnotation(RequiresPermissions.class).value()[0]);
        assertEquals("/warehouse/stocktakes", method.getAnnotation(GetMapping.class).value()[0]);

        when(warehouseStocktakeService.listPage(9L, null, null, 0, 20))
                .thenReturn(new PageResult<>(List.of(), 0, 20, 0));
        assertEquals(0, controller.stocktakes(request, null, null, 0, 20).code());
        verify(warehouseStocktakeService).listPage(9L, null, null, 0, 20);
    }

    @Test
    void shipOutbound_keepsAdminOrchestration() throws Exception {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(9L);
        Method method = OpsWarehouseController.class.getMethod(
                "shipOutbound", HttpServletRequest.class, Long.class);
        assertEquals("/warehouse/outbounds/{outboundId}/ship",
                method.getAnnotation(PostMapping.class).value()[0]);
        when(warehouseAdminService.shipWarehouseOutbound(9L, 55L)).thenReturn(null);
        assertEquals(0, controller.shipOutbound(request, 55L).code());
        verify(warehouseAdminService).shipWarehouseOutbound(9L, 55L);
    }

    @Test
    void commercialController_noLongerOwnsWarehouseList() {
        for (Method method : OpsCommercialController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get == null) {
                continue;
            }
            for (String path : get.value()) {
                assertTrue(!path.startsWith("/warehouse/"),
                        () -> "warehouse path still on OpsCommercialController: " + path);
            }
        }
    }
}
