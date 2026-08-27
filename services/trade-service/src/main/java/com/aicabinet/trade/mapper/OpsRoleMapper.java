package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OpsRoleMapper extends BaseTradeMapper<OpsRole> {

    OpsRole findByIdForUpdateRaw(@Param("roleId") Long roleId);

    default Optional<OpsRole> findByIdForUpdate(Long roleId) {
        return Optional.ofNullable(findByIdForUpdateRaw(roleId));
    }

    default Optional<OpsRole> findByRoleKey(String roleKey) {
    return Optional.ofNullable(selectOne(Wrappers.<OpsRole>lambdaQuery().eq(OpsRole::getRoleKey, roleKey)));
    }

}
