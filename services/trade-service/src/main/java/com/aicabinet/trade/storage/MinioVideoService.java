package com.aicabinet.trade.storage;

import com.aicabinet.common.dto.VideoUploadPresignResponse;
import com.aicabinet.common.storage.ObjectStorageKeys;
import com.aicabinet.trade.config.MinioProperties;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
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
            String url = client().getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(parsed.bucket())
                            .object(parsed.objectKey())
                            .expiry(expirySeconds, TimeUnit.SECONDS)
                            .build());
            return Optional.of(rewritePublicUrl(url));
        } catch (Exception e) {
            log.warn("presign failed uri={}", storageUri, e);
            return Optional.empty();
        }
    }

    public Optional<String> presignPlaybackUrl(String videoUri) {
        return presignDownloadUrl(videoUri, properties.presignExpirySeconds());
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
            String uploadUrl = client().getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(properties.bucket())
                            .object(objectKey)
                            .expiry(expirySeconds, TimeUnit.SECONDS)
                            .build());
            String videoUri = "minio://" + properties.bucket() + "/" + objectKey;
            return Optional.of(new VideoUploadPresignResponse(
                    objectKey, rewritePublicUrl(uploadUrl), videoUri, expirySeconds));
        } catch (Exception e) {
            log.warn("presign upload failed session={} device={} sim={}", sessionId, deviceId, sim, e);
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

            Range range = parseRange(request.getHeader("Range"), totalSize);
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

    private String rewritePublicUrl(String presignedUrl) {
        String publicEndpoint = properties.publicEndpoint();
        if (publicEndpoint == null || publicEndpoint.isBlank()) {
            return presignedUrl;
        }
        try {
            URI signed = URI.create(presignedUrl);
            URI pub = URI.create(publicEndpoint.endsWith("/")
                    ? publicEndpoint.substring(0, publicEndpoint.length() - 1)
                    : publicEndpoint);
            int port = pub.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(pub.getScheme()) ? 443 : 80;
            }
            return new URI(
                    pub.getScheme(),
                    signed.getUserInfo(),
                    pub.getHost(),
                    port,
                    signed.getPath(),
                    signed.getQuery(),
                    signed.getFragment()
            ).toString();
        } catch (Exception e) {
            log.warn("rewrite presign url failed publicEndpoint={}", publicEndpoint, e);
            return presignedUrl;
        }
    }

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
