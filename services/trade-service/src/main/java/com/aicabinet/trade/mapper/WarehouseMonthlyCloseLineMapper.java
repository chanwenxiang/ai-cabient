package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseMonthlyCloseLine;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;

public interface WarehouseMonthlyCloseLineMapper extends BaseTradeMapper<WarehouseMonthlyCloseLine> {

    /**
     * 写路径行锁读（MA-103）。走 MP 的 wrapper + {@code FOR UPDATE}，让结果集沿用实体 resultMap，
     * 不另写 {@code @Select}（避免列名→驼峰映射与实体漂移出第二套真相）。
     *
     * <p>为什么必须锁「行」：{@code disposeLine} 原来是 {@code selectById → 判 gapDisposition=PENDING →
     * 进索赔台账（insert inventory_write_off）→ updateById}。两个并发处置同一行时都读到 PENDING，
     * 于是同一条盘亏被**挂两次索赔**——索赔台账是资金动作，且处置设计上「不可逆」。
     * 行锁把判与改锁在同一行上：后到的事务醒来时该行已是 CLAIM ⇒ 走既有的「处置不可逆」409。
     */
    default Optional<WarehouseMonthlyCloseLine> findByIdForUpdate(Long lineId) {
        return Optional.ofNullable(selectOne(
                Wrappers.<WarehouseMonthlyCloseLine>lambdaQuery()
                        .eq(WarehouseMonthlyCloseLine::getLineId, lineId)
                        .last("FOR UPDATE")));
    }
}
