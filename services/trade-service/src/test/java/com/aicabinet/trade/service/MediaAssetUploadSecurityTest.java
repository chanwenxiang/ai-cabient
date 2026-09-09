package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.MediaAsset;
import com.aicabinet.trade.mapper.AdCampaignItemMapper;
import com.aicabinet.trade.mapper.MediaAssetMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetUploadSecurityTest {

    @Mock private MediaAssetMapper assetRepository;
    @Mock private AdCampaignItemMapper campaignItemMapper;
    @Mock private MinioVideoService minioVideoService;
    @Mock private DistributedLockService distributedLockService;

    private MediaAssetService service;

    @BeforeEach
    void setUp() {
        service = new MediaAssetService(assetRepository, campaignItemMapper, minioVideoService, distributedLockService);
    }

    @Test
    void upload_rejectsHtmlMime() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.html", "text/html", "<script>alert(1)</script>".getBytes());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upload(1L, file, "坏文件", 0, "H5"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(minioVideoService, never()).putObject(anyString(), any(byte[].class), anyString());
    }

    @Test
    void upload_acceptsJpeg() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "ad.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, 0x00});
        when(minioVideoService.putObject(anyString(), any(byte[].class), eq("image/jpeg")))
                .thenReturn(Optional.of("minio://bucket/ad/x.jpg"));

        var dto = service.upload(1L, file, "海报", 5, "IMAGE");

        assertEquals("IMAGE", dto.assetType());
        verify(assetRepository).insert(any(MediaAsset.class));
    }

    @Test
    void streamPreview_rejectsInactive() {
        MediaAsset asset = new MediaAsset();
        asset.setAssetId(9L);
        asset.setStatus("INACTIVE");
        asset.setStorageUri("minio://bucket/ad/x.jpg");
        when(assetRepository.findById(9L)).thenReturn(Optional.of(asset));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.streamPreview(9L, new MockHttpServletRequest(), new MockHttpServletResponse()));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(minioVideoService, never()).streamTo(anyString(), any(), any());
    }
}
