<template>
  <scroll-view
    v-if="scrollable"
    class="wx-tabs wx-tabs--scroll"
    scroll-x
    :show-scrollbar="false"
    :scroll-into-view="scrollInto"
    scroll-with-animation
  >
    <view
      v-for="item in items"
      :id="tabDomId(item.key)"
      :key="item.key"
      class="wx-tab"
      :class="{ active: isActive(item.key) }"
      role="button"
      :aria-label="item.label"
      @click="onPick(item.key)"
    >
      <text class="wx-tab-label">{{ item.label }}</text>
      <text v-if="item.badge" class="wx-tab-badge">{{ item.badge }}</text>
      <view class="wx-tab-ink" />
    </view>
  </scroll-view>
  <view v-else class="wx-tabs wx-tabs--equal">
    <view
      v-for="item in items"
      :key="item.key"
      class="wx-tab"
      :class="{ active: isActive(item.key) }"
      role="button"
      :aria-label="item.label"
      @click="onPick(item.key)"
    >
      <text class="wx-tab-label">{{ item.label }}</text>
      <text v-if="item.badge" class="wx-tab-badge">{{ item.badge }}</text>
      <view class="wx-tab-ink" />
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue';

export type UnderlineTabItem = {
  key: string;
  label: string;
  badge?: string | number;
};

const props = withDefaults(
  defineProps<{
    items: UnderlineTabItem[];
    value?: string | number;
    /** auto：≤5 项均分，更多横滑 */
    layout?: 'auto' | 'equal' | 'scroll';
  }>(),
  {
    items: () => [],
    value: '',
    layout: 'auto'
  }
);

const emit = defineEmits<{
  change: [key: string];
}>();

const scrollable = computed(() => {
  if (props.layout === 'scroll') return true;
  if (props.layout === 'equal') return false;
  return props.items.length > 5;
});

const scrollInto = computed(() => tabDomId(String(props.value ?? '')));

function tabDomId(key: string) {
  const safe = String(key || 'all').replace(/[^a-zA-Z0-9_-]/g, '_');
  return `wx-tab-${safe}`;
}

function isActive(key: string) {
  return String(key) === String(props.value ?? '');
}

function onPick(key: string) {
  if (isActive(key)) return;
  emit('change', key);
}
</script>

<style scoped>
.wx-tabs {
  width: 100%;
  background: var(--card-bg, #fff);
  border-bottom: 1rpx solid var(--card-border, #e8eeea);
  box-sizing: border-box;
}
.wx-tabs--scroll {
  height: 88rpx;
  white-space: nowrap;
}
.wx-tabs--scroll::-webkit-scrollbar {
  display: none;
  width: 0;
  height: 0;
}
.wx-tabs--equal {
  display: flex;
  flex-direction: row;
  align-items: stretch;
  min-height: 88rpx;
}
.wx-tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 88rpx;
  padding: 0 28rpx;
  vertical-align: top;
  box-sizing: border-box;
}
.wx-tabs--equal .wx-tab {
  flex: 1;
  min-width: 0;
  display: flex;
  padding: 0 12rpx;
}
.wx-tab-label {
  font-size: var(--font-size-lg, 30rpx);
  line-height: 1.2;
  color: var(--text-muted, #64748b);
  font-weight: 400;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wx-tab.active .wx-tab-label {
  color: var(--brand, #0f766e);
  font-weight: 600;
}
.wx-tab-badge {
  margin-left: 8rpx;
  min-width: 28rpx;
  height: 28rpx;
  padding: 0 8rpx;
  border-radius: 28rpx;
  background: var(--danger, #b91c1c);
  color: #fff;
  font-size: 20rpx;
  line-height: 28rpx;
  text-align: center;
  flex-shrink: 0;
}
.wx-tab-ink {
  position: absolute;
  left: 50%;
  bottom: 0;
  width: 40rpx;
  height: 6rpx;
  margin-left: -20rpx;
  border-radius: 6rpx;
  background: transparent;
}
.wx-tab.active .wx-tab-ink {
  background: var(--brand, #0f766e);
}
</style>
