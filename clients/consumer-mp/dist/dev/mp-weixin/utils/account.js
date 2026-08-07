"use strict";
const common_vendor = require("../common/vendor.js");
const MIN_BALANCE_CENTS = common_vendor.DEFAULT_PREAUTH_CENTS;
function normalizeEntryChannel(channel) {
  const c = String(channel || "").trim().toUpperCase();
  if (c === "WECHAT" || c === "ALIPAY") return c;
  return null;
}
function detectRuntimeEntryChannel() {
  return "WECHAT";
}
function resolveEntryChannel(scanChannel) {
  return normalizeEntryChannel(scanChannel) || detectRuntimeEntryChannel();
}
function availableCents(acc) {
  if (!acc) return 0;
  if (acc.availableCents != null) return Math.max(0, acc.availableCents);
  return Math.max(0, (acc.balanceCents || 0) - Math.max(0, acc.frozenCents || 0));
}
function resolveClientPreauthCents(opts) {
  const device = Number((opts == null ? void 0 : opts.devicePreauthCents) ?? (opts == null ? void 0 : opts.deviceDepositCents));
  if (Number.isFinite(device) && device > 0) return Math.floor(device);
  const cfg = Number(opts == null ? void 0 : opts.configPreauthCents);
  if (Number.isFinite(cfg) && cfg > 0) return Math.floor(cfg);
  return MIN_BALANCE_CENTS;
}
function preauthYuanLabel(preauthCents = MIN_BALANCE_CENTS) {
  const yuan = Math.max(preauthCents, 1) / 100;
  return Number.isInteger(yuan) ? String(yuan) : yuan.toFixed(2);
}
function isPayReady(acc, entryChannel, preauthCents = MIN_BALANCE_CENTS) {
  if (!acc) return false;
  if (acc.operator) return true;
  const channel = normalizeEntryChannel(entryChannel);
  if (channel === "WECHAT") {
    if (acc.payscoreEnabled) return true;
  } else if (channel === "ALIPAY") {
    if (acc.alipayAgreementEnabled) return true;
  } else if (acc.passwordFreeReady) {
    return true;
  }
  return availableCents(acc) >= Math.max(preauthCents, 1);
}
function payReadyHint(acc, entryChannel, preauthCents = MIN_BALANCE_CENTS) {
  if (!acc) return "请先登录";
  if (!acc.verified) return "需先完成实名认证";
  const needYuan = preauthYuanLabel(preauthCents);
  const channel = normalizeEntryChannel(entryChannel);
  if (channel === "WECHAT") {
    if (acc.payscoreEnabled) return "已开通微信支付分，购物后自动扣款";
    if (availableCents(acc) >= preauthCents) return `可用余额可预授权开门（约 ¥${needYuan}）`;
    return `请开通微信支付分（推荐），或充值可用余额至 ¥${needYuan} 以上`;
  }
  if (channel === "ALIPAY") {
    if (acc.alipayAgreementEnabled) return "已开通支付宝免密，购物后自动扣款";
    if (availableCents(acc) >= preauthCents) return `可用余额可预授权开门（约 ¥${needYuan}）`;
    return `请开通支付宝免密代扣（推荐），或充值可用余额至 ¥${needYuan} 以上`;
  }
  if (acc.passwordFreeReady) return "已开通免密支付";
  if (availableCents(acc) >= preauthCents) return `可用余额可预授权开门（约 ¥${needYuan}）`;
  return `请开通微信/支付宝免密，或充值可用余额至 ¥${needYuan} 以上`;
}
exports.availableCents = availableCents;
exports.isPayReady = isPayReady;
exports.normalizeEntryChannel = normalizeEntryChannel;
exports.payReadyHint = payReadyHint;
exports.preauthYuanLabel = preauthYuanLabel;
exports.resolveClientPreauthCents = resolveClientPreauthCents;
exports.resolveEntryChannel = resolveEntryChannel;
