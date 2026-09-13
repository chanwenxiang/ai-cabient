<template>
  <view
    v-if="visible"
    class="confirm-mask"
    role="dialog"
    aria-modal="true"
    :aria-label="title"
    data-testid="confirm-dialog"
    @click.self="emit('cancel')"
    @touchmove.stop.prevent
  >
    <view role="button" class="confirm-card" @click.stop>
      <text class="confirm-title">{{ title }}</text>
      <text class="confirm-body">{{ content }}</text>
      <view
        v-if="rememberLabel"
        class="confirm-remember"
        role="checkbox"
        :aria-checked="rememberChecked"
        data-testid="confirm-remember"
        @click.stop="emit('update:rememberChecked', !rememberChecked)"
      >
        <text class="remember-box">{{ rememberChecked ? '☑' : '☐' }}</text>
        <text>{{ rememberLabel }}</text>
      </view>
      <view class="confirm-actions">
        <button
          type="button"
          class="confirm-btn cancel"
          :aria-label="cancelText"
          data-testid="confirm-cancel"
          @click.stop="emit('cancel')"
        >
          {{ cancelText }}
        </button>
        <button
          type="button"
          class="confirm-btn ok"
          :aria-label="confirmText"
          data-testid="confirm-ok"
          @click.stop="emit('confirm')"
        >
          {{ confirmText }}
        </button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    visible: boolean;
    title: string;
    content: string;
    confirmText?: string;
    cancelText?: string;
    rememberLabel?: string;
    rememberChecked?: boolean;
  }>(),
  {
    confirmText: '确定',
    cancelText: '取消',
    rememberChecked: false
  }
);

const emit = defineEmits<{
  confirm: [];
  cancel: [];
  'update:rememberChecked': [boolean];
}>();
</script>

<style scoped>
.confirm-mask {
  position: fixed;
  inset: 0;
  z-index: 10050;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48rpx;
  background: rgba(15, 23, 42, 0.62);
  box-sizing: border-box;
  pointer-events: auto;
}
.confirm-card {
  width: 100%;
  max-width: 620rpx;
  padding: 36rpx 32rpx 28rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  box-shadow: 0 24rpx 48rpx rgba(15, 23, 42, 0.18);
}
.confirm-title {
  display: block;
  color: var(--text-primary, #0f172a);
  font-size: var(--font-size-xl);
  font-weight: 700;
}
.confirm-body {
  display: block;
  margin-top: 16rpx;
  color: var(--text-muted, #475569);
  font-size: var(--font-size-body);
  line-height: 1.55;
  white-space: pre-wrap;
}
.confirm-remember {
  display: flex;
  align-items: flex-start;
  gap: 12rpx;
  margin-top: 20rpx;
  padding: 16rpx 14rpx;
  border-radius: var(--radius-control);
  background: var(--page-bg, #f8fafc);
  color: var(--text-muted, #334155);
  font-size: var(--font-size-caption);
  line-height: 1.45;
}
.remember-box {
  flex-shrink: 0;
  color: var(--brand);
  font-size: var(--font-size-md);
}
.confirm-actions {
  display: flex;
  gap: 16rpx;
  margin-top: 32rpx;
}
.confirm-btn {
  flex: 1;
  margin: 0;
  border: none;
  border-radius: var(--radius-control);
  font-size: var(--font-size-md);
  font-weight: 600;
  line-height: 1.2;
  padding: 22rpx 12rpx;
}
.confirm-btn.cancel {
  color: var(--text-muted, #334155);
  background: var(--color-border-subtle, #f1f5f9);
}
.confirm-btn.ok {
  color: var(--white);
  background: linear-gradient(135deg, var(--brand), var(--brand));
}
</style>
