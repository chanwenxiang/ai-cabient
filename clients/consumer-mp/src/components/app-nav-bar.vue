<template>
  <view class="app-nav" :style="wrapStyle">
    <view class="app-nav-row">
      <view
        class="app-nav-back"
        hover-class="app-nav-back-hover"
        role="button"
        aria-label="返回"
        @click="onBack"
      >
        <text class="app-nav-chevron" :style="{ color: color }">‹</text>
      </view>
      <text class="app-nav-title" :style="{ color: color }">{{ title }}</text>
      <view class="app-nav-side">
        <slot name="right" />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { getStatusBarPadPx } from '@aicabinet/shared-uni/status-bar';
import { navigateBackOrHome } from '@/utils/navigate-back';

const props = withDefaults(
  defineProps<{
    title?: string;
    bg?: string;
    color?: string;
    homeUrl?: string;
  }>(),
  {
    title: '',
    bg: '#064e3b',
    color: '#ffffff',
    homeUrl: '/pages/index/index'
  }
);

const statusPad = getStatusBarPadPx();

const wrapStyle = computed(() => ({
  background: props.bg,
  color: props.color,
  borderTop: statusPad + 'px solid ' + props.bg,
  boxSizing: 'border-box' as const,
  width: '100%',
  flexShrink: 0
}));

function onBack() {
  navigateBackOrHome(props.homeUrl);
}
</script>

<script lang="ts">
export default { name: 'AppNavBar' };
</script>

<style scoped>
.app-nav {
  position: relative;
  z-index: 20;
  width: 100%;
  margin: 0;
  border-bottom: 1rpx solid rgba(255, 255, 255, 0.12);
}
.app-nav-row {
  position: relative;
  height: 48px;
  padding: 0 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
}
.app-nav-back,
.app-nav-side {
  min-width: 44px;
  height: 48px;
  display: flex;
  align-items: center;
  flex-shrink: 0;
  z-index: 1;
}
.app-nav-back {
  justify-content: center;
}
.app-nav-side {
  justify-content: flex-end;
  padding-right: 8px;
}
.app-nav-chevron {
  font-size: 36px;
  line-height: 48px;
  font-weight: 300;
}
.app-nav-back-hover {
  opacity: 0.6;
}
.app-nav-title {
  position: absolute;
  left: 0;
  right: 0;
  text-align: center;
  font-size: 17px;
  font-weight: 600;
  line-height: 48px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  pointer-events: none;
  padding: 0 52px;
  box-sizing: border-box;
}
</style>
