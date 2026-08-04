package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.EdgeInferenceLog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EdgeInferenceLogMapper extends BaseTradeMapper<EdgeInferenceLog> {

    default List<EdgeInferenceLog> findByDeviceId(String deviceId) {
    return selectList(Wrappers.<EdgeInferenceLog>lambdaQuery().eq(EdgeInferenceLog::getDeviceId, deviceId));
    }

    default List<EdgeInferenceLog> findBySessionId(String sessionId) {
    return selectList(Wrappers.<EdgeInferenceLog>lambdaQuery().eq(EdgeInferenceLog::getSessionId, sessionId));
    }

        Double getAverageInferenceTime(String deviceId, Instant start);

        Long countSuccessInferences(String deviceId, Instant start);

}
