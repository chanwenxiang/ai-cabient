package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.PaymentPlatformBillLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentPlatformBillLineRepository extends JpaRepository<PaymentPlatformBillLine, Long> {
    List<PaymentPlatformBillLine> findByReconId(Long reconId);
}
