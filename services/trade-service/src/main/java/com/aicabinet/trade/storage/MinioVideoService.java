package com.aicabinet.trade.storage;

import com.aicabinet.trade.config.MinioProperties;
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
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MinioVideoService {
    private static final String HTTPS = "https://";
    private static final String MINIO = "minio://";
    private static final String HTTP = "http://";
    private static final String FILE = "file://";


    private static final Logger log = LoggerFactory.getLogger(MinioVideoService.class);

    private final MinioProperties properties;
    private final AtomicReference<MinioClient> clientRef = new AtomicReference<>();
    /** 仅用于预签名：按 public-endpoint 签名，避免改写 Host 导致 SignatureDoesNotMatch。 */
    private final AtomicReference<MinioClient> presignClientRef = new AtomicReference<>();

    public MinioVideoService(MinioProperties properties) {
        this.properties = properties;
    }

    public Optional<String> presignDownloadUrl(String storageUri, int expirySeconds) {
        if (storageUri == null || storageUri.isBlank()) {
            return Optional.empty();
        }
        if (storageUri.startsWith(FILE) || storageUri.startsWith(HTTP) || storageUri.startsWith(HTTPS)) {
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
     * H46: file:// 与任意 http(s) 一并拒绝 —— 只允许本平台 MinIO 路径预签名。
     */
    public Optional<String> presignPlaybackUrl(String videoUri) {
        if (videoUri == null || videoUri.isBlank()) {
            return Optional.empty();
        }
        if (videoUri.startsWith(FILE) || videoUri.startsWith(HTTP) || videoUri.startsWith(HTTPS)) {
            log.warn("presignPlaybackUrl rejected non-platform uri scheme");
            return Optional.empty();
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
        if (storageUri.startsWith(FILE)) {
            try {
                return Files.isRegularFile(Paths.get(URI.create(storageUri)));
            } catch (Exception e) {
                log.warn("local video objectExists failed uri={}", storageUri, e);
                return false;
            }
        }
        if (storageUri.startsWith(HTTP) || storageUri.startsWith(HTTPS)) {
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
            log.warn("minio objectExists failed bucket={} key={}", parsed.bucket(), parsed.objectKey(), e);
            return false;
        }
    }

    /** 删除对象（minio://bucket/key 或 file:// 本地回退）。对象不存在视为成功。 */
    public boolean removeObject(String storageUri) {
        if (storageUri == null || storageUri.isBlank()) {
            return false;
        }
        if (storageUri.startsWith(FILE)) {
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
            return Optional.of(MINIO + properties.bucket() + "/" + objectKey);
        } catch (Exception e) {
            log.warn("putObject failed key={} size={}", objectKey, data.length, e);
            return Optional.empty();
        }
    }

    public void streamTo(String videoUri, HttpServletRequest request, HttpServletResponse response) {
        if (videoUri == null || videoUri.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "视频不存在");
        }
        if (videoUri.startsWith(HTTP) || videoUri.startsWith(HTTPS)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "外部视频请直接访问原链接");
        }
        if (videoUri.startsWith(FILE)) {
            // 审计 P0-1：file:// 读取可回显进程任意文件（写入侧已只接受 minio://，
            // 此处对存量/异常数据整体 fail-closed），不再提供本地文件流。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file:// 视频不受支持");
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
        MinioClient existing = clientRef.get();
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = clientRef.get();
            if (existing == null) {
                existing = MinioClient.builder()
                        .endpoint(properties.endpoint())
                        .credentials(properties.accessKey(), properties.secretKey())
                        .build();
                clientRef.set(existing);
            }
            return existing;
        }
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
        MinioClient existing = presignClientRef.get();
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = presignClientRef.get();
            if (existing == null) {
                String endpoint = publicEndpoint.endsWith("/")
                        ? publicEndpoint.substring(0, publicEndpoint.length() - 1)
                        : publicEndpoint;
                // 指定 region，避免 SDK 为查 region 去连 public host（容器内 localhost 不可达）
                existing = MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(properties.accessKey(), properties.secretKey())
                        .region("us-east-1")
                        .build();
                presignClientRef.set(existing);
            }
            return existing;
        }
    }

    private ParsedUri parseUri(String videoUri) {
        for (String prefix : new String[]{MINIO, "oss://", "s3://"}) {
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
