<template>
  <view class="step-row four">
    <view class="step" :class="stepClass(1)">
      <text class="step-num">{{ stepDone(1) ? '✓' : '1' }}</text>
      <text class="step-label">签到</text>
    </view>
    <view class="step" :class="stepClass(2)">
      <text class="step-num">{{ stepDone(2) ? '✓' : '2' }}</text>
      <text class="step-label">开门</text>
    </view>
    <view class="step" :class="stepClass(3)">
      <text class="step-num">{{ stepDone(3) ? '✓' : '3' }}</text>
      <text class="step-label">核对</text>
    </view>
    <view class="step" :class="stepClass(4)">
      <text class="step-num">{{ stepDone(4) ? '✓' : '4' }}</text>
      <text class="step-label">{{ pullOff ? '下架' : '上架' }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
const props = defineProps<{
  currentStep: number;
  completed: boolean;
  checkedIn: boolean;
  doorOpened: boolean;
  linesConfirmed: boolean;
  pullOff?: boolean;
}>();

function stepDone(step: number) {
  if (props.completed) return true;
  if (step === 1) return props.checkedIn;
  if (step === 2) return props.doorOpened || props.currentStep > 2;
  if (step === 3) return props.linesConfirmed || props.currentStep > 3;
  return false;
}

function stepClass(step: number) {
  if (stepDone(step)) return 'done';
  if (props.currentStep === step) return 'active';
  return '';
}
</script>

<style scoped>
.step-row {
  display: flex;
  justify-content: space-between;
  gap: 8rpx;
  margin: 8rpx 0 20rpx;
}
.step-row.four .step {
  flex: 1;
}
.step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  opacity: 0.45;
}
.step.active,
.step.done {
  opacity: 1;
}
.step-num {
  width: 44rpx;
  height: 44rpx;
  border-radius: 50%;
  text-align: center;
  line-height: 44rpx;
  font-size: var(--font-size-caption);
  font-weight: 700;
  color: var(--text-muted);
  background: var(--page-bg, #f1f5f9);
}
.step.active .step-num {
  color: #fff;
  background: var(--brand);
}
.step.done .step-num {
  color: var(--brand-deep, #134e4a);
  background: var(--brand-soft, #ecfdf5);
}
.step-label {
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
.step.active .step-label,
.step.done .step-label {
  color: var(--text-primary);
  font-weight: 600;
}
</style>
