package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class OperatorAuth {

    private OperatorAuth() {}

    public static boolean isOperator(Long userId) {
        return userId != null && userId >= CabinetConstants.OPERATOR_USER_ID_START;
    }

    public static void requireOperator(Long userId) {
        if (!isOperator(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.OPERATOR_REQUIRED);
        }
    }
}
