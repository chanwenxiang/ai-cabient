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

/* 微信小程序 page 节点标准：height 100% 供 tabBar 页 flex 一屏布局 */
page {
  --brand: #059669;
  --brand-2: #0d9488;
  --brand-deep: #064e3b;
  --brand-wx: #07c160;
  --brand-soft: #ecfdf5;
  --page-bg: #f5f7f8;
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
}

.page-root {
  min-height: 100%;
  box-sizing: border-box;
  overflow-x: hidden;
  background: var(--page-bg);
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

.card {
  background: #fff;
  border-radius: 24rpx;
  padding: 24rpx;
  margin: 0 24rpx 16rpx;
  border: 1rpx solid rgba(15, 118, 110, 0.06);
  box-shadow: 0 10rpx 32rpx rgba(15, 23, 42, 0.055);
}

.meta { color: #888; font-size: 26rpx; }

.btn-primary,
.action-btn,
.cart-cta,
.empty-btn.primary {
  background: linear-gradient(135deg, var(--brand), var(--brand-2));
  color: #fff;
  border: none;
  border-radius: 44rpx;
  font-weight: 600;
  box-shadow: 0 8rpx 24rpx rgba(5, 150, 105, 0.2);
  box-sizing: border-box;
}

.btn-primary,
.btn-outline,
.empty-btn.primary,
.empty-btn.ghost {
  width: 100%;
  display: block;
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

button, .btn-primary, .btn-ghost, .btn-outline, .menu-cell, .filter-chip, .scan-btn, .tip-btn {
  transition: transform .18s ease, opacity .18s ease, box-shadow .18s ease;
}
button:active, .btn-primary:active, .btn-ghost:active, .btn-outline:active, .menu-cell:active, .filter-chip:active, .tip-btn:active {
  transform: scale(.985);
  opacity: .88;
}

@media (min-width: 600px) {
  uni-page-body {
    width: 520px;
    height: calc(100vh - 36px);
    min-height: calc(100vh - 36px);
    margin: 18px auto;
    border-radius: 28px;
    overflow-x: hidden;
    overflow-y: auto;
    -webkit-overflow-scrolling: touch;
    box-shadow: 0 22px 70px rgba(15, 23, 42, .14);
  }
}
</style>
