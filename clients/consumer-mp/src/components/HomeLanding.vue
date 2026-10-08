<template>
  <!--
    P3-3 第一刀：首页落地页（从 pages/index/index.vue 逐字搬移，仅 Tab 进入时展示）。
    landingError / showManual / deviceInput 为双向 model（原页内联赋值语义不变）；
    动作（扫码/重试/充值/券包/登录/关闭授权/确认开门）全部上抛，编排仍归首页。
  -->
  <view class="landing">
    <image class="landing-bg" :src="landingBgUrl" mode="aspectFill" aria-hidden="true" />
    <view class="landing-overlay" />

    <view class="landing-content">
      <view class="landing-top">
        <view class="landing-head" :style="landingHeadStyle">
          <text class="brand">AI开门柜</text>
          <text class="tagline">扫码开门 · 拿了就走</text>
          <view class="pay-badge">
            <text class="pay-badge-icon">✓</text>
            <text class="pay-badge-text">关门自动结算</text>
          </view>
        </view>

        <view v-if="landingError" class="landing-error" :class="'kind-' + landingErrorKind">
          <view class="error-icon">!</view>
          <view class="error-copy">
            <text class="error-title">{{ landingErrorTitle }}</text>
            <text class="error-detail">{{ landingError }}</text>
            <view class="error-actions">
              <text
                v-if="landingErrorKind === 'balance'"
                role="button"
                class="error-action primary"
                @click="$emit('recharge')"
                >去充值</text
              >
              <text
                v-else-if="lastFailedDeviceId"
                role="button"
                class="error-action primary"
                @click="$emit('retry')"
                >重试开门</text
              >
              <text
                v-if="landingErrorKind === 'device_not_found'"
                role="button"
                class="error-action"
                @click="$emit('scan')"
                >重新扫码</text
              >
              <text
                role="button"
                class="error-action"
                @click="
                  landingError = '';
                  $emit('scan');
                "
                >换一台</text
              >
            </view>
          </view>
          <text
            class="error-close"
            role="button"
            aria-label="关闭错误提示"
            @click="landingError = ''"
            >×</text
          >
        </view>
      </view>

      <view class="landing-action">
        <!--
          扫码 CTA v6（2026-10-08 定稿）：**玻璃透圆 + 取景框图标 + 会动的扫描线 +
          圆内白字**。用户先发参考图（橙圆+图标）要「动态的」，再从四个候选
          （白圆绿标 / 品牌绿圆白标 / 玻璃透圆 / 白圆双环）中**显式选定玻璃透圆**。

          🔴 依据留档：
          · 图标+圆形形态：用户拍板（参考图），推翻 CB-015「同行 0/6 用取景框」的
            「无图标」方向 —— 台账已记「有意不同」（用户决策优先于同行缺席结论）。
          · 文案保留「扫码开门」不用参考图的「扫码购物」：动作导向有同行 3 家支撑，
            且与首页 tagline「扫码开门 · 拿了就走」一致（用户未对文案提异议）。
          · 配色：不照抄参考图的实色圆 —— 品牌绿实底会与 .landing-overlay
            （rgba(19,78,74,·)）同色糊底（v3 教训）；玻璃白既轻盈又与白卡家族同源。

          动效：图标内扫描线上下往复（1.6s，主动效）+ 阴影呼吸（2.8s，环境动效）。
          仍用原生 button：自带按压态与无障碍语义。图标纯装饰 aria-hidden，
          读屏只读文字。
        -->
        <button
          class="scan-cta"
          hover-class="scan-cta-hover"
          :disabled="opening || enteringFlow"
          @click="$emit('scan')"
        >
          <view class="scan-icon" aria-hidden="true">
            <view class="scan-corner scan-corner--tl" />
            <view class="scan-corner scan-corner--tr" />
            <view class="scan-corner scan-corner--bl" />
            <view class="scan-corner scan-corner--br" />
            <view class="scan-line" />
          </view>
          <text class="scan-cta-title">{{ opening ? '正在开门…' : '扫码开门' }}</text>
        </button>
        <!-- 扩展功能 consumer.coupon_entry.enabled：券包入口前置到首页；默认关闭 ⇒ 不渲染 -->
        <view
          v-if="couponEntryVisible"
          class="coupon-link"
          role="button"
          data-testid="landing-coupon-entry"
          aria-label="我的券包"
          @click="$emit('coupons')"
          >我的券包</view
        >
      </view>

      <view v-if="showManualEntry && !showManual" class="landing-foot">
        <text
          class="manual-link"
          role="button"
          data-testid="manual-device-toggle"
          @click="showManual = true"
        >
          手动输入柜机编号
        </text>
      </view>
    </view>

    <view
      v-if="authPromptVisible"
      role="button"
      aria-label="关闭"
      class="landing-mask"
      @click="$emit('dismissAuthPrompt')"
    >
      <view role="button" class="landing-sheet" @click.stop="noop">
        <text class="landing-sheet-title">需要授权</text>
        <text class="landing-sheet-body">扫码开门需先完成微信授权</text>
        <view class="landing-sheet-actions">
          <text role="button" class="landing-sheet-btn" @click="$emit('dismissAuthPrompt')"
            >取消</text
          >
          <text role="button" class="landing-sheet-btn primary" @click="$emit('login')"
            >去登录</text
          >
        </view>
      </view>
    </view>

    <view
      v-if="showManual"
      role="button"
      aria-label="关闭"
      class="landing-mask landing-mask--centered"
      @click="showManual = false"
    >
      <view role="button" class="landing-sheet" @click.stop="noop">
        <text class="landing-sheet-title">手动输入柜机编号</text>
        <text class="landing-sheet-label">柜机编号</text>
        <input
          v-model="deviceInput"
          class="sheet-input"
          data-testid="device-code-input"
          aria-label="柜机编号"
          placeholder="请输入柜机编号"
          type="text"
          placeholder-class="sheet-ph"
        />
        <app-button
          data-testid="open-door-confirm"
          :loading="opening"
          :disabled="opening"
          :label="opening ? '开门中…' : '确认并开门'"
          @click="$emit('confirm')"
        />
        <view class="landing-sheet-cancel-wrap">
          <text role="button" class="landing-sheet-cancel" @click="showManual = false">取消</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  landingBgUrl: string;
  landingHeadStyle: Record<string, string>;
  landingErrorKind: string;
  landingErrorTitle: string;
  lastFailedDeviceId: string;
  couponEntryVisible: boolean;
  showManualEntry: boolean;
  authPromptVisible: boolean;
  opening: boolean;
  enteringFlow: boolean;
  landingError: string;
  showManual: boolean;
  deviceInput: string;
}>();

const emit = defineEmits<{
  scan: [];
  retry: [];
  recharge: [];
  coupons: [];
  login: [];
  dismissAuthPrompt: [];
  confirm: [];
  'update:landingError': [value: string];
  'update:showManual': [value: boolean];
  'update:deviceInput': [value: string];
}>();

/** uni-mp-vue 3.0 无 mergeModels，不能用 defineModel */
const landingError = computed({
  get: () => props.landingError,
  set: (v: string) => emit('update:landingError', v)
});
const showManual = computed({
  get: () => props.showManual,
  set: (v: boolean) => emit('update:showManual', v)
});
const deviceInput = computed({
  get: () => props.deviceInput,
  set: (v: string) => emit('update:deviceInput', v)
});

function noop() {
  /* 阻止冒泡占位（原页面同名空函数） */
}
</script>

<script lang="ts">
export default {
  name: 'HomeLanding',
  options: { virtualHost: true, styleIsolation: 'apply-shared' }
};
</script>

<style scoped>
.landing {
  position: relative;
  flex: 1;
  min-height: 0;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--brand-deep, #134e4a);
}
.landing-bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
}
.landing-overlay {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 1;
  /*
   * 蒙层用品牌青绿 --brand-deep #134e4a 的 rgb(19,78,74)（H≈176）。
   *
   * 色相为什么要压：原值 rgba(6,78,59) 即 #064e3b，H≈164 偏黄，再叠暖调实景照片，
   * 真机实测背景落到 H≈147~154；而扫码盘用品牌色 --brand H≈175 —— 相差 21~28°，
   * 这正是「盘的颜色和背景不符」的根因：盘是青绿、背景是橄榄绿，且二者明度几乎相同
   * （实测盘心 L=0.107 / 背景 L=0.114），同亮度上换色相 ⇒ 眼睛读成「脏」。
   *
   * 🔴 亮度：2026-10-08 用户真机反馈「首页背景太暗，希望和登录页一样亮」。
   * 原三档 0.84/0.76/0.92 把实景照片压成了版画（注释里担心的后果真的发生了）。
   * 现降到 0.58/0.44/0.66 —— 上半部让柜机与货架透出来，下半部仍压深以承载
   * 扫码盘与文案（文案区若太亮会失去对比度）。**只降不透明度、不改色相**，
   * 保住上面论证过的 H≈176 品牌色相，避免为提亮把色相又拉回橄榄绿。
   */
  background: linear-gradient(
    180deg,
    rgba(19, 78, 74, 0.58) 0%,
    rgba(19, 78, 74, 0.44) 45%,
    rgba(19, 78, 74, 0.66) 100%
  );
}
.landing-content {
  position: relative;
  z-index: 2;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  /* 底栏未藏住时扫码文案会从 tabBar 上钻出来；预留原生 tabBar≈48px + 安全区 */
  padding: 0 32rpx calc(112rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.landing-top {
  position: relative;
  flex-shrink: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.landing-head {
  flex-shrink: 0;
  /* padding-top 由 landingHeadStyle（胶囊下方）注入；H5 无胶囊时用状态栏回退 */
  padding-top: 0;
  text-align: center;
  width: 100%;
}
.brand {
  /* 落地页主标题：真机反馈偏小，从 --font-size-h2(40rpx) 提到 52rpx */
  font-size: 52rpx;
  font-weight: 700;
  color: var(--white, #ffffff);
  display: block;
  letter-spacing: 1rpx;
  line-height: 1.25;
}
.tagline {
  /* 真机反馈偏小：26rpx -> 30rpx */
  font-size: 30rpx;
  color: rgba(255, 255, 255, var(--on-deep-opacity-92));
  margin-top: 14rpx;
  display: block;
  line-height: 1.4;
}
.pay-badge {
  display: inline-flex;
  align-items: center;
  gap: 6rpx;
  margin-top: 20rpx;
  padding: 8rpx 18rpx;
  border-radius: var(--radius-pill);
  background: rgba(15, 63, 60, 0.55);
  border: 1rpx solid rgba(255, 255, 255, 0.32);
}
.pay-badge-icon {
  color: var(--white);
  font-size: var(--font-size-sm);
  font-weight: 700;
}
.pay-badge-text {
  color: var(--white);
  font-size: var(--font-size-xs);
}

.landing-action {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  /*
   * 2026-10-08 用户指「放到下部分」：扫码按钮从垂直居中改为**沉底**——
   * 参考图形态就是「圆钮坐在画面下部」，居中会悬在照片正中、与上方标题脱节。
   * flex-end + 底部留白，让它贴近「故障报修/换一台」辅助条上方，
   * 与底部导航之间仍留呼吸距离。
   */
  justify-content: flex-end;
  padding-bottom: 56rpx;
  width: 100%;
}
/*
 * 扫码 CTA 演进史（决策依据都留档，防止后续轮次重复踩）：
 *
 * 🔴 v3（品牌绿实心 pill）被判「更丑了」，根因是**我算漏了背景色**：
 *   `.landing-overlay` 的渐变三档全是 `rgba(19,78,74,·)`，而 `--brand-deep` 就是
 *   `#134e4a = rgb(19,78,74)` —— **按钮底色与整页蒙层色完全相同**。
 *   等于把同色块叠在同色背景上：白底时它是全页最亮块、天然焦点（v2 的判断正确），
 *   换成品牌绿后焦点消失、按钮与背景糊在一起。
 *   ⇒ v3 注释里写的「绿底与 .landing-head 品牌身份同源」并没错，
 *     错在**只看了局部没看底色** —— 这条已写进 MEMORY 铁律（改主色必先核底色）。
 *
 * v4：底色回白 + 单行大字 + 无图标（竞品依据见模板注释）。
 * v5：用户发参考图（橙圆+取景框图标）要「动态的」⇒ 加图标与扫描线。
 * v6：**当前态** —— 用户从四个候选（白圆绿标/品牌绿圆白标/玻璃透圆/白圆双环）
 *     中选定 **C 玻璃透圆**，形态与动效详见下方样式注释。
 *
 * ⚠️ 保留原生 button 元素（自带按压态与无障碍语义），::after 必须显式去边框。
 */
/*
 * 扫码 CTA v6（2026-10-08 用户从四个候选中选定 **C 玻璃透圆**）。
 * 四个候选（白圆绿标 / 品牌绿圆白标 / 玻璃透圆 / 白圆双环）以内联可视化给用户过目，
 * 用户选 C：半透明白 + 白描边，最融入照片、最轻盈。
 *
 * 🔴 实现要点（与候选图的两处刻意差异）：
 *   1. 不用 backdrop-filter 毛玻璃 —— mp-weixin 上 Android 端大面积支持不稳，
 *      半透明白底本身已经读作「玻璃」，模糊只是锦上添花，不能依赖；
 *   2. 描边从 1.5px 提到 3rpx 白（α .9）—— 玻璃态对比天然弱（候选图里已注明
 *      「弱光环境辨识度下降」），白描边是玻璃圆在照片上的「定形线」，不能省。
 * 动效只留两处：图标内扫描线上下往复（scan-icon-line，主动效）+ 阴影呼吸
 * （环境动效）。v4/v5 的斜扫高光已删：玻璃面上再扫高光会像「破膜」，
 * 且与扫描线互相抢注意力。
 * ⚠️ 保留原生 button（自带按压态与无障碍语义），::after 必须显式去边框。
 */
.scan-cta {
  margin: 0;
  padding: 0;
  width: 320rpx;
  height: 320rpx;
  box-sizing: border-box;
  background: rgba(255, 255, 255, 0.16);
  border: 3rpx solid rgba(255, 255, 255, 0.9);
  border-radius: 50%;
  line-height: normal;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
  box-shadow: 0 16rpx 40rpx rgba(4, 47, 36, 0.35);
  /* hover-class 切换时给一点过渡，否则 scale 跳变、缺少按压反馈 */
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
  /*
   * 🔴 白圈雷达脉冲（2026-10-08 用户指「没特效」后加强）：
   * v6 只有阴影呼吸（幅度小，静态截图/扫一眼几乎看不出）。
   * 现改为从玻璃圆边缘向外扩散一圈白环（0→26rpx 渐隐），2.2s 一次，
   * 静态截图上也能看到光圈痕迹，是四个方向里唯一「明显但不刺眼」的循环动效。
   * 落扩散阴影（0 16rpx 40rpx）保留，脉冲只加在第四层 box-shadow 上。
   */
  animation: scan-cta-halo 2.2s ease-out infinite;
}
@keyframes scan-cta-halo {
  0% {
    box-shadow:
      0 16rpx 40rpx rgba(4, 47, 36, 0.35),
      0 0 0 0 rgba(255, 255, 255, 0.32);
  }
  70% {
    box-shadow:
      0 16rpx 40rpx rgba(4, 47, 36, 0.35),
      0 0 0 26rpx rgba(255, 255, 255, 0);
  }
  100% {
    box-shadow:
      0 16rpx 40rpx rgba(4, 47, 36, 0.35),
      0 0 0 0 rgba(255, 255, 255, 0);
  }
}
/*
 * 取景框图标（用户拍板要图标形态；CB-015 的「同行 0/6 用取景框」已被用户显式推翻，
 * 台账记「有意不同」）。四个角括号 = 四个 view 各画两条边；中间的扫描线上下往复
 * —— 它是「正在扫码」的动作隐喻，也是本按钮唯一的主动效。
 */
.scan-icon {
  position: relative;
  width: 120rpx;
  height: 100rpx;
}
.scan-corner {
  position: absolute;
  width: 34rpx;
  height: 34rpx;
  border: 6rpx solid rgba(255, 255, 255, 0.95);
}
.scan-corner--tl {
  top: 0;
  left: 0;
  border-right: none;
  border-bottom: none;
  border-radius: 10rpx 0 0 0;
}
.scan-corner--tr {
  top: 0;
  right: 0;
  border-left: none;
  border-bottom: none;
  border-radius: 0 10rpx 0 0;
}
.scan-corner--bl {
  bottom: 0;
  left: 0;
  border-right: none;
  border-top: none;
  border-radius: 0 0 0 10rpx;
}
.scan-corner--br {
  bottom: 0;
  right: 0;
  border-left: none;
  border-top: none;
  border-radius: 0 0 10rpx 0;
}
.scan-icon-line {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 72rpx;
  height: 8rpx;
  border-radius: 4rpx;
  background: rgba(255, 255, 255, 0.95);
  transform: translate(-50%, -50%);
  /* 2026-10-08 加强：行程 ±26→±34rpx、周期 1.6→1.3s，动效更可感知 */
  animation: scan-icon-sweep 1.3s ease-in-out infinite;
}
@keyframes scan-icon-sweep {
  0%,
  100% {
    transform: translate(-50%, -50%) translateY(-34rpx);
  }
  50% {
    transform: translate(-50%, -50%) translateY(34rpx);
  }
}
/* 偏好减弱动效的系统设置下停掉循环动画（按钮本身功能不受影响） */
@media (prefers-reduced-motion: reduce) {
  .scan-cta,
  .scan-icon-line {
    animation: none;
  }
}
/* 原生 button 的 ::after 是默认边框，必须显式去掉，否则圆角内出现一圈系统描边 */
.scan-cta::after {
  border: none;
}
.scan-cta-hover {
  opacity: 0.88;
  transform: scale(0.96);
}
/*
 * 玻璃底上文字必须白：深色照片透过 16% 白底后底色仍是深绿，
 * 白字对比最高；品牌深绿在玻璃上会消失（v3 同色教训的变体）。
 */
.scan-cta-title {
  font-size: 34rpx;
  font-weight: 600;
  color: var(--white, #ffffff);
  line-height: 1.2;
  letter-spacing: 2rpx;
}
.landing-foot {
  flex-shrink: 0;
  padding: 4rpx 0 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.manual-link {
  display: inline-block;
  margin: 0 auto;
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--white);
  padding: 8rpx 20rpx;
  border-radius: var(--radius-pill);
  background: rgba(19, 78, 74, 0.55);
  border: 1rpx solid rgba(255, 255, 255, 0.32);
}
/* 扩展功能：首页券包入口（consumer.coupon_entry.enabled；默认关闭 ⇒ 不渲染） */
.coupon-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin: 12rpx auto 0;
  padding: 8rpx 24rpx;
  border-radius: var(--radius-pill);
  background: rgba(19, 78, 74, 0.55);
  border: 1rpx solid rgba(255, 255, 255, 0.32);
  font-size: var(--font-size-sm);
  color: var(--white);
}
.btn-hover {
  opacity: 0.85;
}

/* visual overrides (merged) */
.landing-error {
  position: absolute;
  left: 0;
  right: 0;
  top: 100%;
  z-index: 6;
  display: flex;
  align-items: flex-start;
  gap: 14rpx;
  margin-top: 14rpx;
  width: 100%;
  max-width: 620rpx;
  margin-left: auto;
  margin-right: auto;
  padding: 16rpx 20rpx;
  border-radius: var(--radius-panel);
  background: var(--brand-deep, #134e4a);
  border: 1rpx solid rgba(255, 255, 255, 0.16);
  box-sizing: border-box;
}
.landing-error.kind-balance,
.landing-error.kind-device_not_found {
  background: var(--brand-deep, #134e4a);
  border-color: rgba(255, 255, 255, 0.16);
}
.landing-error.kind-balance .error-icon {
  background: var(--warning, #f59e0b);
}
.landing-error.kind-balance .error-title,
.landing-error.kind-balance .error-detail,
.landing-error.kind-device_not_found .error-title,
.landing-error.kind-device_not_found .error-detail {
  color: rgba(255, 255, 255, var(--on-deep-opacity-92));
}
.landing-error.kind-device_not_found .error-icon {
  background: rgba(255, 255, 255, 0.28);
}
.error-icon {
  display: flex;
  flex: 0 0 36rpx;
  height: 36rpx;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--white);
  background: var(--color-danger);
  font-weight: 800;
  font-size: var(--font-size-sm);
}
.error-copy {
  min-width: 0;
  flex: 1;
}
.error-title,
.error-detail {
  display: block;
}
.error-title {
  color: var(--white);
  font-size: var(--font-size-caption);
  font-weight: 700;
}
.error-detail {
  margin-top: 4rpx;
  color: rgba(255, 255, 255, var(--on-deep-opacity-78));
  font-size: var(--font-size-sm);
  line-height: 1.45;
}
.error-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 10rpx;
}
.error-action {
  padding: 6rpx 14rpx;
  border-radius: var(--radius-pill);
  border: 1rpx solid rgba(255, 255, 255, 0.32);
  color: var(--white);
  font-size: var(--font-size-sm);
  background: rgba(19, 78, 74, 0.55);
}
.error-action.primary {
  border-color: rgba(255, 255, 255, 0.4);
  color: var(--white);
  /* 原为 rgba(4,120,87) #047857（H≈164，偏黄），统一到品牌青绿 --brand */
  background: rgba(15, 118, 110, 0.65);
}
.error-close {
  padding: 0 4rpx;
  color: rgba(255, 255, 255, var(--on-deep-opacity-78));
  font-size: var(--font-size-lg);
  line-height: 1;
}
.landing-mask {
  position: absolute;
  inset: 0;
  z-index: 8;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  background: rgba(4, 31, 26, 0.42);
}
/*
 * 🔴 2026-10-08 用户真机反馈「授权弹窗位置太低」：默认贴底只留 32rpx，
 * 在长屏上像「掉到地上」。两个表单类弹窗（授权提示、手动输入柜机编号）
 * 显式加 `landing-mask--centered` 走垂直居中。
 * 默认仍保留 flex-end —— 若日后新增真正的「底部抽屉」类弹窗，语义不必反转。
 */
.landing-mask--centered {
  align-items: center;
}
.landing-sheet {
  width: 100%;
  max-width: 520rpx;
  margin-left: auto;
  margin-right: auto;
  padding: 28rpx 24rpx 24rpx;
  border-radius: var(--radius-card);
  background: var(--brand-deep, #134e4a);
  border: 1rpx solid rgba(255, 255, 255, 0.16);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: stretch;
}
.landing-sheet-title {
  display: block;
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--white);
  text-align: center;
}
.landing-sheet-body {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-caption);
  color: rgba(255, 255, 255, var(--on-deep-opacity-78));
  line-height: 1.5;
  text-align: center;
}
.landing-sheet-label {
  display: block;
  margin-top: 22rpx;
  margin-bottom: 8rpx;
  font-size: var(--font-size-caption);
  color: rgba(255, 255, 255, var(--on-deep-opacity-78));
  text-align: center;
}
.sheet-input {
  display: block;
  width: 100%;
  height: 88rpx;
  margin-bottom: 20rpx;
  padding: 0 24rpx;
  box-sizing: border-box;
  border-radius: var(--radius-control);
  background: var(--brand-ink, #043f32);
  border: 1rpx solid rgba(255, 255, 255, 0.18);
  font-size: var(--font-size-md);
  color: var(--white);
}
.sheet-ph {
  color: rgba(255, 255, 255, var(--on-deep-opacity-50));
}
.landing-sheet-actions {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 16rpx;
  width: 100%;
  margin-top: 24rpx;
  box-sizing: border-box;
}
.landing-sheet .app-btn {
  width: 100% !important;
  max-width: none !important;
  min-width: 0 !important;
  align-self: stretch !important;
  margin-left: 0 !important;
  margin-right: 0 !important;
}
.landing-sheet-btn {
  flex: 1 1 0;
  min-width: 0;
  padding: 18rpx 16rpx;
  border-radius: var(--radius-pill);
  border: 1rpx solid rgba(255, 255, 255, 0.28);
  color: var(--white);
  font-size: var(--font-size-body);
  line-height: 1.2;
  text-align: center;
  box-sizing: border-box;
  background: var(--brand-ink, #043f32);
}
.landing-sheet-btn.primary {
  border-color: transparent;
  background: var(--brand, #0f766e);
  font-weight: 600;
}
.landing-sheet-cancel-wrap {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  margin-top: 16rpx;
}
.landing-sheet-cancel {
  display: block;
  width: 100%;
  text-align: center;
  color: rgba(255, 255, 255, var(--on-deep-opacity-78));
  font-size: var(--font-size-body);
  padding: 8rpx 0;
  box-sizing: border-box;
}
</style>
