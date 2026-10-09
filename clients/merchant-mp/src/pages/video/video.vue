<template>
  <view class="video-page">
    <app-nav-bar title="购物视频" bg="#000000" color="#ffffff" home-url="/pages/home/home" />
    <view class="page-body">
      <view v-if="loading" class="state">
        <text class="state-title">{{ UI_COPY.loading }}</text>
        <text class="state-desc">正在获取购物录像</text>
      </view>
      <view v-else-if="errorView" class="state">
        <text class="state-title">{{ errorView.title }}</text>
        <text class="state-desc">{{ errorView.desc }}</text>
      </view>
      <view v-else-if="!src" class="state">
        <text class="state-title">缺少视频地址</text>
        <text class="state-desc">本单暂无购物视频，可返回订单详情</text>
      </view>
      <template v-else>
        <video
          class="video-player"
          :src="src"
          controls
          object-fit="contain"
          :show-center-play-btn="true"
          :enable-progress-gesture="true"
        />
        <view class="tips">
          <text v-if="metaLine" class="meta">{{ metaLine }}</text>
        </view>
      </template>
      <view v-if="orderId" class="back-row">
        <view role="button" class="back-link app-link-chevron" @click="goOrder">返回订单详情</view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import { downloadAuthedFile } from '@/utils/merchant-api';
import { merchantOrderVideoUrl, merchantVideoErrorView } from '@/utils/order-video-url';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

const src = ref('');
const errorView = ref<ReturnType<typeof merchantVideoErrorView> | null>(null);
const loading = ref(false);
const orderId = ref('');
const deviceId = ref('');

const metaLine = computed(() => {
  const parts: string[] = [];
  if (orderId.value) parts.push(`订单 ${orderId.value}`);
  if (deviceId.value) parts.push(`柜机 ${deviceId.value}`);
  return parts.join(' · ');
});

async function loadOrderVideo(oid: string) {
  loading.value = true;
  errorView.value = null;
  src.value = '';
  const apiUrl = merchantOrderVideoUrl(oid);
  try {
    src.value = await downloadAuthedFile(apiUrl, 120_000);
  } catch (e) {
    errorView.value = merchantVideoErrorView(e);
  } finally {
    loading.value = false;
  }
}

onLoad(async (opts) => {
  orderId.value = String(opts?.orderId || '').trim();
  deviceId.value = String(opts?.deviceId || '').trim();
  if (orderId.value) {
    await loadOrderVideo(orderId.value);
    return;
  }
  errorView.value = {
    title: '缺少订单号',
    desc: '无法打开购物视频',
    showCopy: false
  };
});

function goOrder() {
  if (!orderId.value) return;
  uni.navigateTo({
    url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(orderId.value)}`
  });
}
</script>

<style scoped>
.video-page {
  min-height: 100%;
  background: var(--text-primary);
  display: flex;
  flex-direction: column;
  align-items: stretch;
  padding: 0;
  box-sizing: border-box;
}
.page-body {
  padding: 20rpx;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
}
.video-player {
  width: 100%;
  height: 56vh;
  background: var(--text-primary);
  border-radius: var(--radius-control);
}
.state {
  margin-top: 30vh;
  text-align: center;
  color: var(--text-subtle);
}
.state-title {
  display: block;
  font-size: var(--font-size-lg);
  font-weight: 600;
  color: var(--color-border);
}
.state-desc {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-body);
  padding: 0 32rpx;
  line-height: 1.5;
}
.meta {
  color: var(--text-subtle);
  font-size: var(--font-size-xs);
}
.tips {
  margin-top: 24rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.back-row {
  margin-top: 28rpx;
}
.back-link {
  color: var(--success);
  font-size: var(--font-size-body);
}
</style>
