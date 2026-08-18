<template>
  <view class="video-page">
    <app-nav-bar title="购物视频" bg="#000000" color="#ffffff" />
    <view class="page-body">
    <view v-if="error" class="state">
      <text class="state-title">视频加载失败</text>
      <text class="state-desc">{{ error }}</text>
      <button class="btn-primary" @click="copyUrl">复制链接</button>
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
    />
    <view v-if="src" class="tips">
      <text class="tip">若无法播放，可复制链接到浏览器打开</text>
      <button class="copy-btn" size="mini" @click="copyUrl">复制链接</button>
    </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import { API_BASE_URL } from '@/config/api';

const src = ref('');
const error = ref('');

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
  padding: 20rpx;
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
  border-radius: 16rpx;
}
.state {
  margin-top: 30vh;
  text-align: center;
  color: #94a3b8;
}
.state-title {
  display: block;
  font-size: 32rpx;
  font-weight: 600;
  color: #e2e8f0;
}
.state-desc {
  display: block;
  margin-top: 12rpx;
  font-size: 26rpx;
}
.state .btn-primary {
  margin-top: 40rpx;
}
.tips {
  margin-top: 24rpx;
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.tip {
  color: #94a3b8;
  font-size: 24rpx;
}
.copy-btn {
  background: rgba(255, 255, 255, 0.12);
  color: #e2e8f0;
  border: 1rpx solid rgba(255, 255, 255, 0.25);
  border-radius: 999rpx;
  font-size: 24rpx;
}
</style>
