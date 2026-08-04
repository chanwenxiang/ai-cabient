package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsUserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OpsUserRoleMapper extends BaseTradeMapper<OpsUserRole> {

    default List<OpsUserRole> findByIdUserId(Long userId) {
    return selectList(Wrappers.<OpsUserRole>lambdaQuery().eq(OpsUserRole::getUserId, userId));
    }

    default void deleteByIdUserId(Long userId) {
    delete(Wrappers.<OpsUserRole>lambdaQuery().eq(OpsUserRole::getUserId, userId));
    }

}
