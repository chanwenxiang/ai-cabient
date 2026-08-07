package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FileAttachmentDto;
import com.aicabinet.common.storage.ObjectStorageKeys;
import com.aicabinet.trade.config.MinioProperties;
import com.aicabinet.trade.domain.FileAttachment;
import com.aicabinet.trade.mapper.FileAttachmentMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FileAttachmentService {

    public static final String REF_PENDING = "PENDING_DISPUTE";
    public static final String REF_DISPUTE = "DISPUTE";
    public static final String REF_REPLENISHMENT = "REPLENISHMENT_TASK";
    public static final String REF_SKU_IMAGE = "SKU_IMAGE";
    private static final long MAX_BYTES = 5 * 1024 * 1024L;
    private static final int MAX_EVIDENCE = 5;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif"
    );

    private final FileAttachmentMapper fileAttachmentMapper;
    private final MinioVideoService minioVideoService;
    private final MinioProperties minioProperties;
    private final Path localRoot;

    /** 集群模式应关闭本地回退：MinIO 不可用时直接报错，避免文件只存在单节点。 */
    @Value("${app.storage.local-fallback-enabled:true}")
    private boolean localFallbackEnabled;

    public FileAttachmentService(FileAttachmentMapper fileAttachmentMapper,
                                 MinioVideoService minioVideoService,
                                 MinioProperties minioProperties,
                                 @Value("${app.storage.local-dir:./data/attachments}") String localDir) {
        this.fileAttachmentMapper = fileAttachmentMapper;
        this.minioVideoService = minioVideoService;
        this.minioProperties = minioProperties;
        this.localRoot = Paths.get(localDir).toAbsolutePath().normalize();
    }

    @Transactional
    public FileAttachmentDto uploadDisputeEvidence(Long userId, MultipartFile file) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单张图片不能超过 5MB");
        }
        String contentType = normalizeContentType(file.getContentType(), file.getOriginalFilename());
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 jpg/png/webp/gif");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败");
        }
        String ext = extensionFor(contentType, file.getOriginalFilename());
        String token = UUID.randomUUID().toString().replace("-", "");
        String objectKey = ObjectStorageKeys.disputeEvidenceKey(userId, token, ext);
        String storagePath = minioVideoService.putObject(objectKey, bytes, contentType)
                .orElseGet(() -> fallbackStore(objectKey, bytes));
        FileAttachment row = new FileAttachment();
        row.setRefType(REF_PENDING);
        row.setRefId(String.valueOf(userId));
        row.setFileName(safeName(file.getOriginalFilename(), token + ext));
        row.setFileSize((long) bytes.length);
        row.setContentType(contentType);
        row.setStoragePath(storagePath);
        row.setStorageBucket(storagePath.startsWith("minio://") ? minioProperties.bucket() : "local");
        row.setUploadedBy(userId);
        row.setCreatedAt(Instant.now());
        fileAttachmentMapper.insert(row);
        return toDto(row);
    }

    @Transactional
    public List<FileAttachmentDto> bindEvidenceToDispute(Long userId, String ticketId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        if (fileIds.size() > MAX_EVIDENCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最多上传 " + MAX_EVIDENCE + " 张图片");
        }
        List<FileAttachment> rows = fileAttachmentMapper.findByIds(fileIds);
        if (rows.size() != fileIds.stream().distinct().count()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部分附图不存在或已失效");
        }
        List<FileAttachmentDto> out = new ArrayList<>();
        for (FileAttachment row : rows) {
            if (!userId.equals(row.getUploadedBy())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用该附图");
            }
            boolean pendingMine = REF_PENDING.equals(row.getRefType())
                    && String.valueOf(userId).equals(row.getRefId());
            boolean alreadyBound = REF_DISPUTE.equals(row.getRefType())
                    && ticketId.equals(row.getRefId());
            if (!pendingMine && !alreadyBound) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "附图已被其它工单使用");
            }
            if (pendingMine) {
                row.setRefType(REF_DISPUTE);
                row.setRefId(ticketId);
                fileAttachmentMapper.updateById(row);
            }
            out.add(toDto(row));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<FileAttachmentDto> listDisputeEvidence(String ticketId) {
        return fileAttachmentMapper.findByRef(REF_DISPUTE, ticketId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public FileAttachmentDto uploadReplenishmentEvidence(Long userId, Long taskId, MultipartFile file) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (taskId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务不存在");
        }
        long existing = fileAttachmentMapper.findByRef(REF_REPLENISHMENT, String.valueOf(taskId)).size();
        if (existing >= MAX_EVIDENCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最多上传 " + MAX_EVIDENCE + " 张现场照片");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单张图片不能超过 5MB");
        }
        String contentType = normalizeContentType(file.getContentType(), file.getOriginalFilename());
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 jpg/png/webp/gif");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败");
        }
        String ext = extensionFor(contentType, file.getOriginalFilename());
        String token = UUID.randomUUID().toString().replace("-", "");
        String objectKey = ObjectStorageKeys.replenishmentEvidenceKey(taskId, userId, token, ext);
        String storagePath = minioVideoService.putObject(objectKey, bytes, contentType)
                .orElseGet(() -> fallbackStore(objectKey, bytes));
        FileAttachment row = new FileAttachment();
        row.setRefType(REF_REPLENISHMENT);
        row.setRefId(String.valueOf(taskId));
        row.setFileName(safeName(file.getOriginalFilename(), token + ext));
        row.setFileSize((long) bytes.length);
        row.setContentType(contentType);
        row.setStoragePath(storagePath);
        row.setStorageBucket(storagePath.startsWith("minio://") ? minioProperties.bucket() : "local");
        row.setUploadedBy(userId);
        row.setCreatedAt(Instant.now());
        fileAttachmentMapper.insert(row);
        return toDto(row);
    }

    @Transactional(readOnly = true)
    public List<FileAttachmentDto> listReplenishmentEvidence(Long taskId) {
        return fileAttachmentMapper.findByRef(REF_REPLENISHMENT, String.valueOf(taskId)).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public FileAttachmentDto uploadSkuImage(Long operatorId, MultipartFile file) {
        if (operatorId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单张图片不能超过 5MB");
        }
        String contentType = normalizeContentType(file.getContentType(), file.getOriginalFilename());
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 jpg/png/webp/gif");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败");
        }
        String ext = extensionFor(contentType, file.getOriginalFilename());
        String token = UUID.randomUUID().toString().replace("-", "");
        String objectKey = ObjectStorageKeys.skuImageKey(operatorId, token, ext);
        String storagePath = minioVideoService.putObject(objectKey, bytes, contentType)
                .orElseGet(() -> fallbackStore(objectKey, bytes));
        FileAttachment row = new FileAttachment();
        row.setRefType(REF_SKU_IMAGE);
        row.setRefId(String.valueOf(operatorId));
        row.setFileName(safeName(file.getOriginalFilename(), token + ext));
        row.setFileSize((long) bytes.length);
        row.setContentType(contentType);
        row.setStoragePath(storagePath);
        row.setStorageBucket(storagePath.startsWith("minio://") ? minioProperties.bucket() : "local");
        row.setUploadedBy(operatorId);
        row.setCreatedAt(Instant.now());
        fileAttachmentMapper.insert(row);
        return toDto(row);
    }

    @Transactional(readOnly = true)
    public FileAttachment requireSkuImage(Long fileId) {
        FileAttachment row = fileAttachmentMapper.selectById(fileId);
        if (row == null || !REF_SKU_IMAGE.equals(row.getRefType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品图片不存在");
        }
        return row;
    }

    @Transactional(readOnly = true)
    public FileAttachment requireReadable(Long requesterId, Long fileId, boolean operator) {
        FileAttachment row = fileAttachmentMapper.selectById(fileId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        }
        if (operator || requesterId.equals(row.getUploadedBy())) {
            return row;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该附件");
    }

    @Transactional(readOnly = true)
    public FileAttachment requireReplenishmentEvidence(Long taskId, Long fileId) {
        FileAttachment row = fileAttachmentMapper.selectById(fileId);
        if (row == null
                || !REF_REPLENISHMENT.equals(row.getRefType())
                || !String.valueOf(taskId).equals(row.getRefId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        }
        return row;
    }

    public void stream(FileAttachment row, HttpServletResponse response) throws IOException {
        String path = row.getStoragePath();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件文件缺失");
        }
        response.setContentType(row.getContentType() != null ? row.getContentType() : "application/octet-stream");
        response.setHeader("Cache-Control", "private, max-age=3600");
        if (row.getFileSize() != null && row.getFileSize() > 0) {
            response.setContentLengthLong(row.getFileSize());
        }
        if (path.startsWith("file://")) {
            Path local = Paths.get(URI.create(path));
            try (InputStream in = Files.newInputStream(local); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
            return;
        }
        // MinIO / OSS：优先用流式代理，避免浏览器跨域
        minioVideoService.streamTo(path, null, response);
    }

    public FileAttachmentDto toDto(FileAttachment row) {
        String url;
        if (REF_REPLENISHMENT.equals(row.getRefType())) {
            url = "/api/v2/merchant/replenishment/tasks/" + row.getRefId() + "/evidence/" + row.getFileId();
        } else if (REF_SKU_IMAGE.equals(row.getRefType())) {
            url = "/api/v2/media/sku-images/" + row.getFileId();
        } else {
            url = "/api/v2/disputes/evidence/" + row.getFileId();
        }
        return FileAttachmentDto.of(row.getFileId(), row.getFileName(), row.getContentType(), row.getFileSize(), url);
    }

    /** MinIO 不可用时的兜底：允许本地回退时写本地磁盘，否则直接报错（集群模式）。 */
    private String fallbackStore(String objectKey, byte[] data) {
        if (!localFallbackEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "对象存储不可用且已禁用本地回退（集群模式），请检查 MinIO");
        }
        return writeLocal(objectKey, data);
    }

    private String writeLocal(String objectKey, byte[] data) {
        try {
            Path target = localRoot.resolve(objectKey).normalize();
            if (!target.startsWith(localRoot)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法存储路径");
            }
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            return target.toUri().toString();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存附图失败");
        }
    }

    private static String normalizeContentType(String contentType, String fileName) {
        if (contentType != null && !contentType.isBlank()) {
            String type = contentType.trim().toLowerCase(Locale.ROOT);
            int semi = type.indexOf(';');
            if (semi > 0) {
                type = type.substring(0, semi).trim();
            }
            if ("image/jpg".equals(type)) {
                return "image/jpeg";
            }
            return type;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    private static String extensionFor(String contentType, String fileName) {
        if (contentType != null) {
            return switch (contentType) {
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                case "image/gif" -> ".gif";
                default -> ".jpg";
            };
        }
        if (fileName != null && fileName.contains(".")) {
            return fileName.substring(fileName.lastIndexOf('.'));
        }
        return ".jpg";
    }

    private static String safeName(String original, String fallback) {
        if (original == null || original.isBlank()) {
            return fallback;
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        return name.isEmpty() ? fallback : name.substring(0, Math.min(name.length(), 200));
    }
}
