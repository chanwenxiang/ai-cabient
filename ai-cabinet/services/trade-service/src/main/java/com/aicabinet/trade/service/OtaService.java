package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OtaCheckResponse;
import com.aicabinet.common.dto.OtaReleaseDto;
import com.aicabinet.trade.domain.OtaRelease;
import com.aicabinet.trade.repository.DeviceInfoRepository;
import com.aicabinet.trade.repository.OtaReleaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class OtaService {

    private final OtaReleaseRepository releaseRepository;
    private final DeviceInfoRepository deviceRepository;
    private final OtaCdnService otaCdnService;
    private final ObjectMapper objectMapper;

    public OtaService(OtaReleaseRepository releaseRepository,
                      DeviceInfoRepository deviceRepository,
                      OtaCdnService otaCdnService,
                      ObjectMapper objectMapper) {
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.otaCdnService = otaCdnService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<OtaReleaseDto> listReleases(Long operatorId) {
        return releaseRepository.findByStatusOrderByPublishedAtDesc("PUBLISHED").stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public OtaReleaseDto publishRelease(Long operatorId, OtaReleaseDto body) {
        OtaRelease release = new OtaRelease();
        release.setAppVersion(body.appVersion());
        release.setChannel(body.channel() != null ? body.channel() : "stable");
        release.setDownloadUrl(body.downloadUrl());
        release.setObjectStorageUri(body.objectStorageUri());
        release.setChecksumSha256(body.checksumSha256());
        release.setReleaseNotes(body.releaseNotes());
        release.setMandatory(body.mandatory());
        release.setMinVersion(body.minVersion());
        release.setGrayPercent(body.grayPercent() > 0 ? body.grayPercent() : 100);
        release.setPresignTtlSeconds(body.presignTtlSeconds() > 0 ? body.presignTtlSeconds() : 3600);
        if (body.deviceAllowlist() != null && !body.deviceAllowlist().isEmpty()) {
            try {
                release.setDeviceAllowlist(objectMapper.writeValueAsString(body.deviceAllowlist()));
            } catch (Exception ignored) {
            }
        }
        release.setStatus("PUBLISHED");
        release.setPublishedAt(Instant.now());
        return toDto(releaseRepository.save(release));
    }

    @Transactional(readOnly = true)
    public OtaCheckResponse checkUpdate(String deviceId, String currentVersion, String channel) {
        String ch = channel != null ? channel : "stable";
        OtaRelease latest = releaseRepository
                .findFirstByChannelAndStatusOrderByPublishedAtDesc(ch, "PUBLISHED")
                .orElse(null);
        if (latest == null || latest.getAppVersion().equals(currentVersion)) {
            return new OtaCheckResponse(false, null, null, null, false, null);
        }
        if (currentVersion != null && compareVersion(latest.getAppVersion(), currentVersion) <= 0) {
            return new OtaCheckResponse(false, null, null, null, false, null);
        }
        if (!otaCdnService.isInGrayRollout(deviceId, latest)) {
            return new OtaCheckResponse(false, null, null, null, false, null);
        }
        String signedUrl = otaCdnService.resolveDownloadUrl(latest);
        return new OtaCheckResponse(
                true,
                latest.getAppVersion(),
                signedUrl,
                latest.getChecksumSha256(),
                latest.isMandatory(),
                latest.getReleaseNotes()
        );
    }

    @Transactional
    public void reportVersion(String deviceId, String appVersion) {
        deviceRepository.findById(deviceId).ifPresent(d -> {
            d.setAppVersion(appVersion);
            deviceRepository.save(d);
        });
    }

    private int compareVersion(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private OtaReleaseDto toDto(OtaRelease r) {
        List<String> allowlist = List.of();
        if (r.getDeviceAllowlist() != null && !r.getDeviceAllowlist().isBlank()) {
            try {
                allowlist = objectMapper.readValue(r.getDeviceAllowlist(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception ignored) {
            }
        }
        return new OtaReleaseDto(
                r.getReleaseId(), r.getAppVersion(), r.getChannel(), r.getDownloadUrl(),
                r.getObjectStorageUri(), r.getChecksumSha256(), r.getReleaseNotes(), r.isMandatory(),
                r.getMinVersion(), r.getStatus(), r.getPublishedAt(),
                r.getGrayPercent(), allowlist, r.getPresignTtlSeconds()
        );
    }
}
