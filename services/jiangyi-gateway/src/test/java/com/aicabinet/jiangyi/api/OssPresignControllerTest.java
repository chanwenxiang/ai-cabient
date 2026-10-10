package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.oss.OssStsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CB-029 internal 面预签名端点：三段式返回必须可区分（签名成功 / 非本桶 / 真失败），
 * 因为调用方（trade）据此决定「给出播放地址 / 原样透传 / fail-closed 标记不可播放」。
 */
class OssPresignControllerTest {

    private OssStsService ossStsService;
    private OssPresignController controller;

    @BeforeEach
    void setUp() {
        ossStsService = Mockito.mock(OssStsService.class);
        controller = new OssPresignController(ossStsService);
    }

    @Test
    void ownKeyReturnsSignedUrl() {
        Mockito.when(ossStsService.resolveRef("jiangyi-video/a.mp4"))
                .thenReturn(new OssStsService.RefResolution(OssStsService.RefKind.OWN, "jiangyi-video/a.mp4"));
        Mockito.when(ossStsService.presignGet("jiangyi-video/a.mp4"))
                .thenReturn(new OssStsService.OssPresignUrl("https://signed", "2026-10-10T13:30:00Z"));

        OssPresignController.PresignResponse resp =
                controller.presignGet(new OssPresignController.PresignRequest("jiangyi-video/a.mp4"));

        assertTrue(resp.ok());
        assertFalse(resp.external());
        assertEquals("https://signed", resp.url());
        assertEquals("2026-10-10T13:30:00Z", resp.expiration());
    }

    @Test
    void externalRefIsNotAnError() {
        Mockito.when(ossStsService.resolveRef("https://cdn.example.com/a.mp4"))
                .thenReturn(new OssStsService.RefResolution(OssStsService.RefKind.EXTERNAL, null));

        OssPresignController.PresignResponse resp =
                controller.presignGet(new OssPresignController.PresignRequest("https://cdn.example.com/a.mp4"));

        assertFalse(resp.ok());
        assertTrue(resp.external());
        assertEquals("EXTERNAL", resp.reason());
        // 非本桶不签名（不调 presignGet）
        Mockito.verify(ossStsService, Mockito.never()).presignGet(Mockito.anyString());
    }

    @Test
    void unresolvableRefFailsClosed() {
        Mockito.when(ossStsService.resolveRef("garbage"))
                .thenReturn(new OssStsService.RefResolution(OssStsService.RefKind.INVALID, null));

        OssPresignController.PresignResponse resp =
                controller.presignGet(new OssPresignController.PresignRequest("garbage"));

        assertFalse(resp.ok());
        assertFalse(resp.external());
        assertEquals("UNRESOLVABLE", resp.reason());
        assertNull(resp.url());
    }

    @Test
    void nullBodyFailsClosed() {
        Mockito.when(ossStsService.resolveRef(null))
                .thenReturn(new OssStsService.RefResolution(OssStsService.RefKind.INVALID, null));

        OssPresignController.PresignResponse resp = controller.presignGet(null);

        assertFalse(resp.ok());
        assertFalse(resp.external());
        assertEquals("UNRESOLVABLE", resp.reason());
    }

    @Test
    void stsUnavailableReportsReason() {
        Mockito.when(ossStsService.resolveRef("jiangyi-video/a.mp4"))
                .thenReturn(new OssStsService.RefResolution(OssStsService.RefKind.OWN, "jiangyi-video/a.mp4"));
        Mockito.when(ossStsService.presignGet("jiangyi-video/a.mp4")).thenReturn(null);

        OssPresignController.PresignResponse resp =
                controller.presignGet(new OssPresignController.PresignRequest("jiangyi-video/a.mp4"));

        assertFalse(resp.ok());
        assertFalse(resp.external());
        assertEquals("UNAVAILABLE", resp.reason());
    }
}
