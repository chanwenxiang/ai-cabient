package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.OpsRoleDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.OpsRbacService;
import com.aicabinet.trade.service.OpsTwoFactorService;
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
 * G6 / 路由契约：RBAC 域从 OpsCommercial 抽出后，URL 与权限码不变，并委托 OpsRbacService。
 */
@ExtendWith(MockitoExtension.class)
class OpsRbacControllerTest {

    @Mock OpsRbacService rbacService;
    @Mock OpsTwoFactorService opsTwoFactorService;
    @Mock FileAttachmentService fileAttachmentService;
    @Mock HttpServletRequest request;

    private OpsRbacController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsRbacController(rbacService, opsTwoFactorService, fileAttachmentService);
    }

    @Test
    void classKeepsSameAdminBasePath() {
        RequestMapping mapping = OpsRbacController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertEquals("/api/v2/ops/admin", mapping.value()[0]);
        assertNotNull(OpsRbacController.class.getAnnotation(RestController.class));
    }

    @Test
    void roles_keepsOrPermissionAndPath_andDelegates() throws Exception {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(7L);
        Method method = OpsRbacController.class.getMethod("roles", HttpServletRequest.class);
        RequiresPermissions perm = method.getAnnotation(RequiresPermissions.class);
        assertEquals(RequiresPermissions.Logical.OR, perm.logical());
        assertTrue(List.of(perm.value()).contains("ops:rbac:role"));
        assertTrue(List.of(perm.value()).contains("ops:rbac:assign"));
        assertEquals("/rbac/roles", method.getAnnotation(GetMapping.class).value()[0]);

        when(rbacService.listRoles(7L)).thenReturn(List.of());
        ApiResponse<List<OpsRoleDto>> response = controller.roles(request);
        assertEquals(0, response.code());
        verify(rbacService).listRoles(7L);
    }

    @Test
    void myPermissions_keepsPath_andDelegates() throws Exception {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(7L);
        Method method = OpsRbacController.class.getMethod("myPermissions", HttpServletRequest.class);
        assertEquals("/rbac/me/permissions", method.getAnnotation(GetMapping.class).value()[0]);

        when(rbacService.myPermissions(7L)).thenReturn(java.util.Set.of("ops:admin"));
        assertEquals(0, controller.myPermissions(request).code());
        verify(rbacService).myPermissions(7L);
    }

    @Test
    void twoFactorStatus_delegatesToTwoFactorService() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(7L);
        when(opsTwoFactorService.status(7L)).thenReturn(null);
        assertEquals(0, controller.twoFactorStatus(request).code());
        verify(opsTwoFactorService).status(7L);
    }

    @Test
    void commercialController_noLongerOwnsRbacRoles() {
        for (Method method : OpsCommercialController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get == null) {
                continue;
            }
            for (String path : get.value()) {
                assertTrue(!path.startsWith("/rbac/"),
                        () -> "rbac path still on OpsCommercialController: " + path);
            }
        }
    }
}
