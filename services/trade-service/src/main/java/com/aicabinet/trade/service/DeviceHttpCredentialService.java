package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceHttpCredential;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceHttpCredentialMapper;
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
import java.util.Optional;

/**
 * 设备 HTTP 独立凭据（审计 A1/P0-2，2026-10-15 拍板块 A）：设备可达内部端点
 * （OTA check/progress、screen-content、ad-play、sessions/video*）一机一密。
 *
 * <p>流程：运营签发（明文 secret 仅签发响应返回一次，表里只存 sha256）→ 现场装机时
 * 经运维下发/模拟器 env 注入（android-app 已随 47188717 退役，在网老固件沿用历史凭据）。校验：X-Device-Id + X-Device-Secret
 * → sha256 恒定时间比对 → ACTIVE 且与路径 deviceId 绑定（DEVICE_MISMATCH 语义）。</p>
 *
 * <p>表结构对齐 V290__device_mqtt_credential；差异=无明文列（HTTP 校验不经 EMQX）。</p>
 */
@Service
public class DeviceHttpCredentialService {
    private static final Logger log = LoggerFactory.getLogger(DeviceHttpCredentialService.class);
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    private static final int SECRET_BYTES = 32;

    private final DeviceHttpCredentialMapper repository;
    private final DeviceInfoMapper deviceRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceHttpCredentialService(DeviceHttpCredentialMapper repository, DeviceInfoMapper deviceRepository) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
    }

    public record IssuedCredential(String deviceId, String secret,
                                   String secretSha256, Instant issuedAt) {
    }

    /**
     * 签发（首次）或轮换（显式）。
     *
     * <p>🔴 L-09（2026-11-17）两条语义在此收紧：</p>
     * <ol>
     *   <li><b>轮换必须显式</b>：旧形态对已有凭据**静默轮换**——运营点一次「签发」就把现场
     *       设备的 secret 换掉，而装机现场拿不到新明文（明文只在签发响应里回一次），
     *       结果是一台在线柜机在 401 里静默掉线，事后只能从「设备掉线」倒查。
     *       现在 {@code explicitRotate=false} 撞上已生效凭据直接 409，把「你以为在补发、
     *       其实在废掉」这个歧义挡在入口。</li>
     *   <li><b>行已存在一律 UPDATE</b>：旧形态判据是「有没有 hash」，而 {@code revoke} 会把
     *       hash 清成空串 ⇒「吊销后再签发」掉进 insert 分支，撞 device_id 主键直接 500。
     *       吊销后重新签发是常规运维动作，不能 500。</li>
     * </ol>
     */
    @Transactional
    public IssuedCredential issue(String deviceId, Long operatorId, boolean explicitRotate) {
        String id = requireDeviceId(deviceId);
        // MA-119：原来只验「12 位数字」不验设备存在 ⇒ 手滑一位就签出一条 ACTIVE 孤儿凭据（还把明文 secret 回显给调用方）
        requireDeviceExists(id);
        String secret = randomSecret();
        String hash = sha256Hex(secret);
        Instant now = Instant.now();

        DeviceHttpCredential existing = repository.findById(id).orElse(null);
        boolean hasLiveSecret = existing != null && existing.getSecretSha256() != null
                && !existing.getSecretSha256().isBlank();
        if (hasLiveSecret && !explicitRotate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该设备已有生效凭据；轮换会立刻使现场设备鉴权失败（新明文只在本次响应可见）。"
                            + "确需轮换请显式提交 {\"mode\":\"rotate\"}");
        }

        if (existing != null) {
            // 轮换/复签都走显式 SQL：既清撤销标记（updateById 忽略 null 字段，check-mybatis-null-clear 门禁），
            // 也避免「吊销过 ⇒ 走 insert ⇒ 主键冲突」。
            repository.clearRevocationAndRotate(id, hash, operatorId, now);
        } else {
            DeviceHttpCredential cred = new DeviceHttpCredential();
            cred.setDeviceId(id);
            cred.setSecretSha256(hash);
            cred.setStatus(STATUS_ACTIVE);
            cred.setIssuedBy(operatorId);
            cred.setIssuedAt(now);
            repository.save(cred);
        }
        log.info("device http credential issued deviceId={} rotated={} by={}", id, hasLiveSecret, operatorId);
        return new IssuedCredential(id, secret, hash, now);
    }

    /** 吊销：保留行作审计，状态置 REVOKED，hash 清空（该设备设备面 HTTP 即刻 401）。 */
    @Transactional
    public boolean revoke(String deviceId, Long operatorId, String reason) {
        String id = requireDeviceId(deviceId);
        DeviceHttpCredential cred = repository.findById(id).orElse(null);
        if (cred == null || STATUS_REVOKED.equals(cred.getStatus())) {
            return false;
        }
        cred.setStatus(STATUS_REVOKED);
        cred.setRevokedAt(Instant.now());
        cred.setRevokeReason(reason == null ? "" : reason.trim());
        cred.setSecretSha256("");
        repository.save(cred);
        log.info("device http credential revoked deviceId={} by={} reason={}",
                id, operatorId, cred.getRevokeReason());
        return true;
    }

    public record CredentialStatus(String deviceId, String status,
                                   Instant issuedAt, Instant rotatedAt, Instant revokedAt,
                                   String revokeReason) {
    }

    public Optional<CredentialStatus> status(String deviceId) {
        return repository.findById(requireDeviceId(deviceId))
                .map(c -> new CredentialStatus(c.getDeviceId(), c.getStatus(),
                        c.getIssuedAt(), c.getRotatedAt(), c.getRevokedAt(), c.getRevokeReason()));
    }

    /**
     * 校验（设备面拦截器唯一入口）：deviceId + secret 匹配 ACTIVE 行。
     * 返回错误语义：401（缺头/不匹配）优先于 403（吊销）——不向未持证者泄露凭据存在性。
     */
    public boolean verify(String deviceId, String secret) {
        if (deviceId == null || deviceId.isBlank() || secret == null || secret.isBlank()) {
            return false;
        }
        DeviceHttpCredential cred = repository.findById(deviceId.trim()).orElse(null);
        if (cred == null || !STATUS_ACTIVE.equals(cred.getStatus())
                || cred.getSecretSha256() == null || cred.getSecretSha256().isBlank()) {
            return false;
        }
        byte[] provided = sha256Hex(secret).getBytes(StandardCharsets.UTF_8);
        byte[] expected = cred.getSecretSha256().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expected);
    }

    /** 凭据只能签给真实存在的设备：孤儿 ACTIVE 行会长期留在网关的可鉴权面上。 */
    private void requireDeviceExists(String deviceId) {
        if (deviceRepository.selectById(deviceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在：" + deviceId + "，不签发凭据");
        }
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
