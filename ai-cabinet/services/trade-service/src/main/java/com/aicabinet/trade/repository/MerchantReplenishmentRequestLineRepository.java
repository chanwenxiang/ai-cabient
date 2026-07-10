package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.MerchantReplenishmentRequestLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MerchantReplenishmentRequestLineRepository extends JpaRepository<MerchantReplenishmentRequestLine, Long> {

    List<MerchantReplenishmentRequestLine> findByRequestIdOrderByLineIdAsc(Long requestId);
}
