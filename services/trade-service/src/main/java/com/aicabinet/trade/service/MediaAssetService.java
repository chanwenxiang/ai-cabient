package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MediaAssetDto;
import com.aicabinet.common.dto.UpsertMediaAssetRequest;
import com.aicabinet.trade.domain.MediaAsset;
import com.aicabinet.trade.mapper.AdCampaignItemMapper;
import com.aicabinet.trade.mapper.MediaAssetMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 广告/多媒体素材库：上传到 MinIO，维护标题/时长/状态元数据。
 */
@Service
public class MediaAssetService {
    private static final String VIDEO = "VIDEO";


    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final MediaAssetMapper assetRepository;
    private final AdCampaignItemMapper campaignItemMapper;
    private final MinioVideoService minioVideoService;
    private final DistributedLockService distributedLockService;

    public MediaAssetService(MediaAssetMapper assetRepository,
                             AdCampaignItemMapper campaignItemMapper,
                             MinioVideoService minioVideoService,
                             DistributedLockService distributedLockService) {
        this.assetRepository = assetRepository;
        this.campaignItemMapper = campaignItemMapper;
        this.minioVideoService = minioVideoService;
        this.distributedLockService = distributedLockService;
    }

    @Transactional(readOnly = true)
    public List<MediaAssetDto> list() {
        return assetRepository.findAllOrderByCreatedDesc().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public MediaAssetDto upload(Long operatorId, MultipartFile file,
                                String title, int durationSeconds, String assetType) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件不能超过 50MB");
        }
        String type = normalizeType(assetType, file.getContentType());
        String ext = extensionOf(file.getOriginalFilename());
        String objectKey = "ad/" + LocalDate.now(ZoneId.of("Asia/Shanghai"))
                + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String storageUri;
        try {
            storageUri = minioVideoService.putObject(objectKey, file.getBytes(), file.getContentType())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE, "文件上传失败"));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件上传失败: " + e.getMessage());
        }
        MediaAsset asset = new MediaAsset();
        asset.setTitle(title == null || title.isBlank() ? file.getOriginalFilename() : title.trim());
        asset.setAssetType(type);
        asset.setStorageUri(storageUri);
        asset.setDurationSeconds(resolveDurationSeconds(durationSeconds, type));
        asset.setStatus("ACTIVE");
        asset.setUploadedBy(operatorId);
        assetRepository.insert(asset);
        return toDto(asset);
    }

    @Transactional
    public MediaAssetDto update(Long assetId, UpsertMediaAssetRequest request) {
        return runWithMediaAssetLock(assetId, () -> doUpdate(assetId, request));
    }

    private MediaAssetDto doUpdate(Long assetId, UpsertMediaAssetRequest request) {
        MediaAsset asset = requireAssetForUpdate(assetId);
        asset.setTitle(request.title().trim());
        asset.setDurationSeconds(Math.max(0, request.durationSeconds()));
        if (request.status() != null && !request.status().isBlank()) {
            asset.setStatus(request.status().trim().toUpperCase());
        }
        assetRepository.updateById(asset);
        return toDto(asset);
    }

    @Transactional
    public void delete(Long assetId) {
        runWithMediaAssetLock(assetId, () -> {
            doDelete(assetId);
            return null;
        });
    }

    private void doDelete(Long assetId) {
        MediaAsset asset = requireAssetForUpdate(assetId);
        long used = campaignItemMapper.countByAssetId(assetId);
        if (used > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "素材仍被 " + used + " 个投放计划引用，请先从计划中移除");
        }
        assetRepository.deleteById(assetId);
        // MinIO 对象尽力清理，失败不影响元数据删除结果
        try {
            minioVideoService.removeObject(asset.getStorageUri());
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private MediaAsset requireAsset(Long assetId) {
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "素材不存在"));
    }

    private MediaAsset requireAssetForUpdate(Long assetId) {
        return assetRepository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "素材不存在"));
    }

    static String mediaAssetLockKey(Long assetId) {
        return "media:asset:" + assetId;
    }

    private <T> T runWithMediaAssetLock(Long assetId, java.util.function.Supplier<T> action) {
        String key = mediaAssetLockKey(assetId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "素材处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    /** 同源流式预览（浏览器不直连 MinIO public endpoint）。 */
    @Transactional(readOnly = true)
    public void streamPreview(Long assetId, HttpServletRequest request, HttpServletResponse response) {
        MediaAsset asset = requireAsset(assetId);
        minioVideoService.streamTo(asset.getStorageUri(), request, response);
    }

    private MediaAssetDto toDto(MediaAsset asset) {
        // 同源代理，避免预签名 localhost:19000 在浏览器侧不可达（OBS-025）
        String preview = "/api/v2/media/ad-assets/" + asset.getAssetId();
        return new MediaAssetDto(
                asset.getAssetId(), asset.getTitle(), asset.getAssetType(),
                asset.getStorageUri(), preview, asset.getDurationSeconds(),
                asset.getStatus(), asset.getCreatedAt());
    }

    private static String normalizeType(String requested, String contentType) {
        if (requested != null && !requested.isBlank()) {
            String t = requested.trim().toUpperCase();
            if (t.equals("IMAGE") || t.equals(VIDEO) || t.equals("H5")) {
                return t;
            }
        }
        if (contentType != null) {
            if (contentType.startsWith("image/")) {
                return "IMAGE";
            }
            if (contentType.startsWith("video/")) {
                return VIDEO;
            }
        }
        return "H5";
    }

    private static String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).replaceAll("[^a-zA-Z0-9]", "bin");
    }

    private static int resolveDurationSeconds(int durationSeconds, String type) {
        if (durationSeconds > 0) {
            return durationSeconds;
        }
        return VIDEO.equals(type) ? 0 : 10;
    }
}
