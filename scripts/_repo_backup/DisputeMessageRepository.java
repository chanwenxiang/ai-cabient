package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DisputeMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeMessageRepository extends JpaRepository<DisputeMessage, Long> {

    List<DisputeMessage> findByTicketIdOrderByCreatedAtAsc(String ticketId);

    boolean existsByTicketIdAndAuthorType(String ticketId, String authorType);
}
