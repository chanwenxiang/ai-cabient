<template>
  <view v-if="visible" class="sheet-mask" @click="emit('close')">
    <view class="sheet-panel" @click.stop>
      <view class="sheet-handle-hit" @click="emit('close')">
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
          <view class="sheet-row-main">
            <text class="sheet-name">{{ line.skuName || line.skuId }}</text>
            <text class="sheet-meta"
              >{{ fmtMoney(line.unitPriceCents) }} × {{ line.quantity }}</text
            >
          </view>
          <text class="sheet-line-amt">{{ fmtMoney(line.lineAmountCents) }}</text>
        </view>
      </scroll-view>

      <view class="sheet-foot">
        <view class="sheet-foot-main">
          <view class="sheet-foot-copy">
            <text class="sheet-total-label">预估 {{ totalQty }} 件</text>
            <text class="sheet-foot-hint">{{ footHint }}</text>
          </view>
          <text class="sheet-total-amt">{{ fmtMoney(totalAmountCents) }}</text>
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
};

const props = withDefaults(
  defineProps<{
    visible: boolean;
    items: LiveCartSheetLine[];
    totalQty: number;
    totalAmountCents: number;
    /** 演示步进模拟取货 vs 真实视觉识别 */
    mockMode?: boolean;
  }>(),
  { mockMode: false }
);

const emit = defineEmits<{ close: [] }>();

const title = computed(() => (props.mockMode ? '本次取货' : '本次取走预览'));
const subtitle = computed(() =>
  props.mockMode ? '点选数量加入清单，关门后按此结算' : '识别结果实时更新；放回柜内后件数会减少'
);
const emptyHint = computed(() =>
  props.mockMode ? '在价目上点「+」后，明细会出现在这里' : '请从柜内取货；识别到后会显示在这里'
);
const footHint = computed(() =>
  props.mockMode ? '未选商品关门不扣款' : '关门后以最终识别结果扣款，预估仅供参考'
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
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 4rpx 28rpx calc(28rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  box-shadow: 0 -12rpx 40rpx rgba(15, 23, 42, 0.12);
}
.sheet-handle-hit {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 16rpx 0 12rpx;
}
.sheet-handle {
  width: 72rpx;
  height: 8rpx;
  border-radius: 8rpx;
  background: #e2e8f0;
}
.sheet-head {
  margin-bottom: 12rpx;
}
.sheet-title {
  display: block;
  font-size: 34rpx;
  font-weight: 700;
  color: #0f172a;
}
.sheet-sub {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #64748b;
  line-height: 1.45;
}
.sheet-list {
  flex: 1;
  max-height: 42vh;
  min-height: 120rpx;
}
.sheet-empty {
  padding: 36rpx 12rpx 28rpx;
  text-align: center;
}
.sheet-empty-title {
  display: block;
  font-size: 28rpx;
  color: #334155;
  font-weight: 600;
}
.sheet-empty-hint {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #94a3b8;
  line-height: 1.5;
}
.sheet-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  padding: 22rpx 0;
  border-bottom: 1rpx solid #f1f5f9;
}
.sheet-row-main {
  flex: 1;
  min-width: 0;
}
.sheet-name {
  display: block;
  font-size: 28rpx;
  color: #0f172a;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sheet-meta {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #94a3b8;
}
.sheet-line-amt {
  font-size: 28rpx;
  font-weight: 700;
  color: #047857;
  flex-shrink: 0;
}
.sheet-foot {
  padding-top: 20rpx;
  border-top: 1rpx solid #e2e8f0;
  margin-top: 8rpx;
}
.sheet-foot-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
}
.sheet-foot-copy {
  flex: 1;
  min-width: 0;
}
.sheet-total-label {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #334155;
}
.sheet-total-amt {
  flex-shrink: 0;
  font-size: 40rpx;
  font-weight: 800;
  color: #047857;
  line-height: 1.1;
}
.sheet-foot-hint {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #94a3b8;
  line-height: 1.45;
}
</style>
