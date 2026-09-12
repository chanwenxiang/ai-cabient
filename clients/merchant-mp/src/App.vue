<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app';
import { loadRuntimeDict } from '@/utils/dict-runtime';
import { getToken, installMerchantNavGuard, isMerchantLoginPath } from '@/utils/merchant-api';

/** H5 / 微信小程序通用：当前是否登录页（避免无 token 深链先闪业务页）。 */
function isLoginLaunch(options?: { path?: string }): boolean {
  const launchPath = String(options?.path || '');
  if (isMerchantLoginPath(launchPath) || launchPath.includes('pages/login/login')) {
    return true;
  }
  if (typeof location !== 'undefined') {
    const href = String(location.href || '');
    if (href.includes('/pages/login/login')) return true;
  }
  try {
    const pages = getCurrentPages();
    const route = String(pages[pages.length - 1]?.route || '');
    if (isMerchantLoginPath(route)) return true;
  } catch {
    /* launch 早期可能尚无页面栈 */
  }
  return false;
}

onLaunch((options) => {
  installMerchantNavGuard();
  if (!getToken() && !isLoginLaunch(options)) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  if (!getToken()) return;
  void loadRuntimeDict();
});
</script>

<style>
@import '@aicabinet/shared-uni/theme.css';

/* H5：html/body 与 page 同源字体，根字号与消费者对齐（28rpx @ 375） */
html,
body,
#app {
  font-family: var(--app-font);
  font-size: var(--app-font-size-root, 14px);
}

page,
uni-page-body {
  --brand: #0f766e;
  --brand-deep: #134e4a;
  --brand-soft: #ecfdf5;
  --brand-tint: #ccfbf1;
  --brand-mist: #ccfbf1;
  --color-primary: var(--brand);
  --color-primary-deep: var(--brand-deep);
  --color-primary-soft: var(--brand-soft);
  --color-link: var(--brand);
  --color-danger: var(--danger, #b91c1c);
  --color-link-secondary: #576b95;
  --color-text-primary: var(--text-primary, #0f172a);
  --color-text-secondary: var(--text-muted, #64748b);
  --color-text-muted: var(--text-subtle, #94a3b8);
  --color-bg-page: var(--page-bg);
  --page-tint: #f0fdfa;
  --page-bg: #ffffff;
  --page-gutter: 24rpx;
  --spacing-sm: 12rpx;
  --spacing-md: 16rpx;
  --spacing-lg: 24rpx;
  --shadow-card: 0 8rpx 28rpx rgba(15, 118, 110, 0.08);
  --font-size-xs: 20rpx;
  --font-size-sm: 22rpx;
  --font-size-caption: 24rpx;
  --font-size-body: 26rpx;
  --font-size-md: 28rpx;
  --font-size-lg: 30rpx;
  --font-size-xl: 32rpx;
  --font-size-h3: 34rpx;
  --font-size-h2: 40rpx;
  --font-size-h1: 44rpx;
  --font-size-display-sm: 36rpx;
  --font-size-display: 48rpx;
  --text-muted: #64748b;
  --text-subtle: #94a3b8;
  /* 小程序圆角用 rpx，对齐共享 4 档 token */
  --radius-pill: 999rpx;
  --radius-card: 24rpx;
  --radius-panel: 16rpx;
  --radius-control: 12rpx;
  --radius-tag: 8rpx;
  --card-radius: var(--radius-card);
  height: 100%;
  background: var(--page-bg);
  font-family: var(--app-font);
  font-size: 28rpx;
  color: var(--text-primary, #0f172a);
  overflow-x: hidden;
  box-sizing: border-box;
}

.page-root {
  min-height: 100%;
  box-sizing: border-box;
  overflow-x: hidden;
  padding: 0;
  background: var(--page-bg);
}

/* 顶栏通栏：不再用负 margin 抵消页面 padding；内容区用 .page-body */
.page-root > .app-nav,
.page > .app-nav {
  margin: 0;
  width: 100%;
  box-sizing: border-box;
}

/* 粘性筛选/底栏：不透明底 + 隔离层，避免列表内容透视/盖住 */
.filters,
.action-dock,
.app-footer-bar {
  isolation: isolate;
  background-clip: padding-box;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  padding: 10rpx 22rpx;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  background: var(--card-bg, #fff);
  border: 1rpx solid var(--card-border, #e2e8f0);
  white-space: nowrap;
}
.filter-chip.active {
  color: var(--white);
  background: var(--brand);
  border-color: var(--brand);
  font-weight: 600;
}

.tabs-pill {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-bottom: var(--section-gap, 20rpx);
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
  color: var(--text-primary, #0f172a);
  text-align: right;
  word-break: break-all;
}

.banner-err {
  color: var(--danger, #b91c1c);
  font-size: 26rpx;
}

/* uni-app button 默认 1px 边框 */
button::after {
  border: none !important;
}

/* H5/微信：原生 button 文案居中（theme.css 可能被 uni 默认 display 覆盖） */
button,
uni-button {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  text-align: center !important;
  line-height: 1.2 !important;
}

/* 微信/H5 input：避免仅靠 padding 导致占位符被裁切 */
input {
  box-sizing: border-box;
  min-height: 72rpx;
  font-size: 28rpx;
  line-height: 1.4;
}

/*
 * 与 consumer 对齐（M02）：水平 gutter 由 .page-body / 页面自管；
 * .card 只留纵向间距，避免「page-body 已有 padding + card 四边 gutter」双缩进。
 */
.card {
  background: var(--color-bg-card, #fff);
  border-radius: var(--radius-card, 24rpx);
  padding: 24rpx;
  margin: 0 0 16rpx;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  box-shadow: var(--shadow-card);
  border: 1rpx solid color-mix(in srgb, var(--brand) 6%, transparent);
}

.page-body {
  padding-left: var(--page-gutter, 24rpx);
  padding-right: var(--page-gutter, 24rpx);
  box-sizing: border-box;
}

.app-btn--primary {
  background: linear-gradient(135deg, var(--brand-deep), var(--brand));
  color: var(--white);
  border: none;
  border-radius: var(--radius-pill, 44rpx);
  padding: 0 32rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 88rpx;
  text-align: center;
  font-weight: 600;
  font-size: 30rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.22);
  box-sizing: border-box;
}

.btn-block,
.app-btn--block {
  width: 100% !important;
  max-width: none !important;
  min-width: 0 !important;
  align-self: stretch !important;
}

.card .app-btn,
.action-card .app-btn,
.sheet .app-btn,
.detail-panel .app-btn,
.detail-actions .app-btn,
.actions > .app-btn,
.btn-stack > .app-btn,
.empty-actions > .app-btn,
.empty-actions-row > .app-btn,
.actions > uni-button,
.btn-stack > uni-button,
.detail-actions > uni-button {
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

.actions,
.btn-stack,
.detail-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
}

.action-row > .app-btn,
.action-row > .app-btn-flex,
.action-row > .action-btn,
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

.empty-actions > .app-btn + .app-btn,
.empty-actions > uni-button + uni-button,
.empty-actions > button + button {
  margin-top: 24rpx !important;
}

.app-btn:active {
  opacity: 0.9;
  transform: scale(0.985);
}

.meta {
  color: var(--text-muted, #64748b);
  font-size: var(--font-size-caption);
}
.slot-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
}
.slot-cell {
  min-width: 0;
  border: 1px solid var(--card-border);
  border-radius: 8px;
  padding: 10rpx;
  font-size: 20rpx;
  background: var(--white);
  box-sizing: border-box;
}

/* H5：系统弹窗/遮罩用不透明底，避免背后列表文字透视 */
.uni-mask,
.uni-modal {
  background-color: rgba(15, 23, 42, 0.62) !important;
}
.uni-modal__bd,
.uni-modal .uni-modal__hd,
.uni-modal .uni-modal__ft {
  background-color: var(--white) !important;
}
.uni-modal .uni-modal__bd {
  color: var(--text-primary) !important;
}

/* 桌面手机框：居中真机比例；消掉导航双占位；底栏贴框底（仅 H5；WXSS 不支持 ~ 等选择器） */
/* #ifdef H5 */
@media (min-width: 600px) {
  html {
    font-size: calc(clamp(360px, 100vw - 48px, 430px) * 32 / 750) !important;
  }
}
@media (min-width: 1100px) {
  html {
    font-size: calc(clamp(390px, 100vw - 80px, 480px) * 32 / 750) !important;
  }
}
@media (min-width: 600px) {
  html,
  body,
  #app,
  uni-app {
    height: 100%;
    overflow: hidden;
    background: var(--color-border-subtle);
  }
  uni-app {
    position: relative;
    --phone-w: clamp(360px, calc(100vw - 48px), 430px);
    --phone-h: min(780px, calc(100vh - 48px));
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
    /* 内容区统一白底；顶栏绿色由 app-nav 自己铺 */
    background: var(--white);
    box-sizing: border-box;
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
    /* 44px 状态栏占位 + 48px 导航行 */
    padding: 44px 8px 0 !important;
    box-sizing: content-box !important;
    z-index: 2 !important;
    flex-shrink: 0 !important;
    display: flex !important;
    align-items: center !important;
    border-radius: 0 !important;
    /* 顶栏通栏铺色，避免两侧露白 */
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
  /* 自定义顶栏：padding-top 由组件内联设置，这里不要 padding:0 覆盖 */
  .app-nav-row {
    height: 48px !important;
  }
  .app-nav-title,
  .app-nav-chevron {
    line-height: 48px !important;
  }
  .app-nav-back,
  .app-nav-side {
    height: 48px !important;
    width: 44px !important;
  }
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
    background: var(--white) !important;
    padding-top: 0 !important;
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
    background: var(--white);
    box-sizing: border-box;
    scrollbar-width: none !important;
    -ms-overflow-style: none !important;
  }
  /* 桌面手机框：隐藏系统滚动条 */
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
  uni-page *::-webkit-scrollbar,
  uni-scroll-view::-webkit-scrollbar,
  .uni-scroll-view::-webkit-scrollbar {
    width: 0 !important;
    height: 0 !important;
    display: none !important;
    background: transparent !important;
  }
  /* 长页交给 page-body 滚；一屏 flex 页用 .page-fill */
  .page-root:not(.page-fill),
  .page:not(.page-fill) {
    height: auto !important;
    min-height: 100% !important;
    flex: 1 0 auto !important;
    max-height: none !important;
    overflow: visible !important;
  }
  .page-fill {
    height: 100% !important;
    max-height: 100% !important;
    overflow-x: hidden !important;
    overflow-y: auto !important;
  }
  .page-root,
  .page-fill {
    scrollbar-width: none !important;
    -ms-overflow-style: none !important;
  }
  .page-root::-webkit-scrollbar,
  .page-fill::-webkit-scrollbar {
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
@media (min-width: 1100px) {
  uni-app {
    --phone-w: clamp(390px, calc(100vw - 80px), 480px);
  }
}
/* #endif */
</style>
