package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantReplenishmentRequestLine;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MerchantReplenishmentRequestLineMapper extends BaseTradeMapper<MerchantReplenishmentRequestLine> {

    default List<MerchantReplenishmentRequestLine> findByRequestIdOrderByLineIdAsc(Long requestId) {
    return selectList(Wrappers.<MerchantReplenishmentRequestLine>lambdaQuery().eq(MerchantReplenishmentRequestLine::getRequestId, requestId).orderByAsc(MerchantReplenishmentRequestLine::getLineId));
    }

}
