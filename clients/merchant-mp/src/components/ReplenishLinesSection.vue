<template>
  <view>
    <view class="section-heading">
      <view class="section-heading-main">
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
      <!-- 单行居中：名称 + 数量/扫码 + 货道标签，不再拆成多行堆叠 -->
      <view class="line-row">
        <image
          v-if="skuThumb(skuKey(line))"
          class="product-thumb-img"
          :src="skuThumb(skuKey(line))"
          mode="aspectFill"
        />
        <view class="product-copy">
          <text class="sku-name">{{ displayName(line) }}</text>
          <text v-if="line.skuId" class="device-code">{{ line.skuId }}</text>
        </view>
        <view v-if="isLineEditable(line)" class="qty-actions">
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
        <template v-if="!completed">
          <text v-if="line.slotId" class="fact">货道 {{ line.slotId }}</text>
          <text v-else class="fact muted">货道待分配</text>
          <text v-if="line.batchNo" class="fact">批次 {{ line.batchNo }}</text>
          <text v-if="line.productionDate" class="fact">生产 {{ line.productionDate }}</text>
          <text v-if="line.expiryDate" class="fact">到期 {{ line.expiryDate }}</text>
        </template>
      </view>
      <view v-if="completed" class="line-recap">
        <text class="recap-line">{{ recapPrimary(line) }}</text>
        <text v-if="recapSecondary(line)" class="recap-line muted">{{ recapSecondary(line) }}</text>
      </view>
      <view
        v-if="isLineEditable(line) && !isPullOffType(line.lineType) && !line.slotId"
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
      <!-- 只保留满仓/超量警告；「现有/补后/还能再放」叙事句已删 -->
      <view
        v-if="!completed && line.slotId && isCapacityWarn(line)"
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
import { isPullOffType as lineIsPullOff } from '@/composables/useReplenishmentDisplay';

type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;
type SlotOpt = { slotCode: string; room: number };
type Fn<A extends unknown[], R> = ((...args: A) => R) | undefined;

const props = defineProps<{
  lines: Line[];
  detailLoading?: boolean;
  completed?: boolean;
  pullOff?: boolean;
  scanning?: boolean;
  /** 父页布尔：有补货权限且明细未确认。小程序不能把函数当 prop 可靠传入。 */
  canEdit?: boolean;
  skuName?: (id: string) => string;
  skuThumb?: (id: string) => string;
  productGlyph?: (id: string) => string;
  lineTypeLabel?: (t?: string | null) => string;
  lineStatusLabel?: (line: Line) => string;
  isPullOffType?: (t?: string | null) => boolean;
  slotOptionsFor?: (line: Line) => SlotOpt[];
  slotHeadroom?: (line: Line) => number;
  slotHint?: (line: Line) => string;
}>();

defineEmits<{
  'adjust-qty': [line: Line, delta: number];
  'scan-product': [line: Line];
  'assign-slot': [line: Line, opt: SlotOpt];
}>();

const loadingText = computed(() => loadingLabel('明细'));

const subtitle = computed(() =>
  props.pullOff ? '请逐项核对下架商品与货道' : '请逐项核对商品、批次和货道'
);

function callFn<A extends unknown[], R>(fn: Fn<A, R>, fallback: R, ...args: A): R {
  return typeof fn === 'function' ? fn(...args) : fallback;
}

function skuKey(line: Line) {
  return line.skuId || '';
}

function skuThumb(id: string) {
  return callFn(props.skuThumb, '', id);
}

function displayName(line: Line) {
  return line.skuName || callFn(props.skuName, '', skuKey(line)) || line.skuId || '商品';
}

function isPullOffType(type?: string | null) {
  return callFn(props.isPullOffType, lineIsPullOff(type), type);
}

function lineTypeLabel(type?: string | null) {
  return callFn(props.lineTypeLabel, isPullOffType(type) ? '下架' : '上架', type);
}

function lineStatusLabel(line: Line) {
  const fallback = line.applied
    ? isPullOffType(line.lineType)
      ? '已下架'
      : '已入柜'
    : isPullOffType(line.lineType)
      ? '待下架'
      : '待上架';
  return callFn(props.lineStatusLabel, fallback, line);
}

function slotOptionsFor(line: Line) {
  return callFn(props.slotOptionsFor, [] as SlotOpt[], line);
}

function slotHeadroom(line: Line) {
  return callFn(props.slotHeadroom, 0, line);
}

function slotHint(line: Line) {
  return callFn(props.slotHint, '', line);
}

/** 可改数量：父页允许编辑，且任务未完成、该行未入柜 */
function isLineEditable(line: Line) {
  return !!props.canEdit && !props.completed && !line.applied;
}

function recapPrimary(line: Line) {
  const parts = [
    line.slotId ? `货道 ${line.slotId}` : '',
    lineTypeLabel(line.lineType),
    `×${line.quantity ?? 0}`
  ].filter(Boolean);
  return parts.join(' · ');
}

function recapSecondary(line: Line) {
  return [
    line.batchNo ? `批次 ${line.batchNo}` : '',
    line.productionDate ? `生产 ${line.productionDate}` : '',
    line.expiryDate ? `到期 ${line.expiryDate}` : ''
  ]
    .filter(Boolean)
    .join(' · ');
}

function isCapacityWarn(line: Line) {
  if (isPullOffType(line.lineType)) return false;
  const room = slotHeadroom(line);
  const qty = Number(line.quantity) || 0;
  return room <= 0 || qty > room;
}
</script>

<style scoped>
.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16rpx;
  margin: 28rpx 0 14rpx;
}
.section-heading-main {
  flex: 1;
  min-width: 0;
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
  flex-shrink: 0;
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
.line-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 12rpx 16rpx;
}
.product-thumb-img {
  width: 56rpx;
  height: 56rpx;
  border-radius: var(--radius-panel);
  background: var(--brand-soft);
  flex-shrink: 0;
}
.product-copy {
  min-width: 0;
  max-width: 280rpx;
  text-align: left;
}
.sku-name {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.device-code {
  display: block;
  margin-top: 2rpx;
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
  flex-shrink: 0;
}
.scan-line {
  margin: 0;
  padding: 0 20rpx;
  height: 52rpx;
  line-height: 52rpx;
  border: none;
  border-radius: var(--radius-pill);
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand, #0f766e);
  font-size: var(--font-size-caption);
  font-weight: 600;
}
.scan-line::after {
  border: none;
}
.scan-line[disabled] {
  opacity: 0.5;
}
.qty-btn {
  width: 56rpx;
  height: 56rpx;
  line-height: 56rpx;
  text-align: center;
  border-radius: 50%;
  background: var(--card-bg, #fff);
  color: var(--brand);
  font-size: var(--font-size-lg);
  font-weight: 700;
  box-shadow: 0 2rpx 8rpx rgba(15, 118, 110, 0.12);
}
.fact {
  padding: 6rpx 12rpx;
  border-radius: 8rpx;
  background: var(--page-tint, #f0fdfa);
  color: var(--text-muted, #334155);
  font-size: var(--font-size-xs, 20rpx);
  font-weight: 600;
  line-height: 1.3;
}
.fact.accent {
  color: var(--brand, #0f766e);
  background: var(--brand-soft, #ecfdf5);
}
.fact.muted {
  color: var(--text-subtle, #94a3b8);
}
.line-recap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
  margin-top: 12rpx;
  padding-top: 12rpx;
  border-top: 1rpx solid var(--color-border-subtle, #e2e8f0);
}
.recap-line {
  color: var(--text-muted, #334155);
  font-size: var(--font-size-sm);
  line-height: 1.45;
  text-align: center;
}
.recap-line.muted {
  color: var(--text-subtle, #94a3b8);
  font-size: var(--font-size-xs, 20rpx);
}
.slot-pick {
  margin-top: 12rpx;
  text-align: center;
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
  justify-content: center;
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
  text-align: center;
}
.line-cap.warn {
  color: var(--warning, #b45309);
  background: #f9f1eb;
}
.line-cap.full {
  color: var(--color-danger);
  background: #f9eded;
}
.empty.small {
  padding: 24rpx 0;
  text-align: center;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
</style>
