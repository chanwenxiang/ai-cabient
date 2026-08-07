"use strict";
function isCorruptedMerchantName(name) {
  if (!name) return false;
  const n = name.trim();
  if (!n) return false;
  if (/^\?+$/.test(n)) return true;
  return (n.match(/\?/g) || []).length >= 2 && n.includes("???");
}
function formatMerchantNames(list, emptyLabel = "未绑定商户") {
  const names = (list || []).map((m) => (m.merchantName || "").trim()).filter((n) => n && !isCorruptedMerchantName(n));
  return names.length ? names.join("、") : emptyLabel;
}
exports.formatMerchantNames = formatMerchantNames;
