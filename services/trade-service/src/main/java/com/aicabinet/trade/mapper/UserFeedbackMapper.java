package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserFeedback;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserFeedbackMapper extends BaseTradeMapper<UserFeedback> {

    default List<UserFeedback> findAllOrderByCreatedAtDesc() {
        return selectList(Wrappers.<UserFeedback>lambdaQuery().orderByDesc(UserFeedback::getCreatedAt));
    }

    default List<UserFeedback> findByStatusOrderByCreatedAtDesc(String status) {
        return selectList(Wrappers.<UserFeedback>lambdaQuery()
                .eq(UserFeedback::getStatus, status)
                .orderByDesc(UserFeedback::getCreatedAt));
    }

    default List<UserFeedback> findByUserIdOrderByCreatedAtDesc(Long userId) {
        return selectList(Wrappers.<UserFeedback>lambdaQuery()
                .eq(UserFeedback::getUserId, userId)
                .orderByDesc(UserFeedback::getCreatedAt));
    }
}
