/**
 * 自定义 tabBar（微信端 custom-tab-bar）状态同步。
 *
 * 微信的自定义 tabBar 是「每个 tab 页各持有一个组件实例」，页面 onShow / 落地态变化时
 * 必须由页面自己 setData 同步 selected / hidden，否则高亮错页或落地页露出底栏。
 * 非 mp-weixin 端无 getTabBar，静默跳过。
 */

interface TabBarInstance {
  setData: (data: Record<string, unknown>) => void;
}

interface PageWithTabBar {
  getTabBar?: () => TabBarInstance | null | undefined;
}

export function syncCustomTabBar(selected: number, hidden = false) {
  // #ifdef MP-WEIXIN
  const apply = () => {
    const pages = getCurrentPages();
    const page = pages[pages.length - 1] as unknown as PageWithTabBar | undefined;
    try {
      page?.getTabBar?.()?.setData?.({ selected, hidden });
    } catch {
      /* 组件未就绪时静默；onShow 会再次同步 */
    }
  };
  apply();
  // 冷启动首帧组件可能尚未挂载（onShow 早于组件 attached），延迟补一次
  setTimeout(apply, 300);
  // #endif
}
