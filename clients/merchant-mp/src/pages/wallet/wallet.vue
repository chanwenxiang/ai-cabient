<template>
  <WalletPage v-if="canView" role="merchant" />
  <view v-else class="denied-wrap">
    <empty-state
      icon="/static/menu/wallet.png"
      title="无钱包查看权限"
      hint="当前账号未开通商户钱包权限，请联系商户管理员"
    >
      <app-button label="返回" @click="goBack" />
    </empty-state>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import WalletPage from '@/components/WalletPage.vue';
import EmptyState from '@aicabinet/shared-uni/components/empty-state.vue';
import { hasPerm, isMerchantLoggedIn } from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';

const { me, refresh } = useMerchantMe();
const canView = computed(() => !!me.value && hasPerm(me.value, 'merchant:wallet:view'));

onShow(async () => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  if (!me.value) {
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
