<template>
  <view class="page">
    <text class="title">分账说明</text>
    <text class="body">
      订单分账由平台按规则定期提交至商户微信收款账户。请前往「结算对账」查看日汇总与批次状态。
      商户端不提供自主提现。
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
