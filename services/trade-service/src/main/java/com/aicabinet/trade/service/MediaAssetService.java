package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MediaAssetDto;
import com.aicabinet.common.dto.UpsertMediaAssetRequest;
import com.aicabinet.trade.domain.MediaAsset;
import com.aicabinet.trade.mapper.MediaAssetMapper;
import com.aicabinet.trade.storage.MinioVideoService;
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

    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final MediaAssetMapper assetRepository;
    private final MinioVideoService minioVideoService;

    public MediaAssetService(MediaAssetMapper assetRepository, MinioVideoService minioVideoService) {
        this.assetRepository = assetRepository;
        this.minioVideoService = minioVideoService;
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
        asset.setDurationSeconds(durationSeconds > 0 ? durationSeconds : (type.equals("VIDEO") ? 0 : 10));
        asset.setStatus("ACTIVE");
        asset.setUploadedBy(operatorId);
        assetRepository.insert(asset);
        return toDto(asset);
    }

    @Transactional
    public MediaAssetDto update(Long assetId, UpsertMediaAssetRequest request) {
        MediaAsset asset = requireAsset(assetId);
        asset.setTitle(request.title().trim());
        asset.setDurationSeconds(Math.max(0, request.durationSeconds()));
        if (request.status() != null && !request.status().isBlank()) {
            asset.setStatus(request.status().trim().toUpperCase());
        }
        assetRepository.updateById(asset);
        return toDto(asset);
    }

    private MediaAsset requireAsset(Long assetId) {
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "素材不存在"));
    }

    private MediaAssetDto toDto(MediaAsset asset) {
        String preview = minioVideoService.presignDownloadUrl(asset.getStorageUri(), 600).orElse(null);
        return new MediaAssetDto(
                asset.getAssetId(), asset.getTitle(), asset.getAssetType(),
                asset.getStorageUri(), preview, asset.getDurationSeconds(),
                asset.getStatus(), asset.getCreatedAt());
    }

    private static String normalizeType(String requested, String contentType) {
        if (requested != null && !requested.isBlank()) {
            String t = requested.trim().toUpperCase();
            if (t.equals("IMAGE") || t.equals("VIDEO") || t.equals("H5")) {
                return t;
            }
        }
        if (contentType != null) {
            if (contentType.startsWith("image/")) {
                return "IMAGE";
            }
            if (contentType.startsWith("video/")) {
                return "VIDEO";
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
}
