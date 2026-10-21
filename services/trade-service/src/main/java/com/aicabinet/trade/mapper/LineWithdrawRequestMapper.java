package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface LineWithdrawRequestMapper extends BaseTradeMapper<LineWithdrawRequest> {

    default Optional<LineWithdrawRequest> findByRequestNo(String requestNo) {
        return Optional.ofNullable(selectOne(Wrappers.<LineWithdrawRequest>lambdaQuery()
                .eq(LineWithdrawRequest::getRequestNo, requestNo)));
    }

    /**
     * 抢「出款权」：把单据从指定状态<b>条件</b>迁移到 PAYING（与
     * {@link MerchantWithdrawRequestMapper#claimForPayout} 同形，2026-10-21 镜像）。
     *
     * <p>返回 1 才算拿到出款资格；返回 0 说明状态已被别的调用改走（并发重入 / 提现锁
     * 租约到期后的第二个请求），此时<b>绝不能</b>继续向渠道发起转账。
     *
     * <p>线长单同样没有 {@code @Version}，「先 findById 判状态再 updateById」在读-改-写之间
     * 有丢失更新窗口；提现锁租约 60s 且无看门狗，挂满 60s 后第二个请求能拿到锁。
     * 单条条件 UPDATE 由数据库行锁保证原子，是这个窗口里唯一本地可控的闸门。
     */
    default int claimForPayout(long requestId, String fromStatus, Instant now) {
        return update(null, Wrappers.<LineWithdrawRequest>lambdaUpdate()
                .set(LineWithdrawRequest::getStatus, "PAYING")
                .set(LineWithdrawRequest::getUpdatedAt, now)
                .eq(LineWithdrawRequest::getRequestId, requestId)
                .eq(LineWithdrawRequest::getStatus, fromStatus));
    }

    default List<LineWithdrawRequest> findByManagerIdOrderByCreatedAtDesc(Long managerId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 50);
        return selectList(Wrappers.<LineWithdrawRequest>lambdaQuery()
                .eq(LineWithdrawRequest::getManagerId, managerId)
                .orderByDesc(LineWithdrawRequest::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default long sumAmountByManagerSince(Long managerId, Instant since) {
        List<LineWithdrawRequest> rows = selectList(Wrappers.<LineWithdrawRequest>lambdaQuery()
                .eq(LineWithdrawRequest::getManagerId, managerId)
                .ge(LineWithdrawRequest::getCreatedAt, since)
                .in(LineWithdrawRequest::getStatus, "PENDING_REVIEW", "APPROVED", "PAYING", "PAID"));
        long sum = 0;
        for (LineWithdrawRequest row : rows) {
            if (row.getAmountCents() != null) {
                sum += row.getAmountCents();
            }
        }
        return sum;
    }

    /**
     * V308：指定打款通道当日已出/待出总额（分）—— <b>线长侧部分</b>。
     *
     * <p>🔴 <b>本方法只算线长侧</b>，商户侧在
     * {@link MerchantWithdrawRequestMapper#sumAmountByChannelSince}。
     * 两者<b>必须相加</b>才是渠道共享池的总额（微信的 5 万/日限的是整个商户号，
     * 商户提现与线长提现共用），聚合由
     * {@link com.aicabinet.trade.service.PayoutAccountService#sumPaidAmountByChannelSince} 收口。
     *
     * <p>口径（非终态集合 + {@code createdAt} 当日）必须与商户侧<b>完全一致</b>，
     * 否则两侧相加会算错。
     */
    default long sumAmountByChannelSince(String channel, Instant since) {
        if (channel == null) {
            return 0L;
        }
        List<LineWithdrawRequest> rows = selectList(Wrappers.<LineWithdrawRequest>lambdaQuery()
                .eq(LineWithdrawRequest::getPayChannel, channel)
                .ge(LineWithdrawRequest::getCreatedAt, since)
                .in(LineWithdrawRequest::getStatus, "PENDING_REVIEW", "APPROVED", "PAYING", "PAID"));
        long sum = 0L;
        for (LineWithdrawRequest row : rows) {
            if (row.getAmountCents() != null) {
                sum += row.getAmountCents();
            }
        }
        return sum;
    }

    /**
     * V308：指定收款账户当日已占用额度（分）。
     *
     * <p>⚠️ 线长账户与商户账户<b>可能指向同一人</b>（运营配错），
     * 只按主体统计会漏算 ⇒ 绕过微信「单收款人单日 ¥2000」。故按 {@code payout_account_id} 聚合。
     */
    default long sumAmountByPayeeSince(Long payoutAccountId, Instant since) {
        if (payoutAccountId == null) {
            return 0L;
        }
        List<LineWithdrawRequest> rows = selectList(Wrappers.<LineWithdrawRequest>lambdaQuery()
                .eq(LineWithdrawRequest::getPayoutAccountId, payoutAccountId)
                .ge(LineWithdrawRequest::getCreatedAt, since)
                .in(LineWithdrawRequest::getStatus, "PENDING_REVIEW", "APPROVED", "PAYING", "PAID"));
        long sum = 0L;
        for (LineWithdrawRequest row : rows) {
            if (row.getAmountCents() != null) {
                sum += row.getAmountCents();
            }
        }
        return sum;
    }

    /**
     * V308：出款侧对账 —— 指定时间窗内某状态的提现单。
     *
     * <p>按 {@code paidAt}（出款时间）而非 {@code createdAt}：申请与打款常跨天。
     */
    default List<LineWithdrawRequest> findByStatusAndPaidAtBetween(String status,
                                                                   Instant from, Instant to) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LineWithdrawRequest> q =
                Wrappers.<LineWithdrawRequest>lambdaQuery()
                        .ge(LineWithdrawRequest::getPaidAt, from)
                        .lt(LineWithdrawRequest::getPaidAt, to);
        if (status != null && !status.isBlank()) {
            q.eq(LineWithdrawRequest::getStatus, status);
        }
        return selectList(q.orderByAsc(LineWithdrawRequest::getPaidAt));
    }

    /** 指定状态且 updatedAt 早于 cutoff 的提现单（打款超时扫描用，H38）。 */
    default List<LineWithdrawRequest> findByStatusAndUpdatedAtBefore(String status, Instant cutoff) {
        return selectList(Wrappers.<LineWithdrawRequest>lambdaQuery()
                .eq(LineWithdrawRequest::getStatus, status)
                .lt(LineWithdrawRequest::getUpdatedAt, cutoff)
                .orderByAsc(LineWithdrawRequest::getUpdatedAt)
                .last("LIMIT 100"));
    }
}
