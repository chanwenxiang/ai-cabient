package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.GroupBuy;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GroupBuyMapper extends BaseTradeMapper<GroupBuy> {

    default List<GroupBuy> findByStatus(String status) {
    return selectList(Wrappers.<GroupBuy>lambdaQuery().eq(GroupBuy::getStatus, status));
    }

        List<GroupBuy> findActive(Instant now);

}
