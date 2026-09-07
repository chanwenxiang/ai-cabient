package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.OrgNodeDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.SiteContractDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.OrgService;
import com.aicabinet.trade.service.SiteContractService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * G6 / 路由契约：org 域从 OpsCommercial 抽出后，URL 与权限码不变，并委托原 Service。
 */
@ExtendWith(MockitoExtension.class)
class OpsOrgControllerTest {

    @Mock OrgService orgService;
    @Mock SiteContractService siteContractService;
    @Mock HttpServletRequest request;

    private OpsOrgController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsOrgController(orgService, siteContractService);
    }

    private void stubOperator() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(42L);
    }

    @Test
    void classKeepsSameAdminBasePath() {
        RequestMapping mapping = OpsOrgController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertEquals("/api/v2/ops/admin", mapping.value()[0]);
        assertNotNull(OpsOrgController.class.getAnnotation(RestController.class));
    }

    @Test
    void orgTree_keepsPermissionAndPath_andDelegates() throws Exception {
        stubOperator();
        Method method = OpsOrgController.class.getMethod("orgTree", HttpServletRequest.class);
        assertEquals("ops:org:list", method.getAnnotation(RequiresPermissions.class).value()[0]);
        assertEquals("/org/tree", method.getAnnotation(GetMapping.class).value()[0]);

        when(orgService.tree(42L)).thenReturn(List.of());
        ApiResponse<List<OrgNodeDto>> response = controller.orgTree(request);
        assertEquals(0, response.code());
        verify(orgService).tree(42L);
    }

    @Test
    void siteContracts_keepsPermissionAndPath_andDelegates() throws Exception {
        stubOperator();
        Method method = OpsOrgController.class.getMethod(
                "siteContracts", HttpServletRequest.class, int.class, int.class);
        assertEquals("ops:org:list", method.getAnnotation(RequiresPermissions.class).value()[0]);
        assertEquals("/site-contracts", method.getAnnotation(GetMapping.class).value()[0]);

        when(siteContractService.listPage(42L, 0, 20)).thenReturn(new PageResult<>(List.of(), 0, 20, 0));
        ApiResponse<PageResult<SiteContractDto>> response = controller.siteContracts(request, 0, 20);
        assertEquals(0, response.code());
        verify(siteContractService).listPage(42L, 0, 20);
    }

    @Test
    void mutatingEndpoints_keepEditPermission() throws Exception {
        Method upsert = OpsOrgController.class.getMethod(
                "upsertSiteContract", HttpServletRequest.class, String.class,
                Class.forName("com.aicabinet.common.dto.UpsertSiteContractRequest"));
        assertEquals("ops:org:edit", upsert.getAnnotation(RequiresPermissions.class).value()[0]);
        assertTrue(upsert.isAnnotationPresent(PutMapping.class));

        Method delete = OpsOrgController.class.getMethod(
                "deleteOrgNode", HttpServletRequest.class, Long.class);
        assertEquals("ops:org:edit", delete.getAnnotation(RequiresPermissions.class).value()[0]);
        assertTrue(delete.isAnnotationPresent(DeleteMapping.class));
    }

    @Test
    void commercialController_noLongerOwnsOrgTree() throws Exception {
        for (Method method : OpsCommercialController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                for (String path : get.value()) {
                    assertTrue(!"/org/tree".equals(path), "org/tree should live on OpsOrgController");
                    assertTrue(!"/site-contracts".equals(path), "site-contracts should live on OpsOrgController");
                }
            }
        }
    }
}
