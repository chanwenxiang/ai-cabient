/**
 * 营销位 ctaPath 统一校验与打开（2026-10-23 审计 M3）。
 *
 * 背景：marketing/index.vue 的 openPath 与 marketing-ad-banner 的 onTap 各写一套跳转，
 * banner 侧无路径校验且 fail 静默吞掉，坏配置在两处表现不一致。抽此处统一口径。
 */

/** 仅允许本小程序页面路径：/pages/ 前缀、禁止外链 / 协议跳转 / 空白与反斜杠 */
export function isSafeCampaignPath(path?: string | null): boolean {
  if (!path) return false;
  const raw = String(path).trim();
  if (!raw.startsWith('/pages/') || /[\s\\]/.test(raw) || raw.includes('://')) return false;
  return true;
}

/** tabBar 页面根路径（navigateTo 不支持，须 switchTab） */
const TAB_PAGE_ROOTS = ['/pages/index/', '/pages/orders/', '/pages/mine/'];

function isTabPage(pathOnly: string): boolean {
  return TAB_PAGE_ROOTS.some((root) => pathOnly === root.slice(0, -1) || pathOnly.startsWith(root));
}

/**
 * 打开营销位跳转路径。非法路径静默返回（营销位失败不打扰主流程，与列表加载静默口径一致）；
 * tabBar 页走 switchTab，其余走 navigateTo。
 */
export function openCampaignPath(path?: string | null): void {
  if (!isSafeCampaignPath(path)) return;
  const raw = String(path).trim();
  const pathOnly = raw.split('?')[0] || raw;
  if (isTabPage(pathOnly)) {
    uni.switchTab({ url: pathOnly });
    return;
  }
  uni.navigateTo({ url: raw, fail: () => {} });
}
