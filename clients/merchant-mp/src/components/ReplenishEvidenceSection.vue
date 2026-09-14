<template>
  <view>
    <view class="section-heading">
      <view>
        <text class="section-title">现场照片</text>
        <text class="section-subtitle">{{ subtitle }}</text>
      </view>
      <text class="line-count" :class="{ warn: countWarn }">{{ items.length }} 张</text>
    </view>
    <view class="evidence-row">
      <view
        v-for="(item, idx) in items"
        :key="item.fileId || item.localPath || idx"
        class="evidence-thumb-wrap"
      >
        <image
          class="evidence-thumb"
          :src="item.localPath"
          mode="aspectFill"
          :aria-label="`现场照片 ${idx + 1}`"
          @click="$emit('preview', idx)"
        />
        <text class="evidence-caption">凭证 {{ idx + 1 }}</text>
      </view>
      <view
        v-if="canAdd"
        class="evidence-add"
        role="button"
        aria-label="添加现场照片"
        @click="$emit('add')"
      >
        <text class="evidence-add-plus">+</text>
        <text class="evidence-add-label">拍照</text>
      </view>
      <view
        v-else-if="!items.length"
        class="evidence-empty"
        role="button"
        :aria-label="checkedIn ? '添加现场照片' : '请先签到'"
        @click="checkedIn && canInteract ? $emit('add') : undefined"
      >
        <text class="evidence-empty-title">{{
          completed ? '本次未留存照片' : '暂无现场照片'
        }}</text>
        <text class="evidence-empty-tip">{{ emptyTip }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  items: { localPath: string; fileId?: number }[];
  checkedIn: boolean;
  completed: boolean;
  canInteract: boolean;
  requireEvidence: boolean;
}>();

defineEmits<{
  preview: [idx: number];
  add: [];
}>();

const subtitle = computed(() => {
  if (!props.checkedIn) return '签到后可拍照留存，最多 5 张';
  if (props.items.length) return `已上传 ${props.items.length}/5 · 点图可放大核对`;
  return props.requireEvidence
    ? '须至少 1 张现场照片，最多 5 张'
    : '选填：建议拍柜内/货道全景，最多 5 张';
});

const countWarn = computed(
  () => props.requireEvidence && props.items.length === 0 && props.checkedIn
);

const canAdd = computed(
  () => props.canInteract && !props.completed && props.checkedIn && props.items.length < 5
);

const emptyTip = computed(() => {
  if (!props.checkedIn) return '签到后可拍照';
  if (props.completed) return '完成后不可再补传';
  return props.requireEvidence
    ? '完成前须上传 · 点击拍照或从相册上传'
    : '可选上传 · 点击拍照或从相册上传';
});
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
.line-count.warn {
  color: var(--warning, #b45309);
}
.evidence-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-bottom: 20rpx;
}
.evidence-thumb-wrap {
  width: 140rpx;
}
.evidence-thumb,
.evidence-add {
  width: 140rpx;
  height: 140rpx;
  border-radius: var(--radius-panel);
  background: var(--brand-soft);
}
.evidence-thumb {
  display: block;
}
.evidence-caption {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-xs);
  color: var(--text-muted);
  text-align: center;
}
.evidence-add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 2rpx dashed var(--brand-mist, #99f6e4);
  color: var(--brand);
  gap: 4rpx;
  box-sizing: border-box;
}
.evidence-add-plus {
  font-size: var(--font-size-h2);
  font-weight: 600;
  line-height: 1;
}
.evidence-add-label {
  font-size: var(--font-size-xs);
}
.evidence-empty {
  width: 100%;
  min-height: 140rpx;
  height: auto;
  padding: 24rpx 20rpx;
  box-sizing: border-box;
  border: 2rpx dashed var(--text-subtle, #cbd5e1);
  background: var(--page-bg, #f8fafc);
  border-radius: var(--radius-panel);
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8rpx;
}
.evidence-empty-title {
  font-size: var(--font-size-body);
  font-weight: 650;
  color: var(--text-muted, #334155);
}
.evidence-empty-tip {
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
</style>
