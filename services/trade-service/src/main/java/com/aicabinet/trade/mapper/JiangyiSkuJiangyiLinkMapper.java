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

    /**
     * 按条码取挂接行（第七章对外以 barCode 为键：§7.11 products 支持 productId/barCode 二选一，
     * 且 `jiangyi_product_id` 一列混装 thirdPid/productId 两个命名空间，见台账 12B）。
     */
    default Optional<JiangyiSkuJiangyiLink> byBarCode(String barCode) {
        return selectList(Wrappers.<JiangyiSkuJiangyiLink>lambdaQuery()
                .eq(JiangyiSkuJiangyiLink::getBarCode, barCode)
                .ne(JiangyiSkuJiangyiLink::getSyncStatus, "RETIRED")
                .orderByDesc(JiangyiSkuJiangyiLink::getUpdatedAt))
                .stream().findFirst();
    }

    /** 需要在 §7.10 轮询的挂接行（审核中的两态），按最久未查优先；limit 上限 100（§7.10 批量上限）。 */
    default List<JiangyiSkuJiangyiLink> listPollable(int limit) {
        return selectList(Wrappers.<JiangyiSkuJiangyiLink>lambdaQuery()
                .in(JiangyiSkuJiangyiLink::getJiangyiState, "REVIEWING", "CABINET_REVIEWING")
                .ne(JiangyiSkuJiangyiLink::getSyncStatus, "RETIRED")
                .orderByAsc(JiangyiSkuJiangyiLink::getStateCheckedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
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

    /** 全部有效挂接（商品学习库的「可发起学习的 SKU」清单；RETIRED 已解绑不再可选）。 */
    default List<JiangyiSkuJiangyiLink> listBoundAll() {
        return selectList(Wrappers.<JiangyiSkuJiangyiLink>lambdaQuery()
                .ne(JiangyiSkuJiangyiLink::getSyncStatus, "RETIRED")
                .orderByDesc(JiangyiSkuJiangyiLink::getUpdatedAt));
    }

    /** 按将邑商品 id 批量取挂接行（待采集列表回标「我方哪个 SKU 建的」用）。 */
    default List<JiangyiSkuJiangyiLink> listByProducts(List<String> jiangyiProductIds) {
        if (jiangyiProductIds == null || jiangyiProductIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<JiangyiSkuJiangyiLink>lambdaQuery()
                .in(JiangyiSkuJiangyiLink::getJiangyiProductId, jiangyiProductIds)
                .ne(JiangyiSkuJiangyiLink::getSyncStatus, "RETIRED"));
    }
}
