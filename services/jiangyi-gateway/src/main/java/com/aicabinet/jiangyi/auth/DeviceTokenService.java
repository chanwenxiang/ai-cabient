package com.aicabinet.jiangyi.auth;

import com.aicabinet.jiangyi.client.TradeInternalClient.JiangyiDeviceView;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 将邑设备 token 服务（CB-022，方案 §3）：JWT HS256 签发/校验。
 * <p>claims：sn/deviceId/identifier/ver（签发时表内 token_version）/exp（默认 7 天）。
 * 密钥 {@code JIANGYI_JWT_SECRET} env-only 无默认值：未配置或强度不足（&lt;32 字节）
 * 时签发即抛异常 → 端点 503 fail-closed；表/日志不落 token 本体。</p>
 *
 * <p>吊销语义（方案 §3）：吊销时表 token_version+1；校验方比对 claims.ver 与
 * 表值（claims.ver &lt; 表值 = 已吊销）。本服务只管签发与验签，比对在调用方。</p>
 */
@Service
public class DeviceTokenService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenService.class);

    public static final String CLAIM_SN = "sn";
    public static final String CLAIM_DEVICE_ID = "deviceId";
    public static final String CLAIM_IDENTIFIER = "identifier";
    public static final String CLAIM_VER = "ver";

    private final String jwtSecret;
    private final long tokenTtlSeconds;

    private volatile SecretKey key;

    public DeviceTokenService(@Value("${aicabinet.jiangyi.jwt-secret:}") String jwtSecret,
                              @Value("${aicabinet.jiangyi.token-ttl-seconds:604800}") long tokenTtlSeconds) {
        this.jwtSecret = jwtSecret;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    /** fail-closed：密钥未配置/强度不足（HS256 需 ≥32 字节）直接拒绝签发。 */
    public void ensureKeyAvailable() {
        secretKey();
    }

    /** 签发设备 token（ver = 签发时表内 token_version）。 */
    public String issue(JiangyiDeviceView device) {
        SecretKey k = secretKey();
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .subject(device.deviceId())
                .claim(CLAIM_SN, device.deviceSn())
                .claim(CLAIM_DEVICE_ID, device.deviceId())
                .claim(CLAIM_IDENTIFIER, device.identifier())
                .claim(CLAIM_VER, device.tokenVersion() == null ? 0L : device.tokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(tokenTtlSeconds)))
                .signWith(k)
                .compact();
    }

    /** 验签 + 解 claims；签名不符/过期抛 {@link JwtException}。 */
    public Claims verify(String token) {
        return Jwts.parser().verifyWith(secretKey()).build()
                .parseSignedClaims(token).getPayload();
    }

    private SecretKey secretKey() {
        SecretKey k = key;
        if (k != null) {
            return k;
        }
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("JIANGYI_JWT_SECRET 未配置（fail-closed，方案 §3）");
        }
        byte[] bytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JIANGYI_JWT_SECRET 强度不足（需 ≥32 字节）");
        }
        k = Keys.hmacShaKeyFor(bytes);
        key = k;
        log.info("jiangyi device token key initialized (ttl={}s)", tokenTtlSeconds);
        return k;
    }
}
