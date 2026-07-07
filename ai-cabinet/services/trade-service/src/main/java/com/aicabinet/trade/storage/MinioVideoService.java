package com.aicabinet.trade.storage;

import com.aicabinet.trade.config.MinioProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public Optional<String> presignPlaybackUrl(String videoUri) {
        return presignDownloadUrl(videoUri, properties.presignExpirySeconds());
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
            return Optional.of(url);
        } catch (Exception e) {
            log.warn("presign failed uri={}", storageUri, e);
            return Optional.empty();
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

    private record ParsedUri(String bucket, String objectKey) {}
}
