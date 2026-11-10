<template>
  <view v-if="visible" role="button" aria-label="关闭" class="sheet-mask" @click="emit('close')">
    <view class="sheet-panel" role="dialog" aria-label="购物车明细" @click.stop>
      <view role="button" aria-label="关闭" class="sheet-handle-hit" @click="emit('close')">
        <view class="sheet-handle" />
      </view>
      <view class="sheet-head">
        <text class="sheet-title">{{ title }}</text>
        <text class="sheet-sub">{{ subtitle }}</text>
      </view>

      <scroll-view scroll-y class="sheet-list" :show-scrollbar="false">
        <view v-if="!items.length" class="sheet-empty">
          <text class="sheet-empty-title">暂未识别到商品</text>
          <text class="sheet-empty-hint">{{ emptyHint }}</text>
        </view>
        <view v-for="line in items" :key="line.skuId" class="sheet-row">
          <view class="sheet-thumb">
            <image
              v-if="line.imageUrl"
              class="sheet-thumb-img"
              :src="line.imageUrl"
              mode="aspectFill"
            />
            <text v-else class="sheet-thumb-mark">{{ line.glyph || '品' }}</text>
          </view>
          <view class="sheet-row-copy">
            <text class="sheet-name">{{ line.skuName || line.skuId }}</text>
            <text class="sheet-meta"
              >{{ fmtMoney(line.unitPriceCents) }} × {{ line.quantity }}</text
            >
          </view>
          <text class="sheet-line-amt">{{ fmtMoney(line.lineAmountCents) }}</text>
        </view>
      </scroll-view>

      <view v-if="items.length" class="sheet-foot">
        <view class="sheet-sum">
          <text class="sheet-sum-label">合计 {{ totalQty }} 件</text>
          <text class="sheet-sum-amt">{{ fmtMoney(totalAmountCents) }}</text>
        </view>
        <!--
          支付方式 + 计费规则（CB-067 规范 P11「订单提交页=按钮 + 基本说明」）：
          官方要求提交订单页须含「商品信息 / 计费规则 / 支付方式」。本项目是
          「扫码即开门、关门自动扣」无独立提交页 ⇒ 本抽屉（本次取货/取走预览）承担该职责。
          🔴 支付分标签只在**微信支付分已开通**时展示（未开通挂标签=虚假承诺）。
        -->
        <view class="sheet-pay-row">
          <text class="sheet-pay-label">支付方式</text>
          <view class="sheet-pay-value">
            <pay-score-tag v-if="payScoreReady" variant="label" biz="先取后付" />
            <text v-else class="sheet-pay-text">{{ payChannelText }}</text>
          </view>
        </view>
        <view class="sheet-pay-row">
          <text class="sheet-pay-label">计费规则</text>
          <text class="sheet-pay-text">按实际取走商品金额计费，关门后自动扣款</text>
        </view>
        <view class="sheet-actions">
          <view class="sheet-action">
            <app-button variant="ghost" label="继续选购" @click="emit('close')" />
          </view>
          <!-- R4-C3：mock 按钮按 DC-27 语义诚实命名 —— 非空购物车关门转人工审核（DISPUTED），
               不再冒充「结算」；空车的零元结案出口在 foot 之外（foot 整块 items.length 才渲染）。 -->
          <view v-if="mockMode && items.length" class="sheet-action">
            <app-button
              label="模拟关门（购物车将转人工审核）"
              :loading="closingDoor"
              :disabled="closingDoor"
              @click="emit('settle')"
            />
          </view>
        </view>
        <text v-if="!mockMode" class="sheet-live-hint">取完请直接关门，按识别结果扣款</text>
      </view>
      <!-- R4-C3：mock 空车零元结案出口（sheet-foot 只在有商品时渲染，空车此前无任何出口） -->
      <view v-if="mockMode && !items.length" class="sheet-actions">
        <view class="sheet-action">
          <app-button
            label="模拟关门（空车演示，零元结案）"
            :loading="closingDoor"
            :disabled="closingDoor"
            @click="emit('settle')"
          />
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { fmtMoney } from '@aicabinet/shared-uni/format';

export type LiveCartSheetLine = {
  skuId: string;
  skuName?: string;
  quantity: number;
  unitPriceCents: number;
  lineAmountCents: number;
  imageUrl?: string;
  glyph?: string;
};

const props = withDefaults(
  defineProps<{
    visible: boolean;
    items: LiveCartSheetLine[];
    mockMode?: boolean;
    closingDoor?: boolean;
    /** 微信支付分是否已开通（已开通才展示品牌标签） */
    payScoreReady?: boolean;
    /** 支付方式文案（非支付分渠道时展示） */
    payChannelText?: string;
  }>(),
  { mockMode: false, closingDoor: false, payScoreReady: false, payChannelText: '账户余额' }
);

const emit = defineEmits<{ close: []; settle: [] }>();

const title = computed(() => (props.mockMode ? '本次取货' : '本次取走预览'));
const subtitle = computed(() =>
  props.mockMode
    ? '模拟演示：关门后按端侧识别结算（购物车仅为演示预览）'
    : '识别结果实时更新；放回柜内后件数会减少'
);
const emptyHint = computed(() =>
  props.mockMode ? '在价目上点「+」后，明细会出现在这里' : '请从柜内取货；识别到后会显示在这里'
);
const totalQty = computed(() => props.items.reduce((sum, line) => sum + line.quantity, 0));
const totalAmountCents = computed(() =>
  props.items.reduce((sum, line) => sum + line.lineAmountCents, 0)
);
</script>

<style scoped>
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 1200;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.sheet-panel {
  width: 100%;
  max-height: 72vh;
  background: var(--card-bg, #ffffff);
  border-radius: var(--radius-card) 28rpx 0 0;
  padding: 4rpx 28rpx calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  box-shadow: 0 -12rpx 40rpx rgba(15, 23, 42, 0.08);
}
.sheet-handle-hit {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 12rpx 0 8rpx;
}
.sheet-handle {
  width: 72rpx;
  height: 8rpx;
  border-radius: var(--radius-tag);
  background: var(--color-border);
}
.sheet-head {
  margin-bottom: 8rpx;
}
.sheet-title {
  display: block;
  font-size: var(--font-size-h3);
  font-weight: 700;
  color: var(--text-primary, #0f172a);
}
.sheet-sub {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  line-height: 1.4;
}
.sheet-list {
  flex: none;
  max-height: 42vh;
}
.sheet-empty {
  padding: 36rpx 12rpx 28rpx;
  text-align: center;
}
.sheet-empty-title {
  display: block;
  font-size: var(--font-size-md);
  color: var(--text-muted, #334155);
  font-weight: 600;
}
.sheet-empty-hint {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-caption);
  color: var(--text-subtle);
  line-height: 1.5;
}
.sheet-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 18rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f1f5f9);
}
.sheet-thumb {
  width: 88rpx;
  height: 88rpx;
  flex-shrink: 0;
  border-radius: var(--radius-control, 12rpx);
  background: var(--brand-soft, #f0fdfa);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.sheet-thumb-img {
  width: 88rpx;
  height: 88rpx;
}
.sheet-thumb-mark {
  font-size: 28rpx;
  font-weight: 700;
  color: var(--brand);
}
.sheet-row-copy {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.sheet-name {
  font-size: var(--font-size-md);
  color: var(--text-primary, #0f172a);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sheet-meta {
  font-size: var(--font-size-caption);
  color: var(--text-subtle);
}
.sheet-line-amt {
  flex-shrink: 0;
  min-width: 120rpx;
  text-align: right;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--brand);
}
.sheet-foot {
  padding-top: 16rpx;
  margin-top: 4rpx;
  border-top: 1rpx solid var(--color-border-subtle, #f1f5f9);
}
.sheet-sum {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16rpx;
  margin-bottom: 16rpx;
}
/* 支付方式 / 计费规则行（CB-067 规范 P11 订单提交页=基本说明） */
.sheet-pay-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 10rpx 0;
  border-top: 1rpx solid var(--border-color, #eef2f6);
}
.sheet-pay-label {
  font-size: var(--font-size-sm);
  color: var(--text-secondary, #6b7280);
  flex-shrink: 0;
}
.sheet-pay-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  min-width: 0;
}
.sheet-pay-text {
  font-size: var(--font-size-sm);
  color: var(--text-primary, #0f172a);
  text-align: right;
}
.sheet-sum-label {
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary, #0f172a);
}
.sheet-sum-amt {
  font-size: var(--font-size-h3);
  font-weight: 700;
  color: var(--brand);
}
.sheet-actions {
  display: flex;
  align-items: stretch;
  gap: 16rpx;
}
.sheet-action {
  flex: 1;
  min-width: 0;
}
.sheet-live-hint {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-caption);
  color: var(--text-subtle);
  text-align: center;
  line-height: 1.4;
}
</style>
