<template>
  <!--
    C13 切三：首页底部购物车条（从 pages/index/index.vue 逐字搬移）。
    shopping = 会话活跃且处于 SHOPPING 态（原模板内联判断收敛为 prop）；
    动作（开购物车/清空/关门结算/刷新/求助/再次开门）全部上抛，编排仍归首页。
  -->
  <view class="cart-bar" :class="{ 'cart-bar--above-tab': aboveTabBar }">
    <template v-if="shopping">
      <view
        role="button"
        class="cart-shop-main"
        data-testid="open-live-cart-sheet"
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
      <button
        v-if="mockEnabled"
        class="cart-close-btn"
        hover-class="btn-hover"
        :loading="closingDoor"
        :disabled="closingDoor"
        @click.stop="$emit('closeDoor')"
      >
        关门结算
      </button>
      <view v-else class="live-door-actions">
        <view class="cart-status-chip soft">请关门</view>
        <button
          class="cart-help-btn"
          hover-class="btn-hover"
          :loading="pollRefreshing"
          :disabled="pollRefreshing"
          @click.stop="$emit('refresh')"
        >
          刷新状态
        </button>
        <button class="cart-help-btn ghost" hover-class="btn-hover" @click.stop="$emit('needHelp')">
          未出账单？
        </button>
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
    closingDoor: boolean;
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

const emit = defineEmits<{
  openCart: [];
  clearCart: [];
  closeDoor: [];
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
  background: var(--card-bg, #fff);
  padding: 16rpx 24rpx;
  padding-bottom: calc(16rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  border-top: 0;
  box-shadow: 0 -10rpx 32rpx rgba(15, 23, 42, 0.08);
}
.cart-bar--above-tab {
  /* tabBar 已含底部安全区，这里只留按钮内边距 */
  padding-bottom: 12rpx;
}
.cart-info {
  flex: 1;
  min-width: 0;
  padding-right: 8rpx;
}
.cart-hint {
  font-size: var(--font-size-md);
  color: var(--text-primary, #1e293b);
  font-weight: 600;
  display: block;
}
.cart-sub {
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #888);
  display: block;
  margin-top: 4rpx;
}
.cart-shop-main {
  flex: 1;
  min-width: 0;
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
  color: var(--text-muted, #334155);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cart-shop-amt {
  font-size: var(--font-size-h3);
  font-weight: 800;
  color: var(--brand, #0f766e);
  line-height: 1.15;
}
.cart-cta {
  margin: 0;
  padding: 0 48rpx;
  min-height: 80rpx;
  height: 80rpx;
  line-height: 1.2;
  background: linear-gradient(135deg, var(--brand, #0f766e), var(--brand, #0f766e));
  color: var(--white);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-lg);
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  box-shadow: 0 8rpx 22rpx rgba(5, 150, 105, 0.22);
  flex-shrink: 0;
}
.cart-cta::after {
  border: none;
}
.cart-clear-btn {
  flex-shrink: 0;
  margin-right: 16rpx;
  padding: 10rpx 22rpx;
  font-size: var(--font-size-sm);
  color: var(--brand-deep, #134e4a);
  background: rgba(19, 78, 74, 0.08);
  border-radius: var(--radius-pill);
}
.cart-close-btn {
  margin: 0;
  padding: 0 36rpx;
  min-height: 80rpx;
  height: 80rpx;
  line-height: 1.2;
  background: linear-gradient(135deg, var(--brand, #0f766e), var(--brand, #0f766e));
  color: var(--white);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-md);
  font-weight: 700;
  box-shadow: 0 8rpx 22rpx rgba(5, 150, 105, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  flex-shrink: 0;
}
.cart-status-chip.soft {
  background: var(--brand-soft);
  color: var(--brand);
  border: 1rpx solid var(--brand-mist, #a7f3d0);
}
.cart-close-btn::after {
  border: none;
}
.live-door-actions {
  display: flex;
  align-items: center;
  gap: 12rpx;
  flex-shrink: 0;
  max-width: 58%;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.cart-help-btn {
  margin: 0;
  padding: 0 22rpx;
  min-height: 64rpx;
  height: 64rpx;
  line-height: 1.2;
  background: var(--brand, #0f766e);
  color: var(--white);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-sm);
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
.cart-help-btn.ghost {
  background: transparent;
  color: var(--brand, #0f766e);
  border: 1rpx solid var(--brand-mist, #a7f3d0);
}
.cart-help-btn::after {
  border: none;
}
.cart-status-chip {
  padding: 0 32rpx;
  height: 80rpx;
  line-height: 80rpx;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--brand);
  background: var(--brand-soft, #e8f8ef);
}
.cart-status-chip.wait {
  color: var(--warning, #b45309);
  background: var(--warning-soft);
}
.cart-status-chip.active {
  color: var(--brand);
  background: var(--brand-soft, #e8f8ef);
}
.cart-status-chip.error {
  color: var(--danger, #991b1b);
  background: var(--danger-soft);
}
</style>
