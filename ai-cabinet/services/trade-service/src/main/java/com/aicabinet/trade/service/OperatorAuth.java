package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class OperatorAuth {

    private OperatorAuth() {}

    public static void requireOperator(Long userId) {
        if (userId == null || userId < CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.OPERATOR_REQUIRED);
        }
    }
}
