package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 商户名 / 争议原因乱码兜底。 */
class MerchantNameSupportTest {

    @Test
    void resolve_replacesQuestionMarks() {
        assertEquals("华东演示商户", MerchantNameSupport.resolve("MCH-EAST", "????"));
        assertEquals("默认直营商户", MerchantNameSupport.resolve("MCH-DEFAULT", "???"));
        assertEquals("正常商户", MerchantNameSupport.resolve("MCH-X", "正常商户"));
    }

    @Test
    void disputeReason_mapsReviewCodeWhenGarbled() {
        assertEquals(
                "视觉为空，仅有重力信号（非生产识别精度），需人工审核",
                MerchantNameSupport.disputeReason("GRAVITY_FILL", "????"));
        assertEquals(
                "识别超时，已转人工审核，本次暂未扣款",
                MerchantNameSupport.disputeReason("TIMEOUT", "????????"));
        assertEquals("人工已填写的原因", MerchantNameSupport.disputeReason("MOCK", "人工已填写的原因"));
    }
}
