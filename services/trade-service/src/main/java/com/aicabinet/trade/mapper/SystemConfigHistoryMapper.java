package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SystemConfigHistory;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SystemConfigHistoryMapper extends BaseTradeMapper<SystemConfigHistory> {

    /**
     * 某配置键的版本序列（最新在前）。
     *
     * <p>排序用 {@code created_at DESC, history_id DESC}：同一事务内的连续变更可能落在同一
     * 毫秒上，仅按时间排会出现「顺序不定」，加自增主键兜底才是稳定序列。
     *
     * @param limit 由调用方夹紧的条数上限（内部常量，非外部输入）
     */
    default List<SystemConfigHistory> findByConfigKeyOrderByCreatedAtDesc(String configKey, int limit) {
        return selectList(Wrappers.<SystemConfigHistory>lambdaQuery()
                .eq(SystemConfigHistory::getConfigKey, configKey)
                .orderByDesc(SystemConfigHistory::getCreatedAt)
                .orderByDesc(SystemConfigHistory::getHistoryId)
                .last("LIMIT " + limit));
    }
}
