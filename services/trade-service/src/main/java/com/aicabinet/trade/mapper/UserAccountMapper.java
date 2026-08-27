package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserAccount;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserAccountMapper extends BaseTradeMapper<UserAccount> {

    default List<UserAccount> findByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<UserAccount>lambdaQuery()
                .in(UserAccount::getUserId, userIds));
    }

        UserAccount findByIdForUpdateRaw(@Param("userId") Long userId);

    default Optional<UserAccount> findByIdForUpdate(@Param("userId") Long userId) {
        return Optional.ofNullable(findByIdForUpdateRaw(userId));
    }

}
