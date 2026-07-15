package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserAccount;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserAccountMapper extends BaseTradeMapper<UserAccount> {

        UserAccount _findByIdForUpdateRaw(@Param("userId") Long userId);

    default Optional<UserAccount> findByIdForUpdate(@Param("userId") Long userId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(userId));
    }

}
