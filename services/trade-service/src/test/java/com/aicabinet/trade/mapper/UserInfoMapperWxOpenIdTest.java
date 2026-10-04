package com.aicabinet.trade.mapper;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.UserInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserInfoMapperWxOpenIdTest {

    @Test
    void pickWxOpenIdMatch_prefersConsumerOverOperator() {
        UserInfo operator = new UserInfo();
        operator.setUserId(1L);
        operator.setAccountType(CabinetConstants.ACCOUNT_TYPE_OPERATOR);
        UserInfo consumer = new UserInfo();
        consumer.setUserId(99L);
        consumer.setAccountType(CabinetConstants.ACCOUNT_TYPE_CONSUMER);

        var picked = UserInfoMapper.pickWxOpenIdMatch(List.of(operator, consumer));
        assertTrue(picked.isPresent());
        assertEquals(99L, picked.get().getUserId());
    }

    @Test
    void pickWxOpenIdMatch_empty() {
        assertTrue(UserInfoMapper.pickWxOpenIdMatch(List.of()).isEmpty());
    }
}
