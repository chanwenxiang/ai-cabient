package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OtaRelease;
import com.aicabinet.trade.storage.MinioVideoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OtaCdnService {

    private final MinioVideoService minioVideoService;
    private final ObjectMapper objectMapper;

    public OtaCdnService(MinioVideoService minioVideoService, ObjectMapper objectMapper) {
        this.minioVideoService = minioVideoService;
        this.objectMapper = objectMapper;
    }

    public String resolveDownloadUrl(OtaRelease release) {
        if (release.getObjectStorageUri() != null && !release.getObjectStorageUri().isBlank()) {
            return minioVideoService.presignDownloadUrl(
                    release.getObjectStorageUri(), release.getPresignTtlSeconds()
            ).orElse(release.getDownloadUrl());
        }
        return release.getDownloadUrl();
    }

    public boolean isInGrayRollout(String deviceId, OtaRelease release) {
        if (release.getGrayPercent() >= 100) {
            return true;
        }
        if (isInAllowlist(deviceId, release.getDeviceAllowlist())) {
            return true;
        }
        int bucket = Math.floorMod(deviceId.hashCode(), 100);
        return bucket < release.getGrayPercent();
    }

    private boolean isInAllowlist(String deviceId, String allowlistJson) {
        if (allowlistJson == null || allowlistJson.isBlank()) {
            return false;
        }
        try {
            List<String> list = objectMapper.readValue(allowlistJson, new TypeReference<>() {});
            return list.contains(deviceId);
        } catch (Exception e) {
            return false;
        }
    }
}
