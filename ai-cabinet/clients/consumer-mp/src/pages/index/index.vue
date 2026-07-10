<template>
  <view class="page-root">
    <!-- 落地页：仅 Tab 进入时展示，柜码直达不经过此页 -->
    <view v-if="showLanding" class="landing">
      <view class="landing-head">
        <text class="landing-title">扫码开门 取货即走</text>
        <text class="landing-sub">关门前可随意取用，关门后自动结算</text>
      </view>

      <view v-if="lastDeviceId" class="resume-card" @click="startShoppingFlow(lastDeviceId)">
        <text class="resume-title">继续在本柜购物</text>
        <text class="resume-sub">{{ lastDeviceName || lastDeviceId }}</text>
      </view>

      <view class="landing-body">
        <button class="scan-btn" hover-class="scan-btn-hover" @click="onScan">
          <view class="scan-icon-wrap">
            <view class="scan-corner tl" />
            <view class="scan-corner tr" />
            <view class="scan-corner bl" />
            <view class="scan-corner br" />
            <view class="scan-line" />
          </view>
          <text class="scan-btn-text">扫码开门</text>
        </button>

        <view class="steps">
          <text class="step">① 扫柜码</text>
          <text class="step-sep">·</text>
          <text class="step">② 开门取货</text>
          <text class="step-sep">·</text>
          <text class="step">③ 关门结算</text>
        </view>
      </view>

      <view class="landing-foot">
        <text class="manual-link" @click="showManual = !showManual">
          {{ showManual ? '收起' : '手动输入柜机编号（调试）' }}
        </text>
        <view v-if="showManual" class="manual-form">
          <input v-model="deviceInput" class="input" placeholder="例如 CAB-001" />
          <button class="btn-primary" hover-class="btn-hover" :loading="opening" @click="confirmDevice">
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
import { consumerApi, ensureConsumerAuth, getConsumerToken, requireConsumerAuth } from '@/utils/consumer-api';
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
const pollError = ref('');
const brokenThumbs = ref<Record<string, boolean>>({});
const lastDeviceId = ref('');
const lastDeviceName = ref('');
const showPrepDrawer = ref(false);
const prepAccount = ref<AccountDto | null>(null);
let pollTimer: ReturnType<typeof setInterval> | null = null;
let devicePollTimer: ReturnType<typeof setInterval> | null = null;
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
  return !!acc.passwordFreeReady || (acc.balanceCents || 0) >= MIN_BALANCE_CENTS;
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
  if (!cabinetId || opening.value) return;

  enteringFlow.value = true;

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
      uni.showToast({ title: formatError(sessionResult.reason), icon: 'none' });
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
    deviceStatusText.value = online
      ? s.busy
        ? '使用中'
        : sessionActive.value
          ? '门已开 · 购物中'
          : '在线 · 可再次开门'
      : '离线';
  } catch (e) {
    deviceOffline.value = true;
    deviceStatusText.value = formatError(e);
  }
}

async function reopenShop() {
  if (!deviceId.value) return;
  await startShoppingFlow(deviceId.value);
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
    uni.showToast({ title: '结算处理中，请继续购物或稍后查看订单', icon: 'none' });
  }
}

function applySessionView(s: SessionDto) {
  state.value = s.state;
  stateLabel.value = sessionStateLabel(s.state);
  stateHint.value = sessionStateHint(s.state);
  stateTone.value = sessionStateTone(s.state);
  if (s.deviceId && deviceId.value) refreshDeviceStatus();
}

async function restoreActiveSession() {
  const saved = uni.getStorageSync('active_session_id');
  if (!saved || sessionId.value) return;
  try {
    const s = await consumerApi.getSession(saved);
    if (['COMPLETED', 'FAILED', 'CANCELLED', 'DISPUTED'].includes(s.state)) {
      uni.removeStorageSync('active_session_id');
      return;
    }
    sessionId.value = saved;
    if (s.deviceId) {
      deviceId.value = s.deviceId;
      scanned.value = true;
      await loadDeviceAndProducts();
    }
    applySessionView(s);
    startPoll();
  } catch {
    uni.removeStorageSync('active_session_id');
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
</style>
