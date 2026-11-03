<!--
  Canonical: packages/shared-uni/src/components/list-skeleton.vue
  Keep in sync (uni easycom 需本地路径).

  列表骨架屏：加载态两标准之一（另一为操作按钮 spinner）。
  只用于「列表首载且尚无数据」的场景：loading && !list.length。
  静默刷新/加载更多不显示骨架，避免整页先缩再撑开。

  WXSS 约束：不用 color-mix；色值走语义令牌 + 浅色兜底。
-->
<template>
  <view class="list-skeleton" aria-role="text" :aria-label="'正在加载' + (label || '')">
    <view v-for="i in rows" :key="i" class="sk-card">
      <view class="sk-thumb" />
      <view class="sk-lines">
        <view class="sk-bar sk-bar--title" />
        <view class="sk-bar sk-bar--sub" />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ rows?: number; label?: string }>(), { rows: 3, label: '' });
</script>

<script lang="ts">
export default { name: 'ListSkeleton' };
</script>

<style scoped>
.list-skeleton {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
  width: 100%;
}

.sk-card {
  display: flex;
  align-items: center;
  gap: 24rpx;
  padding: 28rpx;
  border-radius: 20rpx;
  background: var(--card-bg);
  animation: sk-pulse 1.4s ease-in-out infinite;
}

.sk-thumb {
  width: 88rpx;
  height: 88rpx;
  flex-shrink: 0;
  border-radius: 16rpx;
  background: var(--color-border-subtle);
}

.sk-lines {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 18rpx;
}

.sk-bar {
  height: 26rpx;
  border-radius: 8rpx;
  background: var(--color-border-subtle);
}

.sk-bar--title {
  width: 56%;
}

.sk-bar--sub {
  width: 34%;
}

@keyframes sk-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}

@media (prefers-reduced-motion: reduce) {
  .sk-card {
    animation: none;
  }
}
</style>
