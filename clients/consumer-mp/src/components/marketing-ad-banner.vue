<template>
  <!--
    P3-6：营销轮播位（无柜码上下文）。

    与设备绑定的 `device-ad-banner.vue`（切片 1，设备维度）互补：
    本组件消费 `GET /marketing/banners` 里带 `imageUrl` 的 MINI_PROGRAM 渠道广告，
    不依赖 deviceId —— Tab 首页 / 未扫柜场景也能出「自有投放」。

    🔴 fail-silent：无广告数据（或请求失败）⇒ 整块不渲染，不留占位空块。
    曝光/点击经 `POST /marketing/ads/{id}/events` 上报（须登录；服务端 60s 窗口去重），
    本组件再按 campaignId+assetId+事件类型做实例内去重，避免翻页重复上报。
  -->
  <view v-if="slides.length" class="mab" data-testid="marketing-ad-banner">
    <swiper
      class="mab-swiper"
      circular
      :autoplay="slides.length > 1"
      :interval="4200"
      indicator-dots
      indicator-active-color="var(--brand)"
      @change="onChange"
    >
      <swiper-item v-for="(s, i) in slides" :key="String(s.adCampaignId) + '-' + i">
        <view role="button" class="mab-card" @click="onTap(i)">
          <image class="mab-img" :src="s.imageUrl || ''" mode="aspectFill" />
          <view class="mab-scrim" aria-hidden="true" />
          <view class="mab-copy">
            <text class="mab-title">{{ s.title }}</text>
            <text class="mab-cta app-link-chevron">查看详情</text>
          </view>
        </view>
      </swiper-item>
    </swiper>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { consumerApi, type MarketingBannerDto } from '@/utils/consumer-api';

const slides = ref<MarketingBannerDto[]>([]);
/** 实例内去重：campaignId+assetId+事件 各一次（服务端另有 60s 窗口兜底）。 */
const reported = new Set<string>();

onShow(() => {
  // 组件级 onShow 不触发（§11.95⑥），挂页面钩子；已加载过则不重复拉，避免翻页抖动
  if (!slides.value.length) void load();
});

async function load() {
  try {
    const all = await consumerApi.marketingBanners();
    slides.value = (all || []).filter((b) => !!b.imageUrl);
    slides.value.forEach((b) => report(b, 'IMPRESSION'));
  } catch {
    slides.value = []; // 营销位失败必须静默，不得打扰首页
  }
}

function onChange(e: { detail: { current: number } }) {
  const current = slides.value[e?.detail?.current];
  if (current) report(current, 'IMPRESSION');
}

function onTap(index: number) {
  const banner = slides.value[index];
  if (!banner) return;
  report(banner, 'CLICK');
  if (banner.ctaPath) {
    uni.navigateTo({ url: banner.ctaPath, fail: () => {} });
  }
}

function report(banner: MarketingBannerDto, eventType: 'IMPRESSION' | 'CLICK') {
  if (!banner.adCampaignId || !banner.assetId) return;
  const key = eventType + ':' + banner.adCampaignId + ':' + banner.assetId;
  if (reported.has(key)) return;
  reported.add(key);
  consumerApi.marketingAdEvent(banner.adCampaignId, banner.assetId, eventType).catch(() => {});
}
</script>

<style scoped>
.mab {
  width: 100%;
  margin: 0 0 20rpx;
}
.mab-swiper {
  height: 280rpx;
  width: 100%;
}
.mab-card {
  position: relative;
  width: 100%;
  height: 100%;
  border-radius: var(--radius-card);
  overflow: hidden;
}
.mab-img {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  width: 100%;
  height: 100%;
}
.mab-scrim {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0) 40%, rgba(17, 24, 39, 0.55) 100%);
}
.mab-copy {
  position: absolute;
  left: 28rpx;
  right: 28rpx;
  bottom: 24rpx;
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--white);
}
.mab-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
}
.mab-cta {
  font-size: var(--font-size-sm);
}
</style>
