<template>
  <view class="video-page">
    <app-nav-bar title="购物视频" bg="#000000" color="#ffffff" />
    <view class="page-body">
      <view v-if="loading" class="state">
        <text class="state-title">{{ UI_COPY.loading }}</text>
        <text class="state-desc">正在获取购物录像</text>
      </view>
      <template v-else-if="mediaKind === 'image' && imageSrc">
        <image class="video-player still-frame" :src="imageSrc" mode="aspectFit" />
        <view class="tips">
          <text v-if="metaLine" class="meta">{{ metaLine }}</text>
          <text class="tip">当前为现场截图，暂无完整录像</text>
          <button type="button" class="copy-btn" size="mini" @click="copyUrl">复制链接</button>
        </view>
      </template>
      <view v-else-if="!src && error" class="state">
        <text class="state-title">视频加载失败</text>
        <text class="state-desc">{{ error }}</text>
        <app-button v-if="copyTarget" label="复制链接" @click="copyUrl" />
      </view>
      <view v-else-if="!src" class="state">
        <text class="state-title">缺少视频地址</text>
        <text class="state-desc">本单暂无购物视频，可返回订单详情</text>
      </view>
      <template v-else>
        <video
          class="video-player"
          :src="src"
          controls
          autoplay
          object-fit="contain"
          show-center-play-btn
          playsinline
          @loadedmetadata="onLoaded"
          @play="onLoaded"
          @error="onError"
        >
          <track
            kind="captions"
            srclang="zh"
            label="现场录像无对白字幕"
            src="data:text/vtt,WEBVTT"
          />
          <track
            kind="descriptions"
            srclang="zh"
            label="购物过程监控录像"
            src="data:text/vtt,WEBVTT"
          />
        </video>
        <view v-if="error" class="error-banner" role="alert">
          <text class="state-title">视频加载失败</text>
          <text class="state-desc">{{ error }}</text>
          <app-button label="复制链接" @click="copyUrl" />
        </view>
        <view v-else class="tips">
          <text v-if="metaLine" class="meta">{{ metaLine }}</text>
          <text class="tip">若无法播放，可复制链接到浏览器打开</text>
          <button type="button" class="copy-btn" size="mini" @click="copyUrl">复制链接</button>
        </view>
      </template>
      <view v-if="orderId" class="back-row">
        <text role="button" class="back-link app-link-chevron" @click="goOrder">返回订单详情</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  showError
} from '@/utils/notify';
import { onLoad, onUnload } from '@dcloudio/uni-app';
import { API_BASE_URL } from '@/config/api';
import { downloadAuthedFile, getConsumerToken } from '@/utils/consumer-api';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

const src = ref('');
/** 模拟器/网关偶发返回截图而非 mp4 时走图片预览 */
const imageSrc = ref('');
const mediaKind = ref<'video' | 'image' | ''>('');
const error = ref('');
const loading = ref(false);
const orderId = ref('');
const deviceId = ref('');
/** 用于复制的原始 URL（直链或 API 地址） */
const copyTarget = ref('');
let blobUrl = '';

function sniffImageMime(bytes: Uint8Array, declaredType: string): string | null {
  const declared = String(declaredType || '').toLowerCase();
  if (declared.startsWith('image/')) return declared;
  if (
    bytes.length >= 8 &&
    bytes[0] === 0x89 &&
    bytes[1] === 0x50 &&
    bytes[2] === 0x4e &&
    bytes[3] === 0x47
  ) {
    return 'image/png';
  }
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return 'image/jpeg';
  }
  if (
    bytes.length >= 12 &&
    bytes[0] === 0x52 &&
    bytes[1] === 0x49 &&
    bytes[2] === 0x46 &&
    bytes[3] === 0x46 &&
    bytes[8] === 0x57 &&
    bytes[9] === 0x45 &&
    bytes[10] === 0x42 &&
    bytes[11] === 0x50
  ) {
    return 'image/webp';
  }
  return null;
}

const metaLine = computed(() => {
  const parts: string[] = [];
  if (orderId.value) parts.push(`订单 ${orderId.value}`);
  if (deviceId.value) parts.push(`柜机 ${deviceId.value}`);
  return parts.join(' · ');
});

function normalizeVideoUrl(url: string): string {
  const trimmed = String(url || '').trim();
  if (!trimmed) return '';
  if (/^https?:\/\//i.test(trimmed)) return trimmed;
  const base = API_BASE_URL.replace(/\/$/, '');
  return trimmed.startsWith('/') ? base + trimmed : `${base}/${trimmed}`;
}

function revokeBlob() {
  if (blobUrl) {
    URL.revokeObjectURL(blobUrl);
    blobUrl = '';
  }
}

async function loadOrderVideo(oid: string) {
  loading.value = true;
  error.value = '';
  revokeBlob();
  src.value = '';
  imageSrc.value = '';
  mediaKind.value = '';
  const apiUrl = `${API_BASE_URL.replace(/\/$/, '')}/api/v2/orders/${encodeURIComponent(oid)}/video`;
  copyTarget.value = apiUrl;
  const token = getConsumerToken();
  try {
    // #ifdef H5
    const res = await fetch(apiUrl, {
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    });
    if (!res.ok) {
      if (res.status === 404) throw new Error('该订单暂无购物视频');
      throw new Error(`播放失败（HTTP ${res.status}）`);
    }
    const raw = await res.blob();
    const buffer = await raw.arrayBuffer();
    const head = new Uint8Array(buffer.slice(0, 16));
    const imageMime = sniffImageMime(head, raw.type);
    if (imageMime) {
      // 模拟器常存 jpg/png 截图；勿再强制 video/mp4 导致 MEDIA_ERR
      const imageBlob = new Blob([buffer], { type: imageMime });
      blobUrl = URL.createObjectURL(imageBlob);
      imageSrc.value = blobUrl;
      mediaKind.value = 'image';
      return;
    }
    // Vite 代理/部分网关可能把 Content-Type 变成 octet-stream，Chrome 会 MEDIA_ERR_SRC_NOT_SUPPORTED
    const blob =
      raw.type && raw.type.startsWith('video/')
        ? new Blob([buffer], { type: raw.type })
        : new Blob([buffer], { type: 'video/mp4' });
    blobUrl = URL.createObjectURL(blob);
    src.value = blobUrl;
    mediaKind.value = 'video';
    // #endif
    // #ifndef H5
    src.value = await downloadAuthedFile(apiUrl, 120_000);
    mediaKind.value = 'video';
    // #endif
  } catch (e) {
    error.value = e instanceof Error ? e.message : '视频地址无法访问，请复制链接后到浏览器打开';
  } finally {
    loading.value = false;
  }
}

onLoad(async (opts) => {
  orderId.value = String(opts?.orderId || '').trim();
  deviceId.value = String(opts?.deviceId || '').trim();
  const raw = String(opts?.url || opts?.videoUrl || '').trim();

  if (orderId.value) {
    await loadOrderVideo(orderId.value);
    return;
  }

  src.value = normalizeVideoUrl(raw);
  copyTarget.value = src.value;
  if (!src.value) {
    error.value = '缺少视频地址';
  }
});

onUnload(() => revokeBlob());

function onLoaded() {
  error.value = '';
}

function onError() {
  error.value = '视频地址无法访问，请复制链接后到浏览器打开';
}

function copyUrl() {
  const data = copyTarget.value || src.value;
  if (!data) return;
  uni.setClipboardData({
    data,
    success: () => showError('视频链接已复制')
  });
}

function goOrder() {
  if (!orderId.value) return;
  uni.navigateTo({
    url: `/pages/order-detail/order-detail?orderId=${encodeURIComponent(orderId.value)}`
  });
}
</script>

<style scoped>
.video-page {
  min-height: 100%;
  background: #000;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  padding: 0;
  box-sizing: border-box;
}
.page-body {
  padding: 10px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
}
.video-player {
  width: 100%;
  height: 56vh;
  background: #111;
  border-radius: 8px;
}
.still-frame {
  object-fit: contain;
}
.state {
  margin-top: 30vh;
  text-align: center;
  color: var(--text-subtle);
}
.state-title {
  display: block;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-border);
}
.state-desc {
  display: block;
  margin-top: 6px;
  font-size: 13px;
}
.state .app-btn,
.state .app-btn {
  margin-top: 20px;
}
.error-banner {
  margin-top: 12px;
  text-align: center;
  color: var(--text-subtle);
  max-width: 92%;
}
.error-banner .app-btn,
.error-banner .app-btn {
  margin-top: 12px;
}
.tips {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.meta {
  color: var(--text-subtle, #cbd5e1);
  font-size: 11px;
}
.tip {
  color: var(--text-subtle);
  font-size: 12px;
}
.copy-btn {
  background: rgba(15, 23, 42, 0.45);
  color: var(--page-bg, #f8fafc);
  border: 1px solid rgba(255, 255, 255, 0.25);
  border-radius: 999px;
  font-size: 12px;
  min-height: 36px;
  height: 36px;
  line-height: 36px;
  padding: 0 14px;
}
.copy-btn::after {
  border: none;
}
.back-row {
  margin-top: 14px;
}
.back-link {
  color: #34d399;
  font-size: 13px;
}
</style>
