package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface JiangyiSkuJiangyiLinkMapper extends BaseTradeMapper<JiangyiSkuJiangyiLink> {

    default Optional<JiangyiSkuJiangyiLink> byProduct(String jiangyiProductId) {
        return Optional.ofNullable(selectOne(Wrappers.<JiangyiSkuJiangyiLink>lambdaQuery()
                .eq(JiangyiSkuJiangyiLink::getJiangyiProductId, jiangyiProductId)));
    }

    default List<JiangyiSkuJiangyiLink> listBySku(String skuId) {
        return selectList(Wrappers.<JiangyiSkuJiangyiLink>lambdaQuery()
                .eq(JiangyiSkuJiangyiLink::getSkuId, skuId)
                .ne(JiangyiSkuJiangyiLink::getSyncStatus, "RETIRED")
                .orderByDesc(JiangyiSkuJiangyiLink::getUpdatedAt));
    }

    /** 已挂接的将邑商品 id 集合（搜索结果标「已挂接」用）。 */
    default List<String> listBoundProductIds(List<String> jiangyiProductIds) {
        if (jiangyiProductIds == null || jiangyiProductIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<JiangyiSkuJiangyiLink>lambdaQuery()
                        .in(JiangyiSkuJiangyiLink::getJiangyiProductId, jiangyiProductIds)
                        .ne(JiangyiSkuJiangyiLink::getSyncStatus, "RETIRED"))
                .stream().map(JiangyiSkuJiangyiLink::getJiangyiProductId).toList();
    }
}
