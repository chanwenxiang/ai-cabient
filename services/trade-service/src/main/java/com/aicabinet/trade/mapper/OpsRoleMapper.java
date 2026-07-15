package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OpsRoleMapper extends BaseTradeMapper<OpsRole> {

    default Optional<OpsRole> findByRoleKey(String roleKey) {
    return Optional.ofNullable(selectOne(Wrappers.<OpsRole>lambdaQuery().eq(OpsRole::getRoleKey, roleKey)));
    }

}
