package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.RedPacket;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RedPacketMapper extends BaseTradeMapper<RedPacket> {

    default Optional<RedPacket> findByPacketCode(String packetCode) {
    return Optional.ofNullable(selectOne(Wrappers.<RedPacket>lambdaQuery().eq(RedPacket::getPacketCode, packetCode)));
    }

    default List<RedPacket> findBySenderId(Long senderId) {
    return selectList(Wrappers.<RedPacket>lambdaQuery().eq(RedPacket::getSenderId, senderId));
    }

    default List<RedPacket> findByStatus(String status) {
    return selectList(Wrappers.<RedPacket>lambdaQuery().eq(RedPacket::getStatus, status));
    }

}
