<template>
  <view class="video-page">
    <app-nav-bar title="购物视频" bg="#000000" color="#ffffff" home-url="/pages/home/home" />
    <view class="page-body">
      <view v-if="loading" class="state">
        <text class="state-title">{{ UI_COPY.loading }}</text>
        <text class="state-desc">正在获取购物录像</text>
      </view>
      <view v-else-if="errorView" class="state">
        <text class="state-title">{{ errorView.title }}</text>
        <text class="state-desc">{{ errorView.desc }}</text>
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
          object-fit="contain"
          :show-center-play-btn="true"
          :enable-progress-gesture="true"
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
        </view>
      </template>
      <view v-if="orderId" class="back-row">
        <view role="button" class="back-link app-link-chevron" @click="goOrder">返回订单详情</view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import { downloadAuthedFile, request } from '@/utils/merchant-api';
import { MerchantEndpoints } from '@/api/endpoints';
import {
  clipLabel,
  merchantOrderVideoUrl,
  merchantVideoErrorView,
  pickPlayableClips,
  type OrderVideoClip,
  type OrderVideoPlaylist
} from '@/utils/order-video-url';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

const src = ref('');
const errorView = ref<ReturnType<typeof merchantVideoErrorView> | null>(null);
const loading = ref(false);
const orderId = ref('');
const deviceId = ref('');
/** CB-030：将邑多片清单（超过 1 片时展示切换） */
const clips = ref<OrderVideoClip[]>([]);
const activeClip = ref(0);

const metaLine = computed(() => {
  const parts: string[] = [];
  if (orderId.value) parts.push(`订单 ${orderId.value}`);
  if (deviceId.value) parts.push(`柜机 ${deviceId.value}`);
  return parts.join(' · ');
});

async function loadOrderVideo(oid: string) {
  loading.value = true;
  errorView.value = null;
  src.value = '';
  clips.value = [];
  activeClip.value = 0;
  try {
    // CB-030：先取清单决定走哪条链路。将邑柜机的视频不写会话（shopping_session.video_uri
    // 只由旧边缘链路写入），必须回落到将邑视频台账拿预签名地址，否则永远「暂无购物视频」。
    const playlist = await request<OrderVideoPlaylist>(MerchantEndpoints.orderVideos(oid), 'GET');
    if (playlist?.source === 'JIANGYI') {
      const playable = pickPlayableClips(playlist);
      if (!playable.length) {
        errorView.value = {
          title: '暂无购物视频',
          desc: '本单录像该片生成或上传失败，可稍后重试或联系运营核对。',
          showCopy: false
        };
        return;
      }
      clips.value = playable;
      src.value = playable[0].url || '';
      return;
    }
    if (playlist?.source === 'EDGE') {
      src.value = await downloadAuthedFile(merchantOrderVideoUrl(oid), 120_000);
      return;
    }
    errorView.value = {
      title: '暂无购物视频',
      desc: '本单没有可播放的录像。超时免单、模拟柜或文件未上传时常见。',
      showCopy: false
    };
  } catch (e) {
    errorView.value = merchantVideoErrorView(e);
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

onLoad(async (opts) => {
  orderId.value = String(opts?.orderId || '').trim();
  deviceId.value = String(opts?.deviceId || '').trim();
  if (orderId.value) {
    await loadOrderVideo(orderId.value);
    return;
  }
  errorView.value = {
    title: '缺少订单号',
    desc: '无法打开购物视频',
    showCopy: false
  };
});

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
  background: var(--text-primary);
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
  background: var(--text-primary);
  border-radius: var(--radius-control);
}
.state {
  margin-top: 30vh;
  text-align: center;
  color: var(--text-subtle);
}
.state-title {
  display: block;
  font-size: var(--font-size-lg);
  font-weight: 600;
  color: var(--color-border);
}
.state-desc {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-body);
  padding: 0 32rpx;
  line-height: 1.5;
}
.meta {
  color: var(--text-subtle);
  font-size: var(--font-size-xs);
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
  color: var(--success);
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
  padding: 8rpx 24rpx;
  border-radius: var(--radius-control);
  border: 1rpx solid var(--text-subtle);
  color: var(--text-subtle);
  font-size: var(--font-size-xs);
  opacity: 0.6;
}
.clip-chip--active {
  color: var(--white);
  border-color: var(--white);
  opacity: 1;
}
</style>
