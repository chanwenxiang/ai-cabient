package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MediaAssetDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.UpsertMediaAssetRequest;
import com.aicabinet.trade.domain.MediaAsset;
import com.aicabinet.trade.mapper.AdCampaignItemMapper;
import com.aicabinet.trade.mapper.MediaAssetMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 广告/多媒体素材库：上传到 MinIO，维护标题/时长/状态元数据。
 */
@Service
public class MediaAssetService {
    private static final Logger log = LoggerFactory.getLogger(MediaAssetService.class);
    private static final String VIDEO = "VIDEO";
    private static final String IMAGE = "IMAGE";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final long MAX_BYTES = 50L * 1024 * 1024;
    /** 仅允许图片/视频；禁止 HTML 等可执行内容经公开预览路径投毒。 */
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif");
    private static final Set<String> ALLOWED_VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm");
    private static final Map<String, String> EXT_BY_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/jpg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif",
            "video/mp4", "mp4",
            "video/webm", "webm");

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
    public PageResult<MediaAssetDto> list(int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 500);
        Page<MediaAsset> result = assetRepository.searchPage(p, s);
        List<MediaAssetDto> items = result.getRecords().stream().map(this::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    /** 兼容旧调用：全量列表（内部/下拉用）。 */
    @Transactional(readOnly = true)
    public List<MediaAssetDto> list() {
        return list(0, 500).items();
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
        String contentType = normalizeContentType(file.getContentType(), file.getOriginalFilename());
        String type = resolveAssetType(assetType, contentType);
        assertMimeAllowed(type, contentType);
        String ext = EXT_BY_TYPE.getOrDefault(contentType, "bin");
        String objectKey = "ad/" + LocalDate.now(ZoneId.of("Asia/Shanghai"))
                + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String storageUri;
        try {
            storageUri = minioVideoService.putObject(objectKey, file.getBytes(), contentType)
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
        asset.setStatus(STATUS_ACTIVE);
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
        } catch (Exception e) {
            log.warn("ad asset minio cleanup failed assetId={} uri={}", assetId, asset.getStorageUri(), e);
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

    /** 同源流式预览（浏览器不直连 MinIO public endpoint）；仅 ACTIVE 可公开访问。 */
    @Transactional(readOnly = true)
    public void streamPreview(Long assetId, HttpServletRequest request, HttpServletResponse response) {
        MediaAsset asset = requireAsset(assetId);
        if (!STATUS_ACTIVE.equalsIgnoreCase(asset.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "素材不可用");
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
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

    private static String resolveAssetType(String requested, String contentType) {
        if (requested != null && !requested.isBlank()) {
            String t = requested.trim().toUpperCase(Locale.ROOT);
            if (IMAGE.equals(t) || VIDEO.equals(t)) {
                return t;
            }
            if ("H5".equals(t)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "H5 素材请使用投放计划外链，禁止上传可执行页面文件");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "素材类型仅支持 IMAGE 或 VIDEO");
        }
        if (ALLOWED_IMAGE_TYPES.contains(contentType)) {
            return IMAGE;
        }
        if (ALLOWED_VIDEO_TYPES.contains(contentType)) {
            return VIDEO;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的文件类型");
    }

    private static void assertMimeAllowed(String assetType, String contentType) {
        if (IMAGE.equals(assetType) && ALLOWED_IMAGE_TYPES.contains(contentType)) {
            return;
        }
        if (VIDEO.equals(assetType) && ALLOWED_VIDEO_TYPES.contains(contentType)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "文件类型与素材类型不匹配，仅支持 jpg/png/webp/gif 或 mp4/webm");
    }

    private static String normalizeContentType(String contentType, String fileName) {
        if (contentType != null && !contentType.isBlank()) {
            String type = contentType.trim().toLowerCase(Locale.ROOT);
            int semi = type.indexOf(';');
            if (semi > 0) {
                type = type.substring(0, semi).trim();
            }
            if (ALLOWED_IMAGE_TYPES.contains(type) || ALLOWED_VIDEO_TYPES.contains(type)) {
                return type;
            }
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (name.endsWith(".webm")) {
            return "video/webm";
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "不支持的文件类型，仅允许 jpg/png/webp/gif/mp4/webm");
    }

    private static int resolveDurationSeconds(int durationSeconds, String type) {
        if (durationSeconds > 0) {
            return durationSeconds;
        }
        return VIDEO.equals(type) ? 0 : 10;
    }
}
