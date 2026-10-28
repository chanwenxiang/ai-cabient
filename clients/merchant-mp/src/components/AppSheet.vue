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
  >
    <!--
      🔴 手势拦截层必须是面板的**兄弟**，绝不能是面板的祖先（2026-10-28 模拟器实测更正）。
      原先写在 `.app-sheet-mask` 上：`@touchmove.stop.prevent` 编译成遮罩节点上的
      `catchtouchmove`，而微信里**祖先的 catchtouchmove 会吞掉整棵子树的手势默认行为**
      ⇒ 面板内 `<scroll-view>` 的 scrollHeight(640) > 视高(424) 明明可滚，手势却完全不动
      （用户两次反馈「划不动」）。改为铺满遮罩的独立拦截层：点面板外区域照样拦页面上滑，
      面板内的 touchmove 不再经过任何 preventDefault，滚动交给 scroll-view。
    -->
    <view class="app-sheet-scroll-lock" aria-hidden="true" @touchmove.stop.prevent />
    <view
      role="document"
      class="app-sheet"
      :class="{ 'app-sheet--flex-body': !bodyScroll, 'app-sheet--tall': !bodyScroll && fillHeight }"
      @click.stop
    >
      <view class="app-sheet-handle" aria-hidden="true" />
      <slot />
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * bodyScroll（默认 true）= 面板自身滚动（max-height 88vh + overflow-y auto）。
 * 设 false 时面板改为 flex 列、自身不滚，由插槽里的滚动区（flex:1; min-height:0，
 * 如 scroll-view）滚动、底部操作区常驻。为什么需要它：scroll-view 没有定高就不滚，
 * 手势又被它自己吃掉 —— 整页表现为「下滑不了、底部按钮点不到」（争议详情，2026-10-28
 * 用户实测两段购物录像时复现）。
 */
withDefaults(
  defineProps<{
    visible: boolean;
    /** 读屏名称，默认「底部面板」 */
    ariaLabel?: string;
    /** true=面板整体滚动（默认）；false=面板 flex 列，由插槽滚动区滚动 */
    bodyScroll?: boolean;
    /**
     * 与 bodyScroll=false 配套：给面板**确定高度**（88vh）。
     * 只有「内容恒长 + 需要底部操作栏常驻」的面板才需要（补货详情）；
     * 内容短的弹层保持自动高度，否则会出现一大片空白。
     */
    fillHeight?: boolean;
  }>(),
  { ariaLabel: '底部面板', bodyScroll: true, fillHeight: false }
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
/* 只拦「面板之外」区域的手势（防止手指拖动时把背后的页面滚走）。
   🔴 不能把拦截写在 .app-sheet-mask 上：它同时是面板的祖先，会连带废掉面板内 scroll-view 的滚动。 */
.app-sheet-scroll-lock {
  position: absolute;
  inset: 0;
  z-index: 0;
}
.app-sheet {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 520px;
  max-height: 88vh;
  padding: 30rpx 26rpx calc(30rpx + env(safe-area-inset-bottom));
  border-radius: var(--radius-card) 32rpx 0 0;
  background: var(--card-bg, #ffffff);
  overflow-y: auto;
  overscroll-behavior: contain;
  box-sizing: border-box;
  isolation: isolate;
  box-shadow: 0 -12rpx 40rpx rgba(15, 23, 42, 0.18);
}
/* flex 列模式：面板自身不滚（overflow hidden），滚动交给插槽里的 scroll-view */
.app-sheet--flex-body {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
/* 定高变体（fillHeight）：微信原生 scroll-view 的内容层高度由自身 frame 决定，
   frame 高度若来自「内容撑开 + max-height 夹住」，真机上会按内容高渲染 ⇒ 内容溢出到
   操作栏下方、表现为「按钮夹在商品中间」且划不动。给面板确定高度即可消除该不确定性。 */
.app-sheet--tall {
  height: 88vh;
}
.app-sheet-handle {
  width: 64rpx;
  height: 8rpx;
  margin: 0 auto 16rpx;
  border-radius: 4rpx;
  background: var(--text-subtle, #cbd5e1);
}
</style>
