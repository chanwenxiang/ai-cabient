package com.aicabinet.trade.service;

import com.aicabinet.trade.config.MinioProperties;
import com.aicabinet.trade.domain.FileAttachment;
import com.aicabinet.trade.mapper.FileAttachmentMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;

/**
 * 2026-11-09：附件对象缺失时必须是 404，不能退化成 500。
 *
 * <p>回归点（真库实测）：MinIO 返回 NoSuchKey 时，{@code stream()} 已经把
 * {@code Content-Type: image/*} 写进响应，Spring 的响应状态异常处理器再想写 JSON 错误体
 * 就报 {@code No converter for [ApiResponse] with preset Content-Type 'image/jpeg'}，
 * 本该 404 的请求最终 500。修法是把 Content-Type / 长度留到对象确认存在之后再设。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileAttachmentStreamMissingObjectTest {

    @Mock private FileAttachmentMapper fileAttachmentMapper;
    @Mock private MinioVideoService minioVideoService;
    @Mock private MinioProperties minioProperties;
    @Mock private DistributedLockService distributedLockService;

    private FileAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new FileAttachmentService(fileAttachmentMapper, minioVideoService,
                minioProperties, distributedLockService, "./data/attachments");
    }

    private FileAttachment row(String storagePath, String contentType, Long fileSize) {
        FileAttachment row = new FileAttachment();
        row.setStoragePath(storagePath);
        row.setContentType(contentType);
        row.setFileSize(fileSize);
        return row;
    }

    @Test
    void minioObjectMissing_leavesResponseUntouchedFor404Body() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "视频文件不存在或无法读取"))
                .when(minioVideoService).streamTo(anyString(), isNull(), any());

        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.stream(row("minio://cabinet-videos/sku-images/a.webp", "image/webp", 100L), response));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertNull(response.getContentType(),
                "对象缺失时不得预设 image/*，否则 404 会被顶成 500");
        assertEquals(0, response.getContentLength());
    }

    @Test
    void localFileMissing_is404WithoutPreSetHeaders() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.stream(row("file:///nonexistent/attachment-9.jpg", "image/jpeg", 10L), response));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertNull(response.getContentType());
    }

    @Test
    void localFilePresent_streamsWithDeclaredType(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.png");
        byte[] payload = "png-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.stream(row(file.toUri().toString(), "image/png", (long) payload.length), response);

        assertEquals("image/png", response.getContentType());
        assertArrayEquals(payload, response.getContentAsByteArray());
    }
}
