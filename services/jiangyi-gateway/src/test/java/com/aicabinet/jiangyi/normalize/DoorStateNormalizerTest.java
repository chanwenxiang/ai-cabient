package com.aicabinet.jiangyi.normalize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 将邑上报字段归一单测（CB-022，方案 §12）：覆盖文档两处大小写坑
 * （§4.2.7/4.2.8 success|fail、§4.2.11 bigModel vs §4.2.12 BigModel）。
 */
class DoorStateNormalizerTest {

    @Test
    void successAcceptedCaseInsensitively() {
        assertTrue(DoorStateNormalizer.isSuccess("success"));
        assertTrue(DoorStateNormalizer.isSuccess("SUCCESS"));
        assertTrue(DoorStateNormalizer.isSuccess(" Success "));
    }

    @Test
    void failAndUnknownRejected() {
        assertFalse(DoorStateNormalizer.isSuccess("fail"));
        assertFalse(DoorStateNormalizer.isSuccess("FAIL"));
        assertFalse(DoorStateNormalizer.isSuccess(""));
        assertFalse(DoorStateNormalizer.isSuccess(null));
        assertFalse(DoorStateNormalizer.isSuccess("successful-ish"));
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
