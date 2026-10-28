package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * @param managerUserId  兼容字段：单负责人（旧客户端 / CSV 导入仍可能只传这一个）。
 *                       仅在 {@code managerUserIds} 为 null 时生效；{@code <=0} 表示清空。
 * @param managerUserIds 负责人全集（V412 起支持多人）。**null 表示不改**，空列表表示清空；
 *                       顺序有意义：第一位即主责人（同步到 {@code warehouse.manager_user_id}）。
 *                       非 null 时优先级高于 {@code managerUserId}。
 */
public record UpsertWarehouseRequest(
        @NotBlank(message = "仓库名称不能为空")
        @Size(max = 64)
        String warehouseName,
        @Size(max = 255)
        String address,
        String status,
        Long managerUserId,
        List<Long> managerUserIds
) {
    /**
     * 两个字段合并成服务端要的单一入参：{@code managerUserIds} 优先；
     * 只给了旧字段 {@code managerUserId} 时按单人处理（{@code <=0} = 清空）；
     * 都没给返回 {@code null}（= 不改负责人）。
     */
    public List<Long> resolvedManagerUserIds() {
        if (managerUserIds != null) {
            return managerUserIds;
        }
        if (managerUserId == null) {
            return null;
        }
        return managerUserId <= 0L ? List.of() : List.of(managerUserId);
    }
}
