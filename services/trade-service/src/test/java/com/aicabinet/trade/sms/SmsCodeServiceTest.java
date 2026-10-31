package com.aicabinet.trade.sms;

import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.domain.SmsVerificationCode;
import com.aicabinet.trade.mapper.SmsVerificationCodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证码校验的核心判据（MA-017，2026-10-31 R7 code review）：
 * ① 成功校验必须走**原子消费**（markUsedIfUnused），不是「查未用 → save」；
 * ② 并发下已被别的请求消费（markUsedIfUnused=false）⇒ 判失败但**不计失败次数**（不误烧下一条码）；
 * ③ 过期码判失败并标记已用、计入失败次数；
 * ④ 连续错码到上限 ⇒ 烧掉该码（标记已用）并清空计数。
 */
@ExtendWith(MockitoExtension.class)
class SmsCodeServiceTest {

    private static final String PHONE = "13900001111";
    private static final String CODE = "123456";

    @Mock private SmsSender smsSender;
    @Mock private SmsVerificationCodeMapper codeRepository;
    @Mock private RedissonClient redisson;
    @Mock private RAtomicLong failCounter;
    @Mock private RBucket<String> bucket;

    private SmsCodeService service;

    @BeforeEach
    void setUp() {
        AuthProperties auth = new AuthProperties(
                "secret", 3600, false, false, 5, 15,
                new AuthProperties.SmsProperties(null, 300, null, "webhook", null, null, null, null, null),
                86_400L);
        service = new SmsCodeService(auth, new SecurityProperties(false), smsSender, codeRepository, redisson);
        lenient().when(redisson.getAtomicLong(anyString())).thenReturn(failCounter);
        // getBucket 是泛型方法，用 doReturn 绕过类型推断（RBucket<String> vs RBucket<Object>）
        lenient().doReturn(bucket).when(redisson).getBucket(anyString(), any());
        lenient().when(bucket.isExists()).thenReturn(false);
    }

    private SmsVerificationCode entry(Instant expiresAt) {
        SmsVerificationCode e = new SmsVerificationCode();
        e.setId(1L);
        e.setPhoneNumber(PHONE);
        e.setCode(CODE);
        e.setExpiresAt(expiresAt);
        return e;
    }

    @Test
    @DisplayName("正确码：走原子消费并清失败计数")
    void verify_correctCode_consumesAtomically() {
        when(codeRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(entry(Instant.now().plusSeconds(60))));
        when(codeRepository.markUsedIfUnused(1L)).thenReturn(true);

        assertTrue(service.verifyCode(PHONE, CODE));
        verify(codeRepository).markUsedIfUnused(1L);
        verify(codeRepository, never()).save(any());
        verify(failCounter).delete();
    }

    @Test
    @DisplayName("并发已被消费：判失败但不计失败次数（不误烧新码）")
    void verify_raceAlreadyConsumed_doesNotCountFailure() {
        when(codeRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(entry(Instant.now().plusSeconds(60))));
        when(codeRepository.markUsedIfUnused(1L)).thenReturn(false);

        assertFalse(service.verifyCode(PHONE, CODE));
        verify(failCounter, never()).incrementAndGet();
        verify(failCounter, never()).delete();
    }

    @Test
    @DisplayName("过期码：判失败、标记已用、计一次失败")
    void verify_expired_marksUsedAndCountsFailure() {
        SmsVerificationCode e = entry(Instant.now().minusSeconds(1));
        when(codeRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(e));
        when(failCounter.incrementAndGet()).thenReturn(1L);

        assertFalse(service.verifyCode(PHONE, CODE));
        verify(codeRepository).save(e);
        assertTrue(e.getUsedAt() != null, "过期码也应标记 used_at");
    }

    @Test
    @DisplayName("错码达上限：烧掉该码并清空计数")
    void verify_wrongCode_fifthFailureBurnsCode() {
        SmsVerificationCode e = entry(Instant.now().plusSeconds(60));
        when(codeRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(PHONE))
                .thenReturn(Optional.of(e));
        when(failCounter.incrementAndGet()).thenReturn(5L);

        assertFalse(service.verifyCode(PHONE, "000000"));
        verify(codeRepository).save(e);
        assertTrue(e.getUsedAt() != null, "第 5 次错码应把当前码烧掉");
        verify(failCounter).delete();
    }
}
