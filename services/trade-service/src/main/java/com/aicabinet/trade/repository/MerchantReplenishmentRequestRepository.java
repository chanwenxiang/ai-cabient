package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.MerchantReplenishmentRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface MerchantReplenishmentRequestRepository extends JpaRepository<MerchantReplenishmentRequest, Long> {

    List<MerchantReplenishmentRequest> findByDeviceIdInOrderBySubmittedAtDesc(Collection<String> deviceIds);

    List<MerchantReplenishmentRequest> findByStatusOrderBySubmittedAtAsc(String status);

    List<MerchantReplenishmentRequest> findByMerchantIdInOrderBySubmittedAtDesc(Collection<String> merchantIds);
}
