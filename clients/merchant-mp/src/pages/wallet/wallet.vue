<template>
  <WalletPage v-if="canView" ref="walletRef" role="merchant" />
  <view v-else class="denied-wrap">
    <empty-state
      icon="/static/menu/wallet.png"
      :title="meFromServer ? '无钱包查看权限' : '网络异常，暂时无法确认权限'"
      :hint="meFromServer ? '当前账号未开通商户钱包权限，请联系商户管理员' : '请检查网络后重试'"
    >
      <app-button label="返回" @click="goBack" />
    </empty-state>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import WalletPage from '@/components/WalletPage.vue';
import EmptyState from '@aicabinet/shared-uni/components/empty-state.vue';
import { hasPerm, isMerchantLoggedIn } from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';

const { me, meFromServer, refresh } = useMerchantMe();
// R4-B-新3：me 非空可能是去权展示缓存（首页 seed）—— 只认服务端态判权限
const canView = computed(
  () => !!me.value && meFromServer.value && hasPerm(me.value, 'merchant:wallet:view')
);

// 2026-11-10：本页只声明了 enablePullDownRefresh，下拉回调必须落在页面实例上
// （组件内的 onPullDownRefresh 在小程序端不触发），因此通过 ref 调组件暴露的 reload。
const walletRef = ref<{ reload: () => Promise<void> } | null>(null);

onPullDownRefresh(async () => {
  try {
    await walletRef.value?.reload();
  } finally {
    uni.stopPullDownRefresh();
  }
});

onShow(async () => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  if (!me.value || !meFromServer.value) {
    try {
      await refresh();
    } catch {
      seedMerchantMeDisplayCache(me);
    }
  }
});

function goBack() {
  uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
}
</script>

<style scoped>
.denied-wrap {
  padding: 80rpx 40rpx;
}
</style>
