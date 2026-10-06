package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantWalletLedger;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface MerchantWalletLedgerMapper extends BaseTradeMapper<MerchantWalletLedger> {

    default List<MerchantWalletLedger> findByMerchantIdOrderByCreatedAtDesc(String merchantId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        return selectList(Wrappers.<MerchantWalletLedger>lambdaQuery()
                .eq(MerchantWalletLedger::getMerchantId, merchantId)
                .orderByDesc(MerchantWalletLedger::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default Optional<MerchantWalletLedger> findByRef(String merchantId, String refType, String refId) {
        return Optional.ofNullable(selectOne(Wrappers.<MerchantWalletLedger>lambdaQuery()
                .eq(MerchantWalletLedger::getMerchantId, merchantId)
                .eq(MerchantWalletLedger::getRefType, refType)
                .eq(MerchantWalletLedger::getRefId, refId)
                .last("LIMIT 1")));
    }

    /**
     * 指定时间段内某类流水的<b>总额（绝对值口径）</b>。
     *
     * <p>V308：用于平台侧汇总「提现手续费收入」（{@code entryType = WITHDRAW_FEE}）
     * 与出款侧对账。流水里手续费记的是<b>负数</b>（从商户钱包扣走），返回<b>正数</b>便于直接展示/对账。
     *
     * <p>🔴 <b>时间窗是 [from, to) 左闭右开</b>，且<b>必须有上界</b>：
     * 只传下界会把该类型<b>历史全部</b>累加进来，「当日手续费」会随数据增长而虚增。
     * 左闭右开保证相邻两天不重不漏（与 {@code paidAt} 分窗口径一致）。
     *
     * <p>⚠️ 这是<b>全平台</b>口径（不按merchantId 过滤）—— 调用方只能用于运营/对账展示，
     * <b>绝不能</b>拿它给单个商户算账（那会泄露其他商户的手续费）。
     *
     * @param to 排他上界；传 {@code null} 等于不限上界（仅供「累计至今」类场景显式使用）
     */
    default long sumAmountByEntryTypeBetween(String entryType, Instant from, Instant to) {
        if (entryType == null) {
            return 0L;
        }
        List<MerchantWalletLedger> rows = selectList(Wrappers.<MerchantWalletLedger>lambdaQuery()
                .eq(MerchantWalletLedger::getEntryType, entryType)
                .ge(MerchantWalletLedger::getCreatedAt, from)
                .lt(to != null, MerchantWalletLedger::getCreatedAt, to));
        long sum = 0L;
        for (MerchantWalletLedger row : rows) {
            if (row.getAmountCents() != null) {
                sum += Math.abs(row.getAmountCents());
            }
        }
        return sum;
    }

    /**
     * 自当日零点起累计（无上界）—— 用于「今日手续费收入」这类<b>滚动到此刻</b>的展示。
     *
     * <p>与 {@link #sumAmountByEntryTypeBetween} 的区别：<b>不设上界</b>。
     * 只适用于「今天」这种从零点算起的口径，<b>不要</b>拿它算「某历史日」——
     * 那会把该日之后的所有流水都算进来。
     */
    default long sumAmountByEntryTypeSince(String entryType, Instant since) {
        return sumAmountByEntryTypeBetween(entryType, since, null);
    }
}
