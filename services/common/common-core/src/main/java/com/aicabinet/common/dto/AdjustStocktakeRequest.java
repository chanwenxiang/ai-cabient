package com.aicabinet.common.dto;

import java.util.List;

/** lineIds 为空时默认调整全部差异行。 */
public record AdjustStocktakeRequest(
        List<Long> lineIds
) {}
