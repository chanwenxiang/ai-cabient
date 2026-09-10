package com.aicabinet.common.util;

/**
 * 消费者端争议/审核卡片文案（金额结论等）— 单一源，避免前端硬编码。
 */
public final class DisputeConsumerCopy {

    private DisputeConsumerCopy() {
    }

    public record ReviewCopy(String icon, String title, String detail, String tone) {
    }

    /** 状态行：审核中 / 已结案 · 扣款/退款金额。 */
    public static String statusLabel(String status, Integer billedAmountCents, Integer refundedAmountCents) {
        String s = status == null ? "" : status.trim().toUpperCase();
        if ("OPEN".equals(s) || "PENDING".equals(s)) {
            return "审核中 · 暂未扣款";
        }
        if ("RESOLVED".equals(s) || "CLOSED".equals(s)) {
            int billed = billedAmountCents == null ? 0 : billedAmountCents;
            int refunded = refundedAmountCents == null ? 0 : refundedAmountCents;
            if (refunded > 0 && billed > 0) {
                return "已结案 · 扣款 " + fmtYuan(billed) + " / 退款 " + fmtYuan(refunded);
            }
            if (refunded > 0) {
                return "已结案 · 退款 " + fmtYuan(refunded);
            }
            if (billed > 0) {
                return "已结案 · 扣款 " + fmtYuan(billed);
            }
            return "已结案 · 未扣款";
        }
        return "处理中";
    }

    public static ReviewCopy reviewCopy(String status, String reason,
                                        Integer billedAmountCents, Integer refundedAmountCents) {
        String s = status == null ? "" : status.trim().toUpperCase();
        String raw = reason == null ? "" : reason.trim();
        if ("RESOLVED".equals(s) || "CLOSED".equals(s)) {
            return new ReviewCopy("✓", "人工审核已完成",
                    resolvedDetail(raw, billedAmountCents, refundedAmountCents), "success");
        }
        if (isServiceUnavailable(raw)) {
            return new ReviewCopy("!", "识别服务暂不可用",
                    blankTo(raw, "识别服务暂时不可用，本次暂未扣款。审核完成后会生成账单。"), "warn");
        }
        if (isRecognitionSucceeded(raw) || isInternalStagingReason(raw)) {
            return new ReviewCopy("!", "账单待人工确认",
                    blankTo(raw, "识别结果需人工确认，本次暂未扣款。审核完成后会生成账单。"), "wait");
        }
        return new ReviewCopy("!", "账单审核中",
                blankTo(raw, "商品识别结果需要人工确认，本次暂未扣款。审核完成后会生成账单。"), "wait");
    }

    public static String amountDiffNote(Integer claimedAmountCents, Integer billedAmountCents,
                                        int memberDiscountCents, int couponDiscountCents) {
        int claimed = claimedAmountCents == null ? 0 : claimedAmountCents;
        int billed = billedAmountCents == null ? 0 : billedAmountCents;
        if (claimed <= 0 || billed < 0 || claimed == billed) {
            return "";
        }
        int diff = claimed - billed;
        int knownDiscount = Math.max(0, memberDiscountCents) + Math.max(0, couponDiscountCents);
        if (diff > 0) {
            if (knownDiscount > 0 && Math.abs(knownDiscount - diff) <= 1) {
                StringBuilder parts = new StringBuilder();
                if (memberDiscountCents > 0) {
                    parts.append("会员优惠 ").append(fmtYuan(memberDiscountCents));
                }
                if (couponDiscountCents > 0) {
                    if (parts.length() > 0) {
                        parts.append(" + ");
                    }
                    parts.append("优惠券 ").append(fmtYuan(couponDiscountCents));
                }
                return "识别参考 " + fmtYuan(claimed) + "，实扣 " + fmtYuan(billed)
                        + "（" + parts + "）";
            }
            return "识别参考 " + fmtYuan(claimed) + "，实扣 " + fmtYuan(billed)
                    + "（优惠/折扣 " + fmtYuan(diff) + "）";
        }
        return "识别参考 " + fmtYuan(claimed) + "，实扣 " + fmtYuan(billed)
                + "（差额 " + fmtYuan(Math.abs(diff)) + "）";
    }

    /** 与前端 fmtMoney 一致：分 → ¥x.xx */
    public static String fmtYuan(int cents) {
        int n = cents;
        String sign = n < 0 ? "-" : "";
        int abs = Math.abs(n);
        int whole = abs / 100;
        int frac = abs % 100;
        return sign + "¥" + whole + "." + (frac < 10 ? "0" + frac : String.valueOf(frac));
    }

    private static String resolvedDetail(String reason, Integer billedAmountCents, Integer refundedAmountCents) {
        int billed = billedAmountCents == null ? 0 : billedAmountCents;
        int refunded = refundedAmountCents == null ? 0 : refundedAmountCents;
        if (refunded > 0 && billed <= 0) {
            return "已结案：已免单退款 " + fmtYuan(refunded);
        }
        if (refunded > 0 && billed > 0) {
            return "已结案：扣款 " + fmtYuan(billed) + "，已退 " + fmtYuan(refunded);
        }
        if (billed > 0) {
            return "已结案：最终扣款 " + fmtYuan(billed);
        }
        if (refunded > 0) {
            return "已结案：已退款 " + fmtYuan(refunded);
        }
        if (!reason.isBlank()
                && !reason.matches("(?i).*暂未扣款.*")
                && !reason.matches("(?i).*审核完成后会生成账单.*")) {
            return reason;
        }
        return "已结案：未产生扣款，可在订单中查看账单。";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isServiceUnavailable(String reason) {
        return reason.matches("(?i).*(识别服务暂时不可用|识别服务暂不可用|vision|service.?unavailable).*");
    }

    private static boolean isInternalStagingReason(String reason) {
        return reason.matches("(?i).*(非生产|重力信号|仅有重力|重力回填|模拟/兜底|模拟识别|gravity-fill|gravity-mismatch|mock-v).*");
    }

    private static boolean isRecognitionSucceeded(String reason) {
        return reason.matches("(?i).*(置信度|识别结果需人工|未识别到商品|识别到：|confidence|manual review).*");
    }
}
