package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.OpsUserMerchant;
import com.aicabinet.trade.domain.OpsUserMerchantId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OpsUserMerchantRepository extends JpaRepository<OpsUserMerchant, OpsUserMerchantId> {

    List<OpsUserMerchant> findByIdUserId(Long userId);

    void deleteByIdUserId(Long userId);

    boolean existsByIdUserId(Long userId);

    @Query("SELECT m FROM OpsUserMerchant m WHERE m.id.merchantId IN :merchantIds")
    List<OpsUserMerchant> findByMerchantIdIn(@Param("merchantIds") Collection<String> merchantIds);
}
