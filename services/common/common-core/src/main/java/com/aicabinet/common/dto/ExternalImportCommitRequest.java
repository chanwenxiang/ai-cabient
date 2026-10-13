package com.aicabinet.common.dto;

import java.util.Map;

/**
 * 外部渠道批次确认提交请求（CB-036）。
 *
 * @param personWarehouseOverrides 人员→仓库覆盖（personName → warehouseId）；
 *                                 解决预览里「无法归仓」人员；未覆盖且仍无法归仓 → 400 拒绝提交
 */
public record ExternalImportCommitRequest(Map<String, String> personWarehouseOverrides) {
}
