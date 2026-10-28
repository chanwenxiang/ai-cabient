package com.aicabinet.common.dto;

/**
 * 分仓负责人（多人中的一位）。
 *
 * @param userId      user_info.user_id
 * @param name        姓名（空账号名回落「未命名」，绝不回 null 让前端显示空白）
 * @param phoneNumber 🔴 **掩码后**手机号；后台列表直接渲染，不得下发明文
 */
public record WarehouseManagerDto(Long userId, String name, String phoneNumber) {}
