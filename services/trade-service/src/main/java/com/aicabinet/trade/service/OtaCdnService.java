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
        // 定向白名单是「排他」而非「必含」：配置了非空白名单时只有名单内设备参与，
        // 否则 gray=100（缺省值）会绕过白名单推给全部设备
        if (hasAllowlist(release.getDeviceAllowlist())) {
            return isInAllowlist(deviceId, release.getDeviceAllowlist());
        }
        if (release.getGrayPercent() >= 100) {
            return true;
        }
        int bucket = Math.floorMod(deviceId.hashCode(), 100);
        return bucket < release.getGrayPercent();
    }

    private boolean hasAllowlist(String allowlistJson) {
        return allowlistJson != null && !allowlistJson.isBlank();
    }

    private boolean isInAllowlist(String deviceId, String allowlistJson) {
        if (!hasAllowlist(allowlistJson)) {
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
