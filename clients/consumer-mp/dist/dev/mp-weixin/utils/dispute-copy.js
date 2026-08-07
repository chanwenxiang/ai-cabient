"use strict";
const common_vendor = require("../common/vendor.js");
function isServiceUnavailable(reason) {
  return /识别服务暂时不可用|识别服务暂不可用|vision|service.?unavailable/i.test(reason);
}
function isInternalStagingReason(reason) {
  return /非生产|重力信号|仅有重力|重力回填|模拟\/兜底|模拟识别|gravity-fill|gravity-mismatch|mock-v/i.test(
    reason
  );
}
function isRecognitionSucceeded(reason) {
  return /置信度|识别结果需人工|未识别到商品|识别到：|confidence|manual review/i.test(reason);
}
const GENERIC_MANUAL_REVIEW = "商品识别结果需要人工确认，本次暂未扣款。审核完成后会生成账单。";
function consumerDisputeReviewCopy(ticket) {
  const raw = ((ticket == null ? void 0 : ticket.reason) || "").trim();
  const reason = common_vendor.localizeDisputeReason(raw);
  if ((ticket == null ? void 0 : ticket.status) === "RESOLVED") {
    return {
      icon: "✓",
      title: "人工审核已完成",
      detail: reason || "审核结果已生效，可在订单中查看账单。",
      tone: "success"
    };
  }
  if (isServiceUnavailable(raw)) {
    return {
      icon: "!",
      title: "识别服务暂不可用",
      detail: reason || "识别服务暂时不可用，本次暂未扣款。审核完成后会生成账单。",
      tone: "warn"
    };
  }
  if (isRecognitionSucceeded(raw) || isInternalStagingReason(raw)) {
    return {
      icon: "!",
      title: "账单待人工确认",
      detail: reason || "识别结果需人工确认，本次暂未扣款。审核完成后会生成账单。",
      tone: "wait"
    };
  }
  return {
    icon: "!",
    title: "账单审核中",
    detail: reason || GENERIC_MANUAL_REVIEW,
    tone: "wait"
  };
}
function consumerAppealErrorMessage(error, fallback = "提交失败") {
  const raw = error instanceof Error ? error.message : typeof error === "string" ? error : "";
  const msg = (raw || "").trim();
  if (!msg) return fallback;
  if (/本单已结案|不可再申诉|申诉通道已关闭|通道已关闭|关联争议已结案|无法再次退款/i.test(msg)) {
    return "本单已结案，不可再申诉";
  }
  if (/该会话已有申诉|已有申诉工单|已有进行中的申诉/i.test(msg)) {
    return "本单已有申诉，请等待审核结果";
  }
  if (/未开启自助退款|仅可申诉/i.test(msg)) {
    return msg;
  }
  if (/[\u4e00-\u9fff]/.test(msg)) return msg;
  return fallback;
}
exports.consumerAppealErrorMessage = consumerAppealErrorMessage;
exports.consumerDisputeReviewCopy = consumerDisputeReviewCopy;
