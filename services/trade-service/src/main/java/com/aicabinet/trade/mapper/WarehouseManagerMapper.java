package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.WarehouseManager;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分仓负责人（多人）表。
 *
 * <p>🔴 权限判据的唯一真相：判「某人是否该仓负责人」用 {@link #isManager} 或
 * {@link #listWarehouseIdsByUserId}，不要读 {@code warehouse.manager_user_id}
 * （那只是主责人派生列）。
 */
@Mapper
public interface WarehouseManagerMapper extends BaseTradeMapper<WarehouseManager> {

    /** 该仓全部负责人，按绑定先后（= 主责人优先）。 */
    default List<WarehouseManager> listByWarehouseId(String warehouseId) {
        return selectList(Wrappers.<WarehouseManager>lambdaQuery()
                .eq(WarehouseManager::getWarehouseId, warehouseId)
                .orderByAsc(WarehouseManager::getCreatedAt)
                .orderByAsc(WarehouseManager::getManagerId));
    }

    /** 该仓：某人是否负责人。 */
    default boolean isManager(String warehouseId, Long userId) {
        if (warehouseId == null || userId == null) {
            return false;
        }
        return selectCount(Wrappers.<WarehouseManager>lambdaQuery()
                .eq(WarehouseManager::getWarehouseId, warehouseId)
                .eq(WarehouseManager::getUserId, userId)) > 0;
    }

    /** 某人负责的仓编号（按仓号升序，便于「取第一个」时结果稳定）。 */
    default List<String> listWarehouseIdsByUserId(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return selectList(Wrappers.<WarehouseManager>lambdaQuery()
                .eq(WarehouseManager::getUserId, userId))
                .stream()
                .map(WarehouseManager::getWarehouseId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    default int deleteByWarehouseId(String warehouseId) {
        return delete(Wrappers.<WarehouseManager>lambdaQuery()
                .eq(WarehouseManager::getWarehouseId, warehouseId));
    }

    /** 清空该仓全部负责人（供差量同步整表重写）。 */
    default int deleteByWarehouseIdAndUserId(String warehouseId, Long userId) {
        return delete(Wrappers.<WarehouseManager>lambdaQuery()
                .eq(WarehouseManager::getWarehouseId, warehouseId)
                .eq(WarehouseManager::getUserId, userId));
    }
}
