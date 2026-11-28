<template>
  <!--
    C13 切三：首页底部购物车条（从 pages/index/index.vue 逐字搬移）。
    shopping = 会话活跃且处于 SHOPPING 态（原模板内联判断收敛为 prop）；
    动作（开购物车/清空/购物清单上抛/刷新/求助/再次开门）全部上抛，编排仍归首页。
  -->
  <view
    class="cart-bar"
    :class="{ 'cart-bar--above-tab': aboveTabBar, 'cart-bar--shopping': shopping }"
  >
    <template v-if="shopping">
      <!-- 第一行只承载购物车与清空；状态说明和操作不挤在同一横向控件组。 -->
      <view class="shopping-main-row">
        <view
          role="button"
          class="cart-shop-main"
          data-testid="open-live-cart-sheet"
          aria-label="查看购物车"
          @click="$emit('openCart')"
        >
          <view class="cart-icon-wrap">
            <image
              class="cart-icon"
              src="/static/icon-cart.svg"
              mode="aspectFit"
              style="width: 40rpx; height: 40rpx"
            />
            <text class="cart-badge">{{ cartBadgeText }}</text>
          </view>
          <view class="cart-shop-text">
            <text class="cart-shop-label">{{ shoppingCartLabel }}</text>
            <text class="cart-shop-amt">{{ shoppingCartAmount }}</text>
          </view>
        </view>
        <text
          v-if="mockEnabled && shoppingCartQty > 0"
          role="button"
          class="cart-clear-btn"
          data-testid="cart-clear"
          aria-label="清空购物车"
          @click.stop="$emit('clearCart')"
          >清空</text
        >
      </view>
      <!-- mock 的「关门结算」对真机会话无效，只保留物理动作说明，不放假按钮。 -->
      <view v-if="mockEnabled" class="shopping-guidance" role="status" aria-live="polite">
        取货后请关好柜门，系统会自动识别结算
      </view>
      <!-- 真机：请关门=状态；刷新=主操作；未出账单=次操作，三层视觉明确分离。 -->
      <view v-else class="live-door-actions">
        <view class="door-status" role="status" aria-live="polite">
          <text class="door-status-title">请关门</text>
          <text class="door-status-desc">关好后系统自动识别结算</text>
        </view>
        <view class="door-action-buttons">
          <button
            class="cart-help-btn"
            hover-class="btn-hover"
            :loading="pollRefreshing"
            :disabled="pollRefreshing"
            @click.stop="$emit('refresh')"
          >
            刷新状态
          </button>
          <button
            class="cart-help-btn ghost"
            hover-class="btn-hover"
            aria-label="关门后未出账单？获取帮助"
            @click.stop="$emit('needHelp')"
          >
            未出账单？
          </button>
        </view>
      </view>
    </template>
    <template v-else>
      <view class="cart-info">
        <text class="cart-hint">{{ cartBarHint }}</text>
        <text v-if="cartBarSub" class="cart-sub">{{ cartBarSub }}</text>
      </view>
      <view v-if="sessionActive" class="cart-status-chip" :class="stateTone">
        {{ cartBarAction }}
      </view>
      <button
        v-else-if="canReopen"
        class="cart-cta"
        hover-class="btn-hover"
        data-testid="open-door-again"
        :loading="opening"
        :disabled="opening"
        @click="$emit('reopen')"
      >
        {{ opening ? '开门中…' : '再次开门' }}
      </button>
    </template>
  </view>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    shopping: boolean;
    mockEnabled: boolean;
    /** 贴在原生 tabBar 上方时不要再垫 safe-area，否则会多出一条空白 */
    aboveTabBar?: boolean;
    sessionActive: boolean;
    canReopen: boolean;
    opening: boolean;
    pollRefreshing: boolean;
    cartBadgeText: string;
    shoppingCartLabel: string;
    shoppingCartAmount: string;
    shoppingCartQty: number;
    cartBarHint: string;
    cartBarSub: string;
    cartBarAction: string;
    stateTone: string;
  }>(),
  { aboveTabBar: false }
);

// R5-C3b：模板走 $emit，此赋值仅保留类型推导（沿袭形态，无运行时消费）
const _emit = defineEmits<{
  openCart: [];
  clearCart: [];
  refresh: [];
  needHelp: [];
  reopen: [];
}>();
</script>

<script lang="ts">
export default {
  name: 'HomeCartBar',
  options: { virtualHost: true, styleIsolation: 'apply-shared' }
};
</script>

<style scoped>
.cart-bar {
  flex-shrink: 0;
  position: relative;
  z-index: 5;
  isolation: isolate;
  background: var(--card-bg);
  padding: 16rpx var(--page-gutter);
  padding-bottom: calc(16rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  border-top: 0;
  box-shadow: var(--shadow-card);
}
.cart-bar--above-tab {
  /* tabBar 已含底部安全区，这里只留按钮内边距 */
  padding-bottom: 12rpx;
}
.cart-bar--shopping {
  flex-direction: column;
  align-items: stretch;
  gap: 12rpx;
}
.shopping-main-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  min-width: 0;
}
.cart-info {
  flex: 1;
  min-width: 0;
  padding-right: 8rpx;
}
.cart-hint {
  display: block;
  font-size: var(--font-size-md);
  color: var(--text-primary);
  font-weight: 600;
}
.cart-sub {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
.cart-shop-main {
  flex: 1;
  min-width: 88rpx;
  min-height: 88rpx;
  display: flex;
  align-items: center;
  gap: 18rpx;
}
.cart-shop-main:active {
  opacity: 0.85;
}
.cart-icon-wrap {
  position: relative;
  width: 72rpx;
  height: 72rpx;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-card);
  background: var(--brand-soft);
}
.cart-icon {
  width: 40rpx;
  height: 40rpx;
}
.cart-badge {
  position: absolute;
  top: -4rpx;
  right: -8rpx;
  min-width: 32rpx;
  height: 32rpx;
  padding: 0 8rpx;
  border-radius: var(--radius-panel);
  background: var(--brand);
  color: var(--white);
  font-size: 18rpx;
  font-weight: 700;
  line-height: 32rpx;
  text-align: center;
  box-sizing: border-box;
  border: 2rpx solid var(--white);
}
.cart-shop-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4rpx;
}
.cart-shop-label {
  font-size: var(--font-size-body);
  color: var(--text-muted);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cart-shop-amt {
  font-size: var(--font-size-h3);
  font-weight: 800;
  color: var(--brand);
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}
.cart-cta {
  margin: 0;
  padding: 0 48rpx;
  min-width: 88rpx;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  background: var(--brand);
  color: var(--white);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-lg);
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  box-shadow: var(--shadow-card);
  flex-shrink: 0;
}
.cart-cta::after {
  border: none;
}
.cart-clear-btn {
  flex-shrink: 0;
  min-width: 88rpx;
  min-height: 88rpx;
  padding: 0 22rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-sm);
  color: var(--brand-deep);
  background: var(--brand-soft);
  border-radius: var(--radius-pill);
}
.shopping-guidance {
  padding: 16rpx 20rpx;
  border-radius: var(--radius-panel);
  background: var(--surface-muted);
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.5;
}
/* 状态与操作分组，避免「请关门」看起来像第三个按钮。 */
.live-door-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  min-width: 0;
  flex-wrap: nowrap;
}
.door-status {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.door-status-title {
  color: var(--text-primary);
  font-size: var(--font-size-md);
  font-weight: 700;
}
.door-status-desc {
  color: var(--text-muted);
  font-size: var(--font-size-caption);
  line-height: 1.4;
}
.door-action-buttons {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.cart-help-btn {
  margin: 0;
  padding: 0 24rpx;
  min-width: 88rpx;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  background: var(--brand);
  color: var(--white);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-sm);
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
/* 触区门禁按复合选择器的末段类名读取，单列 .ghost 保证短边可测量。 */
.ghost {
  min-width: 88rpx;
  min-height: 88rpx;
}
.cart-help-btn.ghost {
  background: var(--card-bg);
  color: var(--text-muted);
  border: 1rpx solid var(--color-border);
  font-weight: 500;
}
.cart-help-btn[disabled] {
  opacity: 0.6;
}
.cart-help-btn::after {
  border: none;
}
.cart-status-chip {
  padding: 0 32rpx;
  min-height: 88rpx;
  line-height: 88rpx;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--brand);
  background: var(--brand-soft);
}
.cart-status-chip.wait {
  color: var(--warning);
  background: var(--warning-soft);
}
.cart-status-chip.active {
  color: var(--brand);
  background: var(--brand-soft);
}
.cart-status-chip.error {
  color: var(--danger);
  background: var(--danger-soft);
}
</style>
