package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.storage.MinioVideoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoArchiveServiceTest {

    @Mock MinioVideoService minioVideoService;

    private VideoArchiveService service;

    @BeforeEach
    void setUp() {
        service = new VideoArchiveService(minioVideoService, new ObjectMapper());
    }

    @Test
    void cameraFromObjectKey_parsesSuffix() {
        assertEquals("side", VideoArchiveService.cameraFromObjectKey(
                "videos/2026/07/13/CAB-001/user-1/sess-side.jpg"));
        assertEquals("top", VideoArchiveService.cameraFromObjectKey(
                "sim/2026/07/13/dev/user-0/session-top.mp4"));
    }

    @Test
    void archiveAfterSettlement_copiesPerSku() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-ARCHIVE-001");
        session.setUserId(10086L);
        session.setCloseTime(Instant.parse("2026-07-13T04:00:00Z"));
        session.setVideoUri("minio://cabinet-videos/videos/2026/07/13/CAB-001/user-10086/S-ARCHIVE-001-top.mp4");

        CabinetOrderLine line = new CabinetOrderLine();
        line.setSkuId("SKU-WATER-001");

        when(minioVideoService.parseStorageUri(session.getVideoUri()))
                .thenReturn(new MinioVideoService.ParsedUri("cabinet-videos",
                        "videos/2026/07/13/CAB-001/user-10086/S-ARCHIVE-001-top.mp4"));
        when(minioVideoService.copyObject(anyString(), anyString())).thenReturn(true);

        service.archiveAfterSettlement(session, List.of(line));

        verify(minioVideoService).copyObject(
                eq(session.getVideoUri()),
                eq("archive/2026/07/13/SKU-WATER-001/user-10086/S-ARCHIVE-001-top.mp4"));
    }
}
