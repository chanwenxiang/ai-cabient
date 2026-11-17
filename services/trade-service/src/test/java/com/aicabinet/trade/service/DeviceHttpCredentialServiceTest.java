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

    @Mock
    private com.aicabinet.trade.mapper.DeviceInfoMapper deviceRepository;

    private DeviceHttpCredentialService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(deviceRepository.selectById(any())).thenReturn(
                org.mockito.Mockito.mock(com.aicabinet.trade.domain.DeviceInfo.class));
        service = new DeviceHttpCredentialService(repository, deviceRepository);
    }

    @Test
    void issue_storesHashOnlyAndReturnsPlaintextOnce() {
        when(repository.findById("123456789012")).thenReturn(Optional.empty());

        DeviceHttpCredentialService.IssuedCredential issued =
                service.issue("123456789012", 9L, false);

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

        service.issue("123456789012", 9L, true);

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
        assertThatThrownBy(() -> service.issue("EDGE-XXXXXXXXXXXX", 9L, false))
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

    @Test
    void issue_unknownDevice_rejectedWithNotFound() {
        // MA-119：deviceId 只要 12 位数字就放行过 —— 手滑一位即签出一条 ACTIVE 孤儿凭据（明文 secret 还会回显）。
        when(deviceRepository.selectById(any())).thenReturn(null);
        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> service.issue("123456789012", 1L, false));
        assertThat(ex.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        assertThat(ex.getReason()).contains("设备不存在");
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    // ── L-09（2026-11-17）：轮换必须显式 + 行已存在一律 UPDATE ────────────────────

    /**
     * 静默轮换是「运营以为在补发、实际把在线柜机推进 401」的成因：
     * 明文只在签发响应回一次，现场拿不到新 secret ⇒ 设备静默掉线。
     */
    @Test
    void issue_existingLiveSecret_withoutExplicitRotate_is409AndTouchesNothing() {
        when(repository.findById("123456789012"))
                .thenReturn(Optional.of(activeCredential("123456789012")));

        assertThatThrownBy(() -> service.issue("123456789012", 9L, false))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));

        verify(repository, org.mockito.Mockito.never()).clearRevocationAndRotate(any(), any(), any(), any());
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    /**
     * 吊销后重新签发是常规运维动作：{@code revoke} 把 hash 清成空串，旧判据据此认定
     * 「没有凭据」而走 insert ⇒ 撞 device_id 主键 500。修法=按「行是否存在」分流。
     */
    @Test
    void issue_afterRevoke_updatesExistingRowInsteadOfInserting() {
        DeviceHttpCredential revoked = activeCredential("123456789012");
        revoked.setStatus("REVOKED");
        revoked.setSecretSha256("");
        revoked.setRevokedAt(Instant.now());
        when(repository.findById("123456789012")).thenReturn(Optional.of(revoked));

        DeviceHttpCredentialService.IssuedCredential issued = service.issue("123456789012", 9L, false);

        assertThat(issued.secret()).isNotBlank();
        verify(repository).clearRevocationAndRotate(eq("123456789012"), eq(issued.secretSha256()), eq(9L), any());
        // 主键已存在的行绝不能再 insert
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void issue_brandNewDevice_stillInserts() {
        when(repository.findById("123456789012")).thenReturn(Optional.empty());

        service.issue("123456789012", 9L, false);

        verify(repository).save(any());
        verify(repository, org.mockito.Mockito.never()).clearRevocationAndRotate(any(), any(), any(), any());
    }
}
