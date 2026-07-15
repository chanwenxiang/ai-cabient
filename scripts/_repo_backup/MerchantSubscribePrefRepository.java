package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.MerchantSubscribePref;
import com.aicabinet.trade.domain.MerchantSubscribePrefId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MerchantSubscribePrefRepository extends JpaRepository<MerchantSubscribePref, MerchantSubscribePrefId> {

    List<MerchantSubscribePref> findByIdUserId(Long userId);

    List<MerchantSubscribePref> findByIdUserIdAndEnabledTrue(Long userId);
}
