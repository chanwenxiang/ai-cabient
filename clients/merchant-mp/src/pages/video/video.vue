<template>
  <view class="video-page">
    <app-nav-bar :title="pageTitle" home-url="/pages/home/home" />
    <view class="page-body">
      <view v-if="loading" class="state">
        <text class="state-title">{{ UI_COPY.loading }}</text>
        <text class="state-desc">{{ loadingDesc }}</text>
      </view>
      <view v-else-if="errorView" class="state">
        <text class="state-title">{{ errorView.title }}</text>
        <text class="state-desc">{{ errorView.desc }}</text>
        <view v-if="canRetry" role="button" class="retry-link" @click="retryLoad">重新加载</view>
      </view>
      <view v-else-if="!src" class="state">
        <text class="state-title">缺少视频地址</text>
        <text class="state-desc">{{ emptyStateDesc }}</text>
      </view>
      <template v-else>
        <video
          class="video-player"
          :style="{ height: playerHeight }"
          :src="src"
          controls
          object-fit="contain"
          :show-center-play-btn="true"
          :enable-progress-gesture="true"
          @loadedmetadata="onVideoMeta"
          @error="onVideoError"
        />
        <view v-if="clips.length > 1" class="clip-bar">
          <view
            v-for="(clip, idx) in clips"
            :key="`${clip.serialNum}-${clip.channel}-${idx}`"
            role="button"
            class="clip-chip"
            :class="{ 'clip-chip--active': idx === activeClip }"
            @click="selectClip(idx)"
            >{{ clipLabel(clip, clips) }}</view
          >
        </view>
        <view class="tips">
          <text v-if="metaLine" class="meta">{{ metaLine }}</text>
          <text v-if="failedCount > 0" class="meta warn"
            >另有 {{ failedCount }} 片录像生成或上传失败</text
          >
        </view>
      </template>
      <view v-if="canRetry" class="back-row">
        <view role="button" class="back-link app-link-chevron" @click="goBack">{{
          backLabel
        }}</view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import { request } from '@/utils/merchant-api';
import { MerchantEndpoints } from '@/api/endpoints';
import {
  clipLabel,
  merchantVideoErrorView,
  pickPlayableClips,
  type OrderVideoClip,
  type OrderVideoPlaylist
} from '@/utils/order-video-url';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import { readWindowInfo } from '@aicabinet/shared-uni/status-bar';

const src = ref('');
const errorView = ref<ReturnType<typeof merchantVideoErrorView> | null>(null);
const loading = ref(false);
const orderId = ref('');
/** CB-078：补货录像模式（同一播放页，数据源换成补货任务台账）。 */
const taskId = ref('');
const deviceId = ref('');
/** CB-030：将邑多片清单（超过 1 片时展示切换） */
const clips = ref<OrderVideoClip[]>([]);
const activeClip = ref(0);
/** 生成/上传失败的片数：如实告知，不静默当「没有这段」 */
const failedCount = ref(0);

const isTaskMode = computed(() => !!taskId.value);
const pageTitle = computed(() => (isTaskMode.value ? '补货录像' : '购物视频'));
const loadingDesc = computed(() => (isTaskMode.value ? '正在获取补货录像' : '正在获取购物录像'));
const emptyStateDesc = computed(() =>
  isTaskMode.value
    ? '补货录像由柜机回传，一般十几分钟后才有，请稍后再刷新'
    : '本单暂无购物视频，可返回订单详情'
);
const canRetry = computed(() => !!orderId.value || !!taskId.value);
const backLabel = computed(() => (isTaskMode.value ? '返回补货任务' : '返回订单详情'));

const metaLine = computed(() => {
  const parts: string[] = [];
  if (orderId.value) parts.push(`订单 ${orderId.value}`);
  if (taskId.value) parts.push(`补货任务 ${taskId.value}`);
  if (deviceId.value) parts.push(`柜机 ${deviceId.value}`);
  return parts.join(' · ');
});

async function loadPlaylist() {
  loading.value = true;
  errorView.value = null;
  src.value = '';
  clips.value = [];
  activeClip.value = 0;
  failedCount.value = 0;
  const url = isTaskMode.value
    ? MerchantEndpoints.replenishmentTaskVideos(taskId.value)
    : MerchantEndpoints.orderVideos(orderId.value);
  try {
    // CB-030/CB-078：先取清单决定走哪条链路。将邑柜机的视频不写会话（shopping_session.video_uri
    // 只由旧边缘链路写入），必须回落到将邑视频台账拿预签名地址，否则永远「暂无」。
    const playlist = await request<OrderVideoPlaylist>(url, 'GET');
    if (playlist?.source === 'JIANGYI') {
      const playable = pickPlayableClips(playlist);
      failedCount.value = (playlist.clips ?? []).filter((c) => !c.playable).length;
      if (!playable.length) {
        errorView.value = {
          title: isTaskMode.value ? '暂无补货录像' : '暂无购物视频',
          // 台账一条没回 ≠ 有片但坏：前者只是柜机还没回传（实测十几分钟），别写成「上传失败」骗商户报障
          desc: isTaskMode.value
            ? failedCount.value > 0
              ? '本次补货录像该片生成或上传失败，可稍后重试或联系运营核对。'
              : '柜机还没回传本次补货录像，一般十几分钟后才有，请稍后再刷新。'
            : '本单录像该片生成或上传失败，可稍后重试或联系运营核对。',
          showCopy: false
        };
        return;
      }
      clips.value = playable;
      src.value = playable[0].url || '';
      return;
    }
    errorView.value = {
      title: isTaskMode.value ? '暂无补货录像' : '暂无购物视频',
      desc: isTaskMode.value
        ? '本任务没有可播放的录像。柜机未上报录像时常见。'
        : '本单没有可播放的录像。超时免单、模拟柜或文件未上传时常见。',
      showCopy: false
    };
  } catch (e) {
    errorView.value = merchantVideoErrorView(e, isTaskMode.value ? 'replenish' : 'order');
  } finally {
    loading.value = false;
  }
}

function selectClip(index: number) {
  const clip = clips.value[index];
  if (!clip?.url) return;
  activeClip.value = index;
  src.value = clip.url;
}

/**
 * 播放器高度按视频真实宽高比算：原先固定 56vh，柜机 640×352 的片源会在上下各留
 * 一大条黑边，整页看起来像"没加载出来"。元数据未到时按 16:9 兜底。
 */
const playerRatio = ref(16 / 9);
const playerHeight = computed(() => playerHeightFor(playerRatio.value));

function playerHeightFor(ratio: number): string {
  const info = readWindowInfo();
  const vw = Number(info?.windowWidth) || 375;
  const vh = Number(info?.windowHeight) || 667;
  // 与 .page-body 的 --page-gutter(24rpx) 对齐，播放器两侧各让一条
  const innerW = vw - (24 * vw * 2) / 750;
  const px = Math.round(innerW / Math.max(ratio, 0.2));
  return `${Math.min(Math.max(px, 160), Math.round(vh * 0.6))}px`;
}

function onVideoMeta(e: Event) {
  const detail = (e as unknown as { detail?: { width?: number; height?: number } }).detail;
  const w = Number(detail?.width) || 0;
  const h = Number(detail?.height) || 0;
  if (w > 0 && h > 0) playerRatio.value = w / h;
}

/**
 * 修复 2026-10-14：预签名 URL 短时效，过期/对象被清理时 video 直接黑屏无提示。
 * 播放错误 → 给出可重试的失败态（重新拉清单换新预签名地址）。
 */
function onVideoError() {
  if (errorView.value) return;
  errorView.value = {
    title: '视频播放失败',
    desc: '视频地址可能已过期，请重新加载后再试；多次失败请联系运营核对。',
    showCopy: false
  };
  src.value = '';
}

function retryLoad() {
  if (loading.value) return;
  if (!taskId.value && !orderId.value) return;
  void loadPlaylist();
}

onLoad(async (opts) => {
  taskId.value = String(opts?.taskId || '').trim();
  orderId.value = String(opts?.orderId || '').trim();
  deviceId.value = String(opts?.deviceId || '').trim();
  if (taskId.value || orderId.value) {
    await loadPlaylist();
    return;
  }
  errorView.value = {
    title: '无法打开录像',
    desc: '缺少任务号或订单号，请从订单详情或补货任务详情进入',
    showCopy: false
  };
});

function goBack() {
  if (isTaskMode.value) {
    uni.navigateBack();
    return;
  }
  if (!orderId.value) return;
  uni.navigateTo({
    url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(orderId.value)}`
  });
}
</script>

<style scoped>
.video-page {
  min-height: 100%;
  background: var(--page-bg);
  display: flex;
  flex-direction: column;
  align-items: stretch;
  padding: 0;
  box-sizing: border-box;
}
.page-body {
  padding: var(--spacing-lg, 24rpx) var(--page-gutter, 24rpx) 48rpx;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
}
.video-player {
  width: 100%;
  /* 首帧前兜底 16:9；真实高度由 onVideoMeta 按片源宽高比覆盖 */
  height: 52vw;
  background: var(--text-primary, #0f172a);
  border-radius: var(--radius-control);
}
.state {
  margin-top: 24vh;
  text-align: center;
  /* 令牌兜底：小程序端 WXSS 不认 :root，令牌未注入时不能变成黑色不可读（P2-2） */
  color: var(--text-muted, #64748b);
}
.state-title {
  display: block;
  font-size: var(--font-size-lg);
  font-weight: 600;
  color: var(--text-primary);
}
.state-desc {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-body);
  padding: 0 32rpx;
  line-height: 1.5;
}
.retry-link {
  margin-top: 24rpx;
  min-height: 88rpx;
  display: inline-flex;
  align-items: center;
  padding: 0 48rpx;
  border-radius: var(--radius-control);
  border: 1rpx solid var(--brand);
  color: var(--brand);
  font-size: var(--font-size-body);
}
.meta {
  color: var(--text-muted, #64748b);
  font-size: var(--font-size-xs);
}
.meta.warn {
  margin-top: 8rpx;
  color: var(--warning);
}
.tips {
  margin-top: 24rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.back-row {
  margin-top: 28rpx;
}
.back-link {
  color: var(--brand);
  font-size: var(--font-size-body);
}
.clip-bar {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 16rpx;
  margin-top: 24rpx;
}
.clip-chip {
  padding: 8rpx var(--page-gutter, 24rpx);
  border-radius: var(--radius-pill, 999rpx);
  border: 1rpx solid var(--card-border, #e2e8f0);
  background: var(--card-bg);
  color: var(--text-muted);
  font-size: var(--font-size-caption);
}
.clip-chip--active {
  color: var(--brand);
  border-color: var(--brand);
  background: var(--brand-soft);
}
</style>
