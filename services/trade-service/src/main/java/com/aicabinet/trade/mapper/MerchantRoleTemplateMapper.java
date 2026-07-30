package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantRoleTemplate;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

public interface MerchantRoleTemplateMapper extends BaseTradeMapper<MerchantRoleTemplate> {
    default List<MerchantRoleTemplate> findAllOrdered() {
        return selectList(Wrappers.<MerchantRoleTemplate>lambdaQuery().orderByAsc(MerchantRoleTemplate::getSortOrder));
    }
}
