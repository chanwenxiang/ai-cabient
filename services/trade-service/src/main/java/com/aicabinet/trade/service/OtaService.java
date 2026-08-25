package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OtaCheckResponse;
import com.aicabinet.common.dto.OtaReleaseDto;
import com.aicabinet.trade.domain.OtaRelease;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OtaReleaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class OtaService {

    private final OtaReleaseMapper releaseRepository;
    private final DeviceInfoMapper deviceRepository;
    private final OtaCdnService otaCdnService;
    private final ObjectMapper objectMapper;
    private final DistributedLockService distributedLockService;

    public OtaService(OtaReleaseMapper releaseRepository,
                      DeviceInfoMapper deviceRepository,
                      OtaCdnService otaCdnService,
                      ObjectMapper objectMapper,
                      DistributedLockService distributedLockService) {
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.otaCdnService = otaCdnService;
        this.objectMapper = objectMapper;
        this.distributedLockService = distributedLockService;
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

    /** 下架（回滚）：停止向设备推送该版本，设备端 check 将回落到更早的已发布版本。 */
    @Transactional
    public OtaReleaseDto unpublishRelease(Long operatorId, Long releaseId) {
        return runWithReleaseLock(releaseId, () -> doUnpublishRelease(releaseId));
    }

    private OtaReleaseDto doUnpublishRelease(Long releaseId) {
        OtaRelease release = releaseRepository.findByIdForUpdate(releaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "发布版本不存在"));
        if (!"PUBLISHED".equalsIgnoreCase(release.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅已发布版本可下架");
        }
        release.setStatus("UNPUBLISHED");
        releaseRepository.save(release);
        return toDto(release);
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
        runWithDeviceVersionLock(deviceId, () -> {
            deviceRepository.findByIdForUpdate(deviceId).ifPresent(d -> {
                d.setAppVersion(appVersion);
                deviceRepository.save(d);
            });
            return null;
        });
    }

    static String otaReleaseLockKey(Long releaseId) {
        return "ota:release:" + releaseId;
    }

    static String otaDeviceVersionLockKey(String deviceId) {
        return "ota:device-version:" + deviceId.trim();
    }

    private <T> T runWithReleaseLock(Long releaseId, java.util.function.Supplier<T> action) {
        String key = otaReleaseLockKey(releaseId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "OTA 版本处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private <T> T runWithDeviceVersionLock(String deviceId, java.util.function.Supplier<T> action) {
        String key = otaDeviceVersionLockKey(deviceId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备版本上报处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
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
