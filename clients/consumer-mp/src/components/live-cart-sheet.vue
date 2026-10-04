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
        <view class="sheet-actions">
          <view class="sheet-action">
            <app-button variant="ghost" label="继续选购" @click="emit('close')" />
          </view>
          <view v-if="mockMode" class="sheet-action">
            <app-button
              label="关门结算"
              :loading="closingDoor"
              :disabled="closingDoor"
              @click="emit('settle')"
            />
          </view>
        </view>
        <text v-if="!mockMode" class="sheet-live-hint">取完请直接关门，按识别结果扣款</text>
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
  }>(),
  { mockMode: false, closingDoor: false }
);

const emit = defineEmits<{ close: []; settle: [] }>();

const title = computed(() => (props.mockMode ? '本次取货' : '本次取走预览'));
const subtitle = computed(() =>
  props.mockMode ? '点选数量加入清单，关门后按此结算' : '识别结果实时更新；放回柜内后件数会减少'
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
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card) 28rpx 0 0;
  padding: 4rpx 28rpx 24rpx;
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
  background: var(--brand-soft, #ecfdf5);
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
