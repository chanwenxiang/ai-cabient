<template>
  <view class="ps-tag" :class="[`ps-tag--${variant}`, { 'ps-tag--scored': showScore }]">
    <!-- 路径必须写字面量：script setup 里的 const 在小程序端编译后取不到（o.logoSrc 为空） -->
    <image class="ps-tag__logo" src="/static/payscore/payscore-logo.png" mode="widthFix" />
    <text class="ps-tag__brand">微信支付分</text>
    <view class="ps-tag__sep" />
    <!-- 分数版用分数文案占业务词位：官方合作品牌横幅是单行「微信支付分 | 550分及以上优享」 -->
    <text v-if="showScore" class="ps-tag__score">{{ scoreText }}</text>
    <text v-else class="ps-tag__biz">{{ bizText }}</text>
  </view>
</template>

<script setup lang="ts">
/**
 * 微信支付分品牌标签 / 标语（CB-067 /《微信支付分合作品牌线上应用规范》2023-03-22 版）。
 *
 * 🔴 规范硬约束（评审逐条看，违反即退回）：
 *   · 标语**文字不可增删/更改**——「微信支付分」四字与业务词不可改、不可缩写；
 *   · **不得在标语周围添加任何文本**（分数版把分数写在业务词位、保持单行，
 *     不额外加说明文字——同行（大黄鹅）首页底部即此形态，2026-11-02 用户按截图校正）；
 *   · **不得改变比例关系 / 结构**——logo 边长 = 文字高度 X，logo↔文字间距 = 1/4X，
 *     文字↔竖线间距 = 1/4X，竖线宽 = 1/4X（规范 P4 标注）；
 *   · 字体**不可更改**（苹方 regular）、字号**不小于 22px(@2x)**；
 *   · 文字颜色：浅底 `#666666` / 深底 `#000000`（规范 P4）；商品展示页标签内为 `#07C160`（P13）；
 *   · 分割线：与文字同色、透明度 60%、长度与文字高度平齐；
 *   · 组合整体高度**不小于 30px**；
 *   · logo **图形不可重绘/改色/拉伸变形** ⇒ 本组件用官方 PDF 切图（`/static/payscore/payscore-logo.png`），
 *     **禁止**用 CSS 画一个近似图形。
 *
 * 业务词（先X后付）按官方示例替换位：先享后付类 ⇒ 先吃后付 / 先住后付 /
 * **先取后付**（本项目开门柜场景）。⛔ 不得使用「免押XX」——那是免押类场景专用。
 */
export type PayScoreTagVariant = 'label' | 'slogan';

const props = withDefaults(
  defineProps<{
    /** label = 商品展示页/订单完成页的胶囊标签；slogan = 品牌标语（无边框） */
    variant?: PayScoreTagVariant;
    /** 业务词，如「先取后付」 */
    biz?: string;
    /** 是否展示分数要求版（规范 P5：有明确分数要求时必须用带分数版） */
    showScore?: boolean;
    /** 分数文案，如「550分及以上优享」 */
    score?: string;
  }>(),
  {
    variant: 'label',
    biz: '先取后付',
    showScore: false,
    score: '550分及以上优享'
  }
);

const bizText = props.biz;
const scoreText = props.score;
</script>

<style scoped>
/*
 * 几何按规范 P4 标注实现（X = 文字高度）：
 *   logo 边长 = X；logo↔文字 = 1/4X；文字↔竖线 = 1/4X；竖线宽 = 1/4X。
 *   字号 22px（=44px@2x，满足「不小于 22px@2x」）。
 */
.ps-tag {
  display: inline-flex;
  align-items: center;
  box-sizing: border-box;
}

.ps-tag--label {
  height: 30px;
  padding: 0 8px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 15px;
  background: #ffffff;
}

/* 标语版：无边框，整体高度 ≥30px */
.ps-tag--slogan {
  height: 30px;
  padding: 0;
  border: 0;
  background: transparent;
}

.ps-tag__logo {
  width: 22px;
  height: 22px;
  flex-shrink: 0;
}

/* logo ↔ 文字 = 1/4X = 22/4 ≈ 5.5px */
.ps-tag__brand {
  margin-left: 5.5px;
  font-size: 22px;
  line-height: 30px;
  font-weight: 400;
  color: #666666;
}

/* 文字 ↔ 竖线 = 1/4X；竖线宽 = 1/4X */
.ps-tag__sep {
  width: 5.5px;
  height: 22px;
  margin: 0 5.5px;
  background: rgba(102, 102, 102, 0.6);
}

.ps-tag__biz {
  font-size: 22px;
  line-height: 30px;
  font-weight: 500;
  color: #666666;
}

/* 商品展示页标签内：文字色 #07C160（规范 P13） */
.ps-tag--label .ps-tag__brand,
.ps-tag--label .ps-tag__biz {
  color: #07c160;
}

.ps-tag--label .ps-tag__sep {
  background: rgba(7, 193, 96, 0.6);
}

/*
 * 分数版：官方合作品牌横幅形态——单行「微信支付分 | 550分及以上优享」，
 * 分数占业务词位，字号随场景收小（2026-11-02 用户按同行截图校正）。
 */
.ps-tag--scored {
  height: auto;
  min-height: 20px;
  flex-wrap: nowrap;
}
.ps-tag--scored .ps-tag__logo {
  width: 14px;
  height: 14px;
}
.ps-tag--scored .ps-tag__brand,
.ps-tag--scored .ps-tag__score {
  font-size: 14px;
  line-height: 20px;
  font-weight: 400;
  color: #666666;
}
.ps-tag--scored .ps-tag__brand {
  margin-left: 3.5px;
}
.ps-tag--scored .ps-tag__sep {
  width: 1px;
  height: 14px;
  margin: 0 7px;
  background: rgba(102, 102, 102, 0.5);
}
</style>
