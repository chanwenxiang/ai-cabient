package com.aicabinet.trade.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 商品 SKU ID：纯数字，与 sku_code 对齐。 */
public final class SkuIds {

    private SkuIds() {}

    public static boolean isNumeric(String skuId) {
        if (skuId == null || skuId.isEmpty()) {
            return false;
        }
        for (int i = 0; i < skuId.length(); i++) {
            if (!Character.isDigit(skuId.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String fromCode(long code) {
        return Long.toString(code);
    }

    public static void requireNumeric(String skuId) {
        if (!isNumeric(skuId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.SKU_ID_NUMERIC);
        }
    }
}
