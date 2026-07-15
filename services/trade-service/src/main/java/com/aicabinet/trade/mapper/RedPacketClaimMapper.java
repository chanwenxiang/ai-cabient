package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.RedPacketClaim;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface RedPacketClaimMapper extends BaseTradeMapper<RedPacketClaim> {

    default List<RedPacketClaim> findByPacketId(Long packetId) {
    return selectList(Wrappers.<RedPacketClaim>lambdaQuery().eq(RedPacketClaim::getPacketId, packetId));
    }

    default List<RedPacketClaim> findByUserId(Long userId) {
    return selectList(Wrappers.<RedPacketClaim>lambdaQuery().eq(RedPacketClaim::getUserId, userId));
    }

    default Optional<RedPacketClaim> findByPacketIdAndUserId(Long packetId, Long userId) {
    return Optional.ofNullable(selectOne(Wrappers.<RedPacketClaim>lambdaQuery().eq(RedPacketClaim::getPacketId, packetId).eq(RedPacketClaim::getUserId, userId)));
    }

}
