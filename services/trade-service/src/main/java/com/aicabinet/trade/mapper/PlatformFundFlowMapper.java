package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PlatformFundFlow;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface PlatformFundFlowMapper extends BaseTradeMapper<PlatformFundFlow> {

    default List<PlatformFundFlow> findByAccountIdOrderByCreatedDesc(long accountId, int limit) {
        return selectList(Wrappers.<PlatformFundFlow>lambdaQuery()
                .eq(PlatformFundFlow::getAccountId, accountId)
                .orderByDesc(PlatformFundFlow::getCreatedAt)
                .last("LIMIT " + limit));
    }

    /** 指定类型在 [from, to) 区间内的带符号金额合计（归集核对用：本月 SWEEP 合计）。 */
    @Select("SELECT COALESCE(SUM(amount_cents), 0) FROM platform_fund_flow "
            + "WHERE flow_type = #{flowType} AND created_at >= #{from} AND created_at < #{to}")
    long sumAmountByTypeBetween(String flowType, Instant from, Instant to);

    @Select("SELECT COALESCE(SUM(amount_cents), 0) FROM platform_fund_flow "
            + "WHERE account_id = #{accountId} AND created_at >= #{from} AND created_at < #{to}")
    long sumAmountByAccountIdBetween(long accountId, Instant from, Instant to);

    @Select("SELECT COUNT(*) FROM platform_fund_flow "
            + "WHERE ref_type = #{refType} AND ref_id = #{refId}")
    long countByRefTypeAndRefId(String refType, String refId);

    @Select("SELECT COUNT(*) FROM platform_fund_flow "
            + "WHERE flow_type = #{flowType} AND ref_id = #{refId}")
    long countByFlowTypeAndRefId(String flowType, String refId);

    /** 指定类型在区间内的流水条数（0 = 尚未登记过归集，核对软启动用）。 */
    @Select("SELECT COUNT(*) FROM platform_fund_flow "
            + "WHERE flow_type = #{flowType} AND created_at >= #{from} AND created_at < #{to}")
    long countByTypeBetween(String flowType, Instant from, Instant to);
}
