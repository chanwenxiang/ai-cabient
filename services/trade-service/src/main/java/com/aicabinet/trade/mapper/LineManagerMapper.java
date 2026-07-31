package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.LineManager;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface LineManagerMapper extends BaseTradeMapper<LineManager> {

    default Optional<LineManager> findByPhone(String phone) {
        return Optional.ofNullable(selectOne(Wrappers.<LineManager>lambdaQuery().eq(LineManager::getPhone, phone)));
    }

    default Optional<LineManager> findByUserId(Long userId) {
        return Optional.ofNullable(selectOne(Wrappers.<LineManager>lambdaQuery().eq(LineManager::getUserId, userId)));
    }

    default List<LineManager> findByStatus(String status) {
        return selectList(Wrappers.<LineManager>lambdaQuery().eq(LineManager::getStatus, status));
    }
}
