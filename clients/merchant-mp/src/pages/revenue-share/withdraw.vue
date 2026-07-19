<template>
  <view class="page">
    <text class="title">提现不可用</text>
    <text class="body">
      当前结算模型为平台微信分账，不支持商户端发起提现申请。请在「结算对账」查看待分账与已结算金额。
    </text>
    <button v-if="canSettlements" class="btn" @click="goSettlements">打开结算对账</button>
    <text v-else class="hint">当前账号无结算对账权限</text>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { computed } from 'vue';
import { hasPerm } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type { MerchantMe } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canSettlements = computed(() => hasPerm(me.value, 'merchant:settlements:view'));

onShow(async () => {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  try {
    await refreshMe();
  } catch {
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
});

function goSettlements() {
  if (!canSettlements.value) {
    uni.showToast({ title: '无结算对账权限', icon: 'none' });
    return;
  }
  uni.navigateTo({ url: '/pages/settlements/settlements' });
}
</script>

<style scoped>
.page { padding: 40rpx; background: #f0fdfa; min-height: 100vh; }
.title { font-size: 34rpx; font-weight: 600; color: #0f766e; display: block; margin-bottom: 20rpx; }
.body { font-size: 28rpx; color: #334155; line-height: 1.6; display: block; margin-bottom: 40rpx; }
.btn { background: #0f766e; color: #fff; border-radius: 36rpx; }
.hint { font-size: 26rpx; color: #94a3b8; display: block; }
</style>
