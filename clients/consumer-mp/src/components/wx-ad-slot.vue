<template>
  <!--
    腾讯流量主广告位 —— 微信原生 `<ad>` 广告组件（Banner）。

    🔴 我们是**流量主**（收腾讯分成），不是媒体主（不向第三方卖广告位）：
    广告由微信广告平台投放与结算，我们只负责把官方组件放在页面上。
    故本组件**不上报任何自有计量、不参与任何计费** —— 曝光/点击数据由腾讯侧统计，
    收益经 `publisher/stat` 接口对账（后续切片，见 docs/AD_MONETIZATION_DESIGN.md §4）。

    🔴 平台约束：`<ad>` 只在微信小程序端存在。H5/App 端由外层 `visible` 直接关掉，
    避免「平台不支持」被表现成「广告加载失败」。
  -->
  <view v-if="visible" class="wx-ad" data-testid="wx-ad-slot">
    <!-- #ifdef MP-WEIXIN -->
    <!--
      unit-id 由运营在「系统配置 → 扩展功能」填入（adunit- 开头），不硬编码：
      广告位 ID 属账号资产，换广告位不该发版。
    -->
    <ad :unit-id="unitId" @load="onAdLoad" @error="onAdError" />
    <!-- #endif -->
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';

const props = defineProps<{
  /** 广告单元 ID（`adunit-` 开头）。空串一律不渲染 —— 见下方 visible。 */
  unitId: string;
}>();

/**
 * 当前端是否存在微信原生广告组件。
 *
 * 🔴 用**赋值**而不是二次 `let`/`const` 声明：条件编译在源码里两分支都可见，
 * 重复声明会被 `vue-tsc` 判成 duplicate identifier。写成「声明一次 + 条件赋值」，
 * 两个平台各自编译后都只剩自己那一支。
 */
let mpAdSupported = false;
// #ifdef MP-WEIXIN
mpAdSupported = true;
// #endif

/** 广告组件报错（多为未开通流量主 / unit-id 写错 / 无填充）。 */
const failed = ref(false);

/**
 * 🔴 三重合取，缺一不可：
 *   1. 平台支持微信原生组件；
 *   2. 运营填写了 unit-id（空串喂给组件会触发组件自身报错）；
 *   3. 没发生过错误（错误时让整个包裹节点消失，规避残留空盒子）。
 */
const visible = computed(() => mpAdSupported && !!props.unitId && !failed.value);

function onAdLoad() {
  failed.value = false;
}

/**
 * 错误回调刻意**只改本地状态、不上报、不抛**：广告拉不到是我们的收入损失，
 * 但绝不能影响首页可用性（这是门口开柜页，不是广告页）。
 */
function onAdError() {
  failed.value = true;
}
</script>

<style scoped>
.wx-ad {
  /* 高度交给微信广告组件自身决定（Banner 有固定宽高比），不锁死 —— 
     锁死会在不同填充率下把广告裁掉或留白。 */
  width: 100%;
  overflow: hidden;
}
</style>
