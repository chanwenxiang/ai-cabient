<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app';
import { ensureConsumerAuth } from '@/utils/consumer-api';

onLaunch(async () => {
  await ensureConsumerAuth();
});
</script>

<style>
/* 微信小程序 page 节点标准：height 100% 供 tabBar 页 flex 一屏布局 */
page {
  height: 100%;
  background-color: #f5f7f8;
  font-family: -apple-system, BlinkMacSystemFont, 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 28rpx;
  color: #14201b;
  box-sizing: border-box;
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

button, .btn-primary, .btn-ghost, .menu-cell, .filter-chip, .scan-btn {
  transition: transform .18s ease, opacity .18s ease, box-shadow .18s ease;
}
button:active, .btn-primary:active, .btn-ghost:active, .menu-cell:active, .filter-chip:active {
  transform: scale(.985);
  opacity: .88;
}

@media (min-width: 600px) {
  uni-page-body {
    width: 520px;
    min-height: calc(100vh - 36px);
    margin: 18px auto;
    border-radius: 28px;
    overflow: hidden;
    box-shadow: 0 22px 70px rgba(15, 23, 42, .14);
  }
}
</style>
