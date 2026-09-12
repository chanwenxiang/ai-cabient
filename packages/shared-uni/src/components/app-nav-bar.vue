<template>
  <view class="app-nav" :style="wrapStyle">
    <view class="app-nav-row" :style="rowStyle">
      <view
        class="app-nav-back"
        hover-class="app-nav-back-hover"
        role="button"
        aria-label="返回"
        @click="onBack"
      >
        <view class="app-nav-arrow app-icon app-icon--back" aria-hidden="true" />
      </view>
      <text
        class="app-nav-title"
        :style="{ color: color, lineHeight: rowStyle.height, paddingRight: sidePad }"
        >{{ title }}</text
      >
      <view class="app-nav-side" :style="{ minWidth: sidePad, height: rowStyle.height }">
        <slot name="right" />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { getStatusBarPadPx } from '@aicabinet/shared-uni/status-bar';
import { navigateBackOrHome } from '@aicabinet/shared-uni/navigate-back';

const props = withDefaults(
  defineProps<{
    title?: string;
    bg?: string;
    color?: string;
    /** 无历史栈时回落的首页路径（各端默认不同，由本地副本覆写） */
    homeUrl?: string;
  }>(),
  {
    title: '',
    bg: 'var(--brand-deep)',
    color: 'var(--white)',
    homeUrl: '/pages/index/index'
  }
);

/** 顶栏与微信胶囊对齐：paddingTop≈胶囊 top，行高≈胶囊高，右侧预留胶囊宽度 */
function readCapsuleLayout() {
  try {
    if (typeof uni.getMenuButtonBoundingClientRect === 'function') {
      const menu = uni.getMenuButtonBoundingClientRect();
      const info = uni.getSystemInfoSync();
      const winW = Number(info?.windowWidth) || 375;
      const top = Number(menu?.top) || 0;
      const height = Number(menu?.height) || 0;
      const left = Number(menu?.left) || 0;
      if (top > 0 && height > 0 && left > 0) {
        return {
          paddingTop: Math.ceil(top) + 'px',
          rowHeight: Math.ceil(height) + 'px',
          sideMin: Math.max(44, Math.ceil(winW - left + 8)) + 'px'
        };
      }
    }
  } catch {
    /* fall through */
  }
  return {
    paddingTop: getStatusBarPadPx() + 'px',
    rowHeight: '48px',
    sideMin: '44px'
  };
}

const layout = readCapsuleLayout();

const wrapStyle = computed(() => ({
  background: props.bg,
  color: props.color,
  paddingTop: layout.paddingTop,
  boxSizing: 'border-box' as const,
  width: '100%',
  flexShrink: 0
}));

const rowStyle = computed(() => ({
  height: layout.rowHeight
}));

const sidePad = layout.sideMin;

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
  padding: 0 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
}
.app-nav-back,
.app-nav-side {
  min-width: 44px;
  display: flex;
  align-items: center;
  flex-shrink: 0;
  z-index: 1;
}
.app-nav-back {
  justify-content: center;
  height: 100%;
}
.app-nav-side {
  justify-content: flex-end;
}
/* 与共享 .app-icon--back 对齐，保留尺寸以贴近系统返回键 */
.app-nav-arrow {
  width: 11px;
  height: 11px;
  margin-left: 4px;
  border-left-width: 2.5px;
  border-bottom-width: 2.5px;
  color: inherit;
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
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  pointer-events: none;
  padding-left: 52px;
  box-sizing: border-box;
}
</style>
