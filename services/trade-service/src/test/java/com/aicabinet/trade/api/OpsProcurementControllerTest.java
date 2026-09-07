package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.ProcurementService;
import com.aicabinet.trade.service.PurchaseSuggestionService;
import com.aicabinet.trade.service.SupplierPayableService;
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
 * G6 / 路由契约：采购域抽出后 URL/权限不变。
 */
@ExtendWith(MockitoExtension.class)
class OpsProcurementControllerTest {

    @Mock ProcurementService procurementService;
    @Mock PurchaseSuggestionService purchaseSuggestionService;
    @Mock SupplierPayableService supplierPayableService;
    @Mock HttpServletRequest request;

    private OpsProcurementController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsProcurementController(
                procurementService, purchaseSuggestionService, supplierPayableService);
    }

    @Test
    void classKeepsSameAdminBasePath() {
        RequestMapping mapping = OpsProcurementController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertEquals("/api/v2/ops/admin", mapping.value()[0]);
        assertNotNull(OpsProcurementController.class.getAnnotation(RestController.class));
    }

    @Test
    void suppliers_keepsPermissionAndDelegates() throws Exception {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(11L);
        Method method = OpsProcurementController.class.getMethod(
                "suppliers", HttpServletRequest.class, String.class, int.class, int.class);
        assertEquals("ops:procurement:list", method.getAnnotation(RequiresPermissions.class).value()[0]);
        assertEquals("/suppliers", method.getAnnotation(GetMapping.class).value()[0]);

        when(procurementService.listSuppliersPage(11L, null, 0, 20))
                .thenReturn(new PageResult<>(List.of(), 0, 20, 0));
        ApiResponse<?> response = controller.suppliers(request, null, 0, 20);
        assertEquals(0, response.code());
        verify(procurementService).listSuppliersPage(11L, null, 0, 20);
    }

    @Test
    void commercialController_noLongerOwnsSuppliers() {
        for (Method method : OpsCommercialController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get == null) {
                continue;
            }
            for (String path : get.value()) {
                assertTrue(!"/suppliers".equals(path), "suppliers should live on OpsProcurementController");
                assertTrue(!path.startsWith("/purchase-"),
                        () -> "purchase path still on OpsCommercialController: " + path);
                assertTrue(!path.startsWith("/procurement/"),
                        () -> "procurement path still on OpsCommercialController: " + path);
            }
        }
    }
}
