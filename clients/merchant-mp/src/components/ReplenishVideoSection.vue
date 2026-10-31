<template>
  <view>
    <view class="section-heading recap">
      <view>
        <text class="section-title">补货录像</text>
        <text class="section-subtitle">柜机上报的开门补货录像，按片播放</text>
      </view>
    </view>
    <app-button
      variant="outline"
      label="查看补货录像"
      data-testid="replenish-videos"
      @click="$emit('open')"
    />
    <text class="video-hint">录像由柜机生成后上传，未上报时打开为空，属正常</text>
  </view>
</template>

<script setup lang="ts">
defineEmits<{
  open: [];
}>();
</script>

<style scoped>
.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin: 28rpx 0 14rpx;
}
.section-heading.recap {
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
  text-align: center;
}
.section-heading.recap .section-title,
.section-heading.recap .section-subtitle {
  text-align: center;
}
.section-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
}
.section-subtitle {
  display: block;
  margin-top: 4rpx;
  color: var(--text-subtle);
  font-size: var(--font-size-sm);
}
.video-hint {
  display: block;
  margin-top: 12rpx;
  text-align: center;
  color: var(--text-subtle);
  font-size: var(--font-size-xs);
}
</style>
