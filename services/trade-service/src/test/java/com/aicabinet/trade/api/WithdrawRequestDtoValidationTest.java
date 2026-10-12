package com.aicabinet.trade.api;

import com.aicabinet.common.dto.RemarkOnlyRequest;
import com.aicabinet.common.dto.WalletAdjustRequest;
import com.aicabinet.common.dto.WithdrawApplyRequest;
import com.aicabinet.common.dto.WithdrawReviewRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三方扫描 P2 修复的行为钉子（2026-10-12）：
 * - 钱包调账金额必须有界（旧裸 Map longValue() 无界，Long.MIN_VALUE 取负溢出致扣款语义反转）；
 * - 提现审核 approve 必须显式传入（旧 Boolean.TRUE.equals(null) 把漏传静默当成「驳回」）；
 * - 提现申请金额必须 > 0。
 */
class WithdrawRequestDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void walletAdjust_amountRequired() {
        var v = validator.validate(new WalletAdjustRequest(null, "备注"));
        assertThat(v).anyMatch(x -> "amountCents".equals(x.getPropertyPath().toString()));
    }

    @Test
    void walletAdjust_longMinValueRejected() {
        // 旧实现可穿透：Long.MIN_VALUE 取负仍为负 ⇒ 扣款变充值语义反转
        var v = validator.validate(new WalletAdjustRequest(Long.MIN_VALUE, null));
        assertThat(v).anyMatch(x -> "amountCents".equals(x.getPropertyPath().toString()));
    }

    @Test
    void walletAdjust_overCapRejected() {
        var v = validator.validate(new WalletAdjustRequest(100_000_001L, null));
        assertThat(v).anyMatch(x -> "amountCents".equals(x.getPropertyPath().toString()));
    }

    @Test
    void walletAdjust_normalAmountAccepted() {
        var v = validator.validate(new WalletAdjustRequest(-50_000L, "盘亏调账"));
        assertThat(v).isEmpty();
    }

    @Test
    void withdrawReview_approveRequired_missingIsInvalid() {
        // 旧行为：漏传 approve 被静默当成驳回——现必须 400
        var v = validator.validate(new WithdrawReviewRequest(null, null));
        assertThat(v).anyMatch(x -> "approve".equals(x.getPropertyPath().toString()));
    }

    @Test
    void withdrawReview_explicitApproveAccepted() {
        assertThat(validator.validate(new WithdrawReviewRequest(Boolean.FALSE, "材料不全"))).isEmpty();
    }

    @Test
    void withdrawApply_positiveAmountRequired() {
        assertThat(validator.validate(new WithdrawApplyRequest(0L, null, null))).isNotEmpty();
        assertThat(validator.validate(new WithdrawApplyRequest(-100L, null, null))).isNotEmpty();
        assertThat(validator.validate(new WithdrawApplyRequest(10_000L, "REQ123", "MCH-1"))).isEmpty();
    }

    @Test
    void remarkOnly_remarkTooLongRejected() {
        assertThat(validator.validate(new RemarkOnlyRequest("x".repeat(201)))).isNotEmpty();
    }
}
