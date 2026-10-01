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
        <button
          class="scan-circle"
          hover-class="scan-circle-hover"
          :disabled="opening || enteringFlow"
          @click="$emit('scan')"
        >
          <view class="scan-circle-inner">
            <view class="scan-icon-box">
              <view class="scan-corner tl" />
              <view class="scan-corner tr" />
              <view class="scan-corner bl" />
              <view class="scan-corner br" />
              <view class="scan-line" />
            </view>
          </view>
          <text class="scan-circle-text">{{ opening ? '连接中…' : '扫码购物' }}</text>
        </button>
        <text class="scan-tip">对准柜门二维码，即可开门取货</text>
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
      class="landing-mask"
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
defineProps<{
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
}>();

const emit = defineEmits<{
  scan: [];
  retry: [];
  recharge: [];
  coupons: [];
  login: [];
  dismissAuthPrompt: [];
  confirm: [];
}>();

const landingError = defineModel<string>('landingError', { required: true });
const showManual = defineModel<boolean>('showManual', { required: true });
const deviceInput = defineModel<string>('deviceInput', { required: true });

function noop() {
  /* 阻止冒泡占位（原页面同名空函数） */
}
</script>

<style scoped>
.landing {
  position: relative;
  flex: 1;
  min-height: 0;
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
   * 蒙层改用品牌青绿 --brand-deep #134e4a 的 rgb(19,78,74)（H≈176）。
   *
   * 原值 rgba(6,78,59) 即 #064e3b，H≈164 偏黄，再叠暖调实景照片，
   * 真机实测背景落到 H≈147~154；而扫码盘用品牌色 --brand H≈175 —— 相差 21~28°，
   * 这正是「盘的颜色和背景不符」的根因：盘是青绿、背景是橄榄绿，且二者明度几乎相同
   * （实测盘心 L=0.107 / 背景 L=0.114），同亮度上换色相 ⇒ 眼睛读成「脏」。
   *
   * 中间档不透明度 0.45 → 0.62 → 0.76：压低照片暖色的权重，把背景拉回品牌色相。
   * 第一轮只提到 0.62 时，真机实测背景仅到 H=163.8（目标 ≥167，ΔH 11.1° 仍超 8° 判据），
   * 余量不够 —— 照片在该高度（人物/柜机区）比「盘上区」更暖。0.76 是实测能达标的最小值：
   * 再低则 ΔH 越界，再高则实景照片被压成版画、失去落地页的实景说明性。
   * 三档仍同色同源、只调不透明度，保留照片的实景感而不出现灰绿/青绿断层。
   */
  background: linear-gradient(
    180deg,
    rgba(19, 78, 74, 0.84) 0%,
    rgba(19, 78, 74, 0.76) 45%,
    rgba(19, 78, 74, 0.92) 100%
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
  padding: 0 32rpx 12rpx;
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
  color: var(--white);
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
  justify-content: center;
  /* 扫码盘下移：真机反馈位置偏高；顶部留白把盘压向中下部（删除 resume/nearby 后组的重心也自然下落） */
  padding-top: 16vh;
  width: 100%;
}
.scan-circle {
  margin: 0;
  padding: 0;
  background: transparent;
  border: none;
  line-height: normal;
  display: flex;
  flex-direction: column;
  align-items: center;
  /* hover-class 切换时给一点过渡，否则 scale 跳变、缺少按压反馈 */
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
.scan-circle::after {
  border: none;
}
.scan-circle-hover {
  opacity: 0.9;
  transform: scale(0.98);
}
.scan-circle-inner {
  width: 260rpx;
  height: 260rpx;
  border-radius: 50%;
  /*
   * 品牌青绿渐变盘。两轮真机反馈的收敛点：
   *   ① 纯白圆面 → 全页只有它是白底，在深绿照片上像贴上去的贴纸；
   *   ② 只用 --brand→--brand-ink（H175）→ 与当时偏黄的背景（H150）色相差 29°，
   *      且明度几乎相同（盘心 L=0.107 / 背景 L=0.114）⇒ 读成「颜色和背景不符」。
   * 现在背景已统一到品牌青绿（见 .landing-overlay），色相差 ≤ 5°，于是分层改由**明度**承担：
   * 渐变顶端加一档品牌亮阶 #14a89b（H174.5 / V0.647），与背景（V≈0.36~0.41）拉开 ≥ 0.23 的明度差，
   * 底端仍收到 --brand-ink，保留球体受光感。三档色相全部落在 174~176°，不引入新色系。
   *
   * 取景角/扫描线保持白：白 on --brand-ink #0f3f3c ≈ 11.6:1，白 on --brand #0f766e ≈ 5.5:1，
   * 既是「扫一扫」的通用认知，也是这个盘唯一的强对比来源。
   *
   * 外圈原为 rgba(255,255,255,0.14) 白晕 —— 真机实测渲染成 rgb(116,148,146)（灰青，S 仅 0.216），
   * 在照片背景上是一圈脏灰。改为品牌青绿柔光 rgba(20,168,155,0.16)：光晕参与品牌色相，
   * 让盘「从背景里亮起来」而不是「被一圈白隔开」。
   */
  background:
    radial-gradient(120% 100% at 30% 20%, rgba(255, 255, 255, 0.22) 0%, rgba(255, 255, 255, 0) 62%),
    linear-gradient(158deg, #14a89b 0%, var(--brand, #0f766e) 52%, var(--brand-ink, #0f3f3c) 100%);
  border: 2rpx solid rgba(255, 255, 255, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow:
    inset 0 2rpx 1rpx rgba(255, 255, 255, 0.26),
    0 14rpx 36rpx rgba(4, 47, 36, 0.48),
    0 0 0 14rpx rgba(20, 168, 155, 0.16);
}
.scan-icon-box {
  /* 152rpx / 260rpx ≈ 58%：原 132rpx 只占 51%，框在圆里显得空、四角像四枚孤立钉子 */
  width: 152rpx;
  height: 152rpx;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.scan-circle-text {
  margin-top: 16rpx;
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--white);
}
.scan-tip {
  margin-top: 10rpx;
  font-size: var(--font-size-sm);
  color: rgba(255, 255, 255, var(--on-deep-opacity-88));
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

.scan-corner {
  position: absolute;
  /* 54rpx 角长配 152rpx 框：原 44rpx 在 132rpx 框里过短，四角读起来像孤立钉子而非「取景框」 */
  width: 54rpx;
  height: 54rpx;
  /* 与盘底反色：盘为品牌绿渐变，取景角取白（白 on --brand-ink ≈ 11.6:1） */
  border-color: var(--white, #ffffff);
  border-style: solid;
}
.scan-corner.tl {
  top: 0;
  left: 0;
  border-width: 7rpx 0 0 7rpx;
  border-radius: 16rpx 0 0 0;
}
.scan-corner.tr {
  top: 0;
  right: 0;
  border-width: 7rpx 7rpx 0 0;
  border-radius: 0 16rpx 0 0;
}
.scan-corner.bl {
  bottom: 0;
  left: 0;
  border-width: 0 0 7rpx 7rpx;
  border-radius: 0 0 0 16rpx;
}
.scan-corner.br {
  bottom: 0;
  right: 0;
  border-width: 0 7rpx 7rpx 0;
  border-radius: 0 0 16rpx 0;
}
.scan-line {
  /*
   * 横向扫描线 —— 方向是这里的关键：原实现为竖棒（width 10rpx / height 64rpx），
   * 在方框里读起来像数字「1」或一根钉子，与「扫一扫横线扫过二维码」的通用认知**相反**。
   * 现改为横线（92rpx × 8rpx 圆头），并加 2.4s 上下缓动，让 CTA 从静止图标变成活体扫描。
   *
   * 8rpx 而非 5rpx：真机实测 5rpx 只渲染出 ≈2px 厚，比 7rpx 的取景角（≈2.75px）更细，
   * 视觉上这条「运动物」反而比静止的框还弱，读起来是一段虚弱的短横。
   * 92rpx 而非 100rpx：两端收进角竖边之内，避免摆到上下极点时与左右角挤在一起。
   */
  width: 92rpx;
  height: 8rpx;
  /* 同取景角：白线叠品牌绿盘底 */
  background: var(--white, #ffffff);
  border-radius: 8rpx;
  animation: scan-sweep 2.4s ease-in-out infinite;
}
@keyframes scan-sweep {
  0%,
  100% {
    transform: translateY(-42rpx);
    opacity: 0.55;
  }
  50% {
    transform: translateY(42rpx);
    opacity: 1;
  }
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
