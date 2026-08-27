package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AliyunCategoryMapping;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AliyunCategoryMappingMapper extends BaseTradeMapper<AliyunCategoryMapping> {

    AliyunCategoryMapping findByIdForUpdateRaw(@Param("categoryId") String categoryId);

    default Optional<AliyunCategoryMapping> findByIdForUpdate(String categoryId) {
        return Optional.ofNullable(findByIdForUpdateRaw(categoryId));
    }
}
