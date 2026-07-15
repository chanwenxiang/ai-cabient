"use strict";
function isServiceUnavailable(reason) {
  return /识别服务暂时不可用|识别服务暂不可用|vision/i.test(reason);
}
function isRecognitionSucceeded(reason) {
  return /置信度|识别结果需人工|未识别到商品|识别到：/i.test(reason);
}
function consumerDisputeReviewCopy(ticket) {
  const reason = ((ticket == null ? void 0 : ticket.reason) || "").trim();
  if ((ticket == null ? void 0 : ticket.status) === "RESOLVED") {
    return {
      icon: "✓",
      title: "人工审核已完成",
      detail: reason || "审核结果已生效，可在订单页查看账单。",
      tone: "success"
    };
  }
  if (isServiceUnavailable(reason)) {
    return {
      icon: "!",
      title: "识别服务暂不可用",
      detail: reason || "识别服务暂时不可用，本次暂未扣款。审核完成后会生成账单。",
      tone: "warn"
    };
  }
  if (isRecognitionSucceeded(reason)) {
    return {
      icon: "✓",
      title: "识别完成，待人工确认账单",
      detail: reason.includes("暂未扣款") ? reason : `${reason}。本次暂未扣款，审核完成后会生成账单。`,
      tone: "wait"
    };
  }
  return {
    icon: "!",
    title: "本次账单待人工审核",
    detail: reason || "识别结果需人工审核，本次暂未扣款。审核完成后会生成账单。",
    tone: "wait"
  };
}
exports.consumerDisputeReviewCopy = consumerDisputeReviewCopy;
