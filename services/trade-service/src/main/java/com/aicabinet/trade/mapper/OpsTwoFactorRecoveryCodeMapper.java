package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsTwoFactorRecoveryCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface OpsTwoFactorRecoveryCodeMapper extends BaseTradeMapper<OpsTwoFactorRecoveryCode> {

    default List<OpsTwoFactorRecoveryCode> findByUserId(Long userId) {
        return selectList(Wrappers.<OpsTwoFactorRecoveryCode>lambdaQuery()
                .eq(OpsTwoFactorRecoveryCode::getUserId, userId));
    }

    default void deleteByUserId(Long userId) {
        delete(Wrappers.<OpsTwoFactorRecoveryCode>lambdaQuery()
                .eq(OpsTwoFactorRecoveryCode::getUserId, userId));
    }
}
