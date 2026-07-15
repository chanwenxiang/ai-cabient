package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsRolePermission;
import com.aicabinet.trade.domain.OpsRolePermissionId;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OpsRolePermissionMapper extends BaseTradeMapper<OpsRolePermission> {

    default List<OpsRolePermission> findByIdRoleId(Long roleId) {
    return selectList(Wrappers.<OpsRolePermission>lambdaQuery().eq(OpsRolePermission::getRoleId, roleId));
    }

    default void deleteByIdRoleId(Long roleId) {
    delete(Wrappers.<OpsRolePermission>lambdaQuery().eq(OpsRolePermission::getRoleId, roleId));
    }

        List<Long> findPermissionIdsByRoleId(@Param("roleId") Long roleId);


}
