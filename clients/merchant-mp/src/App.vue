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

page {
  --brand: #0f766e;
  --brand-deep: #134e4a;
  --brand-soft: #99f6e4;
  --brand-tint: #ccfbf1;
  height: 100%;
  background: #f0fdfa;
  font-family: var(--app-font);
  color: #0f172a;
}

/* uni-app button 默认 1px 边框 */
button::after {
  border: none !important;
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
  min-height: 88rpx;
  line-height: 88rpx;
  text-align: center;
  font-weight: 600;
  font-size: 30rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.22);
  box-sizing: border-box;
}

.btn-primary,
.btn-outline,
.empty-btn.primary,
.primary-btn,
.empty-btn.ghost {
  width: 100%;
  display: block;
}

.btn-outline,
.empty-btn.ghost {
  background: #fff;
  color: var(--brand);
  border: 2rpx solid var(--brand);
  border-radius: 44rpx;
  padding: 0 32rpx;
  min-height: 80rpx;
  line-height: 80rpx;
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

.meta { color: #64748b; font-size: 24rpx; }
.slot-grid { display: flex; flex-wrap: wrap; gap: 8px; }
.slot-cell {
  width: 30%;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 8px;
  font-size: 22rpx;
  background: #fff;
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

/* 桌面浏览器：与消费者端一致的手机框预览 */
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
    box-shadow: 0 22px 70px rgba(15, 23, 42, 0.14);
    background: #f0fdfa;
  }
}
</style>
