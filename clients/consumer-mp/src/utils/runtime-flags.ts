/**
 * 消费者端运行时开关：生产构建剥离调试/模拟入口，对齐竞品 C 端体验。
 * - showDevTools：仅开发构建显示柜号手输、模拟充值、沙箱入口等
 * - resolveMockEnabled：生产构建永不开启 mock，即使后端返回 true
 */

export const isDevBuild = !!import.meta.env.DEV;

/** 是否展示开发联调工具（手输柜号、模拟充值、沙箱等） */
export function showDevTools(): boolean {
  return isDevBuild;
}

/**
 * 解析后端 consumer-config.mockEnabled。
 * 生产包强制 false，避免误开模拟充值。
 */
export function resolveMockEnabled(flag?: string): boolean {
  if (!isDevBuild) return false;
  if (flag === 'true') return true;
  if (flag === 'false') return false;
  return true;
}

/** 支付宝沙箱充值：仅开发构建且后端开启时展示 */
export function resolveSandboxRecharge(flag?: string): boolean {
  if (!isDevBuild) return false;
  return flag === 'true';
}

/** 微信充值入口：生产仅 live；开发允许 mock 通道 */
export function resolveWechatRechargeVisible(opts: {
  wechatRechargeEnabled?: string;
  wechatPayLive?: string;
}): boolean {
  if (opts.wechatPayLive === 'true') return true;
  if (!isDevBuild) return false;
  return opts.wechatRechargeEnabled === 'true' || isDevBuild;
}
