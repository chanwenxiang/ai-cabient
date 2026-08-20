<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app';
import { ensureConsumerAuth, getConsumerToken } from '@/utils/consumer-api';
import { loadRuntimeDict } from '@/utils/dict-runtime';
import { redirectIfAlipayReturn } from '@/utils/recharge';

onLaunch(async () => {
  // 支付宝同步回跳常落在站点根路径（无 hash），先导回充值页再鉴权
  redirectIfAlipayReturn();
  await ensureConsumerAuth();
  if (getConsumerToken()) {
    await loadRuntimeDict();
  }
});
</script>

<style>
@import '@aicabinet/shared-uni/theme.css';

/* H5：html/body 与 page 同源字体，避免壳层落成浏览器默认 Noto */
html,
body,
#app {
  font-family: var(--app-font);
  font-size: var(--app-font-size-root, 14px);
}

/* 微信小程序 page 节点标准：height 100% 供 tabBar 页 flex 一屏布局 */
page,
uni-page-body {
  /* 唯一品牌绿阶：深底 / 主色 / 浅底；禁止另掺 teal 灰绿 */
  --brand: #047857;
  --brand-2: #047857;
  --brand-deep: #064e3b;
  --brand-ink: #043f32;
  --brand-wx: #07c160;
  --brand-soft: #ecfdf5;
  --brand-mist: #d1fae5;
  --page-bg: #ffffff;
  --text-muted: #64748b;
  --text-subtle: #94a3b8;
  --card-radius: 24rpx;
  --text-primary: #14201b;
  height: 100%;
  background-color: var(--page-bg);
  font-family: var(--app-font);
  font-size: 28rpx;
  color: var(--text-primary);
  box-sizing: border-box;
  overflow-x: hidden;
  scrollbar-width: none;
  -ms-overflow-style: none;
}
page::-webkit-scrollbar,
uni-page-body::-webkit-scrollbar {
  width: 0;
  height: 0;
  display: none;
}

.page-root {
  min-height: 100%;
  box-sizing: border-box;
  overflow-x: hidden;
  background: var(--page-bg);
}

/* 自定义顶栏：与系统 uni-page-head（48px）对齐，禁止负 margin 顶穿圆角 */

/* 底栏/粘性条：隔离层，避免列表内容盖住或透视 */
.cart-bar,
.app-footer-bar {
  isolation: isolate;
  background-clip: padding-box;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  padding: 10rpx 22rpx;
  border-radius: 999rpx;
  font-size: 24rpx;
  color: var(--text-muted);
  background: #fff;
  border: 1rpx solid rgba(15, 118, 110, 0.12);
  white-space: nowrap;
}
.filter-chip.active {
  color: #fff;
  background: var(--brand);
  border-color: var(--brand);
  font-weight: 600;
}

.tabs-pill {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-bottom: 20rpx;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16rpx;
  padding: 12rpx 0;
  font-size: 26rpx;
}
.info-row .lbl,
.info-label {
  color: var(--text-muted);
  flex-shrink: 0;
}
.info-row .val,
.info-value {
  color: var(--text-primary);
  text-align: right;
  word-break: break-all;
}

.empty-title {
  display: block;
  font-size: 32rpx;
  font-weight: 650;
  color: var(--text-primary);
  margin-bottom: 8rpx;
}
.empty-desc {
  display: block;
  font-size: 26rpx;
  color: var(--text-muted);
  line-height: 1.45;
  margin-bottom: 24rpx;
}

button::after {
  border: none !important;
}

/* 微信/H5 input：真机仅靠 padding 易被压扁，需显式高度 */
input {
  box-sizing: border-box;
  min-height: 88rpx;
  height: 88rpx;
  font-size: 28rpx;
  line-height: 1.4;
  padding: 0 24rpx;
}

.card {
  background: #fff;
  border-radius: 24rpx;
  padding: 24rpx;
  margin: 0 24rpx 16rpx;
  border: 1rpx solid rgba(15, 118, 110, 0.06);
  box-shadow: 0 10rpx 32rpx rgba(15, 23, 42, 0.055);
}

.meta {
  color: #888;
  font-size: 26rpx;
}

.btn-primary,
.action-btn,
.cart-cta,
.empty-btn.primary {
  background: linear-gradient(135deg, var(--brand), var(--brand-2));
  color: #fff;
  border: none;
  border-radius: 44rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  box-shadow: 0 8rpx 24rpx rgba(5, 150, 105, 0.2);
  box-sizing: border-box;
}

/* 主按钮：单独出现时收窄居中；通栏用 .btn-block；横向行内均分 */
.btn-primary,
.btn-outline,
.btn-refund,
.btn-ghost,
.action-btn,
.ghost-btn,
.empty-btn.primary,
.empty-btn.ghost,
uni-button.btn-primary,
uni-button.btn-outline,
uni-button.btn-refund,
uni-button.btn-ghost,
uni-button.action-btn,
uni-button.ghost-btn {
  width: fit-content;
  min-width: 240rpx;
  max-width: 100%;
  padding-left: 36rpx;
  padding-right: 36rpx;
  margin-left: auto;
  margin-right: auto;
  align-self: center;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}

.btn-block,
uni-button.btn-block {
  width: 100% !important;
  max-width: none !important;
  min-width: 0 !important;
  align-self: stretch !important;
}

/* 纵向操作区：通栏等宽 + 文字居中（订单详情 / 账单结果 / 争议等） */
.actions,
.btn-stack,
.detail-actions,
.footer-actions,
.error-card {
  display: flex;
  flex-direction: column;
  align-items: stretch;
}
.actions > uni-button + uni-button,
.actions > button + button,
.btn-stack > uni-button + uni-button,
.btn-stack > button + button,
.detail-actions > uni-button + uni-button,
.detail-actions > button + button,
.footer-actions > uni-button + uni-button,
.footer-actions > button + button,
.error-card > uni-button + uni-button,
.error-card > button + button {
  margin-top: 16rpx !important;
}

.actions > .btn-primary,
.actions > .btn-outline,
.actions > .btn-refund,
.actions > .btn-ghost,
.actions > .action-btn,
.actions > .ghost-btn,
.actions > .primary-btn,
.btn-stack > .btn-primary,
.btn-stack > .btn-outline,
.btn-stack > .btn-refund,
.btn-stack > .btn-ghost,
.btn-stack > .action-btn,
.btn-stack > .ghost-btn,
.detail-actions > .btn-primary,
.detail-actions > .btn-outline,
.detail-actions > .primary-btn,
.footer-actions > .action-btn,
.footer-actions > .ghost-btn,
.footer-actions > .btn-primary,
.footer-actions > .btn-outline,
.error-card > .action-btn,
.error-card > .ghost-btn,
.error-card > .btn-primary,
.error-card > .btn-outline,
.actions > uni-button,
.btn-stack > uni-button,
.detail-actions > uni-button,
.footer-actions > uni-button,
.error-card > uni-button {
  width: 100% !important;
  max-width: none !important;
  min-width: 0 !important;
  margin-left: 0 !important;
  margin-right: 0 !important;
  align-self: stretch !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  text-align: center !important;
  box-sizing: border-box !important;
}

.action-row > .btn-primary,
.action-row > .btn-outline,
.btn-inline {
  flex: 1 1 0;
  width: 0;
  min-width: 0;
  max-width: none;
  margin: 0;
  align-self: stretch;
  padding-left: 16rpx;
  padding-right: 16rpx;
}

.empty-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
  width: 100%;
}
/* 微信小程序对 flex gap 支持不稳：竖排空态按钮用相邻 margin */
.empty-actions > .empty-btn + .empty-btn,
.empty-actions > .btn-primary + .empty-btn,
.empty-actions > .empty-btn + .btn-primary,
.empty-actions > .btn-primary + .btn-outline,
.empty-actions > .btn-outline + .btn-primary,
.empty-actions > uni-button + uni-button,
.empty-actions > button + button {
  margin-top: 16rpx !important;
}
.empty-actions > .empty-btn,
.empty-actions > .btn-primary,
.empty-actions > .btn-outline,
.empty-actions > .action-btn,
.empty-actions > .ghost-btn,
.empty-actions > uni-button {
  width: 100% !important;
  max-width: none !important;
  min-width: 0 !important;
  margin-left: 0 !important;
  margin-right: 0 !important;
  margin-bottom: 0 !important;
  align-self: stretch !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  text-align: center !important;
  box-sizing: border-box !important;
}

.btn-outline,
.btn-ghost,
.ghost-btn,
.empty-btn.ghost {
  background: #fff;
  color: var(--brand);
  border: 2rpx solid var(--brand);
  border-radius: 44rpx;
  font-weight: 600;
  box-sizing: border-box;
}

.btn-outline.danger,
.btn-refund {
  border-color: #ef4444;
  color: #b91c1c;
}

.btn-refund {
  background: linear-gradient(135deg, #dc2626, #ef4444);
  color: #fff;
  border: none;
  border-radius: 44rpx;
  font-weight: 600;
  box-shadow: 0 8rpx 24rpx rgba(239, 68, 68, 0.22);
}

button,
.btn-primary,
.btn-ghost,
.btn-outline,
.menu-cell,
.filter-chip,
.scan-btn,
.tip-btn {
  transition:
    transform 0.18s ease,
    opacity 0.18s ease,
    box-shadow 0.18s ease;
}
button:active,
.btn-primary:active,
.btn-ghost:active,
.btn-outline:active,
.menu-cell:active,
.filter-chip:active,
.tip-btn:active {
  transform: scale(0.985);
  opacity: 0.88;
}

@media (prefers-reduced-motion: reduce) {
  button,
  .btn-primary,
  .btn-ghost,
  .btn-outline,
  .menu-cell,
  .filter-chip,
  .scan-btn,
  .tip-btn {
    transition: none;
  }
  button:active,
  .btn-primary:active,
  .btn-ghost:active,
  .btn-outline:active,
  .menu-cell:active,
  .filter-chip:active,
  .tip-btn:active {
    transform: none;
    opacity: 1;
  }
}

/* 桌面手机框：居中真机比例；消掉导航双占位；底栏贴框底（仅 H5；WXSS 不支持 ~ 等选择器） */
/* #ifdef H5 */
@media (min-width: 600px) {
  html,
  body,
  #app,
  uni-app {
    height: 100%;
    overflow: hidden;
    background: #e8eef2;
  }
  uni-app {
    position: relative;
    --phone-w: 390px;
    --phone-h: min(720px, calc(100vh - 48px));
    --phone-inset: max(0px, calc((100vh - var(--phone-h)) / 2));
  }
  uni-page {
    position: absolute !important;
    top: 50% !important;
    left: 50% !important;
    bottom: auto !important;
    transform: translate(-50%, -50%) !important;
    width: var(--phone-w) !important;
    height: var(--phone-h) !important;
    max-height: calc(100vh - 48px) !important;
    margin: 0 !important;
    border-radius: 28px !important;
    overflow: hidden !important;
    display: flex !important;
    flex-direction: column !important;
    box-shadow: 0 22px 70px rgba(15, 23, 42, 0.14);
    /* 内容区统一白底；顶栏绿色由 app-nav 自己铺，勿再把整壳染成深绿 */
    background-color: #ffffff;
    box-sizing: border-box;
    /* 导航改文档流后，清掉 uni 为 fixed 预留的顶栏偏移 */
    --window-top: 0px !important;
  }
  uni-page-head,
  .uni-page-head {
    flex: 0 0 auto !important;
    width: 100% !important;
    max-width: 100% !important;
    margin: 0 !important;
    position: relative !important;
    left: 0 !important;
    right: 0 !important;
    top: auto !important;
    height: 48px !important;
    min-height: 48px !important;
    /* 与自定义顶栏：44px 状态栏占位 + 48px 行 */
    padding: 44px 8px 0 !important;
    box-sizing: content-box !important;
    z-index: 2 !important;
    flex-shrink: 0 !important;
    display: flex !important;
    align-items: center !important;
    border-radius: 0 !important;
    background-clip: border-box !important;
  }
  /* 自定义导航页：禁止把已隐藏的系统头强行 display:flex，否则会出现错位叠层绿条 */
  uni-page-head:not([uni-page-head-type='default']),
  .uni-page-head:not([uni-page-head-type='default']),
  uni-page-head[style*='display: none'],
  .uni-page-head[style*='display: none'] {
    display: none !important;
    height: 0 !important;
    min-height: 0 !important;
    max-height: 0 !important;
    padding: 0 !important;
    margin: 0 !important;
    border: none !important;
    overflow: hidden !important;
    visibility: hidden !important;
    pointer-events: none !important;
  }
  /* 保证系统返回键不被挤没 */
  .uni-page-head-hd,
  .uni-page-head .uni-page-head-btn,
  .uni-page-head-btn {
    flex: 0 0 auto !important;
    flex-shrink: 0 !important;
    min-width: 44px !important;
    height: 48px !important;
    display: flex !important;
    align-items: center !important;
    justify-content: center !important;
    visibility: visible !important;
    opacity: 1 !important;
    overflow: visible !important;
  }
  .uni-page-head-bd {
    flex: 1 1 auto !important;
    min-width: 0 !important;
    top: 50% !important;
    transform: translateY(-50%) !important;
    height: auto !important;
    line-height: 1.2 !important;
  }
  .uni-page-head__title {
    line-height: 48px !important;
  }
  /* 自定义顶栏：勿覆盖组件内状态栏占位与内联样式 */
  .page-nav {
    /* 状态栏占位由组件/页面 border-top 负责，这里只锁行高 */
    height: 48px !important;
    min-height: 48px !important;
    box-sizing: content-box !important;
    align-items: center !important;
  }
  .app-nav-row {
    height: 48px !important;
  }
  .app-nav-title,
  .nav-title,
  .app-nav-chevron {
    line-height: 48px !important;
  }
  .app-nav-back,
  .app-nav-side,
  .nav-back,
  .nav-side {
    height: 48px !important;
    width: 44px !important;
  }
  /* 关键原因：head 已 relative，占位条再占 44px → 标题下大块空白 */
  .uni-page-head ~ .uni-placeholder,
  uni-page-head ~ .uni-placeholder {
    display: none !important;
    height: 0 !important;
    max-height: 0 !important;
    margin: 0 !important;
    padding: 0 !important;
    overflow: hidden !important;
  }
  uni-page-head[uni-page-head-type='default'] ~ uni-page-wrapper {
    height: 0 !important;
    flex: 1 1 0 !important;
    min-height: 0 !important;
  }
  uni-page-wrapper {
    flex: 1 1 0 !important;
    width: 100% !important;
    min-height: 0 !important;
    height: 0 !important;
    max-height: 100% !important;
    display: flex !important;
    flex-direction: column !important;
    position: relative !important;
    overflow: hidden !important;
    background: #ffffff !important;
    padding-top: 0 !important;
  }
  /* 桌面手机框：一律隐藏滚动条，保留滑动 */
  #app,
  uni-app,
  uni-page,
  uni-page-wrapper,
  uni-page-body,
  uni-page * {
    scrollbar-width: none !important;
    -ms-overflow-style: none !important;
  }
  #app::-webkit-scrollbar,
  uni-app::-webkit-scrollbar,
  uni-page::-webkit-scrollbar,
  uni-page-wrapper::-webkit-scrollbar,
  uni-page-body::-webkit-scrollbar,
  uni-page *::-webkit-scrollbar {
    width: 0 !important;
    height: 0 !important;
    display: none !important;
    background: transparent !important;
  }
  uni-page-body {
    position: relative !important;
    left: auto !important;
    bottom: auto !important;
    transform: none !important;
    flex: 1 1 0 !important;
    width: 100% !important;
    height: 100% !important;
    min-height: 0 !important;
    max-height: 100% !important;
    margin: 0 !important;
    padding-top: 0 !important;
    border-radius: 0 !important;
    overflow-x: hidden !important;
    overflow-y: auto !important;
    -webkit-overflow-scrolling: touch;
    overscroll-behavior: contain;
    display: flex !important;
    flex-direction: column !important;
    box-shadow: none !important;
    box-sizing: border-box;
    background-color: #ffffff;
    scrollbar-width: none !important;
    -ms-overflow-style: none !important;
  }
  /*
   * 长页默认仍交给 page-body；.page-fill 用于一屏 flex / 内嵌 scroll-view。
   */
  .page-root:not(.page-fill),
  .page:not(.page-fill) {
    height: auto !important;
    min-height: 100% !important;
    flex: 1 0 auto !important;
    max-height: none !important;
    overflow: visible !important;
  }
  .page-fill {
    position: relative !important;
    height: 100% !important;
    max-height: 100% !important;
    overflow: hidden !important;
    display: flex !important;
    flex-direction: column !important;
    flex: 1 1 0 !important;
    min-height: 0 !important;
    width: 100% !important;
  }
  /* 内嵌滚动：flex 占满剩余高度，勿用 absolute 顶死顶栏 */
  .page-fill > .page-scroll {
    position: relative !important;
    top: auto !important;
    left: auto !important;
    right: auto !important;
    bottom: auto !important;
    flex: 1 1 0 !important;
    height: 0 !important;
    min-height: 0 !important;
    max-height: none !important;
    overflow-x: hidden !important;
    overflow-y: auto !important;
    -webkit-overflow-scrolling: touch !important;
    overscroll-behavior: contain;
    scrollbar-width: none !important;
    -ms-overflow-style: none !important;
  }
  .page-fill > .page-scroll::-webkit-scrollbar {
    width: 0 !important;
    height: 0 !important;
    display: none !important;
  }
  .mine-page {
    height: 100% !important;
    max-height: 100% !important;
    overflow-x: hidden !important;
    overflow-y: auto !important;
    scrollbar-width: none !important;
    -ms-overflow-style: none !important;
  }
  .page-fill::-webkit-scrollbar,
  .mine-page::-webkit-scrollbar {
    width: 0 !important;
    height: 0 !important;
    display: none !important;
  }
  uni-page-body::-webkit-scrollbar,
  .list::-webkit-scrollbar,
  uni-scroll-view::-webkit-scrollbar,
  .uni-scroll-view::-webkit-scrollbar,
  .uni-scroll-view-content::-webkit-scrollbar {
    width: 0 !important;
    height: 0 !important;
    display: none !important;
  }
  .uni-tabbar,
  .uni-tabbar-bottom,
  uni-tabbar {
    max-width: var(--phone-w) !important;
    width: var(--phone-w) !important;
    left: 0 !important;
    right: 0 !important;
    margin-left: auto !important;
    margin-right: auto !important;
    bottom: var(--phone-inset) !important;
    border-radius: 0 !important;
  }
}
/* #endif */
</style>
