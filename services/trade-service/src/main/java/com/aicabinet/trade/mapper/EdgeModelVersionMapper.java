package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.EdgeModelVersion;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EdgeModelVersionMapper extends BaseTradeMapper<EdgeModelVersion> {

    default Optional<EdgeModelVersion> findByModelNameAndVersion(String modelName, String version) {
    return Optional.ofNullable(selectOne(Wrappers.<EdgeModelVersion>lambdaQuery().eq(EdgeModelVersion::getModelName, modelName).eq(EdgeModelVersion::getVersion, version)));
    }

    default List<EdgeModelVersion> findByModelNameOrderByCreatedAtDesc(String modelName) {
    return selectList(Wrappers.<EdgeModelVersion>lambdaQuery().eq(EdgeModelVersion::getModelName, modelName).orderByDesc(EdgeModelVersion::getCreatedAt));
    }

    default Optional<EdgeModelVersion> findFirstByModelNameAndStatusOrderByCreatedAtDesc(String modelName, String status) {
    return Optional.ofNullable(selectOne(Wrappers.<EdgeModelVersion>lambdaQuery().eq(EdgeModelVersion::getModelName, modelName).eq(EdgeModelVersion::getStatus, status).orderByDesc(EdgeModelVersion::getCreatedAt).last("LIMIT 1")));
    }

}
