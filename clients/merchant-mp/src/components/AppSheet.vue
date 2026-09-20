<template>
  <!--
    🔴 遮罩关闭**不要**写 `@click.self`。
    uni-app H5 把 `view` 编译成自定义元素 `<uni-view>`，实测 `@click.self` 的
    `target === currentTarget` 判定不会成立 ⇒ 处理函数**永不触发** ⇒ 底部面板
    点遮罩关不掉（`useReplenishmentDetail.closeDetail` 的守卫已 armed=true、
    事件也确实送到了 `.app-sheet-mask`，`detailVisible` 仍停在 true）。
    改成普通 `@click` 即可：内层 `.app-sheet` 已 `@click.stop`，语义与原意等价
    （实测：点遮罩→关闭；点面板内部→不关闭）。同类写法见 AppConfirmDialog。
  -->
  <view
    v-if="visible"
    class="app-sheet-mask"
    role="dialog"
    aria-modal="true"
    :aria-label="ariaLabel"
    data-testid="app-sheet"
    @click="emit('close')"
    @touchmove.stop.prevent
  >
    <view role="document" class="app-sheet" @click.stop>
      <view class="app-sheet-handle" aria-hidden="true" />
      <slot />
    </view>
  </view>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    visible: boolean;
    /** 读屏名称，默认「底部面板」 */
    ariaLabel?: string;
  }>(),
  { ariaLabel: '底部面板' }
);

const emit = defineEmits<{ close: [] }>();
</script>

<style scoped>
.app-sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 10040;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(15, 23, 42, 0.62);
}
.app-sheet {
  width: 100%;
  max-width: 520px;
  max-height: 88vh;
  padding: 30rpx 26rpx calc(30rpx + env(safe-area-inset-bottom));
  border-radius: var(--radius-card) 32rpx 0 0;
  background: var(--card-bg, #fff);
  overflow-y: auto;
  overscroll-behavior: contain;
  box-sizing: border-box;
  isolation: isolate;
  box-shadow: 0 -12rpx 40rpx rgba(15, 23, 42, 0.18);
}
.app-sheet-handle {
  width: 64rpx;
  height: 8rpx;
  margin: 0 auto 16rpx;
  border-radius: 4rpx;
  background: var(--text-subtle, #cbd5e1);
}
</style>
