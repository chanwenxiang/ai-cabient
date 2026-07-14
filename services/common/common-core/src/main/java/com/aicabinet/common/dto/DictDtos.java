package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DictDtos {
    private DictDtos() {}

    public record DictTypeDto(
            String dictType,
            String dictName,
            String status,
            String remark,
            int sortOrder,
            int itemCount,
            Instant updatedAt
    ) {}

    public record DictDataDto(
            Long dictDataId,
            String dictType,
            String dictValue,
            String dictLabel,
            int sortOrder,
            String status,
            String remark
    ) {}

    public record DictTypeUpsertRequest(
            String dictType,
            String dictName,
            String status,
            String remark,
            Integer sortOrder
    ) {}

    public record DictDataUpsertRequest(
            String dictValue,
            String dictLabel,
            String status,
            String remark,
            Integer sortOrder
    ) {}

    public record DictRuntimeDto(Map<String, List<DictDataDto>> itemsByType) {}
}
