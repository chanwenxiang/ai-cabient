package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MediaAsset;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface MediaAssetMapper extends BaseTradeMapper<MediaAsset> {

    MediaAsset findByIdForUpdateRaw(@Param("assetId") Long assetId);

    default Optional<MediaAsset> findByIdForUpdate(Long assetId) {
        return Optional.ofNullable(findByIdForUpdateRaw(assetId));
    }

    default List<MediaAsset> findAllOrderByCreatedDesc() {
        return selectList(Wrappers.<MediaAsset>lambdaQuery()
                .orderByDesc(MediaAsset::getCreatedAt));
    }

    /** page 为 0-based。 */
    default Page<MediaAsset> searchPage(int page, int size) {
        return selectPage(new Page<>(page + 1L, size),
                Wrappers.<MediaAsset>lambdaQuery().orderByDesc(MediaAsset::getCreatedAt));
    }
}
