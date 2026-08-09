package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserBlacklist;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.Optional;

@Mapper
public interface UserBlacklistMapper extends BaseTradeMapper<UserBlacklist> {

    /** 当前生效中的黑名单记录（未过期或未设过期时间）。 */
    default Optional<UserBlacklist> findActiveByUserId(Long userId) {
        Instant now = Instant.now();
        return Optional.ofNullable(selectOne(Wrappers.<UserBlacklist>lambdaQuery()
                .eq(UserBlacklist::getUserId, userId)
                .and(w -> w.isNull(UserBlacklist::getExpiresAt).or().gt(UserBlacklist::getExpiresAt, now))
                .last("LIMIT 1")));
    }
}
