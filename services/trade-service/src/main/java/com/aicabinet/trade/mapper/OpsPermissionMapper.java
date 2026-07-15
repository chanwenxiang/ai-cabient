package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsPermission;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OpsPermissionMapper extends BaseTradeMapper<OpsPermission> {

    List<String> _findPermCodesByUserId(@Param("userId") Long userId);

    default Set<String> findPermCodesByUserId(Long userId) {
        return new java.util.LinkedHashSet<>(_findPermCodesByUserId(userId));
    }


    default List<OpsPermission> findByStatusOrderBySortOrderAsc(String status) {
    return selectList(Wrappers.<OpsPermission>lambdaQuery().eq(OpsPermission::getStatus, status).orderByAsc(OpsPermission::getSortOrder));
    }

}
