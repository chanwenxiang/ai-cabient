package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Warehouse;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WarehouseMapper extends BaseTradeMapper<Warehouse> {

    /** page 为 0-based。 */
    default Page<Warehouse> searchPage(String keyword, int page, int size) {
        var query = Wrappers.<Warehouse>lambdaQuery().orderByAsc(Warehouse::getWarehouseId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(Warehouse::getWarehouseId, kw)
                    .or().like(Warehouse::getWarehouseName, kw)
                    .or().like(Warehouse::getAddress, kw));
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

    /**
     * 负责人名下第一家启用仓（分仓日常采购默认入库）。
     *
     * <p>🔴 V412 起改查 {@code warehouse_manager}（多人集合）：原来读 {@code warehouse.manager_user_id}
     * 单值列，一仓换成第二个人后第一个人就直接丢掉采购/盘点入口。多仓时按仓号升序取第一，
     * 保证同一账号每次解析结果一致。
     */
    default java.util.Optional<Warehouse> findFirstActiveByManagerUserId(Long managerUserId) {
        if (managerUserId == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(selectFirstActiveByManagerUserId(managerUserId));
    }

    @Select("""
            SELECT w.warehouse_id, w.warehouse_name, w.address, w.status, w.created_at, w.manager_user_id
            FROM warehouse w
            JOIN warehouse_manager m ON m.warehouse_id = w.warehouse_id
            WHERE m.user_id = #{userId}
              AND w.status = 'ACTIVE'
              AND COALESCE(w.is_deleted, FALSE) = FALSE
            ORDER BY w.warehouse_id
            LIMIT 1
            """)
    Warehouse selectFirstActiveByManagerUserId(@Param("userId") Long userId);
}
