package com.aicabinet.trade.storage;

import com.aicabinet.common.dto.VideoUploadPresignResponse;
import com.aicabinet.common.storage.ObjectStorageKeys;
import com.aicabinet.trade.config.MinioProperties;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class MinioVideoService {

    private static final Logger log = LoggerFactory.getLogger(MinioVideoService.class);

    private final MinioProperties properties;
    private volatile MinioClient client;
    /** 仅用于预签名：按 public-endpoint 签名，避免改写 Host 导致 SignatureDoesNotMatch。 */
    private volatile MinioClient presignClient;

    public MinioVideoService(MinioProperties properties) {
        this.properties = properties;
    }

    public Optional<String> presignDownloadUrl(String storageUri, int expirySeconds) {
        if (storageUri == null || storageUri.isBlank()) {
            return Optional.empty();
        }
        if (storageUri.startsWith("file://") || storageUri.startsWith("http://") || storageUri.startsWith("https://")) {
            return Optional.of(storageUri);
        }
        ParsedUri parsed = parseUri(storageUri);
        if (parsed == null) {
            return Optional.empty();
        }
        try {
            // 必须用 public endpoint 直接签名；事后 rewrite host 会破坏 SigV4
            String url = presignClient().getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(parsed.bucket())
                            .object(parsed.objectKey())
                            .expiry(expirySeconds, TimeUnit.SECONDS)
                            .build());
            return Optional.of(url);
        } catch (Exception e) {
            log.warn("presign failed uri={}", storageUri, e);
            return Optional.empty();
        }
    }

    /**
     * 播放预签名：对象不存在时不返回 URL，避免后台 videoPreviewUrl 指向 404。
     */
    public Optional<String> presignPlaybackUrl(String videoUri) {
        if (videoUri == null || videoUri.isBlank()) {
            return Optional.empty();
        }
        if (videoUri.startsWith("file://") || videoUri.startsWith("http://") || videoUri.startsWith("https://")) {
            return Optional.of(videoUri);
        }
        if (!objectExists(videoUri)) {
            return Optional.empty();
        }
        return presignDownloadUrl(videoUri, properties.presignExpirySeconds());
    }

    /** MinIO 对象是否存在（minio://bucket/key）。 */
    public boolean objectExists(String storageUri) {
        if (storageUri == null || storageUri.isBlank()) {
            return false;
        }
        if (storageUri.startsWith("file://")) {
            try {
                return Files.isRegularFile(Paths.get(URI.create(storageUri)));
            } catch (Exception e) {
                return false;
            }
        }
        if (storageUri.startsWith("http://") || storageUri.startsWith("https://")) {
            return true;
        }
        ParsedUri parsed = parseUri(storageUri);
        if (parsed == null) {
            return false;
        }
        try {
            client().statObject(StatObjectArgs.builder()
                    .bucket(parsed.bucket())
                    .object(parsed.objectKey())
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 删除对象（minio://bucket/key 或 file:// 本地回退）。对象不存在视为成功。 */
    public boolean removeObject(String storageUri) {
        if (storageUri == null || storageUri.isBlank()) {
            return false;
        }
        if (storageUri.startsWith("file://")) {
            try {
                return Files.deleteIfExists(Paths.get(URI.create(storageUri)));
            } catch (Exception e) {
                log.warn("remove local file failed uri={}", storageUri, e);
                return false;
            }
        }
        ParsedUri parsed = parseUri(storageUri);
        if (parsed == null) {
            return false;
        }
        try {
            client().removeObject(RemoveObjectArgs.builder()
                    .bucket(parsed.bucket())
                    .object(parsed.objectKey())
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("remove object failed uri={}", storageUri, e);
            return false;
        }
    }

    /**
     * 为柜机/模拟器生成录像上传地址，对象键由 {@link ObjectStorageKeys} 统一生成。
     *
     * @param sim true 时使用 {@code sim/} 前缀（开发模拟器）
     */
    public Optional<VideoUploadPresignResponse> presignVideoUpload(
            String deviceId, long userId, String sessionId, String camera, String extension, boolean sim) {
        if (sessionId == null || sessionId.isBlank() || deviceId == null || deviceId.isBlank()) {
            return Optional.empty();
        }
        int expirySeconds = properties.presignExpirySeconds();
        String objectKey = sim
                ? ObjectStorageKeys.simMediaKey(deviceId, userId, sessionId, camera, extension)
                : ObjectStorageKeys.shoppingVideoKey(deviceId, userId, sessionId, camera, extension);
        try {
            // sim 上传方在 Docker 内网（device-simulator），须用内部 endpoint 签名；
            // 若用 publicEndpoint（localhost:19000）再改写 host，签名 host 不一致会 403。
            MinioClient signer = sim ? client() : presignClient();
            String uploadUrl = signer.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(properties.bucket())
                            .object(objectKey)
                            .expiry(expirySeconds, TimeUnit.SECONDS)
                            .build());
            String videoUri = "minio://" + properties.bucket() + "/" + objectKey;
            return Optional.of(new VideoUploadPresignResponse(
                    objectKey, uploadUrl, videoUri, expirySeconds));
        } catch (Exception e) {
            log.warn("presign upload failed session={} device={} sim={}", sessionId, deviceId, sim, e);
            return Optional.empty();
        }
    }

    /**
     * 服务端直传对象（消费者申诉附图等）。失败时返回 empty，由上层改写本地磁盘。
     */
    public Optional<String> putObject(String objectKey, byte[] data, String contentType) {
        if (objectKey == null || objectKey.isBlank() || data == null || data.length == 0) {
            return Optional.empty();
        }
        try {
            String type = (contentType == null || contentType.isBlank())
                    ? contentTypeForKey(objectKey)
                    : contentType;
            client().putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .stream(new java.io.ByteArrayInputStream(data), data.length, -1)
                    .contentType(type)
                    .build());
            return Optional.of("minio://" + properties.bucket() + "/" + objectKey);
        } catch (Exception e) {
            log.warn("putObject failed key={} size={}", objectKey, data.length, e);
            return Optional.empty();
        }
    }

    /** 将对象复制到归档路径（结算后按 SKU 索引录像）。 */
    public boolean copyObject(String sourceUri, String destObjectKey) {
        ParsedUri parsed = parseUri(sourceUri);
        if (parsed == null || destObjectKey == null || destObjectKey.isBlank()) {
            return false;
        }
        try {
            client().copyObject(CopyObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(destObjectKey)
                    .source(CopySource.builder()
                            .bucket(parsed.bucket())
                            .object(parsed.objectKey())
                            .build())
                    .build());
            log.info("archived video {} -> {}", parsed.objectKey(), destObjectKey);
            return true;
        } catch (Exception e) {
            log.warn("copy object failed src={} dest={}", sourceUri, destObjectKey, e);
            return false;
        }
    }

    public ParsedUri parseStorageUri(String videoUri) {
        return parseUri(videoUri);
    }

    public void streamTo(String videoUri, HttpServletRequest request, HttpServletResponse response) {
        if (videoUri == null || videoUri.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "视频不存在");
        }
        if (videoUri.startsWith("http://") || videoUri.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "外部视频请直接访问原链接");
        }
        if (videoUri.startsWith("file://")) {
            try {
                streamLocalFile(videoUri, request, response);
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                log.warn("stream local file failed uri={}", videoUri, e);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "视频文件不存在或无法读取");
            }
            return;
        }
        ParsedUri parsed = parseUri(videoUri);
        if (parsed == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的视频 URI");
        }
        try {
            long totalSize = client().statObject(
                    StatObjectArgs.builder()
                            .bucket(parsed.bucket())
                            .object(parsed.objectKey())
                            .build()).size();

            String contentType = contentTypeForKey(parsed.objectKey());
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Cache-Control", "private, max-age=3600");

            Range range = parseRange(request != null ? request.getHeader("Range") : null, totalSize);
            if (range != null) {
                long length = range.end - range.start + 1;
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setContentType(contentType);
                response.setHeader("Content-Range",
                        "bytes " + range.start + "-" + range.end + "/" + totalSize);
                response.setContentLengthLong(length);
                try (InputStream in = client().getObject(
                        GetObjectArgs.builder()
                                .bucket(parsed.bucket())
                                .object(parsed.objectKey())
                                .offset(range.start)
                                .length(length)
                                .build());
                     OutputStream out = response.getOutputStream()) {
                    in.transferTo(out);
                }
            } else {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType(contentType);
                response.setContentLengthLong(totalSize);
                try (InputStream in = client().getObject(
                        GetObjectArgs.builder()
                                .bucket(parsed.bucket())
                                .object(parsed.objectKey())
                                .build());
                     OutputStream out = response.getOutputStream()) {
                    in.transferTo(out);
                }
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("stream failed uri={}", videoUri, e);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "视频文件不存在或无法读取");
        }
    }

    private void streamLocalFile(String videoUri, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        Path path = Paths.get(URI.create(videoUri));
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "视频文件不存在");
        }
        long totalSize = Files.size(path);
        String contentType = contentTypeForKey(path.getFileName().toString());
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "private, max-age=3600");

        Range range = parseRange(request.getHeader("Range"), totalSize);
        if (range != null) {
            long length = range.end - range.start + 1;
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setContentType(contentType);
            response.setHeader("Content-Range",
                    "bytes " + range.start + "-" + range.end + "/" + totalSize);
            response.setContentLengthLong(length);
            try (InputStream in = Files.newInputStream(path);
                 OutputStream out = response.getOutputStream()) {
                copyRange(in, out, range.start, length);
            }
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(contentType);
            response.setContentLengthLong(totalSize);
            try (InputStream in = Files.newInputStream(path);
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
        }
    }

    private static void copyRange(InputStream in, OutputStream out, long start, long length)
            throws java.io.IOException {
        in.skipNBytes(start);
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static Range parseRange(String header, long totalSize) {
        if (header == null || !header.startsWith("bytes=") || totalSize <= 0) {
            return null;
        }
        String spec = header.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String startPart = spec.substring(0, dash).trim();
            String endPart = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (startPart.isEmpty()) {
                long suffix = Long.parseLong(endPart);
                start = Math.max(0, totalSize - suffix);
                end = totalSize - 1;
            } else {
                start = Long.parseLong(startPart);
                end = endPart.isEmpty() ? totalSize - 1 : Long.parseLong(endPart);
            }
            if (start < 0 || start >= totalSize) {
                return null;
            }
            end = Math.min(end, totalSize - 1);
            if (end < start) {
                return null;
            }
            return new Range(start, end);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Range(long start, long end) {}

    private MinioClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = MinioClient.builder()
                            .endpoint(properties.endpoint())
                            .credentials(properties.accessKey(), properties.secretKey())
                            .build();
                }
            }
        }
        return client;
    }

    /**
     * 预签名客户端：优先使用浏览器可达的 public-endpoint。
     * 只做本地签名计算，不会对 public host 发起服务端 IO。
     */
    private MinioClient presignClient() {
        String publicEndpoint = properties.publicEndpoint();
        if (publicEndpoint == null || publicEndpoint.isBlank()) {
            return client();
        }
        if (presignClient == null) {
            synchronized (this) {
                if (presignClient == null) {
                    String endpoint = publicEndpoint.endsWith("/")
                            ? publicEndpoint.substring(0, publicEndpoint.length() - 1)
                            : publicEndpoint;
                    // 指定 region，避免 SDK 为查 region 去连 public host（容器内 localhost 不可达）
                    presignClient = MinioClient.builder()
                            .endpoint(endpoint)
                            .credentials(properties.accessKey(), properties.secretKey())
                            .region("us-east-1")
                            .build();
                }
            }
        }
        return presignClient;
    }

    private ParsedUri parseUri(String videoUri) {
        for (String prefix : new String[]{"minio://", "oss://", "s3://"}) {
            if (videoUri.startsWith(prefix)) {
                String rest = videoUri.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash <= 0) {
                    return null;
                }
                return new ParsedUri(rest.substring(0, slash), rest.substring(slash + 1));
            }
        }
        return null;
    }

    private static String contentTypeForKey(String objectKey) {
        String lower = objectKey.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) {
            return "video/mp4";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    public record ParsedUri(String bucket, String objectKey) {}
}
