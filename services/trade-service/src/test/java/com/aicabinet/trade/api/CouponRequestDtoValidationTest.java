package com.aicabinet.trade.api;

import com.aicabinet.common.dto.BatchIssueCouponRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MA-083 钉测：批量发券 DTO 的入参约束（2026-11-03）。
 *
 * <p>原来 {@code userIds=null} 直接 NPE→500、元素数无上限（万级元素 ⇒ 长事务持
 * {@code coupon_def} 行锁）。约束本身挂在 DTO 上、由控制器 {@code @Valid} 生效，
 * 所以断言取的是「记录被 Validator 判成什么」，不依赖 Spring 上下文。</p>
 */
class CouponRequestDtoValidationTest {

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
    void nullCouponDefId_rejected() {
        assertThat(validator.validate(new BatchIssueCouponRequest(null, List.of(7L))))
                .anyMatch(v -> "couponDefId".equals(v.getPropertyPath().toString()));
    }

    /** 钉 @NotEmpty 而不是只钉 @NotNull：空数组也必须 400（发 0 张没有语义）。 */
    @Test
    void null_or_emptyUserIds_rejected() {
        assertThat(validator.validate(new BatchIssueCouponRequest(1L, null)))
                .anyMatch(v -> "userIds".equals(v.getPropertyPath().toString()));
        assertThat(validator.validate(new BatchIssueCouponRequest(1L, List.of())))
                .anyMatch(v -> "userIds".equals(v.getPropertyPath().toString()));
    }

    @Test
    void userIds_over1000_rejected_withCopywrittenMessage() {
        List<Long> over = LongStream.rangeClosed(1, 1001).boxed().toList();
        assertThat(validator.validate(new BatchIssueCouponRequest(1L, over)))
                .anyMatch(v -> "userIds".equals(v.getPropertyPath().toString())
                        && v.getMessage().contains("1000"));
    }

    /** 边界对照：1000 人本身必须放行，否则上限就成了「永远发不出去」。 */
    @Test
    void userIds_at1000_accepted() {
        List<Long> cap = LongStream.rangeClosed(1, 1000).boxed().toList();
        assertThat(validator.validate(new BatchIssueCouponRequest(1L, cap))).isEmpty();
    }

    @Test
    void happyPath_accepted() {
        assertThat(validator.validate(new BatchIssueCouponRequest(1L, List.of(7L, 8L)))).isEmpty();
    }
}
