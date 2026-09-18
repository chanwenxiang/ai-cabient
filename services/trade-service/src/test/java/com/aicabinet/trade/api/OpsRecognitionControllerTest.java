package com.aicabinet.trade.api;

import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.service.RecognitionTestService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H45：recognition-upload 的 settle=true 直通真实结算链路，仅 mock 开关 / dev|uat 环境放行。
 */
@ExtendWith(MockitoExtension.class)
class OpsRecognitionControllerTest {

    private static final Long OPERATOR_ID = 100_000_001L;

    @Mock private RecognitionTestService recognitionTestService;
    @Mock private Environment environment;
    @Mock private HttpServletRequest request;

    private final MockMultipartFile image =
            new MockMultipartFile("image", "a.png", "image/png", new byte[] {1, 2, 3});

    private OpsRecognitionController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsRecognitionController(recognitionTestService,
                new SecurityProperties(false), environment);
    }

    @Test
    void upload_settleRejectedInProductionProfile() throws java.io.IOException {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(OPERATOR_ID);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.upload(request, "D-1", image, null, "FULL", true));

        assertEquals(403, ex.getStatusCode().value());
        verify(recognitionTestService, never())
                .runWithUpload(anyLong(), any(), any(), any(), anyBoolean());
    }

    @Test
    void upload_settleAllowedWithMockEnabled() throws java.io.IOException {
        SecurityProperties mockEnabled = new SecurityProperties(true);
        controller = new OpsRecognitionController(recognitionTestService, mockEnabled, environment);
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(OPERATOR_ID);
        when(recognitionTestService.runWithUpload(eq(OPERATOR_ID), any(), any(), any(), eq(true)))
                .thenReturn(null);

        assertNotNull(controller.upload(request, "D-1", image, null, "FULL", true));

        verify(recognitionTestService).runWithUpload(eq(OPERATOR_ID), any(), any(), any(), eq(true));
    }

    @Test
    void upload_settleAllowedInDevProfile() throws java.io.IOException {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(OPERATOR_ID);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        when(recognitionTestService.runWithUpload(eq(OPERATOR_ID), any(), any(), any(), eq(true)))
                .thenReturn(null);

        assertNotNull(controller.upload(request, "D-1", image, null, "FULL", true));
    }

    @Test
    void upload_noSettle_unguardedStillWorks() throws java.io.IOException {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(OPERATOR_ID);
        when(recognitionTestService.runWithUpload(eq(OPERATOR_ID), any(), any(), any(), eq(false)))
                .thenReturn(null);

        assertNotNull(controller.upload(request, "D-1", image, null, "FULL", false));
        verify(environment, never()).getActiveProfiles();
    }
}
