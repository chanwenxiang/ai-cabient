package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.LineWalletAccount;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LineWalletAccountMapper extends BaseTradeMapper<LineWalletAccount> {

    LineWalletAccount findByIdForUpdateRaw(@Param("managerId") long managerId);

    default Optional<LineWalletAccount> findByIdForUpdate(long managerId) {
        return Optional.ofNullable(findByIdForUpdateRaw(managerId));
    }
}
