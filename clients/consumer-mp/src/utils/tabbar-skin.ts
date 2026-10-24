/**
 * tabBar 深浅双皮肤（2026-10-21 用户反馈：白色 tabBar 贴深绿落地页不和谐）。
 *
 * - `landing`（深绿沉浸落地页）：背景 #115e59、选中白、图标用深底变体
 *   （未选中提亮灰绿 / 选中 teal-300，`src/static/tab/*-darkbg*.png`）；
 * - `shop`（购物/订单/我的，白底）：pages.json 的默认配色 + 默认图标。
 *
 * ⚠️ `uni.setTabBarStyle`/`setTabBarItem` 是**持久设置** ⇒ 每个 tab 页 onShow
 * 都必须显式套用本页应有的皮肤，否则上一页的皮肤会残留（深绿 tabBar 贴白页）。
 * H5 等平台无实现 ⇒ 全部 fail 静默。
 */
export interface TabBarSkinSpec {
  style: {
    backgroundColor: string;
    color: string;
    selectedColor: string;
    borderStyle: 'black' | 'white';
  };
  iconSuffix: '' | '-darkbg';
}

const TABBAR_SKIN: Record<'landing' | 'shop', TabBarSkinSpec> = {
  landing: {
    style: {
      backgroundColor: '#115e59',
      color: '#9cbfb6',
      selectedColor: '#ffffff',
      borderStyle: 'white'
    },
    iconSuffix: '-darkbg'
  },
  shop: {
    style: {
      backgroundColor: '#ffffff',
      color: '#86a89a',
      selectedColor: '#0d9488',
      borderStyle: 'black'
    },
    iconSuffix: ''
  }
};

export const TABBAR_ITEM_NAMES = ['home', 'orders', 'mine'] as const;

/** 按页面形态套用 tabBar 皮肤：落地页传 true，其余（购物/订单/我的）传 false。 */
export function applyTabBarSkin(landing: boolean): void {
  const skin = landing ? TABBAR_SKIN.landing : TABBAR_SKIN.shop;
  uni.setTabBarStyle({ ...skin.style, fail: () => {} });
  TABBAR_ITEM_NAMES.forEach((name, index) => {
    uni.setTabBarItem({
      index,
      iconPath: `/static/tab/${name}${skin.iconSuffix}.png`,
      selectedIconPath: `/static/tab/${name}${skin.iconSuffix}-active.png`,
      fail: () => {
        /* 平台无实现，忽略 */
      }
    });
  });
}
