"use strict";
const DISPUTE_REASON_CHIPS = [
  { label: "没拿这个商品", text: "我没有拿这个商品，请核对识别结果", category: "RECOGNITION" },
  { label: "数量不对", text: "商品数量识别有误，请核对", category: "RECOGNITION" },
  { label: "重复扣款", text: "疑似重复扣款，请核查并退回多扣金额", category: "PAYMENT" },
  { label: "价格有误", text: "商品价格与柜内标价不符", category: "PAYMENT" },
  { label: "申请退款", text: "申请退回本单已扣款项", category: "USER_APPEAL" }
];
function appendChipToReason(current, chip) {
  const base = current.trim();
  if (!base) return chip.text;
  if (base.includes(chip.text)) return base;
  return `${base}；${chip.text}`;
}
exports.DISPUTE_REASON_CHIPS = DISPUTE_REASON_CHIPS;
exports.appendChipToReason = appendChipToReason;
