package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CB-021：DATA_UPDATE 审计 detail 的 col=old→new 形态（纯逻辑，不连库）。
 */
class AdminDataManageChangeDetailTest {

    @Test
    void multipleColumns_joinedWithComma() {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("status", "CLOSED");
        safe.put("remark", "ok");
        Map<String, Object> oldRow = new LinkedHashMap<>();
        oldRow.put("status", "OPEN");
        oldRow.put("remark", null);

        assertEquals("status=OPEN->CLOSED, remark=null->ok",
                AdminDataManageService.changeDetail(safe, oldRow));
    }

    @Test
    void longValue_truncatedAt120() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longText.append('x');
        }
        String v = longText.toString();
        Map<String, Object> safe = Map.of("col", v);
        Map<String, Object> oldRow = Map.of("col", v);

        String detail = AdminDataManageService.changeDetail(safe, oldRow);
        // 单值形态：120 字符 + 截断标注；old→new 各一份，加 col= 与 ->
        assertEquals("col=" + "x".repeat(120) + "...(截断)->" + "x".repeat(120) + "...(截断)", detail);
    }
}
