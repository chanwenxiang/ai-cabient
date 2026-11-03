package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量发券入参（MA-083）。
 *
 * <p>为什么必须带约束：原来 {@code userIds} 是裸 List，{@code null} 直接进
 * {@code CouponService.batchIssue} 的 {@code userIds.stream()} ⇒ NPE 落全局兜底成 500；
 * 而万级 id 一次提交会在一个事务里逐张持 {@code coupon_def} 行锁并**逐张占用活动预算**
 * （发券即 reserve，见 {@code CouponService:186}），既拖垮库又可能把预算一口占满。
 * 上限 1000 与前端一次可选人数同量级，超出请分批。
 */
public record BatchIssueCouponRequest(
        @NotNull(message = "couponDefId 必填") Long couponDefId,
        @NotEmpty(message = "userIds 不能为空")
                @Size(max = 1000, message = "单次批量发券最多 1000 人，请分批") List<Long> userIds) {}
