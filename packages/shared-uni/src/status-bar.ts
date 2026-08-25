/**
 * 自定义顶栏状态栏占位（px）。
 * 小程序 / App：用真实 statusBarHeight；H5 桌面常为 0，回退 44 以模拟手机框。
 */
export function getStatusBarPadPx(fallbackWhenZero = 44, fallbackOnError = 20): number {
  try {
    const info = uni.getSystemInfoSync();
    const h = Number(info?.statusBarHeight) || 0;
    if (h > 0) return h;
    return fallbackWhenZero;
  } catch {
    return fallbackOnError;
  }
}

/**
 * 自定义导航落地页：内容从微信胶囊下方开始。
 * 胶囊 API 未就绪时，用「状态栏 + 胶囊行」估算，避免 env(safe-area) 在微信里为 0。
 */
export function getBelowCapsulePadPx(extraAfterCapsule = 8): number {
  try {
    if (typeof uni.getMenuButtonBoundingClientRect === 'function') {
      const menu = uni.getMenuButtonBoundingClientRect();
      const bottom = Number(menu?.bottom) || 0;
      const top = Number(menu?.top) || 0;
      const height = Number(menu?.height) || 0;
      if (bottom > 20) return Math.ceil(bottom + extraAfterCapsule);
      if (top > 0 && height > 0) return Math.ceil(top + height + extraAfterCapsule);
    }
  } catch {
    /* fall through */
  }
  try {
    const info = uni.getSystemInfoSync();
    const status = Number(info?.statusBarHeight) || 0;
    // 胶囊高约 32px，与状态栏之间约 6–8px
    if (status > 0) return Math.ceil(status + 32 + 8 + extraAfterCapsule);
  } catch {
    /* fall through */
  }
  return 88 + extraAfterCapsule;
}
