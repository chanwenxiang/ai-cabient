package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.storage.MinioVideoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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
    void archiveSession_copiesOncePerVideo() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-ARCHIVE-001");
        session.setUserId(10086L);
        session.setCloseTime(Instant.parse("2026-07-13T04:00:00Z"));
        session.setVideoUri("minio://cabinet-videos/videos/2026/07/13/CAB-001/user-10086/S-ARCHIVE-001-top.mp4");

        when(minioVideoService.parseStorageUri(session.getVideoUri()))
                .thenReturn(new MinioVideoService.ParsedUri("cabinet-videos",
                        "videos/2026/07/13/CAB-001/user-10086/S-ARCHIVE-001-top.mp4"));
        when(minioVideoService.copyObject(anyString(), anyString())).thenReturn(true);

        service.archiveSession(session);

        verify(minioVideoService).copyObject(
                eq(session.getVideoUri()),
                eq("archive/2026/07/13/session-S-ARCHIVE-001/user-10086/S-ARCHIVE-001-top.mp4"));
    }

    @Test
    void archiveAfterSettlement_skipsWhenSamplingDisabled() {
        service.setArchiveSamplingRate(0);
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-NOARCH");
        session.setVideoUri("minio://cabinet-videos/videos/2026/07/13/CAB-001/user-1/S-NOARCH-top.mp4");

        service.archiveAfterSettlement(session);

        verify(minioVideoService, never()).copyObject(anyString(), anyString());
    }

    @Test
    void archiveAfterSettlement_copiesWhenSamplingEnabled() {
        service.setArchiveSamplingRate(1.0);
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-ARCH-ALL");
        session.setUserId(1L);
        session.setVideoUri("minio://cabinet-videos/videos/2026/07/13/CAB-001/user-1/S-ARCH-ALL-top.mp4");
        when(minioVideoService.parseStorageUri(session.getVideoUri()))
                .thenReturn(new MinioVideoService.ParsedUri("cabinet-videos",
                        "videos/2026/07/13/CAB-001/user-1/S-ARCH-ALL-top.mp4"));
        when(minioVideoService.copyObject(anyString(), anyString())).thenReturn(true);

        service.archiveAfterSettlement(session);

        verify(minioVideoService).copyObject(eq(session.getVideoUri()), anyString());
    }
}
