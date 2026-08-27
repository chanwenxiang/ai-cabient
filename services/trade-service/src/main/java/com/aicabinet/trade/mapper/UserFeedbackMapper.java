package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserFeedback;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserFeedbackMapper extends BaseTradeMapper<UserFeedback> {

    UserFeedback findByIdForUpdateRaw(@Param("feedbackId") Long feedbackId);

    default Optional<UserFeedback> findByIdForUpdate(Long feedbackId) {
        return Optional.ofNullable(findByIdForUpdateRaw(feedbackId));
    }

    default List<UserFeedback> findAllOrderByCreatedAtDesc() {
        return selectList(Wrappers.<UserFeedback>lambdaQuery().orderByDesc(UserFeedback::getCreatedAt));
    }

    default List<UserFeedback> findByStatusOrderByCreatedAtDesc(String status) {
        return selectList(Wrappers.<UserFeedback>lambdaQuery()
                .eq(UserFeedback::getStatus, status)
                .orderByDesc(UserFeedback::getCreatedAt));
    }

    /** page 为 0-based。 */
    default Page<UserFeedback> search(String status, int page, int size) {
        var q = Wrappers.<UserFeedback>lambdaQuery().orderByDesc(UserFeedback::getCreatedAt);
        if (status != null && !status.isBlank()) {
            q.eq(UserFeedback::getStatus, status.trim().toUpperCase());
        }
        return selectPage(new Page<>(page + 1L, size), q);
    }

    default List<UserFeedback> findByUserIdOrderByCreatedAtDesc(Long userId) {
        return selectList(Wrappers.<UserFeedback>lambdaQuery()
                .eq(UserFeedback::getUserId, userId)
                .orderByDesc(UserFeedback::getCreatedAt));
    }
}
