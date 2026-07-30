package com.aicabinet.common.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 争议结案请求。
 * <ul>
 *   <li>CONFIRM — 按确认清单扣款（无订单时首次结算；有订单时按差额退/补）</li>
 *   <li>ADJUST — 同 CONFIRM，强调改单退补差</li>
 *   <li>WAIVE — 免单，退还已扣款项（items 可为空）</li>
 *   <li>KEEP — 维持原账单（items 可为空）</li>
 * </ul>
 * {@code action} 为 {@code resolutionType} 的兼容别名（历史客户端/脚本）。
 */
public record ResolveDisputeRequest(
        List<ManualLineItem> items,
        @JsonAlias("action") String resolutionType
) {
    public record ManualLineItem(
            @NotBlank String skuId,
            int quantity
    ) {}

    public ResolveDisputeRequest {
        if (items == null) {
            items = List.of();
        }
    }
}
