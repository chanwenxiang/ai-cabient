package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.SmsVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SmsVerificationCodeRepository extends JpaRepository<SmsVerificationCode, Long> {

    Optional<SmsVerificationCode> findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(String phoneNumber);
}
