package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 分账回退「腿」表（R20-9 / V463 / CB-085）。
 *
 * <p>🔴 资金约定：金额只从 {@code cents} 读，任何调用方不得再拿
 * {@code OrderRevenueSplit.wechatPendingReturnCents} 去提交渠道回退。
 * 同一 {@code out_return_no} 只允许一条腿；SUCCESS 是终态，条件更新
 * {@code WHERE status <> 'SUCCESS'} 防止已成功的腿被后续 PROCESSING/FAILED 复活
 * （铁律 87：状态机用白名单枚举，不靠黑名单）。
 */
@Mapper
public interface OrderSplitReturnLegMapper extends BaseTradeMapper<OrderSplitReturnLeg> {

    /** 未完结状态白名单；partial index 谓词与这里逐字一致（铁律 88①）。 */
    List<String> OPEN_STATUSES = List.of("PENDING", "PROCESSING", "FAILED");

    default OrderSplitReturnLeg findByOutReturnNo(String outReturnNo) {
        if (outReturnNo == null || outReturnNo.isBlank()) {
            return null;
        }
        return selectOne(Wrappers.<OrderSplitReturnLeg>lambdaQuery()
                .eq(OrderSplitReturnLeg::getOutReturnNo, outReturnNo)
                .last("LIMIT 1"));
    }

    /** 某 split 的全部未完结腿，按 leg_id 升序（最老的一条就是投影列的来源）。 */
    default List<OrderSplitReturnLeg> findOpenBySplitId(String splitId) {
        if (splitId == null || splitId.isBlank()) {
            return List.of();
        }
        return selectList(Wrappers.<OrderSplitReturnLeg>lambdaQuery()
                .eq(OrderSplitReturnLeg::getSplitId, splitId)
                .in(OrderSplitReturnLeg::getStatus, OPEN_STATUSES)
                .orderByAsc(OrderSplitReturnLeg::getLegId));
    }

    /**
     * 全局扫未完结腿，供定时兜底轮询/重试。
     * 🔴 必须自带 ORDER BY（铁律 88②：无排序的批量取会让处理顺序随执行计划漂移）；
     * 谓词与 idx_split_return_leg_open 的 partial index 谓词完全一致。
     */
    @Select("""
            SELECT * FROM order_split_return_leg
            WHERE status IN ('PENDING','PROCESSING','FAILED')
            ORDER BY leg_id ASC
            LIMIT #{limit}
            """)
    List<OrderSplitReturnLeg> findOpenLegs(@Param("limit") int limit);

    /** 守恒判据：该 split 已在渠道确认成功的回退总额。 */
    @Select("""
            SELECT COALESCE(SUM(cents), 0) FROM order_split_return_leg
            WHERE split_id = #{splitId} AND status = 'SUCCESS'
            """)
    long sumSuccessCents(@Param("splitId") String splitId);

    /** 告警用：该 split 仍未完结的回退总额（多腿累加，不再取单值列）。 */
    @Select("""
            SELECT COALESCE(SUM(cents), 0) FROM order_split_return_leg
            WHERE split_id = #{splitId} AND status IN ('PENDING','PROCESSING','FAILED')
            """)
    long sumOpenCents(@Param("splitId") String splitId);

    /**
     * 插入一条新腿；撞同号（并发/回放）直接什么都不做，由调用方按 out_return_no 回读。
     * 不用 MP insert 是为了让唯一约束冲突在同一条 SQL 里消解，避免并发下抛异常中断退款事务。
     */
    @Insert("""
            INSERT INTO order_split_return_leg (split_id, out_return_no, cents, status, failure_reason)
            VALUES (#{leg.splitId}, #{leg.outReturnNo}, #{leg.cents}, #{leg.status}, #{leg.failureReason})
            ON CONFLICT (out_return_no) DO NOTHING
            """)
    int insertLegIfAbsent(@Param("leg") OrderSplitReturnLeg leg);

    /** 只推进未成功的腿；返回 0 行表示该腿已终态，调用方必须放弃这次结果（铁律 87②）。 */
    @Update("""
            UPDATE order_split_return_leg
            SET status = #{status}, failure_reason = #{failureReason}, updated_at = NOW()
            WHERE leg_id = #{legId} AND status <> 'SUCCESS'
            """)
    int updateOutcomeIfNotSuccess(@Param("legId") long legId,
                                  @Param("status") String status,
                                  @Param("failureReason") String failureReason);
}
