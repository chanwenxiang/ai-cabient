/**
 * 小程序端运行时开关：生产构建剥离调试/模拟入口。
 * - showDevTools：仅开发构建显示柜号手输、模拟充值、沙箱入口等
 * - resolveMockEnabled / resolveSandboxRecharge：生产构建永不开启 mock/沙箱
 *
 * 注意：`uni build --mode development` 时 import.meta.env.DEV 仍为 false，
 * 需同时认 MODE===development，否则本地联调会误关充值入口。
 */

export const isDevBuild =
  !!import.meta.env.DEV || String(import.meta.env.MODE || '').toLowerCase() === 'development';

/** 是否展示开发联调工具（手输柜号、模拟充值、沙箱等） */
export function showDevTools(): boolean {
  return isDevBuild;
}

/** 解析后端 mockEnabled：生产包强制 false，避免误开模拟充值。 */
export function resolveMockEnabled(flag?: string): boolean {
  if (!isDevBuild) return false;
  return flag === 'true';
}

/** 支付宝沙箱充值：仅开发构建且后端开启时展示 */
export function resolveSandboxRecharge(flag?: string): boolean {
  if (!isDevBuild) return false;
  return flag === 'true';
}

/** 微信充值入口：生产仅 live；开发在后端开启 mock 充值或 live 时展示 */
export function resolveWechatRechargeVisible(opts: {
  wechatRechargeEnabled?: string;
  wechatPayLive?: string;
}): boolean {
  if (opts.wechatPayLive === 'true') return true;
  if (!isDevBuild) return false;
  return opts.wechatRechargeEnabled === 'true';
}
