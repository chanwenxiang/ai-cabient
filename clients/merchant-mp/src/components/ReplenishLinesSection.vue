<template>
  <view>
    <view class="section-heading">
      <view>
        <text class="section-title">{{ pullOff ? '本次下架商品' : '本次补货商品' }}</text>
        <text class="section-subtitle">{{ subtitle }}</text>
      </view>
      <text class="line-count">{{ lines.length }} 项</text>
    </view>
    <view v-if="detailLoading" class="empty small">{{ loadingText }}</view>
    <view v-else-if="!lines.length" class="empty small lines-empty">
      <view class="lines-empty-title">{{ pullOff ? '暂无下架明细' : '暂无补货明细' }}</view>
      <view class="lines-empty-tip">{{
        pullOff
          ? '可先开门执行下架；有任务明细时会显示在此核对'
          : '可先开门上架；有出库明细时会显示在此核对'
      }}</view>
    </view>
    <view
      v-for="line in lines"
      :key="line.lineId || `${line.skuId}-${line.batchNo}-${line.slotId}`"
      class="line-card"
    >
      <view class="line-main">
        <view class="product-thumb">
          <image
            v-if="skuThumb(skuKey(line))"
            class="product-thumb-img"
            :src="skuThumb(skuKey(line))"
            mode="aspectFill"
          />
          <text v-else class="product-mark">{{ productGlyph(skuKey(line)) }}</text>
        </view>
        <view class="product-copy">
          <text class="sku-name">{{ skuName(skuKey(line)) }}</text>
          <text class="device-code">{{ line.skuId }}</text>
        </view>
        <view v-if="canEditLine(line)" class="qty-actions">
          <view class="qty-stepper">
            <text
              class="qty-btn"
              role="button"
              aria-label="减少数量"
              @click="$emit('adjust-qty', line, -1)"
              >−</text
            >
            <text class="qty">{{ line.quantity }}</text>
            <text
              class="qty-btn"
              role="button"
              aria-label="增加数量"
              @click="$emit('adjust-qty', line, 1)"
              >+</text
            >
          </view>
          <button
            class="scan-line"
            :disabled="scanning"
            data-testid="scan-product-line"
            @click="$emit('scan-product', line)"
          >
            扫码
          </button>
        </view>
        <text v-else class="qty">× {{ line.quantity }}</text>
      </view>
      <view class="line-meta">
        <text>批次 {{ line.batchNo || '无批次' }}</text>
        <text>货道 {{ line.slotId || '待分配' }}</text>
        <text class="line-type">{{ lineTypeLabel(line.lineType) }}</text>
      </view>
      <view class="line-meta soft">
        <text>生产 {{ line.productionDate || '未填' }}</text>
        <text>到期 {{ line.expiryDate || '未填' }}</text>
        <text>{{ lineStatusLabel(line) }}</text>
      </view>
      <view class="line-stock" :class="{ muted: !stockDeltaText(line) }">{{
        stockDeltaText(line) ||
        (line.slotId
          ? '货道容量待同步'
          : isPullOffType(line.lineType)
            ? '选货道后显示账面 → 下架后数量'
            : '选货道后显示账面 → 补后数量')
      }}</view>
      <view
        v-if="canEditLine(line) && !isPullOffType(line.lineType) && !line.slotId"
        class="slot-pick"
      >
        <text class="slot-pick-label">选择货道</text>
        <view v-if="slotOptionsFor(line).length" class="slot-chips">
          <text
            v-for="opt in slotOptionsFor(line)"
            role="button"
            :key="opt.slotCode"
            class="slot-chip"
            :class="{ disabled: opt.room <= 0, active: line.slotId === opt.slotCode }"
            @click="$emit('assign-slot', line, opt)"
            >{{ opt.slotCode }} · 余{{ opt.room }}</text
          >
        </view>
        <text v-else class="slot-empty">暂无可用货道，请先腾出容量或将数量调为 0</text>
      </view>
      <view
        v-if="!completed && line.slotId && slotHint(line)"
        class="line-cap"
        :class="{
          full: slotHeadroom(line) <= 0,
          warn: slotHeadroom(line) > 0 && (line.quantity ?? 0) > slotHeadroom(line)
        }"
        >{{ slotHint(line) }}</view
      >
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';

type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;
type SlotOpt = { slotCode: string; room: number };

const props = defineProps<{
  lines: Line[];
  detailLoading: boolean;
  pullOff: boolean;
  outboundId?: number | null;
  canEdit: boolean;
  completed: boolean;
  scanning: boolean;
  skuName: (id: string) => string;
  skuThumb: (id: string) => string;
  productGlyph: (id: string) => string;
  lineTypeLabel: (type?: string) => string;
  lineStatusLabel: (line: Line) => string;
  stockDeltaText: (line: Line) => string;
  isPullOffType: (type?: string) => boolean;
  slotOptionsFor: (line: Line) => SlotOpt[];
  slotHint: (line: Line) => string;
  slotHeadroom: (line: Line) => number;
}>();

defineEmits<{
  'adjust-qty': [line: Line, delta: number];
  'scan-product': [line: Line];
  'assign-slot': [line: Line, opt: SlotOpt];
}>();

const loadingText = loadingLabel('明细');

const subtitle = computed(() => {
  if (props.pullOff) return '请逐项核对下架数量与批次';
  if (props.outboundId) return `仓配出库 #${props.outboundId} · 核对后完成将签收在途`;
  return '请逐项核对商品、批次和货道';
});

function canEditLine(line: Line) {
  return props.canEdit && !props.completed && !line.applied;
}

/**
 * 明细行的 SKU 编号。
 * `line.skuId` 在生成类型里是可选的，而 `skuThumb` / `skuName` / `productGlyph`
 * 三个注入函数签名都是 `(id: string)`，模板直传会报 TS2345，故在此统一兜空串。
 */
function skuKey(line: Line) {
  return line.skuId || '';
}
</script>

<style scoped>
.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin: 28rpx 0 14rpx;
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
.line-count {
  padding: 6rpx 12rpx;
  border-radius: var(--radius-pill);
  color: var(--brand);
  background: var(--brand-mist);
  font-size: var(--font-size-sm);
  font-weight: 700;
}
.lines-empty {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.lines-empty-title {
  font-size: 13px;
  color: var(--text-muted);
}
.lines-empty-tip {
  font-size: 12px;
  color: var(--text-subtle);
  line-height: 1.4;
}
.line-card {
  margin-bottom: 14rpx;
  padding: 20rpx;
  border: 1rpx solid var(--color-border);
  border-radius: 18rpx;
}
.line-main {
  display: flex;
  align-items: center;
  gap: 0;
}
.sku-name {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 360rpx;
}
.device-code {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.qty {
  color: var(--brand);
  font-size: var(--font-size-lg);
  font-weight: 800;
  min-width: 40rpx;
  text-align: center;
}
.qty-stepper {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 4rpx 8rpx;
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
}
.qty-actions {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.scan-line {
  margin: 0;
  padding: 0 20rpx;
  height: 52rpx;
  line-height: 52rpx;
  border-radius: var(--radius-pill);
  background: var(--brand);
  color: var(--white);
  font-size: var(--font-size-caption);
  font-weight: 600;
}
.scan-line[disabled] {
  opacity: 0.5;
}
.qty-btn {
  width: 88rpx;
  height: 88rpx;
  line-height: 88rpx;
  text-align: center;
  border-radius: 50%;
  background: var(--card-bg, #fff);
  color: var(--brand);
  font-size: var(--font-size-xl);
  font-weight: 700;
  box-shadow: 0 2rpx 8rpx rgba(15, 118, 110, 0.12);
}
.line-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx 20rpx;
  margin-top: 12rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #475569);
}
.line-meta.soft {
  color: var(--text-muted);
}
.line-type {
  color: var(--brand);
  font-weight: 600;
}
.line-stock {
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--brand);
  background: var(--brand-soft);
  border-radius: var(--radius-tag);
  padding: 8rpx 12rpx;
}
.line-stock.muted {
  color: var(--text-muted);
  background: var(--page-bg, #f8fafc);
}
.slot-pick {
  margin-top: 12rpx;
}
.slot-pick-label {
  display: block;
  font-size: var(--font-size-sm);
  color: var(--brand);
  margin-bottom: 8rpx;
  font-weight: 600;
}
.slot-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
}
.slot-chip {
  padding: 8rpx 16rpx;
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
  color: var(--brand);
  font-size: var(--font-size-sm);
  border: 1rpx solid var(--brand-mist, #99f6e4);
}
.slot-chip.active {
  background: var(--brand);
  color: var(--white);
  border-color: var(--brand);
}
.slot-chip.disabled {
  background: var(--color-border);
  color: var(--text-muted, #475569);
  border-color: var(--text-subtle, #cbd5e1);
}
.slot-empty {
  font-size: var(--font-size-sm);
  color: var(--color-danger);
}
.line-cap {
  margin-top: 12rpx;
  padding: 10rpx 14rpx;
  border-radius: var(--radius-control);
  font-size: var(--font-size-sm);
  color: var(--brand);
  background: var(--brand-soft);
}
.line-cap.warn {
  color: var(--warning, #b45309);
  background: #f9f1eb;
}
.line-cap.full {
  color: var(--color-danger);
  background: #f9eded;
}
.product-thumb {
  position: relative;
  display: flex;
  width: 72rpx;
  height: 72rpx;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-panel);
  background: var(--brand-soft);
  font-size: var(--font-size-xl);
  margin-right: 16rpx;
}
.product-thumb-img {
  width: 100%;
  height: 100%;
  border-radius: var(--radius-panel);
  background: var(--brand-soft);
}
.product-mark {
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--brand);
}
.product-copy {
  flex: 1;
  min-width: 0;
}
.empty.small {
  padding: 24rpx 0;
  text-align: center;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
</style>
