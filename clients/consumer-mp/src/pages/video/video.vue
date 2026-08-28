<template>
  <view class="video-page">
    <app-nav-bar title="购物视频" bg="#000000" color="#ffffff" />
    <view class="page-body">
      <view v-if="error" class="state">
        <text class="state-title">视频加载失败</text>
        <text class="state-desc">{{ error }}</text>
        <button type="button" class="btn-primary" @click="copyUrl">复制链接</button>
      </view>
      <view v-else-if="!src" class="state">
        <text class="state-title">缺少视频地址</text>
        <text class="state-desc">本单暂无购物视频，可返回订单详情</text>
      </view>
      <video
        v-else
        class="video-player"
        :src="src"
        controls
        autoplay
        object-fit="contain"
        show-center-play-btn
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
      <view v-if="src" class="tips">
        <text v-if="metaLine" class="meta">{{ metaLine }}</text>
        <text class="tip">若无法播放，可复制链接到浏览器打开</text>
        <button type="button" class="copy-btn" size="mini" @click="copyUrl">复制链接</button>
      </view>
      <view v-if="orderId" class="back-row">
        <text class="back-link" @click="goOrder">返回订单详情 ›</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import { API_BASE_URL } from '@/config/api';

const src = ref('');
const error = ref('');
const orderId = ref('');
const deviceId = ref('');
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

onLoad((opts) => {
  const raw = String(opts?.url || opts?.videoUrl || '').trim();
  src.value = normalizeVideoUrl(raw);
  orderId.value = String(opts?.orderId || '').trim();
  deviceId.value = String(opts?.deviceId || '').trim();
  if (!src.value) {
    error.value = '缺少视频地址';
  }
});

function onError() {
  error.value = '视频地址无法访问，请复制链接后到浏览器打开';
}

function copyUrl() {
  if (!src.value) return;
  uni.setClipboardData({
    data: src.value,
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
  border-radius: 999px;
  font-size: 12px;
}
.back-row {
  margin-top: 14px;
}
.back-link {
  color: #34d399;
  font-size: 13px;
}
</style>
