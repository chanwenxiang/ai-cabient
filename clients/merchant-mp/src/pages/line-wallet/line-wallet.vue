<template>
  <WalletPage ref="walletRef" role="line" />
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onPullDownRefresh } from '@dcloudio/uni-app';
import WalletPage from '@/components/WalletPage.vue';

// 2026-11-10：pages.json 对本页开了 enablePullDownRefresh，此前没有处理函数，
// 下拉只会一直转圈不收回。组件内的 onPullDownRefresh 在小程序端不触发，
// 必须由页面实例注册并转调组件暴露的 reload。
const walletRef = ref<{ reload: () => Promise<void> } | null>(null);

onPullDownRefresh(async () => {
  try {
    await walletRef.value?.reload();
  } finally {
    uni.stopPullDownRefresh();
  }
});
</script>
