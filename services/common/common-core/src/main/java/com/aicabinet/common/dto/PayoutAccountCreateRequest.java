package com.aicabinet.common.dto;

/** 新增收款账户请求（accountNo 明文只在请求体出现一次，服务端立即加密）。 */
public record PayoutAccountCreateRequest(
        String ownerType,
        String ownerId,
        String accountType,
        String channel,
        String accountName,
        String accountNo,
        String bankName,
        String bankBranch,
        String taxNo,
        Boolean asDefault
) {}
