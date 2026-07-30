package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.FinanceMarginDailyLock;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.LocalDate;
import java.util.List;

public interface FinanceMarginDailyLockMapper extends BaseTradeMapper<FinanceMarginDailyLock> {
    default List<FinanceMarginDailyLock> findByBizDateBetween(LocalDate from, LocalDate to) {
        return selectList(Wrappers.<FinanceMarginDailyLock>lambdaQuery()
                .ge(FinanceMarginDailyLock::getBizDate, from)
                .le(FinanceMarginDailyLock::getBizDate, to)
                .orderByDesc(FinanceMarginDailyLock::getBizDate));
    }
}
