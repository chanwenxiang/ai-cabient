/** 微信小程序窗口/安全区信息（对齐 wx.getWindowInfo / getSystemInfoSync） */
export interface WxLayoutInfo {
  /** 可用窗口高度 px（tabBar 页已扣除导航栏与 tabBar） */
  windowHeight: number;
  windowWidth: number;
  statusBarHeight: number;
  safeAreaBottom: number;
}

export function getWxLayout(): WxLayoutInfo {
  try {
    const win = uni.getWindowInfo?.();
    if (win) {
      const insetBottom = win.screenHeight - (win.safeArea?.bottom ?? win.screenHeight);
      return {
        windowHeight: win.windowHeight,
        windowWidth: win.windowWidth,
        statusBarHeight: win.statusBarHeight ?? 0,
        safeAreaBottom: insetBottom > 0 ? insetBottom : 0
      };
    }
  } catch {
    /* fallback */
  }
  const sys = uni.getSystemInfoSync();
  const safeBottom = sys.safeArea ? sys.screenHeight - sys.safeArea.bottom : 0;
  return {
    windowHeight: sys.windowHeight,
    windowWidth: sys.windowWidth,
    statusBarHeight: sys.statusBarHeight ?? 0,
    safeAreaBottom: safeBottom
  };
}
