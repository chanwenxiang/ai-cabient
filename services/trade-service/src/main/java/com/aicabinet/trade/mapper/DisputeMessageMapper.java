package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DisputeMessage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DisputeMessageMapper extends BaseTradeMapper<DisputeMessage> {

    default List<DisputeMessage> findByTicketIdOrderByCreatedAtAsc(String ticketId) {
    return selectList(Wrappers.<DisputeMessage>lambdaQuery().eq(DisputeMessage::getTicketId, ticketId).orderByAsc(DisputeMessage::getCreatedAt));
    }

    default boolean existsByTicketIdAndAuthorType(String ticketId, String authorType) {
    return selectCount(Wrappers.<DisputeMessage>lambdaQuery().eq(DisputeMessage::getTicketId, ticketId).eq(DisputeMessage::getAuthorType, authorType)) > 0;
    }

}
