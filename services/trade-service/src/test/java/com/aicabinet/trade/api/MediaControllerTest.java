package com.aicabinet.trade.api;

import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.domain.FileAttachment;
import com.aicabinet.trade.service.FileAttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H43：/api/v2/media/ops-avatars/** 不再公开——运营头像要求运营会话，
 * 防止按自增 fileId 匿名枚举（未登录在拦截器层 401；消费者等非运营账号 403）。
 */
@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

    @Mock private FileAttachmentService fileAttachmentService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    private MediaController controller;

    @BeforeEach
    void setUp() {
        controller = new MediaController(fileAttachmentService, null);
    }

    @Test
    void opsAvatar_nonOperator_shouldBeForbidden() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(5L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.opsAvatar(request, 9L, response));

        assertEquals(403, ex.getStatusCode().value());
        verify(fileAttachmentService, never()).requireOpsAvatar(any());
    }

    @Test
    void opsAvatar_missingLogin_shouldBeForbidden() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.opsAvatar(request, 9L, response));

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void opsAvatar_operator_shouldStream() throws Exception {
        FileAttachment row = new FileAttachment();
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(100_000_001L);
        when(fileAttachmentService.requireOpsAvatar(9L)).thenReturn(row);
        doNothing().when(fileAttachmentService).stream(row, response);

        controller.opsAvatar(request, 9L, response);

        verify(fileAttachmentService).stream(row, response);
    }
}
