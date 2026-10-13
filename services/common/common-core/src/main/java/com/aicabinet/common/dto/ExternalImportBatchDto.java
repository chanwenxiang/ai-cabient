package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 外部渠道导入批次（CB-036，V347）。
 *
 * @param batchId           批次 ID
 * @param channelCode       渠道码
 * @param channelName       渠道名
 * @param periodLabel       账期标签=结束月（如 202609，26~25 账期）
 * @param fileName          原始文件名
 * @param personOverride    MANUAL 渠道人工指定线长
 * @param status            PARSED / COMMITTED
 * @param rowsTotal         解析总行数
 * @param rowsMapped        可提交行数（商品已映射且人员可归仓）
 * @param rowsUnmapped      不可提交行数
 * @param unmappedProducts  未映射外部商品清单
 * @param unresolvedPersons 无法归仓人员
 * @param rows              统一 9 列解析行（预览全量；COMMITTED 后仍可回看）
 * @param commitSummary     {flows, qtyTotal, warehouses}
 * @param uploadedByName    上传人
 * @param uploadedAt        上传时间
 * @param committedAt       确认时间
 */
public record ExternalImportBatchDto(
        Long batchId,
        String channelCode,
        String channelName,
        String periodLabel,
        String fileName,
        String personOverride,
        String status,
        int rowsTotal,
        int rowsMapped,
        int rowsUnmapped,
        List<ExternalUnmappedProductDto> unmappedProducts,
        List<String> unresolvedPersons,
        List<ExternalParsedRowDto> rows,
        Map<String, Object> commitSummary,
        String uploadedByName,
        Instant uploadedAt,
        Instant committedAt) {
}
