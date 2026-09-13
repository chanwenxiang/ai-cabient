<template>
  <view v-if="deviceId" class="cabinet-card">
    <text class="cabinet-addr">{{
      addressLine || '暂无点位地址，请对照编号或扫码核对柜机'
    }}</text>
    <view class="cabinet-actions">
      <view
        class="cabinet-chip"
        role="button"
        data-testid="copy-device-id"
        @click.stop="$emit('copy')"
        >复制编号</view
      >
      <view
        class="cabinet-chip"
        role="button"
        data-testid="navigate-device"
        @click.stop="$emit('navigate')"
        >导航</view
      >
      <view
        class="cabinet-chip primary"
        role="button"
        data-testid="verify-cabinet-scan"
        @click.stop="$emit('verify-scan')"
        >扫码核对</view
      >
    </view>
  </view>
</template>

<script setup lang="ts">
defineProps<{
  deviceId?: string | null;
  addressLine?: string;
}>();
defineEmits<{
  copy: [];
  navigate: [];
  'verify-scan': [];
}>();
</script>

<style scoped>
.cabinet-card {
  margin: 0 0 20rpx;
  padding: 20rpx 22rpx;
  border-radius: 18rpx;
  background: var(--brand-soft, #f0fdf4);
  border: 1rpx solid var(--brand-mist, #99f6e4);
}
.cabinet-addr {
  display: block;
  color: var(--brand-deep);
  font-size: var(--font-size-caption);
  line-height: 1.5;
}
.cabinet-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 16rpx;
}
.cabinet-chip {
  padding: 10rpx 20rpx;
  border-radius: var(--radius-pill);
  background: var(--card-bg, #fff);
  border: 1rpx solid var(--text-subtle, #cbd5e1);
  color: var(--text-muted, #334155);
  font-size: var(--font-size-sm);
  font-weight: 600;
}
.cabinet-chip.primary {
  background: var(--brand-soft);
  border-color: var(--brand);
  color: var(--brand);
}
</style>
