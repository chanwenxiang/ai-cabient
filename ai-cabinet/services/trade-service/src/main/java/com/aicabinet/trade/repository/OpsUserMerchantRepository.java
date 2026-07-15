package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.OpsUserMerchant;
import com.aicabinet.trade.domain.OpsUserMerchantId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OpsUserMerchantRepository extends JpaRepository<OpsUserMerchant, OpsUserMerchantId> {

    List<OpsUserMerchant> findByIdUserId(Long userId);

    void deleteByIdUserId(Long userId);

    boolean existsByIdUserId(Long userId);
}
