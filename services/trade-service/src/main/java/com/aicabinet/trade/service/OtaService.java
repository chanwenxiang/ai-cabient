package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OtaCheckResponse;
import com.aicabinet.common.dto.OtaReleaseDto;
import com.aicabinet.common.dto.OtaUpgradeProgressDto;
import com.aicabinet.trade.domain.OtaDeviceReport;
import com.aicabinet.trade.domain.OtaRelease;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OtaDeviceReportMapper;
import com.aicabinet.trade.mapper.OtaReleaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class OtaService {
    private static final Logger log = LoggerFactory.getLogger(OtaService.class);
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 升级状态取值（O2）。收纳在服务端是为了让「不认识的状态」显式 400，而不是静默落库。 */
    static final String PROGRESS_IDLE = "IDLE";
    static final String PROGRESS_DOWNLOADING = "DOWNLOADING";
    static final String PROGRESS_INSTALLING = "INSTALLING";
    static final String PROGRESS_SUCCESS = "SUCCESS";
    static final String PROGRESS_FAILED = "FAILED";
    private static final Set<String> ALLOWED_PROGRESS_STATUS =
            Set.of(PROGRESS_IDLE, PROGRESS_DOWNLOADING, PROGRESS_INSTALLING, PROGRESS_SUCCESS, PROGRESS_FAILED);

    private final OtaReleaseMapper releaseRepository;
    private final DeviceInfoMapper deviceRepository;
    private final OtaDeviceReportMapper reportRepository;
    private final OtaCdnService otaCdnService;
    private final ObjectMapper objectMapper;
    private final DistributedLockService distributedLockService;
    private final SystemConfigService systemConfigService;

    public OtaService(OtaReleaseMapper releaseRepository,
                      DeviceInfoMapper deviceRepository,
                      OtaDeviceReportMapper reportRepository,
                      OtaCdnService otaCdnService,
                      ObjectMapper objectMapper,
                      DistributedLockService distributedLockService,
                      SystemConfigService systemConfigService) {
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.reportRepository = reportRepository;
        this.otaCdnService = otaCdnService;
        this.objectMapper = objectMapper;
        this.distributedLockService = distributedLockService;
        this.systemConfigService = systemConfigService;
    }

    @Transactional(readOnly = true)
    public List<OtaReleaseDto> listReleases(Long operatorId) {
        return releaseRepository.findByStatusOrderByPublishedAtDesc(STATUS_PUBLISHED).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public OtaReleaseDto publishRelease(Long operatorId, OtaReleaseDto body) {
        if (body == null || body.appVersion() == null || body.appVersion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "版本号不能为空");
        }
        if (body.downloadUrl() == null || body.downloadUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "下载地址不能为空");
        }
        String checksum = body.checksumSha256() == null ? "" : body.checksumSha256().trim().toLowerCase();
        if (!checksum.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "checksumSha256 必填且须为 64 位十六进制（edge 端强制校验）");
        }
        OtaRelease release = new OtaRelease();
        release.setAppVersion(body.appVersion());
        release.setChannel(body.channel() != null ? body.channel() : "stable");
        release.setDownloadUrl(body.downloadUrl());
        release.setObjectStorageUri(body.objectStorageUri());
        release.setChecksumSha256(checksum);
        release.setReleaseNotes(body.releaseNotes());
        release.setMandatory(body.mandatory());
        release.setMinVersion(body.minVersion());
        release.setGrayPercent(body.grayPercent() > 0 ? body.grayPercent() : 100);
        release.setPresignTtlSeconds(body.presignTtlSeconds() > 0 ? body.presignTtlSeconds() : 3600);
        if (body.deviceAllowlist() != null && !body.deviceAllowlist().isEmpty()) {
            try {
                release.setDeviceAllowlist(objectMapper.writeValueAsString(body.deviceAllowlist()));
            } catch (Exception e) {
                log.warn("OTA allowlist serialize failed", e);
            }
        }
        release.setStatus(STATUS_PUBLISHED);
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
        if (!STATUS_PUBLISHED.equalsIgnoreCase(release.getStatus())) {
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
                .findFirstByChannelAndStatusOrderByPublishedAtDesc(ch, STATUS_PUBLISHED)
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

    /**
     * 设备侧上报 OTA 升级进度（O2）。
     *
     * <p>开关 {@code ota.progress.enabled} 关闭时**不写库**并返回 {@link Optional#empty()}
     * —— 即行为与接入前完全一致（该表保持零写入）。这是刻意的 fail-closed：
     * 新表在开关打开前不该被任何流量写入。
     *
     * <p>语义：
     * <ul>
     *   <li>首次上报 INSERT，其后 UPDATE（主键 device_id，天然幂等，重复上报只是刷新）；</li>
     *   <li>{@code progressPercent} 收敛到 0-100（越界不报错，按边界存，避免脏数据入库）；</li>
     *   <li>非 FAILED 一律清空 {@code errorMessage} —— 上一次的失败原因残留会变成假线索；</li>
     *   <li>SUCCESS 时同步改写 {@code device_info.app_version}，与既有
     *       {@link #reportVersion} 落**同一个字段**，不制造第二套「当前版本」。</li>
     * </ul>
     */
    @Transactional
    public Optional<OtaUpgradeProgressDto> reportProgress(String deviceId,
                                                          String targetVersion,
                                                          String status,
                                                          Integer progressPercent,
                                                          String errorMessage) {
        if (!progressEnabled()) {
            return Optional.empty();
        }
        String dev = requireDeviceId(deviceId);
        String st = normalizeProgressStatus(status);
        String target = trimToNull(targetVersion);
        String error = PROGRESS_FAILED.equals(st) ? trimToNull(errorMessage) : null;
        int pct = clampProgressPercent(progressPercent);
        return Optional.of(
                runWithDeviceVersionLock(dev, () -> doReportProgress(dev, target, st, pct, error)));
    }

    private OtaUpgradeProgressDto doReportProgress(String deviceId, String targetVersion,
                                                   String status, int progressPercent, String errorMessage) {
        Instant now = Instant.now();
        OtaDeviceReport existing = reportRepository.selectById(deviceId);
        if (existing == null) {
            OtaDeviceReport row = new OtaDeviceReport();
            row.setDeviceId(deviceId);
            row.setReportedAt(now);
            row.setUpdatedAt(now);
            row.setUpgradeStatus(status);
            row.setProgressPercent(progressPercent);
            row.setTargetVersion(targetVersion);
            row.setErrorMessage(errorMessage);
            reportRepository.insert(row);
            existing = row;
        } else {
            // 进度列里有「必须能写 null」的字段 ⇒ 走显式 set（updateById 会跳过 null，清不掉列）
            reportRepository.updateProgress(deviceId, targetVersion, status, progressPercent, errorMessage, now);
            existing.setTargetVersion(targetVersion);
            existing.setUpgradeStatus(status);
            existing.setProgressPercent(progressPercent);
            existing.setErrorMessage(errorMessage);
            existing.setUpdatedAt(now);
        }
        if (PROGRESS_SUCCESS.equals(status) && targetVersion != null) {
            deviceRepository.findByIdForUpdate(deviceId).ifPresent(d -> {
                d.setAppVersion(targetVersion);
                deviceRepository.save(d);
            });
        }
        log.info("ota progress reported device={} status={} target={} pct={}",
                deviceId, status, targetVersion, progressPercent);
        return toProgressDto(existing);
    }

    /**
     * 运营台查看设备升级进度（O2）。
     *
     * <p>读侧**不**受开关控制：关掉开关是「不再写入新进度」，不该把历史进度也藏起来
     * （排障时恰恰要看开关关掉之前发生了什么）。
     */
    @Transactional(readOnly = true)
    public List<OtaUpgradeProgressDto> listProgress(String status, int limit) {
        List<OtaDeviceReport> rows = (status == null || status.isBlank())
                ? reportRepository.findAllOrderByUpdatedAtDesc(limit)
                : reportRepository.findByUpgradeStatusOrderByUpdatedAtDesc(normalizeProgressStatus(status));
        return rows.stream().map(OtaService::toProgressDto).toList();
    }

    private boolean progressEnabled() {
        return systemConfigService.getBoolean(SystemConfigService.OTA_PROGRESS_ENABLED, false);
    }

    private static String requireDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 不能为空");
        }
        return deviceId.trim();
    }

    /** 空值视为 IDLE（设备只表示「没在升级」）；不认识的状态显式 400，不静默落库。 */
    static String normalizeProgressStatus(String status) {
        String s = trimToNull(status);
        if (s == null) {
            return PROGRESS_IDLE;
        }
        String upper = s.toUpperCase(java.util.Locale.ROOT);
        if (!ALLOWED_PROGRESS_STATUS.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的升级状态：" + status);
        }
        return upper;
    }

    /** 收敛到 0-100；null 视作 0。 */
    static int clampProgressPercent(Integer progressPercent) {
        if (progressPercent == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, progressPercent));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static OtaUpgradeProgressDto toProgressDto(OtaDeviceReport r) {
        return new OtaUpgradeProgressDto(
                r.getDeviceId(),
                r.getAppVersion(),
                r.getTargetVersion(),
                r.getUpgradeStatus(),
                r.getProgressPercent(),
                r.getErrorMessage(),
                r.getReportedAt(),
                r.getUpdatedAt());
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
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
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
                // Corrupt allowlist JSON in DB: fall back to empty allowlist.
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
