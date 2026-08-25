package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MediaAsset;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface MediaAssetMapper extends BaseTradeMapper<MediaAsset> {

    MediaAsset _findByIdForUpdateRaw(@Param("assetId") Long assetId);

    default Optional<MediaAsset> findByIdForUpdate(Long assetId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(assetId));
    }

    default List<MediaAsset> findAllOrderByCreatedDesc() {
        return selectList(Wrappers.<MediaAsset>lambdaQuery()
                .orderByDesc(MediaAsset::getCreatedAt));
    }
}
