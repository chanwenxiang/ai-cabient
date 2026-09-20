<template>
  <view v-if="items.length || placeholderVisible" class="ad-banner" data-testid="device-ad-banner">
    <swiper
      v-if="items.length"
      class="ad-swiper"
      circular
      :autoplay="items.length > 1"
      :interval="slideMs"
      :duration="400"
      @change="onSwiperChange"
    >
      <swiper-item v-for="(item, idx) in items" :key="item.assetId + '-' + idx">
        <image
          v-if="isImage(item)"
          class="ad-media"
          mode="aspectFill"
          :src="mediaSrc(item)"
          :alt="item.title || '广告'"
          role="button"
          :aria-label="item.title || '查看广告'"
          @click="onClick(item)"
        />
        <video
          v-else-if="isVideo(item)"
          class="ad-media"
          :src="mediaSrc(item)"
          :controls="false"
          :autoplay="idx === currentIndex"
          :muted="true"
          :show-center-play-btn="false"
          object-fit="cover"
          @click="onClick(item)"
        />
        <view v-else role="button" aria-label="查看广告" class="ad-fallback" @click="onClick(item)">
          <text class="ad-fallback-title">{{ item.title || '推广内容' }}</text>
        </view>
      </swiper-item>
    </swiper>
    <!--
      站位占位图（F4 切片 1）：开关已开、但该柜没有生效中的投放计划时渲染。
      🔴 刻意**不绑定任何点击、也不上报任何事件** —— 占位不是广告，不该产生计量数据
      （计量即计费依据，见 docs/AD_MONETIZATION_DESIGN.md §5）。
    -->
    <image
      v-else
      class="ad-media"
      mode="aspectFill"
      :src="placeholderUrl"
      alt="广告位招租"
      data-testid="device-ad-banner-placeholder"
    />
    <text v-if="campaignName" class="ad-caption">{{ campaignName }}</text>
  </view>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { API_BASE_URL } from '@/config/api';
import { consumerApi } from '@/utils/consumer-api';
import type { ScreenContentItemDto } from '@aicabinet/shared-types';
import placeholderUrl from '@/static/ad/slot-placeholder.png';

const props = defineProps<{
  deviceId: string;
}>();

const campaignId = ref<number | null>(null);
const campaignName = ref('');
const items = ref<ScreenContentItemDto[]>([]);
const currentIndex = ref(0);
/**
 * 是否已完成一次加载（成功或失败）。
 * 🔴 用它（而不是 `!items.length`）当占位图的闸门：加载中 `items` 也是空数组，
 * 直接判空会让占位图在真实素材到达前**闪一下**，看起来像「投放内容丢了」。
 */
const loaded = ref(false);
const completeTimers = new Map<number, ReturnType<typeof setTimeout>>();
const impressed = new Set<number>();

/** 站位：开关开、加载完成、但该柜没有生效投放 ⇒ 显示占位图。 */
const placeholderVisible = computed(() => loaded.value && items.value.length === 0);

const slideMs = computed(() => {
  const cur = items.value[currentIndex.value];
  const sec = cur?.durationSeconds && cur.durationSeconds > 0 ? cur.durationSeconds : 8;
  return Math.min(30_000, Math.max(3_000, sec * 1000));
});

function isImage(item: ScreenContentItemDto) {
  return String(item.assetType || '').toUpperCase() === 'IMAGE';
}

function isVideo(item: ScreenContentItemDto) {
  return String(item.assetType || '').toUpperCase() === 'VIDEO';
}

function mediaSrc(item: ScreenContentItemDto) {
  const raw = (item.playUrl || item.storageUri || '').trim();
  if (!raw) return '';
  if (raw.startsWith('http://') || raw.startsWith('https://')) return raw;
  const base = API_BASE_URL.replace(/\/$/, '');
  return raw.startsWith('/') ? base + raw : `${base}/${raw}`;
}

async function load() {
  const id = (props.deviceId || '').trim();
  if (!id) {
    reset();
    loaded.value = false;
    return;
  }
  loaded.value = false;
  try {
    const data = await consumerApi.screenContent(id);
    campaignId.value = data?.campaignId ?? null;
    campaignName.value = data?.campaignName || '';
    items.value = Array.isArray(data?.items) ? data.items : [];
    currentIndex.value = 0;
    impressed.clear();
    clearCompleteTimers();
    if (items.value.length) {
      void reportImpression(items.value[0]);
      scheduleComplete(items.value[0]);
    }
  } catch {
    reset();
  }
  // 🔴 无论成功失败都置 true：取不到投放内容 ≠ 取不到广告位。开关由页面把关，
  // 这里落占位图，避免「接口失败」被表现成「整块消失」（运营会误判开关没生效）。
  loaded.value = true;
}

function reset() {
  campaignId.value = null;
  campaignName.value = '';
  items.value = [];
  currentIndex.value = 0;
  impressed.clear();
  clearCompleteTimers();
}

function clearCompleteTimers() {
  for (const t of completeTimers.values()) clearTimeout(t);
  completeTimers.clear();
}

function onSwiperChange(e: { detail?: { current?: number } }) {
  const idx = Number(e?.detail?.current ?? 0);
  currentIndex.value = Number.isFinite(idx) ? idx : 0;
  const item = items.value[currentIndex.value];
  if (!item) return;
  void reportImpression(item);
  scheduleComplete(item);
}

async function reportImpression(item: ScreenContentItemDto) {
  if (!campaignId.value || !item?.assetId || impressed.has(item.assetId)) return;
  impressed.add(item.assetId);
  try {
    await consumerApi.reportAdPlay(props.deviceId, {
      campaignId: campaignId.value,
      assetId: item.assetId,
      eventType: 'IMPRESSION'
    });
  } catch {
    /* 曝光失败不打断开门 */
  }
}

function scheduleComplete(item: ScreenContentItemDto) {
  if (!campaignId.value || !item?.assetId) return;
  if (completeTimers.has(item.assetId)) return;
  const waitMs = Math.min(30_000, Math.max(1_500, (item.durationSeconds || 8) * 1000));
  const timer = setTimeout(() => {
    completeTimers.delete(item.assetId);
    void consumerApi
      .reportAdPlay(props.deviceId, {
        campaignId: campaignId.value!,
        assetId: item.assetId,
        eventType: 'COMPLETE'
      })
      .catch(() => undefined);
  }, waitMs);
  completeTimers.set(item.assetId, timer);
}

async function onClick(item: ScreenContentItemDto) {
  if (!campaignId.value || !item?.assetId) return;
  try {
    await consumerApi.reportAdPlay(props.deviceId, {
      campaignId: campaignId.value,
      assetId: item.assetId,
      eventType: 'CLICK'
    });
  } catch {
    /* ignore */
  }
}

watch(
  () => props.deviceId,
  () => {
    void load();
  },
  { immediate: true }
);

onBeforeUnmount(() => {
  clearCompleteTimers();
});
</script>

<style scoped>
.ad-banner {
  margin: 12rpx 24rpx 0;
  border-radius: var(--radius-panel);
  overflow: hidden;
  background: var(--color-border-subtle);
}
.ad-swiper {
  height: 220rpx;
  width: 100%;
}
.ad-media {
  width: 100%;
  height: 220rpx;
  display: block;
}
.ad-fallback {
  height: 220rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e8eef5, var(--surface-muted));
}
.ad-fallback-title {
  font-size: var(--font-size-md);
  color: var(--text-muted, #4b5563);
}
.ad-caption {
  display: block;
  padding: 8rpx 16rpx 12rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
</style>
