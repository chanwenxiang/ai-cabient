package com.aicabinet.trade.service;

/** 结算余额不足：须转人工争议，事务不应回滚已关门状态。 */
public class BalanceInsufficientException extends RuntimeException {

    public BalanceInsufficientException(String message) {
        super(message);
    }
}
