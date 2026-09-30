/**
 * 自定义 tabBar（微信端）：替代原生 tabBar。
 *
 * 为什么自研：安卓真机部分基础库在 tab 页 hideTabBar 后底部残留原生 tabBar 白底区域，
 * page 元素背景与 json 背景配置都盖不住（官方确认的 bug，2026-09-30 真机两轮复验）。
 * custom 模式下页面变为全屏、本组件悬浮其上，无原生占位区域可残留。
 *
 * 约定：
 * - 每个 tab 页各持有一个组件实例，页面 onShow 时用 utils/custom-tabbar.ts 同步 selected/hidden。
 * - index 落地态 hidden=true（组件不渲染），购物态及其余 tab 页 false。
 * - uni.showTabBar/hideTabBar 对 custom 模式同样作用于包裹层，原有调用保持不变。
 */
Component({
  data: {
    hidden: false,
    selected: 0,
    color: '#86a89a',
    selectedColor: '#0f766e',
    list: [
      {
        pagePath: '/pages/index/index',
        text: '首页',
        iconPath: '/static/tab/home.png',
        selectedIconPath: '/static/tab/home-active.png'
      },
      {
        pagePath: '/pages/orders/orders',
        text: '订单',
        iconPath: '/static/tab/orders.png',
        selectedIconPath: '/static/tab/orders-active.png'
      },
      {
        pagePath: '/pages/mine/mine',
        text: '我的',
        iconPath: '/static/tab/mine.png',
        selectedIconPath: '/static/tab/mine-active.png'
      }
    ]
  },
  methods: {
    switchTab(e) {
      const { path, index } = e.currentTarget.dataset;
      if (this.data.selected === index) return;
      wx.switchTab({ url: path });
      this.setData({ selected: index });
    }
  }
});
