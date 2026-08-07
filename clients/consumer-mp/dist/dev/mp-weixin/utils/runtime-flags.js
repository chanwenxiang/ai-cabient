"use strict";
const isDevBuild = true;
function showDevTools() {
  return isDevBuild;
}
function resolveMockEnabled(flag) {
  if (flag === "true") return true;
  if (flag === "false") return false;
  return false;
}
function resolveSandboxRecharge(flag) {
  return flag === "true";
}
function resolveWechatRechargeVisible(opts) {
  if (opts.wechatPayLive === "true") return true;
  return opts.wechatRechargeEnabled === "true";
}
exports.resolveMockEnabled = resolveMockEnabled;
exports.resolveSandboxRecharge = resolveSandboxRecharge;
exports.resolveWechatRechargeVisible = resolveWechatRechargeVisible;
exports.showDevTools = showDevTools;
