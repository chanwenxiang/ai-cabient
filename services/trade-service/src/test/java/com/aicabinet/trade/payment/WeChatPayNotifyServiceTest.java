package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M8: 微信回调 nonce 防重放。 */
@ExtendWith(MockitoExtension.class)
class WeChatPayNotifyServiceTest {

    @Mock WeChatPayV3Client v3Client;
    @Mock WeChatPayV3Aead aead;
    @Mock RedissonClient redisson;
    @Mock RBucket<String> nonceBucket;

    private WeChatPayNotifyService service;

    @BeforeEach
    void setUp() {
        WeChatPayProperties properties = new WeChatPayProperties(
                true, "wx-app", "mch-1", "https://example.com/notify",
                "0123456789abcdef0123456789abcdef", "serial", "-----BEGIN PRIVATE KEY-----\nKEY\n-----END PRIVATE KEY-----",
                "cert", true);
        service = new WeChatPayNotifyService(properties, v3Client, aead, new ObjectMapper(), redisson);
        org.mockito.Mockito.lenient().when(redisson.getBucket(anyString(), eq(StringCodec.INSTANCE)))
                .thenAnswer(inv -> nonceBucket);
    }

    @Test
    void parseAndVerify_nonceReplay_rejectedBeforeSignatureCheck() {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        when(nonceBucket.setIfAbsent(eq("1"), any(Duration.class))).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.parseAndVerify("{}", ts, "replay-nonce-1", "sig", "serial"));

        assertEquals(ApiMessages.WECHAT_NOTIFY_REPLAY, ex.getMessage());
        verify(v3Client, never()).verifyNotifySignature(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(aead, never()).decrypt(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void parseAndVerify_firstNonce_continuesToSignature() {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        when(nonceBucket.setIfAbsent(eq("1"), any(Duration.class))).thenReturn(true);
        when(v3Client.verifyNotifySignature(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.parseAndVerify("{}", ts, "fresh-nonce-1", "bad-sig", "serial"));

        assertEquals(ApiMessages.INVALID_WECHAT_NOTIFY, ex.getMessage());
        verify(nonceBucket).setIfAbsent(eq("1"), any(Duration.class));
        verify(v3Client).verifyNotifySignature(eq(ts), eq("fresh-nonce-1"), eq("{}"), eq("bad-sig"), eq("serial"));
    }
}
