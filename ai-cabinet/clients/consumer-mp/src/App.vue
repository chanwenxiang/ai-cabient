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
  background-color: #f7f7f7;
  font-family: -apple-system, BlinkMacSystemFont, 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 28rpx;
  color: #191919;
  box-sizing: border-box;
}

.card {
  background: #fff;
  border-radius: 12rpx;
  padding: 24rpx;
  margin: 0 24rpx 16rpx;
}

.meta { color: #888; font-size: 26rpx; }
</style>
