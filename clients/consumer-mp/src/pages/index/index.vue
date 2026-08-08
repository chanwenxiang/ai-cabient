<template>
  <view class="page-root">
    <!-- 落地页：仅 Tab 进入时展示，柜码直达不经过此页 -->
    <view v-if="showLanding" class="landing">
      <image class="landing-bg" :src="landingBgUrl" mode="aspectFill" aria-hidden="true" />
      <view class="landing-overlay" />

      <view class="landing-content">
        <view class="landing-head">
          <text class="brand">AI开门柜</text>
          <text class="tagline">扫码开门 · 拿了就走</text>
          <view class="pay-badge">
            <text class="pay-badge-icon">✓</text>
            <text class="pay-badge-text">关门自动结算</text>
          </view>
          <view class="flow-steps">
            <text class="flow-step">扫码</text>
            <text class="flow-sep">→</text>
            <text class="flow-step">开门</text>
            <text class="flow-sep">→</text>
            <text class="flow-step">取货</text>
            <text class="flow-sep">→</text>
            <text class="flow-step">关门扣款</text>
          </view>
        </view>

        <view v-if="lastDeviceId" class="resume-card" @click="startShoppingFlow(lastDeviceId)">
          <text class="resume-title">继续在本柜购物</text>
          <text class="resume-sub">{{ lastDeviceName || lastDeviceId }}</text>
        </view>

        <view v-if="landingError" class="landing-error" :class="'kind-' + landingErrorKind">
          <view class="error-icon">!</view>
          <view class="error-copy">
            <text class="error-title">{{ landingErrorTitle }}</text>
            <text class="error-detail">{{ landingError }}</text>
            <view class="error-actions">
              <text
                v-if="landingErrorKind === 'balance'"
                class="error-action primary"
                @click="goRechargeFromError"
                >去充值</text
              >
              <text
                v-else-if="lastFailedDeviceId"
                class="error-action primary"
                @click="retryLastOpen"
                >重试开门</text
              >
              <text
                v-if="landingErrorKind === 'device_not_found'"
                class="error-action"
                @click="onScan"
                >重新扫码</text
              >
              <text
                class="error-action"
                @click="
                  landingError = '';
                  showManual = true;
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

        <view class="landing-spacer" />

        <view class="landing-action">
          <button
            class="scan-circle"
            hover-class="scan-circle-hover"
            :disabled="opening || enteringFlow"
            @click="onScan"
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
        </view>

        <view v-if="showManualEntry" class="landing-foot">
          <text
            class="manual-link"
            role="button"
            data-testid="manual-device-toggle"
            @click="showManual = !showManual"
          >
            {{ showManual ? '收起' : manualEntryLabel }}
          </text>
          <view v-if="showManual" class="manual-form">
            <text class="field-label">柜机编号</text>
            <input
              v-model="deviceInput"
              class="input"
              data-testid="device-code-input"
              aria-label="柜机编号"
              placeholder="例如 CAB-001…"
            />
            <button
              class="btn-primary"
              hover-class="btn-hover"
              role="button"
              data-testid="open-door-confirm"
              :loading="opening"
              :disabled="opening"
              @click="confirmDevice"
            >
              {{ opening ? '开门中…' : '确认并开门' }}
            </button>
          </view>
        </view>
      </view>
    </view>

    <!-- 购物页：开门后展示参考价目 -->
    <view v-if="scanned" class="shop">
      <view class="device-bar">
        <view class="device-info">
          <text class="device-name">{{ deviceName || deviceId }}</text>
          <text class="device-status" :class="{ offline: deviceOffline }">{{
            deviceStatusText
          }}</text>
        </view>
        <view class="device-actions">
          <text class="device-report" @click="goReport">报修</text>
          <text class="device-change" @click="resetDevice">换一台</text>
        </view>
      </view>

      <view
        v-if="reviewSessionId && !sessionActive"
        class="settlement-review-card"
        :class="'tone-' + reviewCopy.tone"
      >
        <view class="review-icon" :class="'tone-' + reviewCopy.tone">{{ reviewCopy.icon }}</view>
        <view class="review-copy">
          <text class="review-title">{{ reviewCopy.title }}</text>
          <text class="review-detail">{{ reviewCopy.detail }}</text>
          <view class="review-actions">
            <text class="review-link primary" @click="goReviewDetail">查看审核详情</text>
            <text class="review-link" @click="goOrders">稍后查看订单</text>
            <text class="review-link" @click="contactOps">联系运营</text>
            <text class="review-link subtle" @click="dismissReview">知道了</text>
          </view>
        </view>
      </view>

      <view class="shopping-banner" :class="stateTone">
        <text class="shopping-banner-title">{{ shoppingBannerTitle }}</text>
        <text class="shopping-banner-sub">{{ shoppingBannerSub }}</text>
      </view>
      <view class="catalog-notice">
        <text>本柜价目仅供参考，请直接取货；实付以关门识别为准，无需在小程序点选商品</text>
      </view>

      <scroll-view scroll-y class="product-scroll" :show-scrollbar="false" enhanced>
        <view v-if="productsLoading" class="card loading-card"
          ><text class="meta">加载商品中…</text></view
        >
        <view v-else-if="!products.length" class="card loading-card catalog-empty">
          <text class="empty-title">本柜暂无上架商品</text>
          <text class="empty-hint">仍可开门购物；实付以关门识别为准。有疑问可故障报修或换一台</text>
          <view class="empty-actions">
            <text class="empty-link" @click="goReport">故障报修</text>
            <text class="empty-link" @click="resetDevice">换一台</text>
          </view>
        </view>
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
              <text v-else class="product-mark">{{ productGlyph(p) }}</text>
            </view>
            <text class="product-name">{{ p.skuName }}</text>
            <text class="product-price">{{ fmtMoney(p.priceCents) }}</text>
          </view>
        </view>
        <view class="list-bottom" />
      </scroll-view>

      <view class="cart-bar">
        <view class="cart-info">
          <text class="cart-hint">{{ cartBarHint }}</text>
          <text v-if="sessionActive && state === 'SHOPPING'" class="cart-sub"
            >拿错可放回，关门后按最终取走结算</text
          >
        </view>
        <view v-if="sessionActive" class="cart-status-chip" :class="stateTone">
          {{ cartBarAction }}
        </view>
        <button
          v-else-if="canReopen"
          class="cart-cta"
          hover-class="btn-hover"
          role="button"
          data-testid="open-door-again"
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
        v-if="state === 'CREATED' || state === 'OPENING' || (opening && !sessionId)"
        class="flow-cancel"
        :loading="cancelling"
        :disabled="cancelling"
        @click="cancelOpening"
      >
        取消本次开门
      </button>
      <button v-if="recognitionSlow" class="flow-cancel" @click="deferRecognitionWait">
        稍后再看结果
      </button>
      <text v-if="recognitionSlow" class="flow-slow-hint"
        >识别时间较长，可先离开，账单出来后在「订单」查看</text
      >
    </view>

    <OpenPrepDrawer
      v-if="showPrepDrawer"
      :account="prepAccount"
      :entry-channel="entryChannel"
      :device-preauth-cents="devicePreauthCents"
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
import landingBgUrl from '@/static/bg-shop-indoor.jpg';
import {
  sessionStateHint,
  sessionStateLabel,
  sessionStateTone
} from '@aicabinet/shared-uni/session-labels';
import {
  classifyOpenError,
  formatError,
  fmtMoney,
  type OpenErrorKind
} from '@aicabinet/shared-uni/format';
import { resumePendingRechargeIfAny } from '@/utils/recharge';
import { isPayReady, resolveEntryChannel, type EntryChannel } from '@/utils/account';
import { productGlyph, productThumb } from '@/utils/product-thumb';
import { consumerDisputeReviewCopy } from '@/utils/dispute-copy';
import {
  delay,
  requestDisputeSubscribe,
  requestOrderSubscribe,
  showBillToast,
  showDisputeResolvedToast
} from '@/utils/notify';
import { showDevTools } from '@/utils/runtime-flags';
import type {
  AccountDto,
  DeviceProduct,
  DisputeTicketDto,
  SessionDto
} from '@aicabinet/shared-types';

const devTools = showDevTools();
/** H5 无可靠扫码时始终提供手输；微信小程序仅开发构建显示 */
const isH5 = ref(false);
// #ifdef H5
isH5.value = true;
// #endif
const showManualEntry = computed(() => devTools || isH5.value);
const manualEntryLabel = computed(() =>
  isH5.value ? '手动输入柜机编号' : '开发：手动输入柜机编号'
);

const deviceInput = ref('');
const deviceId = ref('');
const deviceName = ref('');
const entryChannel = ref<EntryChannel | null>(null);
const scanned = ref(false);
const enteringFlow = ref(false);
const showManual = ref(false);
const products = ref<DeviceProduct[]>([]);
const productsLoading = ref(false);
const deviceStatusText = ref('');
const deviceOffline = ref(false);
/** 本柜开门预授权门槛（分），来自 DeviceStatus.preauthCents */
const devicePreauthCents = ref<number | null>(null);
const sessionId = ref('');
const state = ref('');
const stateLabel = ref('');
const stateHint = ref('');
const stateTone = ref('idle');
const opening = ref(false);
const cancelling = ref(false);
const pollError = ref('');
const landingError = ref('');
const landingErrorKind = ref<OpenErrorKind>('other');
const lastFailedDeviceId = ref('');
const lastFailedChannel = ref<string | null>(null);
const reviewSessionId = ref(String(uni.getStorageSync('last_disputed_session_id') || ''));
const reviewTicket = ref<DisputeTicketDto | null>(null);
const reviewCopy = computed(() => consumerDisputeReviewCopy(reviewTicket.value));
const servicePhone = ref('400-888-0018');
const openingSeconds = ref(90);
const brokenThumbs = ref<Record<string, boolean>>({});
const lastDeviceId = ref('');
const lastDeviceName = ref('');
const showPrepDrawer = ref(false);
const prepAccount = ref<AccountDto | null>(null);
const recognitionDeferred = ref(false);
const recognitionElapsedSec = ref(0);
let recognitionTimer: ReturnType<typeof setInterval> | null = null;
let pollTimer: ReturnType<typeof setInterval> | null = null;
let devicePollTimer: ReturnType<typeof setInterval> | null = null;
let countdownTimer: ReturnType<typeof setInterval> | null = null;
let prepResolve: ((ok: boolean) => void) | null = null;
/** 设备状态去抖：避免 2s 会话轮询期间重复请求 /devices/{id}/status */
let lastDeviceStatusRefreshAt = 0;
let lastDeviceStatusRefreshDevice = '';

const recognitionSlow = computed(
  () =>
    ['RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(state.value) &&
    recognitionElapsedSec.value >= 90
);

const sessionActive = computed(
  () =>
    !!sessionId.value &&
    ['CREATED', 'OPENING', 'SHOPPING', 'RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(
      state.value
    )
);

const showLanding = computed(() => !scanned.value && !enteringFlow.value);

const landingErrorTitle = computed(() => {
  switch (landingErrorKind.value) {
    case 'balance':
      return '余额不足';
    case 'device_not_found':
      return '柜机不存在';
    case 'device_paused':
      return '柜机暂停营业';
    case 'device_busy':
      return '柜机正忙';
    case 'rate_limit':
      return '开门过于频繁';
    default:
      return '暂时无法开门';
  }
});

const canReopen = computed(
  () =>
    scanned.value &&
    !!deviceId.value &&
    !sessionActive.value &&
    !opening.value &&
    !enteringFlow.value
);

const flowOverlayVisible = computed(() => {
  if (showPrepDrawer.value) return false;
  if (
    recognitionDeferred.value &&
    ['RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(state.value)
  ) {
    return false;
  }
  if (enteringFlow.value && !scanned.value) return true;
  if (opening.value && !sessionId.value) return true;
  if (['OPENING', 'CREATED', 'RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(state.value))
    return true;
  return false;
});

const flowOverlayPulse = computed(
  () =>
    ['OPENING', 'CREATED', 'RECOGNIZING', 'SETTLING', 'WAITING_UPLOAD'].includes(state.value) ||
    opening.value
);

const flowOverlayTitle = computed(() => {
  if (opening.value && !sessionId.value) return '正在开门';
  if (recognitionSlow.value) return '识别时间较长';
  if (stateLabel.value && stateLabel.value !== '-') return stateLabel.value;
  return '准备中';
});

const flowOverlayHint = computed(() => {
  if (state.value === 'OPENING' || state.value === 'CREATED') {
    const elapsed = Math.max(0, 90 - openingSeconds.value);
    return `已等待 ${elapsed} 秒，柜门无响应时可安全取消本次开门`;
  }
  if (recognitionSlow.value) {
    return `已识别 ${recognitionElapsedSec.value} 秒，结果出来后会生成账单，也可稍后再看`;
  }
  if (stateHint.value) return stateHint.value;
  if (opening.value) return '正在连接柜机并验证开门资格…';
  return '请稍候';
});

const shoppingBannerTitle = computed(() => {
  if (state.value === 'SHOPPING') return '柜门已开，请自由取货';
  if (state.value === 'OPENING' || state.value === 'CREATED') return '正在开门，请稍候';
  if (['RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(state.value)) return '正在识别结算';
  if (canReopen.value) return '本柜可继续购物';
  return '选好商品后请关好柜门';
});

const shoppingBannerSub = computed(() => {
  if (state.value === 'SHOPPING') return '无需在手机上点选商品，拿了就走';
  if (['RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(state.value)) {
    return '可先离开，账单会在「订单」中展示';
  }
  if (canReopen.value) return '点击下方再次开门，或扫其他柜机';
  return '实际扣款以视觉识别结果为准';
});

const cartBarHint = computed(() => {
  if (state.value === 'SHOPPING') return '请取货后关好柜门';
  if (!sessionActive.value) return '可再次开门继续购买';
  return '关门后自动识别并扣款';
});

const cartBarAction = computed(() => {
  if (state.value === 'SHOPPING') return '请关门';
  if (state.value === 'OPENING' || state.value === 'CREATED') return '开门中';
  if (state.value === 'RECOGNIZING' || state.value === 'WAITING_UPLOAD') return '识别中';
  if (state.value === 'SETTLING') return '结算中';
  return '进行中';
});

function payReady(acc: AccountDto) {
  return isPayReady(acc, entryChannel.value, devicePreauthCents.value || undefined);
}

onLoad(async (opts) => {
  let launch = parseLaunchOptions((opts || {}) as Record<string, string>);
  // H5：兼容 ?deviceId= / hash 查询（柜门二维码 deep link）
  if (!launch.deviceId && typeof window !== 'undefined') {
    try {
      const fromSearch = parseLaunchOptions(
        Object.fromEntries(new URLSearchParams(window.location.search).entries())
      );
      if (fromSearch.deviceId) {
        launch = fromSearch;
      } else if (window.location.hash.includes('deviceId=')) {
        const hashQuery = window.location.hash.split('?')[1] || '';
        const fromHash = parseLaunchOptions(
          Object.fromEntries(new URLSearchParams(hashQuery).entries())
        );
        if (fromHash.deviceId) launch = fromHash;
      }
    } catch {
      /* ignore */
    }
  }
  if (launch.channel) {
    entryChannel.value = resolveEntryChannel(launch.channel);
  }
  if (launch.deviceId) {
    // 深链直接开门；进入态由 startShoppingFlow 自行管理，勿提前置位以免被守卫短路
    await startShoppingFlow(launch.deviceId, launch.channel);
  }
});

onShow(async () => {
  lastDeviceId.value = uni.getStorageSync('last_device_id') || '';
  lastDeviceName.value = uni.getStorageSync('last_device_name') || '';
  await loadConsumerConfig();
  await ensureConsumerAuth();
  if (getConsumerToken()) {
    await resumePendingRechargeIfAny();
    await refreshReviewState();
    if (scanned.value && deviceId.value) refreshDeviceStatus();
    const reopen = uni.getStorageSync('reopen_device_id');
    if (reopen) {
      uni.removeStorageSync('reopen_device_id');
      const ch = uni.getStorageSync('reopen_entry_channel');
      if (ch) {
        uni.removeStorageSync('reopen_entry_channel');
        entryChannel.value = resolveEntryChannel(ch);
      }
      await startShoppingFlow(reopen, ch || undefined);
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
  stopRecognitionTimer();
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

async function startShoppingFlow(id: string, scanChannel?: string | null) {
  const cabinetId = id.trim().toUpperCase();
  if (!cabinetId || opening.value || enteringFlow.value) return;
  if (!/^[A-Z0-9][A-Z0-9_-]{1,63}$/.test(cabinetId)) {
    setLandingError('柜机编号无效，请扫描柜门二维码或输入如 CAB-001。', 'device_not_found');
    lastFailedDeviceId.value = '';
    uni.showToast({ title: '柜机编号无效', icon: 'none' });
    return;
  }

  const resolved = resolveEntryChannel(scanChannel) || entryChannel.value;
  if (resolved) entryChannel.value = resolved;

  enteringFlow.value = true;
  landingError.value = '';
  landingErrorKind.value = 'other';

  try {
    if (!(await requireConsumerAuth('扫码开门需先完成微信授权'))) {
      // 登录成功回到首页后由 onShow 读取 reopen_device_id 续开
      uni.setStorageSync('reopen_device_id', cabinetId);
      if (entryChannel.value) {
        uni.setStorageSync('reopen_entry_channel', entryChannel.value);
      }
      return;
    }
    if (!(await ensureCanOpenDoor())) {
      return;
    }

    opening.value = true;
    deviceId.value = cabinetId;
    scanned.value = true;
    const status = await consumerApi.deviceStatus(cabinetId);
    deviceName.value = status.deviceName || cabinetId;
    const pre = Number(status.preauthCents);
    devicePreauthCents.value = Number.isFinite(pre) && pre > 0 ? pre : null;
    const online = status.online === true || (status.onlineStatus || '').toUpperCase() === 'ONLINE';
    const reason = String(status.busyReason || '').toUpperCase();
    deviceOffline.value = !online;
    if (!online) {
      deviceStatusText.value = '离线';
    } else if (status.available === false && reason === 'LOCKED') {
      deviceStatusText.value = '暂停营业';
    } else if (status.available === false && reason === 'REPLENISHMENT') {
      deviceStatusText.value = '补货中';
    } else if (status.available === false || reason === 'SESSION') {
      deviceStatusText.value = '使用中';
    } else {
      deviceStatusText.value = '在线 · 可开门';
    }

    if (!online || status.available === false) {
      scanned.value = false;
      deviceId.value = '';
      lastFailedDeviceId.value = cabinetId;
      lastFailedChannel.value = entryChannel.value;
      let kind: OpenErrorKind = 'other';
      let msg = deviceStatusText.value;
      if (!online) {
        kind = 'other';
        msg = '该柜机当前离线，请稍后再试或更换其他柜机。';
      } else if (reason === 'LOCKED') {
        kind = 'device_paused';
        msg = '柜机已暂停营业，请稍后再试或换一台';
      } else if (reason === 'REPLENISHMENT') {
        kind = 'device_busy';
        msg = '柜机正在补货，请稍后再试';
      } else if (reason === 'SESSION') {
        kind = 'device_busy';
        msg = '柜机正在被使用，请稍后再试';
      }
      setLandingError(msg, kind);
      uni.showToast({
        title:
          kind === 'device_paused'
            ? '柜机暂停营业'
            : kind === 'device_busy'
              ? '柜机正忙'
              : '暂时无法开门',
        icon: 'none'
      });
      return;
    }
    productsLoading.value = true;
    const OPEN_TIMEOUT_MS = 20000;
    const [productsResult, sessionResult] = await Promise.allSettled([
      withTimeout(consumerApi.deviceProducts(cabinetId), OPEN_TIMEOUT_MS, '商品加载超时，请重试'),
      withTimeout(
        consumerApi.createSession(cabinetId, entryChannel.value),
        OPEN_TIMEOUT_MS,
        '开门请求超时，请检查网络后重试'
      )
    ]);
    if (productsResult.status === 'fulfilled') {
      products.value = productsResult.value;
    } else {
      products.value = [];
      // 商品失败不阻断开门，仅提示
      uni.showToast({ title: formatError(productsResult.reason), icon: 'none' });
    }
    if (sessionResult.status !== 'fulfilled') {
      // 超时兜底：请求超时但服务端可能已创建会话，先尝试认领，避免孤儿会话
      if (await adoptOrphanSession(cabinetId)) {
        return;
      }
      scanned.value = false;
      deviceId.value = '';
      lastFailedDeviceId.value = cabinetId;
      lastFailedChannel.value = entryChannel.value;
      const failReason = sessionResult.reason;
      const kind = classifyOpenError(failReason);
      setLandingError(formatError(failReason), kind);
      uni.showToast({ title: landingError.value, icon: 'none' });
      return;
    }
    const s = sessionResult.value;
    lastFailedDeviceId.value = '';
    lastFailedChannel.value = null;
    sessionId.value = s.sessionId;
    uni.setStorageSync('active_session_id', s.sessionId);
    applySessionView(s);
    startPoll();
  } catch (e) {
    // 未成功创建会话即失败：回到落地页展示错误，避免停留在“空柜机购物页”
    if (!sessionId.value) {
      scanned.value = false;
      deviceId.value = '';
      deviceName.value = '';
      deviceStatusText.value = '';
      products.value = [];
      lastFailedDeviceId.value = cabinetId;
      lastFailedChannel.value = entryChannel.value;
    }
    setLandingError(formatError(e), 'other');
    uni.showToast({ title: formatError(e), icon: 'none' });
  } finally {
    productsLoading.value = false;
    opening.value = false;
    enteringFlow.value = false;
  }
}

/**
 * 开门请求超时后认领服务端可能已创建的会话（同柜机、非终态）。
 * 成功则接管会话继续轮询，返回 true；否则走原失败路径。
 */
async function adoptOrphanSession(cabinetId: string): Promise<boolean> {
  try {
    const s = await consumerApi.activeSession();
    const matched =
      s &&
      String(s.deviceId || '')
        .trim()
        .toUpperCase() ===
        String(cabinetId || '')
          .trim()
          .toUpperCase() &&
      ['CREATED', 'OPENING', 'SHOPPING', 'RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(
        s.state
      );
    if (matched) {
      lastFailedDeviceId.value = '';
      lastFailedChannel.value = null;
      sessionId.value = s.sessionId;
      uni.setStorageSync('active_session_id', s.sessionId);
      applySessionView(s);
      startPoll();
      uni.showToast({ title: '已恢复开门会话', icon: 'none' });
      return true;
    }
  } catch {
    /* 查询失败仍按原失败路径处理 */
  }
  return false;
}

function setLandingError(message: string, kind: OpenErrorKind = 'other') {
  landingError.value = message;
  landingErrorKind.value = kind;
}

function goRechargeFromError() {
  landingError.value = '';
  uni.navigateTo({ url: '/pages/recharge/recharge' });
}

function withTimeout<T>(promise: Promise<T>, ms: number, timeoutMessage: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(timeoutMessage)), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      }
    );
  });
}

function retryLastOpen() {
  const id = lastFailedDeviceId.value;
  if (!id) return;
  landingError.value = '';
  startShoppingFlow(id, lastFailedChannel.value);
}

function goOrders() {
  uni.switchTab({ url: '/pages/orders/orders' });
}

function contactOps() {
  uni.showModal({
    title: '联系运营',
    content: `请联系客服 ${servicePhone.value}，并提供审核编号：` + reviewSessionId.value,
    showCancel: false,
    confirmText: '我知道了'
  });
}

async function loadConsumerConfig() {
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    const phone = cfg?.servicePhone || cfg?.['consumer.service_phone'];
    if (phone) servicePhone.value = phone;
  } catch {
    /* 使用默认客服电话 */
  }
}

async function refreshReviewState() {
  const sid = String(uni.getStorageSync('last_disputed_session_id') || '');
  if (!sid || !getConsumerToken()) {
    reviewSessionId.value = '';
    reviewTicket.value = null;
    return;
  }
  try {
    const disputes = await consumerApi.listMyDisputes();
    const ticket = disputes.find((d) => d.sessionId === sid);
    if (!ticket || ticket.status !== 'OPEN') {
      reviewSessionId.value = '';
      reviewTicket.value = null;
      uni.removeStorageSync('last_disputed_session_id');
      if (ticket?.status === 'RESOLVED') {
        showDisputeResolvedToast(ticket);
      }
      return;
    }
    reviewSessionId.value = sid;
    reviewTicket.value = ticket;
  } catch {
    // 弱网不造假票，避免误显示「审核中」；保留 storage 供下次 onShow 重试
    reviewSessionId.value = '';
    reviewTicket.value = null;
  }
}

function dismissReview() {
  reviewSessionId.value = '';
  reviewTicket.value = null;
  uni.removeStorageSync('last_disputed_session_id');
}

function goReviewDetail() {
  const sid = reviewSessionId.value || String(uni.getStorageSync('last_disputed_session_id') || '');
  const tid = reviewTicket.value?.ticketId || '';
  const q = [
    tid ? `ticketId=${encodeURIComponent(tid)}` : '',
    sid ? `sessionId=${encodeURIComponent(sid)}` : ''
  ]
    .filter(Boolean)
    .join('&');
  if (!q) {
    goOrders();
    return;
  }
  uni.navigateTo({ url: `/pages/dispute/detail?${q}` });
}

function ensureCanOpenDoor(): Promise<boolean> {
  return consumerApi
    .account()
    .then((acc) => {
      if (acc.operator || (acc.verified && payReady(acc))) return true;
      prepAccount.value = acc;
      showPrepDrawer.value = true;
      return new Promise<boolean>((resolve) => {
        prepResolve = resolve;
      });
    })
    .catch((e) => {
      uni.showToast({ title: formatError(e) || '账户信息加载失败', icon: 'none' });
      return false;
    });
}

function onPrepDone(channel?: EntryChannel | null) {
  if (channel) entryChannel.value = channel;
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
      const raw = String(res.result || '').trim();
      if (!raw) {
        uni.showToast({ title: '未识别到有效内容，请对准柜门二维码', icon: 'none' });
        return;
      }
      const parsed = parseCabinetScan(raw);
      if (parsed.alipayOnly) {
        uni.showToast({ title: '请使用支付宝扫码', icon: 'none' });
        return;
      }
      if (!parsed.deviceId) {
        landingError.value = '无法识别柜机二维码，请扫描柜门上的专用码，或手动输入柜机编号。';
        landingErrorKind.value = 'device_not_found';
        if (showManualEntry.value) showManual.value = true;
        uni.showToast({ title: '无法识别柜机二维码', icon: 'none' });
        return;
      }
      startShoppingFlow(parsed.deviceId, parsed.channel);
    },
    fail() {
      if (isH5.value) {
        showManual.value = true;
        uni.showToast({ title: '浏览器请手动输入柜机编号', icon: 'none' });
        return;
      }
      uni.showToast({ title: '扫码取消或失败', icon: 'none' });
    }
  });
}

function confirmDevice() {
  let raw = deviceInput.value;
  // #ifdef H5
  if (!raw.trim() && typeof document !== 'undefined') {
    const el = document.querySelector(
      '.manual-form input, .uni-input-input, input'
    ) as HTMLInputElement | null;
    if (el?.value) raw = el.value;
  }
  // #endif
  deviceInput.value = raw;
  const parsed = parseCabinetScan(raw);
  const id = parsed.deviceId || raw.trim().toUpperCase();
  if (!id) {
    landingError.value = '请输入柜机编号，例如 CAB-001。';
    landingErrorKind.value = 'device_not_found';
    uni.showToast({ title: '请输入柜机编号', icon: 'none' });
    return;
  }
  startShoppingFlow(id, parsed.channel);
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
    const pre = Number(s.preauthCents);
    devicePreauthCents.value = Number.isFinite(pre) && pre > 0 ? pre : null;
    const online = s.online === true || (s.onlineStatus || '').toUpperCase() === 'ONLINE';
    const reason = String(s.busyReason || '').toUpperCase();
    const unavailable = s.available === false;
    // 仅真正离线视为 offline；暂停营业/占用走业务错误，避免误报「离线」
    deviceOffline.value = !online;
    if (!online) {
      deviceStatusText.value = '离线';
    } else if (state.value === 'SHOPPING') {
      deviceStatusText.value = '门已开 · 购物中';
    } else if (state.value === 'CREATED' || state.value === 'OPENING') {
      deviceStatusText.value = '正在开门';
    } else if (unavailable && reason === 'LOCKED') {
      deviceStatusText.value = '暂停营业';
    } else if (unavailable && reason === 'REPLENISHMENT') {
      deviceStatusText.value = '补货中';
    } else if (unavailable || s.busy || reason === 'SESSION') {
      deviceStatusText.value = '使用中';
    } else {
      deviceStatusText.value = '在线 · 可开门';
    }
  } catch (e) {
    const kind = classifyOpenError(e);
    deviceOffline.value = kind !== 'device_paused' && kind !== 'device_busy';
    deviceStatusText.value = formatError(e);
  }
}

/** 设备状态去抖：仅在设备变化或距上次刷新 ≥30s 时拉取，避免会话轮询重复请求 */
function refreshDeviceStatusThrottled(device: string) {
  const now = Date.now();
  const changed = device !== lastDeviceStatusRefreshDevice;
  const expired = now - lastDeviceStatusRefreshAt >= 30000;
  if (!changed && !expired) return;
  lastDeviceStatusRefreshDevice = device;
  lastDeviceStatusRefreshAt = now;
  void refreshDeviceStatus();
}

async function reopenShop() {
  if (!deviceId.value) return;
  await startShoppingFlow(deviceId.value);
}

async function cancelOpening() {
  if (cancelling.value) return;
  // 尚无 session：仅取消本地开门等待
  if (!sessionId.value) {
    opening.value = false;
    enteringFlow.value = false;
    scanned.value = false;
    deviceId.value = '';
    products.value = [];
    uni.showToast({ title: '已取消开门', icon: 'none' });
    return;
  }
  const confirmed = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '取消开门',
      content: '确定取消本次开门吗？已创建的会话将被关闭。',
      confirmText: '取消开门',
      cancelText: '继续等待',
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    });
  });
  if (!confirmed) return;
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
  recognitionDeferred.value = false;
  stopRecognitionTimer();
}

function startRecognitionTimer(since?: string) {
  stopRecognitionTimer();
  const started = since ? new Date(since).getTime() : Date.now();
  const tick = () => {
    recognitionElapsedSec.value = Math.max(0, Math.floor((Date.now() - started) / 1000));
  };
  tick();
  recognitionTimer = setInterval(tick, 1000);
}

function stopRecognitionTimer() {
  if (recognitionTimer) clearInterval(recognitionTimer);
  recognitionTimer = null;
  recognitionElapsedSec.value = 0;
}

function deferRecognitionWait() {
  recognitionDeferred.value = true;
  uni.showToast({ title: '可稍后在订单页查看', icon: 'none' });
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
    void refreshReviewState();
    void requestDisputeSubscribe();
    uni.showToast({ title: '识别完成，账单待人工确认', icon: 'none' });
    // 无订单时直接进审核详情，避免只停在首页提示卡
    setTimeout(() => {
      uni.navigateTo({
        url: `/pages/dispute/detail?sessionId=${encodeURIComponent(sid)}`,
        fail: () => {
          /* 首页审核卡仍可点 */
        }
      });
    }, 600);
  }
}

function applySessionView(s: SessionDto) {
  state.value = s.state;
  stateLabel.value = sessionStateLabel(s.state);
  stateHint.value = sessionStateHint(s.state);
  stateTone.value = sessionStateTone(s.state);
  if (s.state === 'OPENING' || s.state === 'CREATED') startOpeningCountdown(s.createdAt);
  else stopOpeningCountdown();
  if (['RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(s.state)) {
    startRecognitionTimer(s.closeTime || s.createdAt);
  } else {
    stopRecognitionTimer();
    recognitionDeferred.value = false;
  }
  if (s.deviceId && deviceId.value) refreshDeviceStatusThrottled(s.deviceId);
}

function startOpeningCountdown(createdAt?: string) {
  stopOpeningCountdown();
  const started = createdAt ? new Date(createdAt).getTime() : Date.now();
  const tick = () => {
    openingSeconds.value = Math.max(0, 90 - Math.floor((Date.now() - started) / 1000));
  };
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
      if (s.state === 'DISPUTED') {
        reviewSessionId.value = s.sessionId;
        uni.setStorageSync('last_disputed_session_id', s.sessionId);
        void requestDisputeSubscribe();
      }
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
  } catch (e) {
    // 404/已失效：本地会话已不存在，清掉避免每次进首页都白查；
    // 弱网或服务短暂不可用时保留，下次 onShow 继续恢复。
    const status = (e as { status?: number } | null)?.status;
    if (status === 404) {
      uni.removeStorageSync('active_session_id');
      clearOpenAttempt();
    }
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
        const hint =
          sessionStateHint(s.state) || (s.state === 'CANCELLED' ? '会话已取消' : '购物未完成');
        uni.removeStorageSync('active_session_id');
        clearOpenAttempt();
        clearSessionUi();
        uni.showToast({ title: hint, icon: 'none', duration: 2800 });
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
    if (!opening.value && scanned.value && deviceId.value)
      refreshDeviceStatusThrottled(deviceId.value);
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
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: #0b3d38;
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
  background: linear-gradient(
    180deg,
    rgba(6, 66, 60, 0.52) 0%,
    rgba(10, 94, 84, 0.22) 34%,
    rgba(9, 78, 70, 0.3) 66%,
    rgba(4, 42, 38, 0.78) 100%
  );
}
.landing-content {
  position: relative;
  z-index: 2;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 32rpx calc(24rpx + env(safe-area-inset-bottom));
}
.landing-head {
  flex-shrink: 0;
  padding-top: 48rpx;
  text-align: center;
}
.brand {
  font-size: 56rpx;
  font-weight: 800;
  color: #ffffff;
  display: block;
  letter-spacing: 2rpx;
  text-shadow: 0 4rpx 20rpx rgba(6, 78, 59, 0.35);
}
.tagline {
  font-size: 30rpx;
  color: rgba(255, 255, 255, 0.86);
  margin-top: 10rpx;
  display: block;
}
.pay-badge {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  margin-top: 18rpx;
  padding: 10rpx 22rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.16);
  border: 1rpx solid rgba(255, 255, 255, 0.28);
  backdrop-filter: blur(12rpx);
}
.pay-badge-icon {
  color: #a7f3d0;
  font-size: 24rpx;
  font-weight: 700;
}
.pay-badge-text {
  color: #ffffff;
  font-size: 22rpx;
}
.flow-steps {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8rpx;
  margin-top: 20rpx;
  padding: 0 12rpx;
}
.flow-step {
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.92);
  font-weight: 600;
}
.flow-sep {
  font-size: 20rpx;
  color: rgba(255, 255, 255, 0.45);
}

.resume-card {
  margin-top: 24rpx;
  background: rgba(8, 24, 30, 0.5);
  border-radius: 20rpx;
  padding: 24rpx 28rpx;
  border: 2rpx solid rgba(148, 210, 198, 0.28);
  backdrop-filter: blur(16rpx);
  box-shadow: 0 10rpx 28rpx rgba(2, 12, 16, 0.28);
}
.resume-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #ecfeff;
  display: block;
}
.resume-sub {
  font-size: 24rpx;
  color: rgba(207, 250, 254, 0.72);
  margin-top: 4rpx;
  display: block;
}

.landing-spacer {
  flex: 1;
  min-height: 80rpx;
}

.landing-action {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-bottom: 16rpx;
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
}
.scan-circle::after {
  border: none;
}
.scan-circle-hover {
  opacity: 0.9;
  transform: scale(0.98);
}
.scan-circle-inner {
  width: 220rpx;
  height: 220rpx;
  border-radius: 50%;
  background: linear-gradient(145deg, #0d9488, #14b8a6);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 16rpx 48rpx rgba(13, 148, 136, 0.38);
}
.scan-icon-box {
  width: 96rpx;
  height: 96rpx;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.scan-circle-text {
  margin-top: 20rpx;
  font-size: 34rpx;
  font-weight: 700;
  color: #ffffff;
}
.scan-tip {
  margin-top: 16rpx;
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.82);
}

.landing-foot {
  flex-shrink: 0;
  padding: 8rpx 0 0;
}
.manual-link {
  display: block;
  text-align: center;
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.76);
  padding: 12rpx 0;
}
.manual-form {
  margin-top: 8rpx;
}
.field-label {
  display: block;
  font-size: 24rpx;
  color: #64748b;
  margin-bottom: 8rpx;
}
.input {
  background: #fff;
  border: 1rpx solid #e5e5e5;
  border-radius: 12rpx;
  padding: 20rpx 24rpx;
  margin-bottom: 16rpx;
  font-size: 28rpx;
  color: #191919;
}
.btn-primary {
  margin: 0;
  background: linear-gradient(135deg, #059669, #0d9488);
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
  font-weight: 600;
  line-height: 88rpx;
  height: 88rpx;
  box-shadow: 0 10rpx 28rpx rgba(5, 150, 105, 0.28);
}
.btn-primary::after {
  border: none;
}
.btn-hover {
  opacity: 0.85;
}

.scan-corner {
  position: absolute;
  width: 28rpx;
  height: 28rpx;
  border-color: rgba(255, 255, 255, 0.95);
  border-style: solid;
}
.scan-corner.tl {
  top: 0;
  left: 0;
  border-width: 5rpx 0 0 5rpx;
  border-radius: 4rpx 0 0 0;
}
.scan-corner.tr {
  top: 0;
  right: 0;
  border-width: 5rpx 5rpx 0 0;
  border-radius: 0 4rpx 0 0;
}
.scan-corner.bl {
  bottom: 0;
  left: 0;
  border-width: 0 0 5rpx 5rpx;
  border-radius: 0 0 0 4rpx;
}
.scan-corner.br {
  bottom: 0;
  right: 0;
  border-width: 0 5rpx 5rpx 0;
  border-radius: 0 0 4rpx 0;
}
.scan-line {
  width: 8rpx;
  height: 40rpx;
  background: #fff;
  border-radius: 4rpx;
}

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
.device-name {
  font-size: 32rpx;
  font-weight: 600;
  color: #191919;
  display: block;
}
.device-status {
  font-size: 24rpx;
  color: #07c160;
  display: block;
  margin-top: 4rpx;
}
.device-status.offline {
  color: #fa5151;
}
.device-actions {
  display: flex;
  align-items: center;
  gap: 20rpx;
  flex-shrink: 0;
}
.device-change {
  font-size: 26rpx;
  color: #576b95;
}
.device-report {
  font-size: 24rpx;
  color: #888;
}

.shopping-banner {
  margin: 12rpx 24rpx 0;
  padding: 22rpx 24rpx;
  border-radius: 16rpx;
  background: linear-gradient(135deg, #ecfdf5, #f0fdf4);
  border: 1rpx solid #bbf7d0;
}
.shopping-banner.wait {
  background: linear-gradient(135deg, #fff7ed, #fffbeb);
  border-color: #fde68a;
}
.shopping-banner-title {
  display: block;
  font-size: 30rpx;
  font-weight: 700;
  color: #065f46;
}
.shopping-banner.wait .shopping-banner-title {
  color: #92400e;
}
.shopping-banner-sub {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #047857;
  line-height: 1.4;
}
.shopping-banner.wait .shopping-banner-sub {
  color: #a16207;
}
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
.list-bottom {
  height: 16rpx;
}

.loading-card {
  text-align: center;
  padding: 48rpx;
  margin: 0 16rpx;
}
.catalog-empty .empty-title {
  display: block;
  font-size: 30rpx;
  font-weight: 700;
  color: #223029;
}
.catalog-empty .empty-hint {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #849087;
  line-height: 1.5;
}
.catalog-empty .empty-actions {
  display: flex;
  justify-content: center;
  gap: 28rpx;
  margin-top: 20rpx;
}
.catalog-empty .empty-link {
  font-size: 26rpx;
  color: #059669;
  font-weight: 650;
}

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
.product-thumb.cat-drink {
  background: linear-gradient(135deg, #e6f4ff, #bae0ff);
}
.product-thumb.cat-snack {
  background: linear-gradient(135deg, #fff7e6, #ffe58f);
}
.product-thumb.cat-dairy {
  background: linear-gradient(135deg, #f9f0ff, #d3adf7);
}
.product-thumb.cat-food {
  background: linear-gradient(135deg, #fff2e8, #ffbb96);
}
.product-thumb.cat-default {
  background: #f5f5f5;
}
.product-img {
  width: 100%;
  height: 100%;
}
.product-mark {
  width: 96rpx;
  height: 96rpx;
  border-radius: 24rpx;
  background: rgba(255, 255, 255, 0.72);
  color: #047857;
  font-size: 40rpx;
  font-weight: 800;
  line-height: 96rpx;
  text-align: center;
}
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
.cart-hint {
  font-size: 28rpx;
  color: #1e293b;
  font-weight: 600;
  display: block;
}
.cart-sub {
  font-size: 22rpx;
  color: #888;
  display: block;
  margin-top: 4rpx;
}
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
.cart-cta::after {
  border: none;
}
.settlement-review-card {
  display: flex;
  gap: 18rpx;
  margin: 14rpx 20rpx 0;
  padding: 22rpx;
  border: 1rpx solid #fed7aa;
  border-radius: 20rpx;
  background: linear-gradient(135deg, #fffaf0, #fff7ed);
  box-shadow: 0 9rpx 26rpx rgba(194, 65, 12, 0.08);
}
.settlement-review-card.tone-success {
  border-color: #bbf7d0;
  background: linear-gradient(135deg, #f0fdf4, #ecfdf5);
}
.settlement-review-card.tone-wait {
  border-color: #fed7aa;
  background: linear-gradient(135deg, #fffaf0, #fff7ed);
}
.settlement-review-card.tone-warn {
  border-color: #fecaca;
  background: linear-gradient(135deg, #fff7f7, #fff1f2);
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
  font-size: 24rpx;
}
.review-icon.tone-wait {
  background: #059669;
}
.review-icon.tone-success {
  background: #059669;
}
.review-icon.tone-warn {
  background: #ef4444;
}
.review-copy {
  min-width: 0;
  flex: 1;
}
.review-title,
.review-detail {
  display: block;
}
.review-title {
  color: #9a3412;
  font-size: 26rpx;
  font-weight: 750;
}
.review-detail {
  margin-top: 7rpx;
  color: #9a5b39;
  font-size: 22rpx;
  line-height: 1.55;
}
.review-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 22rpx;
  margin-top: 15rpx;
}
.review-link {
  color: #c2410c;
  font-size: 23rpx;
  font-weight: 600;
}
.review-link.primary {
  color: #047857;
}
.review-link.subtle {
  color: #9ca3af;
}
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
.cart-status-chip.wait {
  color: #fa9d3b;
  background: #fff7e6;
}
.cart-status-chip.active {
  color: #07c160;
  background: #e8f8ef;
}
.cart-status-chip.error {
  color: #fa5151;
  background: #ffecec;
}

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
.flow-overlay.wait {
  background: #fffdf5;
}
.flow-overlay.active {
  background: #f0fdf4;
}
.flow-overlay.error {
  background: #fff5f5;
}
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
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .flow-spinner.pulse {
    animation: none;
    border-top-color: #07c160;
    opacity: 0.85;
  }
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
.flow-cancel::after {
  border: none;
}
.flow-slow-hint {
  margin-top: 16rpx;
  padding: 0 40rpx;
  font-size: 24rpx;
  color: #888;
  text-align: center;
  line-height: 1.5;
}

/* visual overrides (merged) */
.landing {
  padding: 0;
}
.landing-head {
  padding-top: 52rpx;
  text-align: center;
}
.brand {
  font-size: 58rpx;
}
.tagline {
  font-size: 31rpx;
}
.resume-card {
  margin-top: 22rpx;
  padding: 24rpx 28rpx;
  border-radius: 22rpx;
}
.landing-error {
  display: flex;
  align-items: flex-start;
  gap: 18rpx;
  margin-top: 20rpx;
  padding: 22rpx;
  border: 1rpx solid #fecaca;
  border-radius: 20rpx;
  background: rgba(255, 247, 247, 0.94);
  box-shadow: 0 9rpx 24rpx rgba(220, 38, 38, 0.07);
}
.landing-error.kind-balance {
  border-color: #fcd34d;
  background: rgba(255, 251, 235, 0.96);
  box-shadow: 0 9rpx 24rpx rgba(217, 119, 6, 0.08);
}
.landing-error.kind-balance .error-icon {
  background: #f59e0b;
}
.landing-error.kind-balance .error-title {
  color: #92400e;
}
.landing-error.kind-balance .error-detail {
  color: #b45309;
}
.landing-error.kind-device_not_found {
  border-color: #cbd5e1;
  background: rgba(248, 250, 252, 0.96);
}
.landing-error.kind-device_not_found .error-icon {
  background: #64748b;
}
.landing-error.kind-device_not_found .error-title {
  color: #334155;
}
.landing-error.kind-device_not_found .error-detail {
  color: #475569;
}
.error-icon {
  display: flex;
  flex: 0 0 42rpx;
  height: 42rpx;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #fff;
  background: #ef4444;
  font-weight: 800;
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
  color: #991b1b;
  font-size: 25rpx;
  font-weight: 700;
}
.error-detail {
  margin-top: 6rpx;
  color: #b45353;
  font-size: 22rpx;
  line-height: 1.5;
}
.error-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 14rpx;
}
.error-action {
  padding: 8rpx 18rpx;
  border-radius: 999rpx;
  border: 1rpx solid #f0b4b4;
  color: #9f1239;
  font-size: 22rpx;
  background: #fff;
}
.error-action.primary {
  border-color: #059669;
  color: #047857;
  background: #ecfdf5;
}
.error-close {
  padding: 0 5rpx;
  color: #b98b8b;
  font-size: 34rpx;
  line-height: 1;
}
.scan-circle-inner {
  width: 228rpx;
  height: 228rpx;
  box-shadow: 0 18rpx 52rpx rgba(234, 88, 12, 0.4);
}
.scan-circle-text {
  font-size: 36rpx;
}
.landing-foot {
  padding-bottom: 22rpx;
}
.device-bar {
  margin: 18rpx 20rpx 0;
  padding: 25rpx;
  border: 1rpx solid #edf2ef;
  border-radius: 22rpx;
  box-shadow: 0 9rpx 28rpx rgba(15, 23, 42, 0.055);
}
.device-status {
  margin-top: 7rpx;
  font-weight: 600;
}
.catalog-notice {
  margin: 14rpx 20rpx 0;
  padding: 18rpx 20rpx;
  border: 1rpx solid #fde7a9;
  border-radius: 15rpx;
  background: #fffbeb;
}
.product-grid {
  padding: 0 20rpx;
  gap: 18rpx;
}
.product-cell {
  width: calc(50% - 9rpx);
  padding: 15rpx;
  border: 1rpx solid #eef2f0;
  border-radius: 22rpx;
  box-shadow: 0 9rpx 26rpx rgba(15, 23, 42, 0.055);
}
.product-thumb {
  height: 210rpx;
  border-radius: 17rpx;
}
.product-name {
  font-size: 27rpx;
  font-weight: 600;
  color: #26342d;
}
.product-price {
  color: #047857;
  font-size: 34rpx;
}
.cart-bar {
  border-top: 0;
  box-shadow: 0 -10rpx 32rpx rgba(15, 23, 42, 0.08);
  padding: 18rpx 24rpx;
}
.cart-cta {
  background: linear-gradient(135deg, #059669, #0d9488);
  box-shadow: 0 8rpx 22rpx rgba(5, 150, 105, 0.22);
}
.flow-overlay {
  background: radial-gradient(circle at 50% 35%, #ecfdf5, #fff 55%);
}
.flow-spinner {
  width: 132rpx;
  height: 132rpx;
  border-width: 10rpx;
  box-shadow: 0 16rpx 44rpx rgba(5, 150, 105, 0.13);
}
.flow-title {
  font-size: 44rpx;
  color: #173026;
}
.flow-device {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: #ecfdf5;
  font-weight: 600;
}
</style>
