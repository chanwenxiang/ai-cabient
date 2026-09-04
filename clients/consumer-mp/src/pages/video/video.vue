<template>
  <view class="video-page">
    <app-nav-bar title="购物视频" bg="#000000" color="#ffffff" />
    <view class="page-body">
      <view v-if="loading" class="state">
        <text class="state-title">加载中…</text>
        <text class="state-desc">正在获取购物录像</text>
      </view>
      <view v-else-if="!src && error" class="state">
        <text class="state-title">视频加载失败</text>
        <text class="state-desc">{{ error }}</text>
        <button v-if="copyTarget" type="button" class="btn-primary" @click="copyUrl">复制链接</button>
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
          <track kind="captions" srclang="zh" label="现场录像无对白字幕" src="data:text/vtt,WEBVTT" />
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
          <button type="button" class="btn-primary" @click="copyUrl">复制链接</button>
        </view>
        <view v-else class="tips">
          <text v-if="metaLine" class="meta">{{ metaLine }}</text>
          <text class="tip">若无法播放，可复制链接到浏览器打开</text>
          <button type="button" class="copy-btn" size="mini" @click="copyUrl">复制链接</button>
        </view>
      </template>
      <view v-if="orderId" class="back-row">
        <text class="back-link" @click="goOrder">返回订单详情 ›</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad, onUnload } from '@dcloudio/uni-app';
import { API_BASE_URL } from '@/config/api';
import { downloadAuthedFile, getConsumerToken } from '@/utils/consumer-api';

const src = ref('');
const error = ref('');
const loading = ref(false);
const orderId = ref('');
const deviceId = ref('');
/** 用于复制的原始 URL（直链或 API 地址） */
const copyTarget = ref('');
let blobUrl = '';

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
    // Vite 代理/部分网关可能把 Content-Type 变成 octet-stream，Chrome 会 MEDIA_ERR_SRC_NOT_SUPPORTED
    const blob =
      raw.type && raw.type.startsWith('video/')
        ? raw
        : new Blob([await raw.arrayBuffer()], { type: 'video/mp4' });
    blobUrl = URL.createObjectURL(blob);
    src.value = blobUrl;
    // #endif
    // #ifndef H5
    src.value = await downloadAuthedFile(apiUrl, 120_000);
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
    success: () => uni.showToast({ title: '视频链接已复制', icon: 'none' })
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
.state {
  margin-top: 30vh;
  text-align: center;
  color: #94a3b8;
}
.state-title {
  display: block;
  font-size: 16px;
  font-weight: 600;
  color: #e2e8f0;
}
.state-desc {
  display: block;
  margin-top: 6px;
  font-size: 13px;
}
.state .btn-primary {
  margin-top: 20px;
}
.error-banner {
  margin-top: 12px;
  text-align: center;
  color: #94a3b8;
  max-width: 92%;
}
.error-banner .btn-primary {
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
  color: #cbd5e1;
  font-size: 11px;
}
.tip {
  color: #94a3b8;
  font-size: 12px;
}
.copy-btn {
  background: rgba(15, 23, 42, 0.45);
  color: #f8fafc;
  border: 1px solid rgba(255, 255, 255, 0.25);
  border-radius: 999rpx;
  font-size: 24rpx;
  min-height: 72rpx;
  height: 72rpx;
  line-height: 72rpx;
  padding: 0 28rpx;
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
