<template>
  <view class="page">
    <text class="title">提现不可用</text>
    <text class="body">
      当前结算模型为平台微信分账，不支持商户端发起提现申请。请在「结算对账」查看待分账与已结算金额。
    </text>
    <button class="btn" @click="goSettlements">打开结算对账</button>
  </view>
</template>

<script setup lang="ts">
function goSettlements() {
  uni.navigateTo({ url: '/pages/settlements/settlements' });
}
</script>

<style scoped>
.page { padding: 40rpx; background: #f0fdfa; min-height: 100vh; }
.title { font-size: 34rpx; font-weight: 600; color: #0f766e; display: block; margin-bottom: 20rpx; }
.body { font-size: 28rpx; color: #334155; line-height: 1.6; display: block; margin-bottom: 40rpx; }
.btn { background: #0f766e; color: #fff; border-radius: 36rpx; }
</style>
