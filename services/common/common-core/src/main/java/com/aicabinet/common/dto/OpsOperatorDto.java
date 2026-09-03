package com.aicabinet.common.dto;

import java.util.List;

public record OpsOperatorDto(
        Long userId,
        String phoneNumber,
        String name,
        String status,
        List<String> roleNames,
        List<Long> roleIds,
        /** 绑定商户；空列表表示全局数据范围（未限定设备） */
        List<String> merchantIds,
        List<String> merchantNames,
        /** 兼任/所属部门 ID（含主部门） */
        List<Long> deptIds,
        List<String> deptNames,
        /** 主部门 ID；可空（尚未归属） */
        Long primaryDeptId,
        String primaryDeptName,
        /** OPERATOR / CONSUMER 等；前端勿再用 userId 数值区间猜测 */
        String accountType
) {}
