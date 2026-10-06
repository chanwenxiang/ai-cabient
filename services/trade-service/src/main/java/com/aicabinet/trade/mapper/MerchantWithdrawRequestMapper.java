package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface MerchantWithdrawRequestMapper extends BaseTradeMapper<MerchantWithdrawRequest> {

    default Optional<MerchantWithdrawRequest> findByRequestNo(String requestNo) {
        return Optional.ofNullable(selectOne(Wrappers.<MerchantWithdrawRequest>lambdaQuery()
                .eq(MerchantWithdrawRequest::getRequestNo, requestNo)));
    }

    default List<MerchantWithdrawRequest> findByMerchantIdOrderByCreatedAtDesc(String merchantId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 50);
        return selectList(Wrappers.<MerchantWithdrawRequest>lambdaQuery()
                .eq(MerchantWithdrawRequest::getMerchantId, merchantId)
                .orderByDesc(MerchantWithdrawRequest::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default long sumAmountByMerchantSince(String merchantId, Instant since) {
        List<MerchantWithdrawRequest> rows = selectList(Wrappers.<MerchantWithdrawRequest>lambdaQuery()
                .eq(MerchantWithdrawRequest::getMerchantId, merchantId)
                .ge(MerchantWithdrawRequest::getCreatedAt, since)
                .in(MerchantWithdrawRequest::getStatus, "PENDING_REVIEW", "APPROVED", "PAYING", "PAID"));
        long sum = 0;
        for (MerchantWithdrawRequest row : rows) {
            if (row.getAmountCents() != null) {
                sum += row.getAmountCents();
            }
        }
        return sum;
    }

    /**
     * 指定收款账户在 since 之后已占用的当日额度（分）。
     *
     * <p>🔴 <b>口径必须与 {@link #sumAmountByMerchantSince} 完全一致</b>（含终态集合），
     * 否则会出现「商户维度拦得住、账户维度算少了」⇒ 绕过单用户单日限额。
     * 两处若要改，<b>必须同时改</b>。
     */
    default long sumAmountByPayeeSince(Long payoutAccountId, Instant since) {
        if (payoutAccountId == null) {
            return 0L;
        }
        List<MerchantWithdrawRequest> rows = selectList(Wrappers.<MerchantWithdrawRequest>lambdaQuery()
                .eq(MerchantWithdrawRequest::getPayoutAccountId, payoutAccountId)
                .ge(MerchantWithdrawRequest::getCreatedAt, since)
                .in(MerchantWithdrawRequest::getStatus, "PENDING_REVIEW", "APPROVED", "PAYING", "PAID"));
        long sum = 0;
        for (MerchantWithdrawRequest row : rows) {
            if (row.getAmountCents() != null) {
                sum += row.getAmountCents();
            }
        }
        return sum;
    }

    /**
     * 指定打款通道当日已出/待出总额（分）—— 对应微信「单商户号单日最高转账额度」这个<b>共享池</b>。
     *
     * <p>🔴 这是<b>全平台跨商户</b>口径：微信的 5 万/日是<b>整个商户号共用</b>的，
     * 不是每个商户各一份。按商户各自统计会严重高估可用额度。
     *
     * <p>📌 若将来一个部署多套微信商户号，需把维度改成「按商户号分组」——
     * 现在单商户号部署，等价。
     */
    default long sumAmountByChannelSince(String channel, Instant since) {
        if (channel == null) {
            return 0L;
        }
        List<MerchantWithdrawRequest> rows = selectList(Wrappers.<MerchantWithdrawRequest>lambdaQuery()
                .eq(MerchantWithdrawRequest::getPayChannel, channel)
                .ge(MerchantWithdrawRequest::getCreatedAt, since)
                .in(MerchantWithdrawRequest::getStatus, "PENDING_REVIEW", "APPROVED", "PAYING", "PAID"));
        long sum = 0;
        for (MerchantWithdrawRequest row : rows) {
            if (row.getAmountCents() != null) {
                sum += row.getAmountCents();
            }
        }
        return sum;
    }

    /**
     * V308：出款侧对账 —— 指定时间窗内某状态的提现单。
     *
     * <p>按 {@code paidAt}（出款时间）而非 {@code createdAt}（申请时间）过滤：
     * 申请与打款可能跨天（T+1 审核、人工复核），按申请时间会把昨天的单算进今天。
     *
     * @param status 终态过滤（如 {@code PAID}）；null/空表示不过滤
     */
    default List<MerchantWithdrawRequest> findByStatusAndPaidAtBetween(String status,
                                                                      Instant from, Instant to) {
        LambdaQueryWrapper<MerchantWithdrawRequest> q = Wrappers.<MerchantWithdrawRequest>lambdaQuery()
                .ge(MerchantWithdrawRequest::getPaidAt, from)
                .lt(MerchantWithdrawRequest::getPaidAt, to);
        if (status != null && !status.isBlank()) {
            q.eq(MerchantWithdrawRequest::getStatus, status);
        }
        return selectList(q.orderByAsc(MerchantWithdrawRequest::getPaidAt));
    }

    /** 指定状态且 updatedAt 早于 cutoff 的提现单（打款超时扫描用，H38）。 */    default List<MerchantWithdrawRequest> findByStatusAndUpdatedAtBefore(String status, Instant cutoff) {
        return selectList(Wrappers.<MerchantWithdrawRequest>lambdaQuery()
                .eq(MerchantWithdrawRequest::getStatus, status)
                .lt(MerchantWithdrawRequest::getUpdatedAt, cutoff)
                .orderByAsc(MerchantWithdrawRequest::getUpdatedAt)
                .last("LIMIT 100"));
    }
}
