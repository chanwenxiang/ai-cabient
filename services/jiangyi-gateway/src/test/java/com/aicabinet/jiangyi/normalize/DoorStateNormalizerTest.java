package com.aicabinet.jiangyi.normalize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 将邑上报字段归一单测（CB-022，方案 §12）。
 *
 * <p>2026-10-19 修订（第三轮审计 P0-3）：归一从布尔改三态——真机实际词汇
 * success/yes/open（log3 实证）之外，缺字段/未识别词一律 UNKNOWN（null），
 * 由调用方 fail-open（只告警不动会话）；只有明确失败词才 FALSE。</p>
 */
class DoorStateNormalizerTest {

    @Test
    void explicitSuccessWords() {
        assertEquals(Boolean.TRUE, DoorStateNormalizer.normalize("success"));
        assertEquals(Boolean.TRUE, DoorStateNormalizer.normalize("SUCCESS"));
        assertEquals(Boolean.TRUE, DoorStateNormalizer.normalize(" Success "));
        // 真机实词（log3：{"openDoorStatus":"yes"}）
        assertEquals(Boolean.TRUE, DoorStateNormalizer.normalize("yes"));
        assertEquals(Boolean.TRUE, DoorStateNormalizer.normalize("open"));
        assertEquals(Boolean.TRUE, DoorStateNormalizer.normalize("1"));
    }

    @Test
    void explicitFailWords() {
        assertEquals(Boolean.FALSE, DoorStateNormalizer.normalize("fail"));
        assertEquals(Boolean.FALSE, DoorStateNormalizer.normalize("FAIL"));
        assertEquals(Boolean.FALSE, DoorStateNormalizer.normalize("error"));
        assertEquals(Boolean.FALSE, DoorStateNormalizer.normalize("0"));
    }

    @Test
    void unknownOrNullIsUnknown_notFail() {
        // 旧实现把 null/未知词当「明确失败」→ 误杀进行中订单；修订后为 UNKNOWN
        assertNull(DoorStateNormalizer.normalize(""));
        assertNull(DoorStateNormalizer.normalize(null));
        assertNull(DoorStateNormalizer.normalize("successful-ish"));
        assertNull(DoorStateNormalizer.normalize("maybe"));
    }

    @Test
    void doingAcceptedCaseInsensitively() {
        // §4.2.11 用 bigModel:"doing"；§4.2.12 用 BigModel——值本身大小写也归一
        assertTrue(DoorStateNormalizer.isDoing("doing"));
        assertTrue(DoorStateNormalizer.isDoing("DOING"));
        assertTrue(DoorStateNormalizer.isDoing(" Doing "));
    }

    @Test
    void nonDoingRejected() {
        assertFalse(DoorStateNormalizer.isDoing(null));
        assertFalse(DoorStateNormalizer.isDoing(""));
        assertFalse(DoorStateNormalizer.isDoing("error"));
        // 不设值=异常订单（§4.2.11 原文），绝不能误判为 doing
        assertFalse(DoorStateNormalizer.isDoing("1"));
    }
}
