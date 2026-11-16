package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.RefundFlow;
import org.apache.ibatis.annotations.Select;

import java.util.LinkedHashMap;

public interface RefundFlowMapper extends BaseTradeMapper<RefundFlow> {

    /** 单笔流水（巡检/对账用）。 */
    @Select("SELECT * FROM refund_flow WHERE flow_id = #{flowId}")
    LinkedHashMap<String, Object> selectRawById(Long flowId);
}
