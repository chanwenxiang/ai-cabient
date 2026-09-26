<template>
  <view
    v-if="visible"
    role="button"
    aria-label="关闭"
    class="order-appeal-mask"
    :class="surfaceClass"
    @click="emit('close')"
  >
    <view role="button" class="order-appeal-panel" :class="surfaceClass" @click.stop>
      <text class="order-appeal-title" :class="surfaceClass">{{ title }}</text>
      <text class="order-appeal-sub" :class="surfaceClass">{{ subtitle }}</text>
      <view class="chip-row">
        <text
          v-for="chip in reasonChips"
          role="button"
          :key="chip.label"
          class="reason-chip"
          :class="{ on: selectedCategory === chip.category }"
          @click="emit('pick-chip', chip)"
          >{{ chip.label }}</text
        >
      </view>
      <text class="field-label">申诉说明</text>
      <textarea
        :value="reason"
        class="order-appeal-input"
        :class="surfaceClass"
        maxlength="200"
        aria-label="申诉说明"
        placeholder="例如：我没有拿这个商品 / 数量不对…"
        @input="onReasonInput"
      />
      <slot name="partial" />
      <view class="evidence-block">
        <text class="evidence-label">{{ evidenceLabel }}</text>
        <view class="evidence-row">
          <view
            v-for="(img, idx) in evidence"
            :key="img.localPath + idx"
            class="evidence-item"
            :class="surfaceClass"
          >
            <image
              class="evidence-img"
              :class="surfaceClass"
              :src="previewSrc(img)"
              mode="aspectFill"
              :aria-label="`证据图 ${idx + 1}`"
            />
            <text
              class="evidence-del"
              :class="surfaceClass"
              role="button"
              aria-label="删除证据图"
              @click="emit('remove-evidence', idx)"
              >×</text
            >
            <text v-if="img.uploading" class="evidence-uploading" :class="surfaceClass"
              >上传中…</text
            >
          </view>
          <view
            v-if="evidence.length < 5"
            class="evidence-add"
            :class="surfaceClass"
            role="button"
            aria-label="添加证据图"
            @click="emit('add-evidence')"
            >+</view
          >
        </view>
      </view>
      <app-button
        :loading="disputeLoading || refundLoading"
        :disabled="disputeLoading || refundLoading"
        :label="submitLabel"
        @click="emit('submit')"
      />
      <text
        role="button"
        class="order-appeal-cancel"
        :class="surfaceClass"
        aria-label="取消申诉"
        @click="emit('close')"
        >取消</text
      >
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { DISPUTE_REASON_CHIPS, type DisputeReasonChip } from '@/utils/dispute-form';
import {
  appealEvidenceLabel,
  appealPanelSubtitle,
  appealPanelTitle,
  appealSubmitLabel,
  type AppealSurface
} from '@/utils/order-appeal';
import { previewEvidenceSrc, type LocalEvidence } from '@/utils/dispute-evidence';

const props = defineProps<{
  visible: boolean;
  surface: AppealSurface;
  refundMode: boolean;
  reason: string;
  selectedCategory: string;
  evidence: LocalEvidence[];
  disputeLoading: boolean;
  refundLoading: boolean;
}>();

const emit = defineEmits<{
  close: [];
  submit: [];
  'update:reason': [value: string];
  'pick-chip': [chip: DisputeReasonChip];
  'add-evidence': [];
  'remove-evidence': [idx: number];
}>();

const reasonChips = DISPUTE_REASON_CHIPS;
const surfaceClass = computed(() => `surface-${props.surface}`);
const title = computed(() => appealPanelTitle(props.refundMode, props.surface));
const subtitle = computed(() => appealPanelSubtitle(props.refundMode, props.surface));
const evidenceLabel = computed(() => appealEvidenceLabel(props.surface));
const submitLabel = computed(() =>
  appealSubmitLabel({
    refundMode: props.refundMode,
    refundLoading: props.refundLoading,
    disputeLoading: props.disputeLoading
  })
);

function previewSrc(img: LocalEvidence): string {
  return previewEvidenceSrc(img);
}

function onReasonInput(e: unknown) {
  const detail = (e as { detail?: { value?: string } } | null)?.detail;
  emit('update:reason', detail?.value ?? '');
}
</script>

<style scoped>
/* surface 修饰保留 order-detail / result 既有视觉差（z-index、对齐、证据缩略图尺寸）。 */
.order-appeal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: flex-end;
}
.order-appeal-mask.surface-order-detail {
  z-index: 100;
}
.order-appeal-mask.surface-result {
  z-index: 300;
}
.order-appeal-panel {
  width: 100%;
  max-height: 90vh;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: var(--card-bg, #fff);
  box-sizing: border-box;
}
.order-appeal-panel.surface-order-detail {
  border-radius: var(--radius-card) 24rpx 0 0;
  padding: 32rpx 28rpx calc(32rpx + env(safe-area-inset-bottom));
}
.order-appeal-panel.surface-result {
  max-width: 520px;
  margin: 0 auto;
  border-radius: var(--radius-card) 30rpx 0 0;
  padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
}
.order-appeal-title {
  font-size: var(--font-size-h3);
  font-weight: 700;
  display: block;
}
.order-appeal-title.surface-result {
  text-align: center;
}
.order-appeal-sub {
  display: block;
  color: var(--text-subtle, #888);
  line-height: 1.5;
}
.order-appeal-sub.surface-order-detail {
  font-size: var(--font-size-caption);
  margin: 12rpx 0 20rpx;
}
.order-appeal-sub.surface-result {
  font-size: var(--font-size-body);
  text-align: center;
  margin: 12rpx 0 24rpx;
}
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-bottom: 16rpx;
}
.reason-chip {
  padding: 10rpx 18rpx;
  border-radius: var(--radius-pill);
  background: var(--color-border-subtle);
  color: var(--text-primary);
  font-size: var(--font-size-caption);
  border: 1rpx solid transparent;
}
.reason-chip.on {
  background: color-mix(in srgb, var(--danger, #b91c1c) 8%, var(--white));
  color: var(--color-danger);
  border-color: color-mix(in srgb, var(--danger, #b91c1c) 18%, var(--white));
}
.field-label {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  margin-bottom: 8rpx;
}
.order-appeal-input {
  width: 100%;
  box-sizing: border-box;
  font-size: var(--font-size-md);
  border-radius: var(--radius-control);
  padding: 20rpx;
}
.order-appeal-input.surface-order-detail {
  min-height: 140rpx;
  background: var(--page-bg, #f5f7f8);
  margin-bottom: 16rpx;
}
.order-appeal-input.surface-result {
  min-height: 180rpx;
  background: var(--page-bg, #f8faf9);
  border: 1rpx solid var(--color-border-subtle);
  margin-bottom: 20rpx;
}
.evidence-block {
  margin-bottom: 20rpx;
}
.evidence-label {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  margin-bottom: 12rpx;
}
.surface-result .evidence-label {
  color: var(--text-subtle, #888);
  margin-bottom: 10rpx;
}
.evidence-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.surface-result .evidence-row {
  gap: 14rpx;
}
.evidence-item {
  position: relative;
}
.evidence-item.surface-order-detail {
  width: 140rpx;
  height: 140rpx;
}
.evidence-item.surface-result {
  width: 120rpx;
  height: 120rpx;
}
.evidence-img {
  border-radius: var(--radius-control);
  background: var(--color-border-subtle);
}
.evidence-img.surface-order-detail {
  width: 140rpx;
  height: 140rpx;
}
.evidence-img.surface-result {
  width: 120rpx;
  height: 120rpx;
  border-radius: var(--radius-tag);
}
.evidence-del {
  position: absolute;
  top: -8rpx;
  right: -8rpx;
  border-radius: 50%;
  background: var(--text-primary);
  color: var(--white);
  text-align: center;
}
.evidence-del.surface-order-detail {
  width: 36rpx;
  height: 36rpx;
  line-height: 36rpx;
  font-size: var(--font-size-caption);
}
.evidence-del.surface-result {
  width: 32rpx;
  height: 32rpx;
  line-height: 32rpx;
  font-size: var(--font-size-sm);
}
.evidence-uploading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  color: var(--white);
}
.evidence-uploading.surface-order-detail {
  font-size: var(--font-size-sm);
  border-radius: var(--radius-control);
}
.evidence-uploading.surface-result {
  font-size: var(--font-size-xs);
  border-radius: var(--radius-tag);
}
.evidence-add {
  border: 2rpx dashed var(--card-border);
  color: var(--text-subtle);
  display: flex;
  align-items: center;
  justify-content: center;
}
.evidence-add.surface-order-detail {
  width: 140rpx;
  height: 140rpx;
  border-radius: var(--radius-control);
  font-size: var(--font-size-display);
}
.evidence-add.surface-result {
  width: 120rpx;
  height: 120rpx;
  border-radius: var(--radius-tag);
  font-size: var(--font-size-h2);
}
.order-appeal-cancel {
  display: block;
  text-align: center;
  color: var(--text-subtle, #888);
  font-size: var(--font-size-md);
}
.order-appeal-cancel.surface-order-detail {
  margin-top: 20rpx;
  padding: 8rpx;
}
.order-appeal-cancel.surface-result {
  margin-top: 16rpx;
  padding: 12rpx;
}
</style>
