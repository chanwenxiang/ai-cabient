package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantSettlementBill;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Mapper
public interface MerchantSettlementBillMapper extends BaseTradeMapper<MerchantSettlementBill> {

    /** 联合主键点查（实体无 @TableId，禁用 selectById）。 */
    default MerchantSettlementBill findByMerchantAndPeriod(String merchantId, LocalDate periodMonth) {
        return selectOne(Wrappers.<MerchantSettlementBill>lambdaQuery()
                .eq(MerchantSettlementBill::getMerchantId, merchantId)
                .eq(MerchantSettlementBill::getPeriodMonth, periodMonth));
    }

    /** 商户集合 × 账期月区间（左闭右开）列表，月份倒序。 */
    default List<MerchantSettlementBill> listByMerchantsAndPeriodBetween(Collection<String> merchantIds,
                                                                         LocalDate fromMonthIncl,
                                                                         LocalDate toMonthExcl) {
        if (merchantIds == null || merchantIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<MerchantSettlementBill>lambdaQuery()
                .in(MerchantSettlementBill::getMerchantId, merchantIds)
                .ge(MerchantSettlementBill::getPeriodMonth, fromMonthIncl)
                .lt(MerchantSettlementBill::getPeriodMonth, toMonthExcl)
                .orderByDesc(MerchantSettlementBill::getPeriodMonth)
                .orderByAsc(MerchantSettlementBill::getMerchantId));
    }
}
