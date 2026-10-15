package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceHttpCredential;
import com.aicabinet.trade.mapper.DeviceHttpCredentialMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备 HTTP 独立凭据单测（审计 A1/P0-2）：签发只存 hash、明文一次性返回、
 * 轮换显式清撤销标记（check-mybatis-null-clear）、吊销即刻失效、verify 恒定比对。
 */
@ExtendWith(MockitoExtension.class)
class DeviceHttpCredentialServiceTest {

    @Mock
    private DeviceHttpCredentialMapper repository;

    private DeviceHttpCredentialService service;

    @BeforeEach
    void setUp() {
        service = new DeviceHttpCredentialService(repository);
    }

    @Test
    void issue_storesHashOnlyAndReturnsPlaintextOnce() {
        when(repository.findById("123456789012")).thenReturn(Optional.empty());

        DeviceHttpCredentialService.IssuedCredential issued =
                service.issue("123456789012", 9L);

        assertThat(issued.secret()).isNotBlank();
        assertThat(issued.secretSha256())
                .isEqualTo(DeviceHttpCredentialService.sha256Hex(issued.secret()));
        ArgumentCaptor<DeviceHttpCredential> captor = ArgumentCaptor.forClass(DeviceHttpCredential.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDeviceId()).isEqualTo("123456789012");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        // 本表只存 sha256，无明文列（与 V290 MQTT 凭据差异点）
        assertThat(captor.getValue().getSecretSha256()).isEqualTo(issued.secretSha256());
    }

    @Test
    void rotate_clearsRevocationExplicitly() {
        DeviceHttpCredential revoked = activeCredential("123456789012");
        revoked.setStatus("REVOKED");
        revoked.setRevokedAt(Instant.now());
        when(repository.findById("123456789012")).thenReturn(Optional.of(revoked));

        service.issue("123456789012", 9L);

        // updateById 忽略 null 清不掉 revoked_at/revoke_reason ⇒ 必须走显式 SQL
        verify(repository).clearRevocationAndRotate(eq("123456789012"), any(), eq(9L), any());
    }

    @Test
    void revoke_clearsHashAndVerifyFails() {
        DeviceHttpCredential cred = activeCredential("123456789012");
        when(repository.findById("123456789012")).thenReturn(Optional.of(cred));

        boolean revoked = service.revoke("123456789012", 9L, "设备退役");

        assertThat(revoked).isTrue();
        assertThat(cred.getStatus()).isEqualTo("REVOKED");
        assertThat(cred.getSecretSha256()).isBlank();
        // 吊销后 verify 一律 false（hash 已清，无从比对）
        assertThat(service.verify("123456789012", "whatever")).isFalse();
    }

    @Test
    void verify_acceptsOnlyMatchingActiveSecret() {
        String secret = "abc-secret";
        DeviceHttpCredential cred = activeCredential("123456789012");
        cred.setSecretSha256(DeviceHttpCredentialService.sha256Hex(secret));
        when(repository.findById("123456789012")).thenReturn(Optional.of(cred));

        assertThat(service.verify("123456789012", secret)).isTrue();
        assertThat(service.verify("123456789012", "wrong")).isFalse();
        assertThat(service.verify("123456789012", " ")).isFalse();
        assertThat(service.verify(null, secret)).isFalse();
        assertThat(service.verify("123456789012", null)).isFalse();
    }

    @Test
    void issue_rejectsNonTwelveDigitDeviceId() {
        assertThatThrownBy(() -> service.issue("EDGE-XXXXXXXXXXXX", 9L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(ApiMessages.INVALID_REQUEST);
    }

    private static DeviceHttpCredential activeCredential(String deviceId) {
        DeviceHttpCredential cred = new DeviceHttpCredential();
        cred.setDeviceId(deviceId);
        cred.setSecretSha256("hash");
        cred.setStatus("ACTIVE");
        cred.setIssuedAt(Instant.now());
        return cred;
    }
}
