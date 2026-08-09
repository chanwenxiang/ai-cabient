package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MediaAsset;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MediaAssetMapper extends BaseTradeMapper<MediaAsset> {

    default List<MediaAsset> findAllOrderByCreatedDesc() {
        return selectList(Wrappers.<MediaAsset>lambdaQuery()
                .orderByDesc(MediaAsset::getCreatedAt));
    }
}
