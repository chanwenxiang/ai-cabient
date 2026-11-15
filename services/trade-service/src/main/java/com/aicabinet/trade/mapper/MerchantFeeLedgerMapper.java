package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantFeeLedger;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface MerchantFeeLedgerMapper extends BaseTradeMapper<MerchantFeeLedger> {

    /**
     * 商户集合在 {@code [monthStart, monthEnd]} 内的某类费用实扣合计（分）。
     *
     * <p>MA-272：商户月账单的「流量费」必须读这张<b>真实扣过钱</b>的台账。
     * 原实现读 {@code device_data_fee_bill}（月度出账表，开关默认关闭）⇒ 常年 0，
     * 而日结批早就改成「先扣钱包、再写 merchant_fee_ledger」，两边各算一套，
     * 结果是商户看到的利润额里根本没减流量费。
     *
     * <p>不筛 {@code status}：日结写入的行恒为 SETTLED（行即收据，没扣到钱不留行）；
     * PENDING 只用于人工补录的历史行，钱同样已经发生，必须计入，否则补录的费用在账单里消失。
     */
    default long sumAmountByMerchantsAndTypeAndMonth(Collection<String> merchantIds, String feeType,
                                                     LocalDate monthStart, LocalDate monthEnd) {
        if (merchantIds == null || merchantIds.isEmpty() || feeType == null
                || monthStart == null || monthEnd == null) {
            return 0L;
        }
        List<MerchantFeeLedger> rows = selectList(Wrappers.<MerchantFeeLedger>lambdaQuery()
                .in(MerchantFeeLedger::getMerchantId, merchantIds)
                .eq(MerchantFeeLedger::getFeeType, feeType)
                .ge(MerchantFeeLedger::getFeeDate, monthStart)
                .le(MerchantFeeLedger::getFeeDate, monthEnd));
        long sum = 0L;
        for (MerchantFeeLedger row : rows) {
            sum += row.getAmountCents() == null ? 0L : row.getAmountCents();
        }
        return sum;
    }
}
