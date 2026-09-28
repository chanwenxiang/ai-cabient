package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceMqttCredential;
import com.aicabinet.trade.mapper.DeviceMqttCredentialMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * S1 设备凭据：每设备独立 MQTT 账号（username=deviceId），替代共享 aicabinet-device。
 *
 * <p>流程：运营签发（明文 secret 仅在签发响应返回一次，表里同时存明文与哈希——明文是生成
 * EMQX bootstrap CSV 所必需，敏感级与 auth-bootstrap CSV 相同，禁止进日志/导出）→
 * 生成器读本表产出 EMQX bootstrap → EMQX 内置数据库认证。吊销后生成器不再输出该行，
 * EMQX 重建后凭据即失效。</p>
 *
 * <p>哈希 secretSha256 用于快速校验/审计比对，不是认证依据（认证在 EMQX）。</p>
 */
@Service
public class DeviceMqttCredentialService {
    private static final Logger log = LoggerFactory.getLogger(DeviceMqttCredentialService.class);
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    /** EMQX 内置库认证对 MQTT 3.1.1 口令有 6~128 字节限制；32 字节随机 base64 在界内。 */
    private static final int SECRET_BYTES = 32;

    private final DeviceMqttCredentialMapper repository;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceMqttCredentialService(DeviceMqttCredentialMapper repository) {
        this.repository = repository;
    }

    public record IssuedCredential(String deviceId, String username, String secret,
                                   String secretSha256, Instant issuedAt) {
    }

    /** 签发/轮换：同一 deviceId 旧凭据直接被新凭据替换（EMQX 侧重建 bootstrap 后生效）。 */
    @Transactional
    public IssuedCredential issue(String deviceId, Long operatorId) {
        String id = requireDeviceId(deviceId);
        String secret = randomSecret();
        String hash = sha256Hex(secret);
        Instant now = Instant.now();

        DeviceMqttCredential cred = repository.findById(id).orElseGet(() -> {
            DeviceMqttCredential c = new DeviceMqttCredential();
            c.setDeviceId(id);
            c.setIssuedAt(now);
            return c;
        });
        boolean rotation = cred.getMqttSecret() != null;
        cred.setMqttUsername(id);
        cred.setMqttSecret(secret);
        cred.setSecretSha256(hash);
        cred.setStatus(STATUS_ACTIVE);
        cred.setIssuedBy(operatorId);
        cred.setRevokedAt(null);
        cred.setRevokeReason(null);
        cred.setRotatedAt(rotation ? now : null);
        repository.save(cred);
        log.info("device mqtt credential issued deviceId={} rotated={} by={}", id, rotation, operatorId);
        return new IssuedCredential(id, id, secret, hash, now);
    }

    /** 吊销：保留行作审计，状态置 REVOKED；生成器不再输出 REVOKED 行。 */
    @Transactional
    public boolean revoke(String deviceId, Long operatorId, String reason) {
        String id = requireDeviceId(deviceId);
        DeviceMqttCredential cred = repository.findById(id).orElse(null);
        if (cred == null || STATUS_REVOKED.equals(cred.getStatus())) {
            return false;
        }
        cred.setStatus(STATUS_REVOKED);
        cred.setRevokedAt(Instant.now());
        cred.setRevokeReason(reason == null ? "" : reason.trim());
        cred.setMqttSecret("");
        cred.setSecretSha256("");
        repository.save(cred);
        log.info("device mqtt credential revoked deviceId={} by={} reason={}", id, operatorId, cred.getRevokeReason());
        return true;
    }

    public record CredentialStatus(String deviceId, String username, String status,
                                   Instant issuedAt, Instant rotatedAt, Instant revokedAt,
                                   String revokeReason) {
    }

    public Optional<CredentialStatus> status(String deviceId) {
        return repository.findById(requireDeviceId(deviceId))
                .map(c -> new CredentialStatus(c.getDeviceId(), c.getMqttUsername(), c.getStatus(),
                        c.getIssuedAt(), c.getRotatedAt(), c.getRevokedAt(), c.getRevokeReason()));
    }

    /** 生成器（内部通道）读取 ACTIVE 凭据行：含明文 secret，仅限内部管理端点调用。 */
    public List<DeviceMqttCredential> listActiveForBootstrap() {
        return repository.findByStatusOrderByDeviceId(STATUS_ACTIVE);
    }

    private String requireDeviceId(String deviceId) {
        String id = deviceId == null ? "" : deviceId.trim();
        if (id.isEmpty() || !id.matches("[0-9]{12}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ApiMessages.INVALID_REQUEST + "：deviceId 须为 12 位数字");
        }
        return id;
    }

    private String randomSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
