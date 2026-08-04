package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SkuCatalog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkuCatalogMapper extends BaseTradeMapper<SkuCatalog> {

    default java.util.List<SkuCatalog> findAllByOrderBySkuIdAsc() {
    return selectList(Wrappers.<SkuCatalog>lambdaQuery().orderByAsc(SkuCatalog::getSkuId));
    }

}
