package com.aicabinet.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 消费者/运营发起订单退款。
 * <p>
 * {@code restoreInventory} 对齐无人柜竞品常见分岔：
 * <ul>
 *   <li>{@code true} — 退货退款 / 误识别未拿走：退款并回库</li>
 *   <li>{@code false} — 仅退款（货已离柜）：退款不回库</li>
 *   <li>{@code null} — 由服务端按角色与原因文案推断</li>
 * </ul>
 * {@code lines} 非空时为按行部分退（运营为主）；为空则全额退。
 */
public record OrderRefundRequest(
        @NotBlank @Size(max = 256) String reason,
        List<Long> evidenceFileIds,
        Boolean restoreInventory,
        @Valid List<PartialRefundLine> lines
) {
    public OrderRefundRequest(String reason, List<Long> evidenceFileIds) {
        this(reason, evidenceFileIds, null, null);
    }

    public OrderRefundRequest(String reason, List<Long> evidenceFileIds, Boolean restoreInventory) {
        this(reason, evidenceFileIds, restoreInventory, null);
    }

    public record PartialRefundLine(
            @NotBlank String skuId,
            @Min(1) int quantity,
            /** 行级覆盖；null 则回落到请求级 restoreInventory / 策略推断 */
            Boolean restoreInventory
    ) {}
}
