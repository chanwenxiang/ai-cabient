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
        :style="{
          color: color,
          lineHeight: rowStyle.height,
          paddingLeft: titlePad,
          paddingRight: titlePad
        }"
        >{{ title }}</text
      >
      <view
        class="app-nav-side"
        :style="{ minWidth: sidePad, height: rowStyle.height, paddingRight: sidePad }"
      >
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
    bg: 'var(--nav-bar-bg, var(--brand-deep))',
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

/**
 * 标题两侧留白**必须等宽**：标题是 absolute left:0/right:0 + text-align:center，
 * 左右 padding 不等 ⇒ 文字中心离屏幕中线偏移 (右-左)/2。历史上左固定 52px、右为
 * sidePad（胶囊避让≈90~102px），标题被整体推左 ≈25px，各页面标题表现为「不居中」。
 * 取两侧较大者做等宽留白，既不压胶囊也不偏左。
 */
const titlePad = computed(() => {
  const reserve = Math.max(52, Math.ceil(Number.parseFloat(layout.sideMin) || 0));
  return `${reserve}px`;
});

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
  /*
   * 插槽内容必须避让微信胶囊按钮。
   * 胶囊占据右上角 menu.left→winW（实测 390 宽机：296→382），而 sidePad = winW-menu.left+8。
   * 若只给 min-width: sidePad 并把内容 flex-end 对齐，内容会贴到导航栏右缘(382) —— 整段压在胶囊下面
   * （nearby 的「刷新」实测 left=356，完全落在 296→382 内）。
   * 故：flex:1 撑满返回键右侧的全部空间，再用 padding-right: sidePad 把内容推到胶囊左缘之前。
   */
  flex: 1;
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
  /* 左右留白由内联 titlePad 等宽设置（避让返回键 + 微信胶囊），此处不得单边硬编码 */
  box-sizing: border-box;
}
</style>
