package com.aicabinet.trade.service;

import com.aicabinet.common.dto.VideoClipDto;
import com.aicabinet.common.storage.ObjectStorageKeys;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.storage.MinioVideoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 结算完成后将购物录像按 SKU 复制到归档路径，便于运营按商品检索。
 */
@Service
public class VideoArchiveService {

    private static final Logger log = LoggerFactory.getLogger(VideoArchiveService.class);

    private final MinioVideoService minioVideoService;
    private final ObjectMapper objectMapper;

    public VideoArchiveService(MinioVideoService minioVideoService, ObjectMapper objectMapper) {
        this.minioVideoService = minioVideoService;
        this.objectMapper = objectMapper;
    }

    /** 最佳努力归档，失败不影响结算主流程。 */
    public void archiveAfterSettlement(ShoppingSession session, List<CabinetOrderLine> lines) {
        if (session == null || lines == null || lines.isEmpty()) {
            return;
        }
        List<String> videoUris = collectVideoUris(session);
        if (videoUris.isEmpty()) {
            return;
        }
        Instant archivedAt = session.getCloseTime() != null ? session.getCloseTime() : Instant.now();
        long userId = session.getUserId() != null ? session.getUserId() : 0L;
        int copied = 0;
        for (String videoUri : videoUris) {
            var parsed = minioVideoService.parseStorageUri(videoUri);
            if (parsed == null) {
                continue;
            }
            String camera = cameraFromObjectKey(parsed.objectKey());
            String extension = extensionFromObjectKey(parsed.objectKey());
            String archiveKey = ObjectStorageKeys.archiveVideoKey(
                    userId, session.getSessionId(), camera, extension, archivedAt);
            if (minioVideoService.copyObject(videoUri, archiveKey)) {
                copied++;
            }
        }
        if (copied > 0) {
            log.info("video archive session={} copies={}", session.getSessionId(), copied);
        }
    }

    private List<String> collectVideoUris(ShoppingSession session) {
        Set<String> uris = new LinkedHashSet<>();
        if (session.getVideoUri() != null && !session.getVideoUri().isBlank()) {
            uris.add(session.getVideoUri().trim());
        }
        String clipsJson = session.getVideoClips();
        if (clipsJson != null && !clipsJson.isBlank()) {
            try {
                List<VideoClipDto> clips = objectMapper.readValue(clipsJson, new TypeReference<>() {});
                for (VideoClipDto clip : clips) {
                    if (clip.videoUri() != null && !clip.videoUri().isBlank()) {
                        uris.add(clip.videoUri().trim());
                    }
                }
            } catch (Exception e) {
                log.warn("parse video clips failed session={}", session.getSessionId(), e);
            }
        }
        return new ArrayList<>(uris);
    }

    static String cameraFromObjectKey(String objectKey) {
        String file = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        int dash = base.lastIndexOf('-');
        if (dash < 0 || dash == base.length() - 1) {
            return "top";
        }
        return base.substring(dash + 1).toLowerCase(Locale.ROOT);
    }

    static String extensionFromObjectKey(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        if (dot < 0 || dot == objectKey.length() - 1) {
            return ".mp4";
        }
        return objectKey.substring(dot).toLowerCase(Locale.ROOT);
    }
}
