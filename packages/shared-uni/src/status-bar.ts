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
