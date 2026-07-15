package com.aicabinet.trade.service;

/** 识别需人工审核：业务上非失败，事务不应回滚。 */
public class DisputeRequiredException extends RuntimeException {

    public DisputeRequiredException(String message) {
        super(message);
    }
}
