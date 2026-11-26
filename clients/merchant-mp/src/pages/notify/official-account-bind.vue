<template>
  <view class="page-root">
    <web-view v-if="url" :src="url" />
    <view v-else class="empty">
      <text class="empty-title">绑定地址无效</text>
      <text class="empty-hint">请返回微信提醒页重新发起绑定</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';

const url = ref('');

onLoad((opts) => {
  try {
    const raw = opts?.url ? decodeURIComponent(String(opts.url)) : '';
    if (raw.startsWith('https://open.weixin.qq.com/')) {
      url.value = raw;
    }
  } catch {
    url.value = '';
  }
});
</script>

<style scoped>
.page-root {
  min-height: 100vh;
  background: var(--page-bg);
}
.empty {
  padding: 160rpx 48rpx;
  text-align: center;
}
.empty-title {
  display: block;
  font-size: var(--font-size-md);
  color: var(--text-primary);
}
.empty-hint {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
</style>
