package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, String> {
}
