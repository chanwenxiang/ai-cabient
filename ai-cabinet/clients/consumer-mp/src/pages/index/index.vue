<template>
  <view class="page-root">
    <!-- 落地页：仅 Tab 进入时展示，柜码直达不经过此页 -->
    <view v-if="showLanding" class="landing">
      <view class="landing-head">
        <view class="brand-pill"><text class="brand-dot" />AI 智能零售</view>
        <text class="landing-title">扫码开门 取货即走</text>
        <text class="landing-sub">无需排队 · 无需自助结账 · 关门自动扣款</text>
      </view>

      <view v-if="lastDeviceId" class="resume-card" @click="startShoppingFlow(lastDeviceId)">
        <text class="resume-title">继续在本柜购物</text>
        <text class="resume-sub">{{ lastDeviceName || lastDeviceId }}</text>
      </view>

      <view v-if="landingError" class="landing-error">
        <view class="error-icon">!</view>
        <view class="error-copy"><text class="error-title">暂时无法开门</text><text class="error-detail">{{ landingError }}</text></view>
        <text class="error-close" @click="landingError = ''">×</text>
      </view>

      <view class="landing-body">
        <text class="scan-kicker">扫描柜门二维码</text>
        <button class="scan-btn" hover-class="scan-btn-hover" :disabled="opening || enteringFlow" @click="onScan">
          <view class="scan-ring ring-one" /><view class="scan-ring ring-two" />
          <view class="scan-icon-wrap">
            <view class="scan-corner tl" />
            <view class="scan-corner tr" />
            <view class="scan-corner bl" />
            <view class="scan-corner br" />
            <view class="scan-line" />
          </view>
          <text class="scan-btn-text">扫码开门</text>
          <text class="scan-helper">{{ opening ? '正在连接柜机…' : '点击识别柜机二维码' }}</text>
        </button>

        <view class="steps">
          <text class="step">① 扫柜码</text>
          <text class="step-sep">·</text>
          <text class="step">② 开门取货</text>
          <text class="step-sep">·</text>
          <text class="step">③ 关门结算</text>
        </view>
        <view class="trust-grid">
          <view><text class="trust-icon">⚡</text><text>秒级开门</text></view>
          <view><text class="trust-icon">🛡</text><text>余额保护</text></view>
          <view><text class="trust-icon">🧾</text><text>账单可查</text></view>
        </view>
      </view>

      <view class="landing-foot">
        <text class="manual-link" @click="showManual = !showManual">
          {{ showManual ? '收起' : '手动输入柜机编号（调试）' }}
        </text>
        <view v-if="showManual" class="manual-form">
          <input v-model="deviceInput" class="input" placeholder="例如 CAB-001" />
          <button class="btn-primary" hover-class="btn-hover" :loading="opening" :disabled="opening" @click="confirmDevice">
            {{ opening ? '开门中…' : '确认并开门' }}
          </button>
        </view>
      </view>
    </view>

    <!-- 购物页：开门后展示参考价目 -->
    <view v-if="scanned" class="shop">
      <view class="device-bar">
        <view class="device-info">
          <text class="device-name">{{ deviceName || deviceId }}</text>
          <text class="device-status" :class="{ offline: deviceOffline }">{{ deviceStatusText }}</text>
        </view>
        <view class="device-actions">
          <text class="device-report" @click="goReport">报修</text>
          <text class="device-change" @click="resetDevice">换一台</text>
        </view>
      </view>

      <view v-if="reviewSessionId" class="settlement-review-card">
        <view class="review-icon">!</view>
        <view class="review-copy">
          <text class="review-title">本次账单正在人工审核</text>
          <text class="review-detail">识别服务暂时不可用，本次暂未扣款。审核完成后会生成账单，请勿重复提交结算。</text>
          <view class="review-actions">
            <text class="review-link primary" @click="goOrders">稍后查看订单</text>
            <text class="review-link" @click="contactOps">联系运营</text>
            <text class="review-link subtle" @click="dismissReview">知道了</text>
          </view>
        </view>
      </view>

      <view class="catalog-notice">
        <text>以下价格仅供参考，实际扣款以取货识别为准</text>
      </view>

      <scroll-view scroll-y class="product-scroll" :show-scrollbar="false" enhanced>
        <view v-if="productsLoading" class="card loading-card"><text class="meta">加载商品中…</text></view>
        <view v-else-if="!products.length" class="card loading-card"><text class="meta">暂无在售商品</text></view>
        <view v-else class="product-grid">
          <view v-for="p in products" :key="p.skuId" class="product-cell">
            <view class="product-thumb" :class="'cat-' + thumbTone(p)">
              <image
                v-if="showThumb(p)"
                class="product-img"
                :src="productThumb(p)"
                mode="aspectFill"
                @error="onThumbError(p.skuId)"
              />
              <text v-else class="product-emoji">{{ productEmoji(p) }}</text>
            </view>
            <text class="product-name">{{ p.skuName }}</text>
            <text class="product-price">¥{{ (p.priceCents / 100).toFixed(2) }}</text>
          </view>
        </view>
        <view class="list-bottom" />
      </scroll-view>

      <view class="cart-bar">
        <view class="cart-info">
          <text class="cart-hint">{{ cartBarHint }}</text>
        </view>
        <view v-if="sessionActive" class="cart-status-chip" :class="stateTone">
          {{ cartBarAction }}
        </view>
        <button
          v-else-if="canReopen"
          class="cart-cta"
          hover-class="btn-hover"
          :loading="opening"
          :disabled="opening"
          @click="reopenShop"
        >
          {{ opening ? '开门中…' : '再次开门' }}
        </button>
      </view>
    </view>

    <!-- 全屏开门/结算状态（竞品 openDoor 页） -->
    <view v-if="flowOverlayVisible" class="flow-overlay" :class="stateTone">
      <view class="flow-spinner" :class="{ pulse: flowOverlayPulse }" />
      <text class="flow-title">{{ flowOverlayTitle }}</text>
      <text class="flow-hint">{{ flowOverlayHint }}</text>
      <text v-if="deviceId" class="flow-device">{{ deviceName || deviceId }}</text>
      <text v-if="pollError" class="flow-err">{{ pollError }}</text>
      <button
        v-if="state === 'CREATED' || state === 'OPENING'"
        class="flow-cancel"
        :loading="cancelling"
        :disabled="cancelling"
        @click="cancelOpening"
      >
        取消本次开门
      </button>
    </view>

    <OpenPrepDrawer
      v-if="showPrepDrawer"
      :account="prepAccount"
      @done="onPrepDone"
      @cancel="onPrepCancel"
    />
  </view>
</template>

<script setup lang="ts">
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import OpenPrepDrawer from '@/components/open-prep-drawer.vue';
import {
  clearOpenAttempt,
  consumerApi,
  ensureConsumerAuth,
  getConsumerToken,
  requireConsumerAuth
} from '@/utils/consumer-api';
import { parseCabinetScan, parseLaunchOptions } from '@aicabinet/shared-uni/qrcode';
import { sessionStateHint, sessionStateLabel, sessionStateTone } from '@aicabinet/shared-uni/session-labels';
import { formatError } from '@aicabinet/shared-uni/format';
import { productEmoji, productThumb } from '@/utils/product-thumb';
import { delay, requestOrderSubscribe, showBillToast } from '@/utils/notify';
import type { AccountDto, DeviceProduct, SessionDto } from '@aicabinet/shared-types';

const MIN_BALANCE_CENTS = 500;
const deviceInput = ref('');
const deviceId = ref('');
const deviceName = ref('');
const scanned = ref(false);
const enteringFlow = ref(false);
const showManual = ref(false);
const products = ref<DeviceProduct[]>([]);
const productsLoading = ref(false);
const deviceStatusText = ref('');
const deviceOffline = ref(false);
const sessionId = ref('');
const state = ref('');
const stateLabel = ref('');
const stateHint = ref('');
const stateTone = ref('idle');
const opening = ref(false);
const cancelling = ref(false);
const pollError = ref('');
const landingError = ref('');
const reviewSessionId = ref(String(uni.getStorageSync('last_disputed_session_id') || ''));
const openingSeconds = ref(90);
const brokenThumbs = ref<Record<string, boolean>>({});
const lastDeviceId = ref('');
const lastDeviceName = ref('');
const showPrepDrawer = ref(false);
const prepAccount = ref<AccountDto | null>(null);
let pollTimer: ReturnType<typeof setInterval> | null = null;
let devicePollTimer: ReturnType<typeof setInterval> | null = null;
let countdownTimer: ReturnType<typeof setInterval> | null = null;
let prepResolve: ((ok: boolean) => void) | null = null;

const sessionActive = computed(() =>
  !!sessionId.value &&
  ['CREATED', 'OPENING', 'SHOPPING', 'RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(state.value)
);

const showLanding = computed(() => !scanned.value && !enteringFlow.value);

const canReopen = computed(() =>
  scanned.value && !!deviceId.value && !sessionActive.value && !opening.value && !enteringFlow.value
);

const flowOverlayVisible = computed(() => {
  if (showPrepDrawer.value) return false;
  if (enteringFlow.value && !scanned.value) return true;
  if (opening.value && !sessionId.value) return true;
  if (['OPENING', 'CREATED', 'RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(state.value)) return true;
  return false;
});

const flowOverlayPulse = computed(() =>
  ['OPENING', 'CREATED', 'RECOGNIZING', 'SETTLING', 'WAITING_UPLOAD'].includes(state.value) || opening.value
);

const flowOverlayTitle = computed(() => {
  if (opening.value && !sessionId.value) return '正在开门';
  if (stateLabel.value && stateLabel.value !== '-') return stateLabel.value;
  return '准备中';
});

const flowOverlayHint = computed(() => {
  if (state.value === 'OPENING' || state.value === 'CREATED') {
    const elapsed = Math.max(0, 90 - openingSeconds.value);
    return `已等待 ${elapsed} 秒，柜门无响应时可安全取消本次开门`;
  }
  if (stateHint.value) return stateHint.value;
  if (opening.value) return '正在连接柜机并验证开门资格…';
  return '请稍候';
});

const cartBarHint = computed(() => {
  if (state.value === 'SHOPPING') return '门已开 · 请直接取货，无需点选';
  if (!sessionActive.value) return '取货后关门自动结算，可再次开门继续购';
  return '关门后自动识别并扣款';
});

const cartBarAction = computed(() => {
  if (state.value === 'SHOPPING') return '购物中';
  if (state.value === 'OPENING' || state.value === 'CREATED') return '开门中';
  if (state.value === 'RECOGNIZING' || state.value === 'WAITING_UPLOAD') return '识别中';
  if (state.value === 'SETTLING') return '结算中';
  return '进行中';
});

function payReady(acc: AccountDto) {
  return (acc.balanceCents || 0) >= MIN_BALANCE_CENTS;
}

onLoad(async (opts) => {
  const launch = parseLaunchOptions((opts || {}) as Record<string, string>);
  if (launch.deviceId) {
    await startShoppingFlow(launch.deviceId);
  }
});

onShow(async () => {
  lastDeviceId.value = uni.getStorageSync('last_device_id') || '';
  lastDeviceName.value = uni.getStorageSync('last_device_name') || '';
  await ensureConsumerAuth();
  if (getConsumerToken()) {
    if (scanned.value && deviceId.value) refreshDeviceStatus();
    const reopen = uni.getStorageSync('reopen_device_id');
    if (reopen) {
      uni.removeStorageSync('reopen_device_id');
      await startShoppingFlow(reopen);
      return;
    }
    restoreActiveSession();
  }
  startDevicePoll();
});

onHide(() => stopDevicePoll());
onUnload(() => {
  stopPoll();
  stopDevicePoll();
  stopOpeningCountdown();
});

function thumbTone(p: DeviceProduct) {
  const c = p.category || '';
  if (c.includes('饮料')) return 'drink';
  if (c.includes('零食')) return 'snack';
  if (c.includes('乳品')) return 'dairy';
  if (c.includes('方便')) return 'food';
  return 'default';
}

function onThumbError(skuId: string) {
  brokenThumbs.value[skuId] = true;
}

function showThumb(p: DeviceProduct) {
  return productThumb(p) && !brokenThumbs.value[p.skuId];
}

function resetDevice() {
  if (sessionActive.value) {
    uni.showModal({
      title: '购物进行中',
      content: '请先关闭柜门完成结算，或等待当前购物流程结束',
      showCancel: false
    });
    return;
  }
  stopPoll();
  clearSessionUi();
  uni.removeStorageSync('active_session_id');
  scanned.value = false;
  enteringFlow.value = false;
  showManual.value = false;
  deviceId.value = '';
  deviceName.value = '';
  deviceStatusText.value = '';
  products.value = [];
}

async function startShoppingFlow(id: string) {
  const cabinetId = id.trim().toUpperCase();
  if (!cabinetId || opening.value || enteringFlow.value) return;

  enteringFlow.value = true;
    landingError.value = '';

  if (!(await requireConsumerAuth('扫码开门需先完成微信授权'))) {
    enteringFlow.value = false;
    return;
  }
  if (!(await ensureCanOpenDoor())) {
    enteringFlow.value = false;
    return;
  }

  opening.value = true;
  deviceId.value = cabinetId;
  scanned.value = true;
  try {
    await refreshDeviceStatus();
    if (deviceOffline.value) {
      scanned.value = false;
      deviceId.value = '';
      landingError.value = deviceStatusText.value && deviceStatusText.value !== '离线'
        ? deviceStatusText.value
        : '该柜机当前离线或编号无效，请确认柜号后重试，或更换其他柜机。';
      uni.showToast({ title: '柜机离线，请换一台或稍后再试', icon: 'none' });
      return;
    }
    productsLoading.value = true;
    const [productsResult, sessionResult] = await Promise.allSettled([
      consumerApi.deviceProducts(cabinetId),
      consumerApi.createSession(cabinetId)
    ]);
    if (productsResult.status === 'fulfilled') {
      products.value = productsResult.value;
    } else {
      products.value = [];
      uni.showToast({ title: formatError(productsResult.reason), icon: 'none' });
    }
    if (sessionResult.status !== 'fulfilled') {
      scanned.value = false;
      deviceId.value = '';
      landingError.value = formatError(sessionResult.reason);
      uni.showToast({ title: landingError.value, icon: 'none' });
      return;
    }
    const s = sessionResult.value;
    sessionId.value = s.sessionId;
    uni.setStorageSync('active_session_id', s.sessionId);
    applySessionView(s);
    startPoll();
  } finally {
    productsLoading.value = false;
    opening.value = false;
    enteringFlow.value = false;
  }
}

function goOrders() {
  uni.switchTab({ url: '/pages/orders/orders' });
}

function contactOps() {
  uni.showModal({
    title: '联系运营',
    content: '请联系客服 400-888-0018，并提供审核编号：' + reviewSessionId.value,
    showCancel: false,
    confirmText: '我知道了'
  });
}

function dismissReview() {
  reviewSessionId.value = '';
  uni.removeStorageSync('last_disputed_session_id');
}

function ensureCanOpenDoor(): Promise<boolean> {
  return consumerApi.account().then((acc) => {
    if (acc.operator || (acc.verified && payReady(acc))) return true;
    prepAccount.value = acc;
    showPrepDrawer.value = true;
    return new Promise<boolean>((resolve) => {
      prepResolve = resolve;
    });
  });
}

function onPrepDone() {
  showPrepDrawer.value = false;
  prepResolve?.(true);
  prepResolve = null;
}

function onPrepCancel() {
  showPrepDrawer.value = false;
  prepResolve?.(false);
  prepResolve = null;
}

function onScan() {
  uni.scanCode({
    onlyFromCamera: false,
    scanType: ['qrCode', 'barCode'],
    success(res) {
      const parsed = parseCabinetScan(res.result);
      if (parsed.alipayOnly) {
        uni.showToast({ title: '请使用支付宝扫码', icon: 'none' });
        return;
      }
      if (!parsed.deviceId) {
        uni.showToast({ title: '无法识别柜机', icon: 'none' });
        return;
      }
      startShoppingFlow(parsed.deviceId);
    }
  });
}

function confirmDevice() {
  const parsed = parseCabinetScan(deviceInput.value);
  const id = parsed.deviceId || deviceInput.value.trim().toUpperCase();
  if (!id) {
    landingError.value = '请输入柜机编号，例如 CAB-001。';
    uni.showToast({ title: '请输入柜机编号', icon: 'none' });
    return;
  }
  startShoppingFlow(id);
}

async function loadDeviceAndProducts() {
  if (!(await ensureConsumerAuth())) return;
  productsLoading.value = true;
  try {
    await refreshDeviceStatus();
    products.value = await consumerApi.deviceProducts(deviceId.value);
  } catch (e) {
    uni.showToast({ title: formatError(e), icon: 'none' });
  } finally {
    productsLoading.value = false;
  }
}

async function refreshDeviceStatus() {
  try {
    const s = await consumerApi.deviceStatus(deviceId.value);
    deviceName.value = s.deviceName || deviceId.value;
    const online = (s.onlineStatus || '').toUpperCase() === 'ONLINE';
    deviceOffline.value = !online;
    if (!online) deviceStatusText.value = '离线';
    else if (state.value === 'SHOPPING') deviceStatusText.value = '门已开 · 购物中';
    else if (state.value === 'CREATED' || state.value === 'OPENING') deviceStatusText.value = '正在开门';
    else if (s.busy) deviceStatusText.value = '使用中';
    else deviceStatusText.value = '在线 · 可再次开门';
  } catch (e) {
    deviceOffline.value = true;
    deviceStatusText.value = formatError(e);
  }
}

async function reopenShop() {
  if (!deviceId.value) return;
  await startShoppingFlow(deviceId.value);
}

async function cancelOpening() {
  if (!sessionId.value || cancelling.value) return;
  cancelling.value = true;
  try {
    const s = await consumerApi.cancelSession(sessionId.value);
    applySessionView(s);
    stopPoll();
    uni.removeStorageSync('active_session_id');
    clearOpenAttempt();
    clearSessionUi();
    scanned.value = false;
    uni.showToast({ title: '已取消本次开门', icon: 'none' });
  } catch (e) {
    uni.showToast({ title: formatError(e), icon: 'none' });
  } finally {
    cancelling.value = false;
  }
}

function goReport() {
  uni.navigateTo({
    url: `/pages/report/report?deviceId=${encodeURIComponent(deviceId.value || '')}`
  });
}

function clearSessionUi() {
  sessionId.value = '';
  state.value = '';
  stateLabel.value = '';
  stateHint.value = '';
  stateTone.value = 'idle';
}

async function finishSession(sessionState: string, sid: string) {
  uni.removeStorageSync('active_session_id');
  clearOpenAttempt();
  if (sessionState === 'COMPLETED') {
    if (deviceId.value) {
      uni.setStorageSync('last_device_id', deviceId.value);
      uni.setStorageSync('last_device_name', deviceName.value || deviceId.value);
      lastDeviceId.value = deviceId.value;
      lastDeviceName.value = deviceName.value || deviceId.value;
    }
    clearSessionUi();
    let totalCents = 0;
    try {
      const order = await consumerApi.getSessionOrder(sid);
      totalCents = order?.totalAmountCents || 0;
    } catch {
      /* 零元单或查询失败仍跳转结果页 */
    }
    await requestOrderSubscribe();
    showBillToast(totalCents);
    await delay(1200);
    uni.redirectTo({ url: `/pages/result/result?sessionId=${encodeURIComponent(sid)}` });
    return;
  }
  clearSessionUi();
  if (sessionState === 'DISPUTED') {
    try {
      const order = await consumerApi.getSessionOrder(sid);
      if (order?.orderId) {
        uni.redirectTo({
          url: `/pages/result/result?sessionId=${encodeURIComponent(sid)}&orderId=${encodeURIComponent(order.orderId)}`
        });
        return;
      }
    } catch {
      /* 争议单可能尚未生成订单 */
    }
    reviewSessionId.value = sid;
    uni.setStorageSync('last_disputed_session_id', sid);
    uni.showToast({ title: '本次暂未扣款，已转人工审核', icon: 'none' });
  }
}

function applySessionView(s: SessionDto) {
  state.value = s.state;
  stateLabel.value = sessionStateLabel(s.state);
  stateHint.value = sessionStateHint(s.state);
  stateTone.value = sessionStateTone(s.state);
  if (s.state === 'OPENING' || s.state === 'CREATED') startOpeningCountdown(s.createdAt);
  else stopOpeningCountdown();
  if (s.deviceId && deviceId.value) refreshDeviceStatus();
}

function startOpeningCountdown(createdAt?: string) {
  stopOpeningCountdown();
  const started = createdAt ? new Date(createdAt).getTime() : Date.now();
  const tick = () => { openingSeconds.value = Math.max(0, 90 - Math.floor((Date.now() - started) / 1000)); };
  tick();
  countdownTimer = setInterval(tick, 1000);
}

function stopOpeningCountdown() {
  if (countdownTimer) clearInterval(countdownTimer);
  countdownTimer = null;
  openingSeconds.value = 90;
}

async function restoreActiveSession() {
  const saved = uni.getStorageSync('active_session_id');
  if (sessionId.value) return;
  try {
    const s = saved ? await consumerApi.getSession(saved) : await consumerApi.activeSession();
    if (!s) return;
    if (['COMPLETED', 'FAILED', 'CANCELLED', 'DISPUTED'].includes(s.state)) {
      uni.removeStorageSync('active_session_id');
      clearOpenAttempt();
      return;
    }
    sessionId.value = s.sessionId;
    uni.setStorageSync('active_session_id', s.sessionId);
    if (s.deviceId) {
      deviceId.value = s.deviceId;
      scanned.value = true;
      await loadDeviceAndProducts();
    }
    applySessionView(s);
    startPoll();
  } catch {
    // 保留本地会话；弱网或服务短暂不可用时，下次 onShow 继续恢复。
  }
}

function startPoll() {
  stopPoll();
  pollTimer = setInterval(async () => {
    if (!sessionId.value) return;
    try {
      const s = await consumerApi.getSession(sessionId.value);
      applySessionView(s);
      pollError.value = '';
      if (s.state === 'COMPLETED' || s.state === 'DISPUTED') {
        stopPoll();
        const sid = sessionId.value;
        await finishSession(s.state, sid);
      } else if (['FAILED', 'CANCELLED'].includes(s.state)) {
        stopPoll();
        uni.removeStorageSync('active_session_id');
        clearOpenAttempt();
        clearSessionUi();
      }
    } catch (e) {
      pollError.value = formatError(e);
    }
  }, 2000);
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

function startDevicePoll() {
  stopDevicePoll();
  devicePollTimer = setInterval(() => {
    if (!opening.value && scanned.value && deviceId.value) refreshDeviceStatus();
  }, 30000);
}

function stopDevicePoll() {
  if (devicePollTimer) {
    clearInterval(devicePollTimer);
    devicePollTimer = null;
  }
}
</script>

<style scoped>
.page-root {
  height: 100%;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  overflow: hidden;
  background: #f7f7f7;
  position: relative;
}

.landing {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 32rpx;
}
.landing-head {
  flex-shrink: 0;
  padding-top: 32rpx;
  text-align: center;
}
.landing-title {
  font-size: 40rpx;
  font-weight: 600;
  color: #191919;
  display: block;
  line-height: 1.4;
}
.landing-sub {
  font-size: 26rpx;
  color: #888;
  margin-top: 8rpx;
  display: block;
  line-height: 1.5;
}

.resume-card {
  margin-top: 24rpx;
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx 28rpx;
  border-left: 6rpx solid #07c160;
}
.resume-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #07c160;
  display: block;
}
.resume-sub {
  font-size: 24rpx;
  color: #888;
  margin-top: 4rpx;
  display: block;
}

.landing-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.scan-btn {
  margin: 0;
  padding: 0;
  background: transparent;
  border: none;
  line-height: normal;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.scan-btn::after { border: none; }
.scan-btn-hover { opacity: 0.85; }
.scan-icon-wrap {
  width: 200rpx;
  height: 200rpx;
  border-radius: 50%;
  background: linear-gradient(145deg, #07c160, #06ae56);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  box-shadow: 0 8rpx 32rpx rgba(7, 193, 96, 0.35);
}
.scan-corner {
  position: absolute;
  width: 36rpx;
  height: 36rpx;
  border-color: rgba(255, 255, 255, 0.9);
  border-style: solid;
}
.scan-corner.tl { top: 52rpx; left: 52rpx; border-width: 4rpx 0 0 4rpx; border-radius: 4rpx 0 0 0; }
.scan-corner.tr { top: 52rpx; right: 52rpx; border-width: 4rpx 4rpx 0 0; border-radius: 0 4rpx 0 0; }
.scan-corner.bl { bottom: 52rpx; left: 52rpx; border-width: 0 0 4rpx 4rpx; border-radius: 0 0 0 4rpx; }
.scan-corner.br { bottom: 52rpx; right: 52rpx; border-width: 0 4rpx 4rpx 0; border-radius: 0 0 4rpx 0; }
.scan-line {
  width: 8rpx;
  height: 48rpx;
  background: #fff;
  border-radius: 4rpx;
  opacity: 0.95;
}
.scan-btn-text {
  margin-top: 24rpx;
  font-size: 32rpx;
  font-weight: 600;
  color: #07c160;
}

.steps {
  margin-top: 48rpx;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8rpx;
}
.step { font-size: 24rpx; color: #888; }
.step-sep { font-size: 24rpx; color: #ccc; }

.landing-foot {
  flex-shrink: 0;
  padding: 24rpx 0 16rpx;
}
.manual-link {
  display: block;
  text-align: center;
  font-size: 24rpx;
  color: #b2b2b2;
  padding: 16rpx 0;
}
.manual-form { margin-top: 8rpx; }
.input {
  background: #fff;
  border: 1rpx solid #e5e5e5;
  border-radius: 8rpx;
  padding: 20rpx 24rpx;
  margin-bottom: 16rpx;
  font-size: 28rpx;
  color: #191919;
}
.btn-primary {
  margin: 0;
  background: #07c160;
  color: #fff;
  border-radius: 8rpx;
  font-size: 32rpx;
  font-weight: 500;
  line-height: 88rpx;
  height: 88rpx;
}
.btn-primary::after { border: none; }
.btn-hover { opacity: 0.85; }

.shop {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.device-bar {
  flex-shrink: 0;
  margin: 16rpx 24rpx 0;
  padding: 24rpx;
  background: #fff;
  border-radius: 12rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.device-name { font-size: 32rpx; font-weight: 600; color: #191919; display: block; }
.device-status { font-size: 24rpx; color: #07c160; display: block; margin-top: 4rpx; }
.device-status.offline { color: #fa5151; }
.device-actions { display: flex; align-items: center; gap: 20rpx; flex-shrink: 0; }
.device-change { font-size: 26rpx; color: #576b95; }
.device-report { font-size: 24rpx; color: #888; }

.catalog-notice {
  margin: 12rpx 24rpx 0;
  padding: 16rpx 20rpx;
  background: #fffbe6;
  border-radius: 8rpx;
  font-size: 24rpx;
  color: #8c6d1f;
  line-height: 1.4;
}

.product-scroll {
  flex: 1;
  height: 0;
  min-height: 0;
  margin-top: 12rpx;
}
.list-bottom { height: 16rpx; }

.loading-card { text-align: center; padding: 48rpx; margin: 0 16rpx; }

.product-grid {
  display: flex;
  flex-wrap: wrap;
  padding: 0 16rpx;
  gap: 16rpx;
}
.product-cell {
  width: calc(50% - 8rpx);
  box-sizing: border-box;
  background: #fff;
  border-radius: 16rpx;
  padding: 16rpx;
  display: flex;
  flex-direction: column;
}
.product-thumb {
  width: 100%;
  height: 200rpx;
  border-radius: 12rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  margin-bottom: 12rpx;
}
.product-thumb.cat-drink { background: linear-gradient(135deg, #e6f4ff, #bae0ff); }
.product-thumb.cat-snack { background: linear-gradient(135deg, #fff7e6, #ffe58f); }
.product-thumb.cat-dairy { background: linear-gradient(135deg, #f9f0ff, #d3adf7); }
.product-thumb.cat-food { background: linear-gradient(135deg, #fff2e8, #ffbb96); }
.product-thumb.cat-default { background: #f5f5f5; }
.product-img { width: 100%; height: 100%; }
.product-emoji { font-size: 72rpx; line-height: 1; }
.product-name {
  font-size: 26rpx;
  color: #191919;
  line-height: 1.35;
  min-height: 72rpx;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* #ifdef H5 */
/* H5 的原生导航栏和 TabBar 都是 fixed，uni-page-body 的 100% 会把底部操作区
   继续撑到 TabBar 下方。显式扣除两者高度，保证“再次开门”等关键按钮可点击。 */
.page-root {
  height: calc(100vh - 94px);
}
/* #endif */
.product-price {
  font-size: 32rpx;
  color: #07c160;
  font-weight: 700;
  margin-top: 8rpx;
}

.cart-bar {
  flex-shrink: 0;
  background: #fff;
  padding: 16rpx 24rpx;
  padding-bottom: calc(16rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-top: 1rpx solid #e5e5e5;
}
.cart-hint { font-size: 24rpx; color: #888; display: block; }
.cart-cta {
  margin: 0;
  padding: 0 48rpx;
  height: 80rpx;
  line-height: 80rpx;
  background: #07c160;
  color: #fff;
  border-radius: 40rpx;
  font-size: 30rpx;
  font-weight: 500;
}
.cart-cta::after { border: none; }
.settlement-review-card {
  display: flex;
  gap: 18rpx;
  margin: 14rpx 20rpx 0;
  padding: 22rpx;
  border: 1rpx solid #fed7aa;
  border-radius: 20rpx;
  background: linear-gradient(135deg, #fffaf0, #fff7ed);
  box-shadow: 0 9rpx 26rpx rgba(194, 65, 12, .08);
}
.review-icon {
  display: flex;
  flex: 0 0 42rpx;
  height: 42rpx;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #fff;
  background: #f97316;
  font-weight: 800;
}
.review-copy { min-width: 0; flex: 1; }
.review-title, .review-detail { display: block; }
.review-title { color: #9a3412; font-size: 26rpx; font-weight: 750; }
.review-detail { margin-top: 7rpx; color: #9a5b39; font-size: 22rpx; line-height: 1.55; }
.review-actions { display: flex; flex-wrap: wrap; gap: 22rpx; margin-top: 15rpx; }
.review-link { color: #c2410c; font-size: 23rpx; font-weight: 600; }
.review-link.primary { color: #047857; }
.review-link.subtle { color: #9ca3af; }
.cart-status-chip {
  padding: 0 32rpx;
  height: 80rpx;
  line-height: 80rpx;
  border-radius: 40rpx;
  font-size: 28rpx;
  font-weight: 600;
  color: #07c160;
  background: #e8f8ef;
}
.cart-status-chip.wait { color: #fa9d3b; background: #fff7e6; }
.cart-status-chip.active { color: #07c160; background: #e8f8ef; }
.cart-status-chip.error { color: #fa5151; background: #ffecec; }

.flow-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48rpx;
  box-sizing: border-box;
}
.flow-overlay.wait { background: #fffdf5; }
.flow-overlay.active { background: #f0fdf4; }
.flow-overlay.error { background: #fff5f5; }
.flow-spinner {
  width: 120rpx;
  height: 120rpx;
  border-radius: 50%;
  border: 8rpx solid #e8f8ef;
  border-top-color: #07c160;
  margin-bottom: 40rpx;
}
.flow-spinner.pulse {
  animation: spin 1.2s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.flow-title {
  font-size: 40rpx;
  font-weight: 700;
  color: #191919;
  text-align: center;
}
.flow-hint {
  font-size: 28rpx;
  color: #888;
  margin-top: 16rpx;
  text-align: center;
  line-height: 1.5;
  max-width: 560rpx;
}
.flow-device {
  font-size: 26rpx;
  color: #07c160;
  margin-top: 24rpx;
}
.flow-err {
  font-size: 26rpx;
  color: #fa5151;
  margin-top: 16rpx;
  text-align: center;
}
.flow-cancel {
  margin-top: 40rpx;
  padding: 0 36rpx;
  height: 72rpx;
  line-height: 72rpx;
  border-radius: 36rpx;
  background: #f2f3f5;
  color: #576b95;
  font-size: 26rpx;
}
.flow-cancel::after { border: none; }
</style>
<style scoped>
.page-root{background:radial-gradient(circle at 85% 8%,rgba(16,185,129,.11),transparent 25%),linear-gradient(180deg,#effcf6 0,#f7f9fa 55%,#f7f9fa 100%)}
.landing{position:relative;padding:0 28rpx 12rpx}.landing-head{padding:38rpx 14rpx 8rpx;text-align:left}.brand-pill{display:flex;width:max-content;align-items:center;gap:10rpx;padding:8rpx 16rpx;border:1rpx solid rgba(5,150,105,.16);border-radius:999rpx;color:#047857;background:rgba(255,255,255,.72);font-size:21rpx;font-weight:700;letter-spacing:2rpx}.brand-dot{width:12rpx;height:12rpx;border-radius:50%;background:#10b981;box-shadow:0 0 0 8rpx rgba(16,185,129,.12)}.landing-title{margin-top:24rpx;font-size:52rpx;font-weight:800;letter-spacing:-2rpx;color:#10251d}.landing-sub{margin-top:12rpx;color:#607068;font-size:24rpx}
.resume-card{position:relative;overflow:hidden;margin-top:24rpx;padding:25rpx 28rpx;border:1rpx solid rgba(5,150,105,.12);border-left:0;border-radius:22rpx;box-shadow:0 12rpx 34rpx rgba(15,118,110,.08)}.resume-card::before{content:'';position:absolute;left:0;top:0;bottom:0;width:7rpx;background:linear-gradient(#10b981,#0d9488)}.resume-title{color:#047857}.resume-sub{color:#738078}
.landing-error{display:flex;align-items:flex-start;gap:18rpx;margin-top:20rpx;padding:22rpx;border:1rpx solid #fecaca;border-radius:20rpx;background:rgba(255,247,247,.94);box-shadow:0 9rpx 24rpx rgba(220,38,38,.07)}.error-icon{display:flex;flex:0 0 42rpx;height:42rpx;align-items:center;justify-content:center;border-radius:50%;color:#fff;background:#ef4444;font-weight:800}.error-copy{min-width:0;flex:1}.error-title,.error-detail{display:block}.error-title{color:#991b1b;font-size:25rpx;font-weight:700}.error-detail{margin-top:6rpx;color:#b45353;font-size:22rpx;line-height:1.5}.error-close{padding:0 5rpx;color:#b98b8b;font-size:34rpx;line-height:1}
.landing-body{justify-content:flex-start;padding-top:55rpx}.scan-kicker{margin-bottom:28rpx;color:#64756c;font-size:23rpx;font-weight:600;letter-spacing:1rpx}.scan-btn{position:relative}.scan-ring{position:absolute;left:50%;top:100rpx;border:1rpx solid rgba(16,185,129,.16);border-radius:50%;transform:translate(-50%,-50%);pointer-events:none}.ring-one{width:270rpx;height:270rpx}.ring-two{width:330rpx;height:330rpx;opacity:.55}.scan-icon-wrap{width:216rpx;height:216rpx;background:linear-gradient(145deg,#059669 0%,#10b981 60%,#2dd4bf 100%);box-shadow:0 22rpx 55rpx rgba(5,150,105,.32),inset 0 1rpx 0 rgba(255,255,255,.28)}.scan-btn-text{margin-top:34rpx;color:#047857;font-size:34rpx;font-weight:800}.scan-helper{display:block;margin-top:8rpx;color:#95a19b;font-size:22rpx;font-weight:400}.steps{margin-top:38rpx;padding:14rpx 22rpx;border-radius:999rpx;background:rgba(255,255,255,.76);box-shadow:0 6rpx 20rpx rgba(15,23,42,.04)}.step{color:#526159;font-size:22rpx}.step-sep{color:#b8c3bd}
.trust-grid{display:grid;width:100%;grid-template-columns:repeat(3,1fr);gap:14rpx;margin-top:28rpx}.trust-grid>view{display:flex;align-items:center;justify-content:center;gap:7rpx;padding:17rpx 8rpx;border:1rpx solid #edf2ef;border-radius:17rpx;color:#627169;background:rgba(255,255,255,.82);font-size:21rpx}.trust-icon{font-size:24rpx}.landing-foot{padding-bottom:22rpx}.manual-link{color:#9aa69f}
.device-bar{margin:18rpx 20rpx 0;padding:25rpx;border:1rpx solid #edf2ef;border-radius:22rpx;box-shadow:0 9rpx 28rpx rgba(15,23,42,.055)}.device-status{margin-top:7rpx;font-weight:600}.catalog-notice{margin:14rpx 20rpx 0;padding:18rpx 20rpx;border:1rpx solid #fde7a9;border-radius:15rpx;background:#fffbeb}.product-grid{padding:0 20rpx;gap:18rpx}.product-cell{width:calc(50% - 9rpx);padding:15rpx;border:1rpx solid #eef2f0;border-radius:22rpx;box-shadow:0 9rpx 26rpx rgba(15,23,42,.055)}.product-thumb{height:210rpx;border-radius:17rpx}.product-name{font-size:27rpx;font-weight:600;color:#26342d}.product-price{color:#047857;font-size:34rpx}.cart-bar{border-top:0;box-shadow:0 -10rpx 32rpx rgba(15,23,42,.08);padding:18rpx 24rpx}.cart-cta{background:linear-gradient(135deg,#059669,#0d9488);box-shadow:0 8rpx 22rpx rgba(5,150,105,.22)}
.flow-overlay{background:radial-gradient(circle at 50% 35%,#ecfdf5,#fff 55%)}.flow-spinner{width:132rpx;height:132rpx;border-width:10rpx;box-shadow:0 16rpx 44rpx rgba(5,150,105,.13)}.flow-title{font-size:44rpx;color:#173026}.flow-device{padding:10rpx 18rpx;border-radius:999rpx;background:#ecfdf5;font-weight:600}
</style>
