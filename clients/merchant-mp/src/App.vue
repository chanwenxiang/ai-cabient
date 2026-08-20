<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app';
import { loadRuntimeDict } from '@/utils/dict-runtime';

onLaunch(() => {
  if (!uni.getStorageSync('merchant_token')) return;
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
  --brand-soft: #99f6e4;
  --brand-tint: #ccfbf1;
  --page-tint: #f0fdfa;
  --page-bg: #ffffff;
  --text-muted: #64748b;
  --text-subtle: #94a3b8;
  --card-radius: 22rpx;
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
  border-radius: 999rpx;
  font-size: 24rpx;
  color: var(--text-muted);
  background: #fff;
  border: 1rpx solid var(--card-border, #e2e8f0);
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

/* 微信/H5 input：避免仅靠 padding 导致占位符被裁切 */
input {
  box-sizing: border-box;
  min-height: 72rpx;
  font-size: 28rpx;
  line-height: 1.4;
}

.card {
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx;
  margin: 20rpx;
  box-shadow: 0 8rpx 28rpx rgba(15, 118, 110, 0.08);
  border: 1rpx solid rgba(15, 118, 110, 0.06);
}

.btn-primary,
.retry,
.empty-btn.primary,
.primary-btn {
  background: linear-gradient(135deg, var(--brand-deep), var(--brand));
  color: #fff;
  border: none;
  border-radius: 44rpx;
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

/* 主按钮：单独出现时收窄居中；通栏用 .btn-block；横向行内用 .btn-inline */
.btn-primary,
.btn-outline,
.empty-btn.primary,
.empty-btn.ghost,
.empty-btn,
.primary-btn,
.action-btn,
uni-button.btn-primary,
uni-button.btn-outline,
uni-button.primary-btn,
uni-button.empty-btn {
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
  line-height: 1.2;
}

.btn-block,
uni-button.btn-block {
  width: 100% !important;
  max-width: none !important;
  min-width: 0 !important;
  align-self: stretch !important;
}

/* 纵向操作区：通栏等宽 + 文字居中 */
.actions,
.btn-stack,
.detail-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
}

.actions > .btn-primary,
.actions > .btn-outline,
.actions > .primary-btn,
.actions > .retry,
.btn-stack > .btn-primary,
.btn-stack > .btn-outline,
.btn-stack > .primary-btn,
.detail-actions > .btn-primary,
.detail-actions > .btn-outline,
.detail-actions > .primary-btn,
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

.action-row > .btn-primary,
.action-row > .btn-outline,
.action-row > .primary-btn,
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

.empty-actions > .empty-btn,
.empty-actions > .btn-primary,
.empty-actions > .btn-outline,
.empty-actions-row > .empty-btn,
.empty-actions-row > .btn-primary,
.empty-btns > .empty-btn {
  min-width: 0;
  max-width: none;
  margin: 0;
  flex: 0 1 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  line-height: 1.2;
}

.btn-outline,
.empty-btn.ghost {
  background: #fff;
  color: var(--brand);
  border: 2rpx solid var(--brand);
  border-radius: 44rpx;
  padding: 0 32rpx;
  align-items: center;
  justify-content: center;
  min-height: 80rpx;
  text-align: center;
  font-weight: 600;
  font-size: 28rpx;
  box-shadow: none;
  box-sizing: border-box;
}

.btn-primary:active,
.retry:active,
.btn-outline:active {
  opacity: 0.9;
  transform: scale(0.985);
}

.meta {
  color: #64748b;
  font-size: 24rpx;
}
.slot-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
}
.slot-cell {
  min-width: 0;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10rpx;
  font-size: 20rpx;
  background: #fff;
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
  background-color: #fff !important;
}
.uni-modal .uni-modal__bd {
  color: #0f172a !important;
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
    /* 内容区统一白底；顶栏绿色由 app-nav 自己铺 */
    background: #ffffff;
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
    background: #ffffff !important;
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
    background: #ffffff;
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
/* #endif */
</style>
