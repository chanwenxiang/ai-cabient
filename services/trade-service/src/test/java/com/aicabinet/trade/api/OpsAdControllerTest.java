package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AdCampaignService;
import com.aicabinet.trade.service.MediaAssetService;
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
 * G6 / 路由契约：广告域抽出后 URL/权限不变。
 */
@ExtendWith(MockitoExtension.class)
class OpsAdControllerTest {

    @Mock MediaAssetService mediaAssetService;
    @Mock AdCampaignService adCampaignService;

    private OpsAdController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsAdController(mediaAssetService, adCampaignService);
    }

    @Test
    void classKeepsSameAdminBasePath() {
        RequestMapping mapping = OpsAdController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertEquals("/api/v2/ops/admin", mapping.value()[0]);
        assertNotNull(OpsAdController.class.getAnnotation(RestController.class));
    }

    @Test
    void adAssets_keepsPermissionAndDelegates() throws Exception {
        Method method = OpsAdController.class.getMethod(
                "adAssets", HttpServletRequest.class, int.class, int.class);
        assertEquals("ops:ad:list", method.getAnnotation(RequiresPermissions.class).value()[0]);
        assertEquals("/ad/assets", method.getAnnotation(GetMapping.class).value()[0]);

        when(mediaAssetService.list(0, 20)).thenReturn(new PageResult<>(List.of(), 0, 20, 0));
        ApiResponse<?> response = controller.adAssets(null, 0, 20);
        assertEquals(0, response.code());
        verify(mediaAssetService).list(0, 20);
    }

    @Test
    void commercialController_noLongerOwnsAdAssets() {
        for (Method method : OpsCommercialController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get == null) {
                continue;
            }
            for (String path : get.value()) {
                assertTrue(!path.startsWith("/ad/"),
                        () -> "ad path still on OpsCommercialController: " + path);
            }
        }
    }
}
