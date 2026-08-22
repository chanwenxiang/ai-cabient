package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserBlacklist;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Mapper
public interface UserBlacklistMapper extends BaseTradeMapper<UserBlacklist> {

    UserBlacklist _findByIdForUpdateRaw(@Param("userId") Long userId);

    default Optional<UserBlacklist> findByIdForUpdate(Long userId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(userId));
    }

    /** 当前生效中的黑名单记录（未过期或未设过期时间）。 */
    default Optional<UserBlacklist> findActiveByUserId(Long userId) {
        Instant now = Instant.now();
        return Optional.ofNullable(selectOne(Wrappers.<UserBlacklist>lambdaQuery()
                .eq(UserBlacklist::getUserId, userId)
                .and(w -> w.isNull(UserBlacklist::getExpiresAt).or().gt(UserBlacklist::getExpiresAt, now))
                .last("LIMIT 1")));
    }

    /** 批量取生效中的黑名单用户 ID（未过期或未设过期时间）。 */
    default Set<Long> findActiveUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        Instant now = Instant.now();
        List<UserBlacklist> rows = selectList(Wrappers.<UserBlacklist>lambdaQuery()
                .in(UserBlacklist::getUserId, userIds)
                .and(w -> w.isNull(UserBlacklist::getExpiresAt).or().gt(UserBlacklist::getExpiresAt, now)));
        Set<Long> out = new HashSet<>();
        for (UserBlacklist row : rows) {
            out.add(row.getUserId());
        }
        return out;
    }
}
