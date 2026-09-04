<template>
  <view class="page-root page-fill" :class="{ 'is-landing': showLanding }">
    <!-- 落地页：仅 Tab 进入时展示，柜码直达不经过此页 -->
    <view v-if="showLanding" class="landing">
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
        </view>

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
          <view v-if="lastDeviceId" class="resume-card" @click="startShoppingFlow(lastDeviceId)">
            <text class="resume-title">继续在本柜购物</text>
            <text class="resume-sub">{{ lastDeviceName || lastDeviceId }}</text>
          </view>
          <text class="nearby-link" role="button" @click="goNearby">附近找柜</text>
        </view>

        <view v-if="showManualEntry && !showManual" class="landing-foot">
          <text
            class="manual-link"
            role="button"
            data-testid="manual-device-toggle"
            @click="showManual = true"
          >
            {{ manualEntryLabel }}
          </text>
        </view>
      </view>

      <view v-if="authPromptVisible" class="landing-mask" @click="dismissAuthPrompt">
        <view class="landing-sheet" @click.stop="noop">
          <text class="landing-sheet-title">需要授权</text>
          <text class="landing-sheet-body">扫码开门需先完成微信授权</text>
          <view class="landing-sheet-actions">
            <text class="landing-sheet-btn" @click="dismissAuthPrompt">取消</text>
            <text class="landing-sheet-btn primary" @click="goLoginFromScan">去登录</text>
          </view>
        </view>
      </view>

      <view v-if="showManual" class="landing-mask" @click="showManual = false">
        <view class="landing-sheet" @click.stop="noop">
          <text class="landing-sheet-title">{{ manualEntryLabel }}</text>
          <text class="landing-sheet-label">柜机编号</text>
          <input
            v-model="deviceInput"
            class="sheet-input"
            data-testid="device-code-input"
            aria-label="柜机编号"
            placeholder="例如 CAB-001…"
            placeholder-class="sheet-ph"
          />
          <button
            class="btn-primary btn-block"
            hover-class="btn-hover"
            data-testid="open-door-confirm"
            :loading="opening"
            :disabled="opening"
            @click="confirmDevice"
          >
            {{ opening ? '开门中…' : '确认并开门' }}
          </button>
          <view class="landing-sheet-cancel-wrap">
            <text class="landing-sheet-cancel" @click="showManual = false">取消</text>
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
        <text>{{ catalogNotice }}</text>
      </view>

      <scroll-view scroll-y class="product-scroll" :show-scrollbar="false" enhanced>
        <view v-if="products.length" class="catalog-tools">
          <view class="search-box">
            <input
              v-model="searchKeyword"
              class="search-input"
              placeholder="搜索本柜商品"
              placeholder-class="search-placeholder"
              confirm-type="search"
            />
            <text v-if="searchKeyword" class="search-clear" @click="clearSearchKeyword">×</text>
          </view>
          <scroll-view scroll-x class="category-row" :show-scrollbar="false">
            <view class="category-chip" :class="{ active: !activeCategory }" @click="clearCategory"
              >全部</view
            >
            <view
              v-for="cat in productCategories"
              :key="cat"
              class="category-chip"
              :class="{ active: activeCategory === cat }"
              :data-cat="cat"
              @click="onCategoryChipTap"
              >{{ cat }}</view
            >
          </scroll-view>
        </view>
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
        <view v-else-if="!filteredProducts.length" class="card loading-card catalog-empty">
          <text class="empty-title">未找到匹配商品</text>
          <text class="empty-hint">换个关键词或分类试试</text>
          <view class="empty-actions">
            <text class="empty-link" @click="resetCatalogFilter">查看全部商品</text>
          </view>
        </view>
        <view v-else class="product-grid">
          <view
            v-for="p in filteredProducts"
            :key="p.skuId"
            class="product-cell"
            :class="{ selected: (selected[p.skuId] || 0) > 0 }"
            role="button"
            :aria-label="`选择 ${p.skuName}`"
            :data-sku-id="p.skuId"
            @click="onProductCellTap"
          >
            <view class="product-cell-inner">
              <view class="product-thumb" :class="'cat-' + thumbTone(p)">
                <image
                  v-if="showThumb(p)"
                  class="product-img"
                  :src="productThumb(p)"
                  mode="aspectFill"
                  @error="onThumbError(p.skuId)"
                />
                <text v-else class="product-mark">{{ productGlyph(p) }}</text>
                <text
                  v-if="sessionActive && state === 'SHOPPING' && mockEnabled && selectedQty(p) > 0"
                  class="product-badge"
                  >{{ selectedQty(p) }}</text
                >
              </view>
              <text class="product-name">{{ p.skuName }}</text>
              <text class="product-price">{{ fmtMoney(p.priceCents) }}</text>
              <text v-if="p.category" class="product-cat">{{ p.category }}</text>
              <view
                v-if="sessionActive && state === 'SHOPPING' && mockEnabled"
                class="product-stepper"
                @click.stop="noop"
              >
                <text
                  class="stepper-btn"
                  role="button"
                  :aria-label="`减少 ${p.skuName}`"
                  :data-testid="`product-step-minus-${p.skuId}`"
                  :data-sku-id="p.skuId"
                  @click.stop="onRemoveProductTap"
                  >−</text
                >
                <text class="stepper-qty">{{ selectedQty(p) }}/{{ stockOf(p) }}</text>
                <text
                  class="stepper-btn plus"
                  :class="{ disabled: !canAddProduct(p) }"
                  role="button"
                  :aria-disabled="(!canAddProduct(p)).toString()"
                  :aria-label="`增加 ${p.skuName}`"
                  :data-testid="`product-step-plus-${p.skuId}`"
                  :data-sku-id="p.skuId"
                  @click.stop="onAddProductTap"
                  >+</text
                >
              </view>
            </view>
          </view>
        </view>
        <view class="list-bottom" />
      </scroll-view>

      <view class="cart-bar">
        <view
          class="cart-info"
          :class="{ tappable: sessionActive && state === 'SHOPPING' }"
          @click="onCartBarInfoTap"
        >
          <text class="cart-hint">{{ cartBarHint }}</text>
          <text v-if="sessionActive && state === 'SHOPPING'" class="cart-sub">{{
            cartBarSub
          }}</text>
        </view>
        <view
          v-if="sessionActive && state === 'SHOPPING'"
          class="cart-demo"
          :class="{ live: !mockEnabled }"
        >
          <view
            class="cart-demo-info tappable"
            data-testid="open-live-cart-sheet"
            @click="openCartSheet"
          >
            <view class="cart-badge-row">
              <text class="cart-badge">{{ shoppingCartQty }}</text>
              <text class="cart-demo-label">{{ shoppingCartLabel }}</text>
            </view>
            <text class="cart-demo-amt">{{ shoppingCartAmount }}</text>
          </view>
          <button
            v-if="mockEnabled"
            class="cart-close-btn"
            hover-class="btn-hover"
            :loading="closingDoor"
            :disabled="closingDoor"
            @click.stop="closeDoorDemo"
          >
            关门结算
          </button>
          <view v-else class="cart-status-chip soft">请关门</view>
        </view>
        <view v-else-if="sessionActive" class="cart-status-chip" :class="stateTone">
          {{ cartBarAction }}
        </view>
        <button
          v-else-if="canReopen"
          class="cart-cta"
          hover-class="btn-hover"
          data-testid="open-door-again"
          :loading="opening"
          :disabled="opening"
          @click="reopenShop"
        >
          {{ opening ? '开门中…' : '再次开门' }}
        </button>
      </view>
    </view>

    <LiveCartSheet
      :visible="cartSheetVisible"
      :items="shoppingCartLines"
      :total-qty="shoppingCartQty"
      :total-amount-cents="shoppingCartAmountCents"
      :mock-mode="mockEnabled"
      @close="cartSheetVisible = false"
    />

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
import { onHide, onLoad, onReady, onShow, onUnload } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import OpenPrepDrawer from '@/components/open-prep-drawer.vue';
import LiveCartSheet, { type LiveCartSheetLine } from '@/components/live-cart-sheet.vue';
import {
  clearOpenAttempt,
  consumerApi,
  ensureConsumerAuth,
  getConsumerToken
} from '@/utils/consumer-api';
import { parseCabinetScan, parseLaunchOptions } from '@aicabinet/shared-uni/qrcode';
import { getBelowCapsulePadPx } from '@aicabinet/shared-uni/status-bar';
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
import { parseQuery } from '@aicabinet/shared-uni/query';
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
/** 真机：标题从微信胶囊下方起排，避免顶穿状态栏 */
const landingHeadStyle = ref({
  paddingTop: getBelowCapsulePadPx(10) + 'px'
});
function refreshLandingPad() {
  landingHeadStyle.value = {
    paddingTop: getBelowCapsulePadPx(10) + 'px'
  };
}
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
const searchKeyword = ref('');
const activeCategory = ref('');
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
const ACTIVE_SESSION_KEY = 'active_session_id';
const REVIEW_SESSION_KEY = 'last_disputed_session_id';
const reviewSessionId = ref(String(uni.getStorageSync(REVIEW_SESSION_KEY) || ''));

function setActiveSession(id: string) {
  sessionId.value = id;
  uni.setStorageSync(ACTIVE_SESSION_KEY, id);
}

function clearActiveSession() {
  sessionId.value = '';
  try {
    uni.removeStorageSync(ACTIVE_SESSION_KEY);
  } catch {
    /* ignore */
  }
}

function setReviewSession(id: string) {
  reviewSessionId.value = id;
  uni.setStorageSync(REVIEW_SESSION_KEY, id);
}

function clearReviewSession() {
  reviewSessionId.value = '';
  try {
    uni.removeStorageSync(REVIEW_SESSION_KEY);
  } catch {
    /* ignore */
  }
}

const reviewTicket = ref<DisputeTicketDto | null>(null);
const reviewCopy = computed(() => consumerDisputeReviewCopy(reviewTicket.value));
const servicePhone = ref('400-888-0018');
const openingSeconds = ref(90);
const brokenThumbs = ref<Record<string, boolean>>({});
const lastDeviceId = ref('');
const lastDeviceName = ref('');
const authPromptVisible = ref(false);
const showPrepDrawer = ref(false);
const prepAccount = ref<AccountDto | null>(null);
const recognitionDeferred = ref(false);
const recognitionElapsedSec = ref(0);
/** 演示点选：skuId -> 件数；实际扣款仍以关门识别为准 */
const selected = ref<Record<string, number>>({});
const mockEnabled = ref(false);
const closingDoor = ref(false);
const finishingSession = ref(false);
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

const productCategories = computed(() => {
  const cats = new Set<string>();
  for (const p of products.value) {
    const cat = String(p.category || '').trim();
    if (cat) cats.add(cat);
  }
  return Array.from(cats).sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'));
});

const filteredProducts = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase();
  const cat = activeCategory.value;
  return products.value.filter((p) => {
    if (cat && String(p.category || '').trim() !== cat) return false;
    if (
      kw &&
      !String(p.skuName || '')
        .toLowerCase()
        .includes(kw)
    )
      return false;
    return true;
  });
});

function resetCatalogFilter() {
  searchKeyword.value = '';
  activeCategory.value = '';
}

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
  if (state.value === 'SHOPPING') {
    return mockEnabled.value ? '柜门已开（演示·模拟取货）' : '柜门已开，请自由取货';
  }
  if (state.value === 'OPENING' || state.value === 'CREATED') return '正在开门，请稍候';
  if (['RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(state.value)) return '正在识别结算';
  if (canReopen.value) return '本柜价目（浏览）';
  return '选好商品后请关好柜门';
});

const shoppingBannerSub = computed(() => {
  if (state.value === 'SHOPPING') {
    return mockEnabled.value
      ? '演示：点选模拟取货，底栏可看清单；关门后按清单结算'
      : '无需在手机上点选，柜内取货后关门自动结算';
  }
  if (['RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(state.value)) {
    return '可先离开，账单会在「订单」中展示';
  }
  if (canReopen.value) return '上一单已结束。可先浏览价目，再买需再次开门';
  return '实际扣款以视觉识别结果为准';
});

const catalogNotice = computed(() => {
  if (mockEnabled.value && state.value === 'SHOPPING') {
    return '演示·模拟取货：点选后底栏查看清单，再点关门结算';
  }
  if (canReopen.value) {
    return '本柜价目可先浏览；再买需要重新开门，因为上一单关门后柜门已锁';
  }
  return '本柜价目仅供参考，请直接取货；实付以关门识别为准';
});

const cartBarHint = computed(() => {
  if (state.value === 'SHOPPING') {
    return mockEnabled.value ? '模拟取货中 · 点右侧看清单' : '拿了就走 · 点右侧看识别清单';
  }
  if (!sessionActive.value) return '浏览价目无需开门';
  return '关门后自动识别并扣款';
});

const cartBarSub = computed(() => {
  if (!sessionActive.value || state.value !== 'SHOPPING') return '';
  if (mockEnabled.value) return '步进器仅演示；正式环境靠视觉识别';
  if (liveCartQty.value > 0) return '识别中实时更新，拿错可放回';
  return '取货后识别会出现在底栏，关门结算';
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
  refreshLandingPad();
  let launch = parseLaunchOptions((opts || {}) as Record<string, string>);
  // H5：兼容 ?deviceId= / hash 查询（柜门二维码 deep link）
  if (!launch.deviceId && typeof globalThis !== 'undefined') {
    try {
      const fromSearchMap = parseQuery(globalThis.location.search);
      const fromSearch = parseLaunchOptions(fromSearchMap);
      if (fromSearch.deviceId) {
        launch = fromSearch;
      } else if (globalThis.location.hash.includes('deviceId=')) {
        const hashQuery = globalThis.location.hash.split('?')[1] || '';
        const fromHash = parseLaunchOptions(parseQuery(hashQuery));
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
    if (launch.autoOpen) {
      // 柜门深链 / autoOpen=1 才自动进购物流（B-23）
      await startShoppingFlow(launch.deviceId, launch.channel);
    } else {
      deviceInput.value = launch.deviceId;
      showManual.value = true;
    }
  }
});

onReady(() => refreshLandingPad());

async function resumeReopenDeviceFlow(): Promise<boolean> {
  const reopen = uni.getStorageSync('reopen_device_id');
  if (!reopen) return false;
  uni.removeStorageSync('reopen_device_id');
  const ch = uni.getStorageSync('reopen_entry_channel');
  if (ch) {
    uni.removeStorageSync('reopen_entry_channel');
    entryChannel.value = resolveEntryChannel(ch);
  }
  await startShoppingFlow(reopen, ch || undefined);
  return true;
}

async function resumeBrowseDeviceFlow() {
  const browse = uni.getStorageSync('browse_device_id');
  if (!browse) return;
  uni.removeStorageSync('browse_device_id');
  await showDeviceCatalog(String(browse));
}

async function onAuthenticatedShow() {
  await resumePendingRechargeIfAny();
  await refreshReviewState();
  if (scanned.value && deviceId.value) refreshDeviceStatus();
  if (await resumeReopenDeviceFlow()) return;
  await resumeBrowseDeviceFlow();
  restoreActiveSession();
}

onShow(async () => {
  refreshLandingPad();
  lastDeviceId.value = uni.getStorageSync('last_device_id') || '';
  lastDeviceName.value = uni.getStorageSync('last_device_name') || '';
  await loadConsumerConfig();
  await ensureConsumerAuth();
  if (getConsumerToken()) {
    await onAuthenticatedShow();
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
  scanned.value = false;
  enteringFlow.value = false;
  showManual.value = false;
  deviceId.value = '';
  deviceName.value = '';
  deviceStatusText.value = '';
  products.value = [];
  resetCatalogFilter();
}

type DeviceAvailability = {
  online: boolean;
  reason: string;
  blocked: boolean;
};

function normalizeCabinetId(id: string): string {
  return id.trim().toUpperCase();
}

function isCabinetIdInvalid(cabinetId: string): boolean {
  return !/^[A-Z0-9][A-Z0-9_-]{1,63}$/.test(cabinetId);
}

function applyDeviceAvailability(
  status: Awaited<ReturnType<typeof consumerApi.deviceStatus>>
): DeviceAvailability {
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
  return { online, reason, blocked: !online || status.available === false };
}

function blockedDeviceLandingError(
  online: boolean,
  reason: string
): { kind: OpenErrorKind; msg: string; toastTitle: string } {
  if (!online) {
    return {
      kind: 'other',
      msg: '该柜机当前离线，请稍后再试或更换其他柜机。',
      toastTitle: '暂时无法开门'
    };
  }
  if (reason === 'LOCKED') {
    return {
      kind: 'device_paused',
      msg: '柜机已暂停营业，请稍后再试或换一台',
      toastTitle: '柜机暂停营业'
    };
  }
  if (reason === 'REPLENISHMENT') {
    return {
      kind: 'device_busy',
      msg: '柜机正在补货，请稍后再试',
      toastTitle: '柜机正忙'
    };
  }
  if (reason === 'SESSION') {
    return {
      kind: 'device_busy',
      msg: '柜机正在被使用，请稍后再试',
      toastTitle: '柜机正忙'
    };
  }
  return { kind: 'other', msg: deviceStatusText.value, toastTitle: '暂时无法开门' };
}

function markOpenFailed(cabinetId: string) {
  scanned.value = false;
  deviceId.value = '';
  lastFailedDeviceId.value = cabinetId;
  lastFailedChannel.value = entryChannel.value;
}

function rejectBlockedDevice(cabinetId: string, avail: DeviceAvailability): boolean {
  if (!avail.blocked) return false;
  markOpenFailed(cabinetId);
  const err = blockedDeviceLandingError(avail.online, avail.reason);
  setLandingError(err.msg, err.kind);
  uni.showToast({ title: err.toastTitle, icon: 'none' });
  return true;
}

function applyProductsResult(result: PromiseSettledResult<DeviceProduct[]>) {
  if (result.status === 'fulfilled') {
    products.value = normalizeProducts(result.value);
    clampSelectionToStock();
    return;
  }
  products.value = [];
  resetCatalogFilter();
  uni.showToast({ title: formatError(result.reason), icon: 'none' });
}

async function handleSessionOpenResult(
  cabinetId: string,
  sessionResult: PromiseSettledResult<SessionDto>
): Promise<boolean> {
  if (sessionResult.status === 'fulfilled') {
    const s = sessionResult.value;
    lastFailedDeviceId.value = '';
    lastFailedChannel.value = null;
    setActiveSession(s.sessionId);
    applySessionView(s);
    startPoll();
    return true;
  }
  if (await adoptOrphanSession(cabinetId)) return true;
  markOpenFailed(cabinetId);
  const failReason = sessionResult.reason;
  const kind = classifyOpenError(failReason);
  setLandingError(formatError(failReason), kind);
  uni.showToast({ title: landingError.value, icon: 'none' });
  return false;
}

function resetDeviceOnOpenFailure(cabinetId: string) {
  if (sessionId.value) return;
  scanned.value = false;
  deviceId.value = '';
  deviceName.value = '';
  deviceStatusText.value = '';
  products.value = [];
  resetCatalogFilter();
  lastFailedDeviceId.value = cabinetId;
  lastFailedChannel.value = entryChannel.value;
}

function beginCabinetEntry(cabinetId: string, scanChannel?: string | null): boolean {
  if (!cabinetId || opening.value || enteringFlow.value) return false;
  if (isCabinetIdInvalid(cabinetId)) {
    setLandingError('柜机编号无效，请扫描柜门二维码或输入如 CAB-001。', 'device_not_found');
    lastFailedDeviceId.value = '';
    uni.showToast({ title: '柜机编号无效', icon: 'none' });
    return false;
  }
  const resolved = resolveEntryChannel(scanChannel) || entryChannel.value;
  if (resolved) entryChannel.value = resolved;
  return true;
}

async function ensureAuthForOpen(cabinetId: string): Promise<boolean> {
  if (await ensureConsumerAuth()) return true;
  uni.setStorageSync('reopen_device_id', cabinetId);
  if (entryChannel.value) {
    uni.setStorageSync('reopen_entry_channel', entryChannel.value);
  }
  authPromptVisible.value = true;
  return false;
}

async function prepareDeviceForOpen(cabinetId: string): Promise<boolean> {
  opening.value = true;
  deviceId.value = cabinetId;
  scanned.value = true;
  const status = await consumerApi.deviceStatus(cabinetId);
  deviceName.value = status.deviceName || cabinetId;
  const pre = Number(status.preauthCents);
  devicePreauthCents.value = Number.isFinite(pre) && pre > 0 ? pre : null;
  const avail = applyDeviceAvailability(status);
  return !rejectBlockedDevice(cabinetId, avail);
}

async function openDeviceSession(cabinetId: string) {
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
  applyProductsResult(productsResult);
  await handleSessionOpenResult(cabinetId, sessionResult);
}

async function startShoppingFlow(id: string, scanChannel?: string | null) {
  const cabinetId = normalizeCabinetId(id);
  if (!beginCabinetEntry(cabinetId, scanChannel)) return;

  enteringFlow.value = true;
  landingError.value = '';
  landingErrorKind.value = 'other';

  try {
    if (!(await ensureAuthForOpen(cabinetId))) return;
    if (!(await ensureCanOpenDoor())) return;
    if (!(await prepareDeviceForOpen(cabinetId))) return;
    await openDeviceSession(cabinetId);
  } catch (e) {
    resetDeviceOnOpenFailure(cabinetId);
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
      setActiveSession(s.sessionId);
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

function goNearby() {
  uni.navigateTo({ url: '/pages/nearby/nearby' });
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

function dismissAuthPrompt() {
  authPromptVisible.value = false;
}

function goLoginFromScan() {
  authPromptVisible.value = false;
  uni.navigateTo({
    url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/index/index')
  });
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
    mockEnabled.value = String(cfg?.mockEnabled) === 'true';
  } catch {
    /* 使用默认客服电话 */
  }
}

async function refreshReviewState() {
  const sid = String(uni.getStorageSync(REVIEW_SESSION_KEY) || '');
  if (!sid || !getConsumerToken()) {
    clearReviewSession();
    reviewTicket.value = null;
    return;
  }
  try {
    const disputes = await consumerApi.listMyDisputes();
    const ticket = disputes.find((d) => d.sessionId === sid);
    if (!ticket || ticket.status !== 'OPEN') {
      clearReviewSession();
      reviewTicket.value = null;
      if (ticket?.status === 'RESOLVED') {
        showDisputeResolvedToast(ticket);
      }
      return;
    }
    setReviewSession(sid);
    reviewTicket.value = ticket;
  } catch {
    // 弱网不造假票，避免误显示「审核中」；保留 storage 供下次 onShow 重试
    reviewSessionId.value = '';
    reviewTicket.value = null;
  }
}

function dismissReview() {
  clearReviewSession();
  reviewTicket.value = null;
}

function goReviewDetail() {
  const sid = reviewSessionId.value || String(uni.getStorageSync(REVIEW_SESSION_KEY) || '');
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
      const raw = String(res.result || res.path || '').trim();
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
      '.sheet-input, .uni-input-input, input'
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
    products.value = normalizeProducts(await consumerApi.deviceProducts(deviceId.value));
    clampSelectionToStock();
  } catch (e) {
    uni.showToast({ title: formatError(e), icon: 'none' });
  } finally {
    productsLoading.value = false;
  }
}

function normalizeProducts(list: DeviceProduct[] | null | undefined): DeviceProduct[] {
  return (list || [])
    .map((p) => {
      const n = Number(p.quantity);
      // C-22：非法库存不静默归 0，直接丢弃该行避免假库存
      if (!Number.isFinite(n)) return null;
      return {
        ...p,
        quantity: Math.max(0, Math.floor(n))
      };
    })
    .filter((p): p is DeviceProduct => p != null);
}

function clampSelectionToStock() {
  let changed = false;
  const next: Record<string, number> = { ...selected.value };
  for (const [skuId, qty] of Object.entries(next)) {
    const p = products.value.find((x) => x.skuId === skuId);
    const max = p ? stockOf(p) : 0;
    const want = Math.max(0, qty || 0);
    const capped = Math.min(want, max);
    if (capped !== want) {
      changed = true;
      if (capped > 0) next[skuId] = capped;
      else delete next[skuId];
    }
  }
  if (changed) selected.value = next;
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
    const remoteState = String(s.activeSessionState || '').toUpperCase();
    // 设备侧会话已进购物时，纠正本地仍停在开门中的文案
    if (
      remoteState === 'SHOPPING' &&
      (state.value === 'OPENING' || state.value === 'CREATED' || !state.value)
    ) {
      state.value = 'SHOPPING';
      stateLabel.value = sessionStateLabel('SHOPPING');
      stateHint.value = sessionStateHint('SHOPPING');
      stateTone.value = sessionStateTone('SHOPPING');
      stopOpeningCountdown();
    }
    // 仅真正离线视为 offline；暂停营业/占用走业务错误，避免误报「离线」
    deviceOffline.value = !online;
    if (!online) {
      deviceStatusText.value = '离线';
    } else if (state.value === 'SHOPPING' || remoteState === 'SHOPPING') {
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

/** 只展示本柜价目，不创建会话、不开门。结算后「返回本柜」走这里。 */
async function showDeviceCatalog(id: string) {
  const cabinetId = id.trim().toUpperCase();
  if (!cabinetId || sessionActive.value || opening.value || enteringFlow.value) return;
  deviceId.value = cabinetId;
  scanned.value = true;
  productsLoading.value = true;
  try {
    const status = await consumerApi.deviceStatus(cabinetId);
    deviceName.value = status.deviceName || cabinetId;
    await refreshDeviceStatus();
    products.value = normalizeProducts(await consumerApi.deviceProducts(cabinetId));
    clampSelectionToStock();
    uni.setStorageSync('last_device_id', cabinetId);
    uni.setStorageSync('last_device_name', deviceName.value);
    lastDeviceId.value = cabinetId;
    lastDeviceName.value = deviceName.value;
  } catch (e) {
    uni.showToast({ title: formatError(e), icon: 'none' });
  } finally {
    productsLoading.value = false;
  }
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
    resetCatalogFilter();
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
    clearActiveSession();
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
  clearActiveSession();
  state.value = '';
  stateLabel.value = '';
  stateHint.value = '';
  stateTone.value = 'idle';
  recognitionDeferred.value = false;
  selected.value = {};
  liveCartQty.value = 0;
  liveCartAmountCents.value = 0;
  liveCartItems.value = [];
  cartSheetVisible.value = false;
  stopRecognitionTimer();
}

function clearSearchKeyword() {
  searchKeyword.value = '';
}

function clearCategory() {
  activeCategory.value = '';
}

function onCategoryChipTap(e: { currentTarget?: { dataset?: Record<string, string> } }) {
  const cat = String(e?.currentTarget?.dataset?.cat ?? '');
  if (!cat) return;
  activeCategory.value = activeCategory.value === cat ? '' : cat;
}

function productBySkuId(skuId: string): DeviceProduct | undefined {
  return products.value.find((p) => p.skuId === skuId);
}

function onProductCellTap(e: { currentTarget?: { dataset?: Record<string, string> } }) {
  const skuId = String(e?.currentTarget?.dataset?.skuId ?? '');
  const p = productBySkuId(skuId);
  if (p) addProduct(p);
}

function onAddProductTap(e: { currentTarget?: { dataset?: Record<string, string> } }) {
  onProductCellTap(e);
}

function onRemoveProductTap(e: { currentTarget?: { dataset?: Record<string, string> } }) {
  const skuId = String(e?.currentTarget?.dataset?.skuId ?? '');
  const p = productBySkuId(skuId);
  if (p) removeProduct(p);
}

function noop() {
  /* 阻止步进器点击冒泡到商品卡片 */
}

function selectedQty(p: DeviceProduct) {
  return selected.value[p.skuId] || 0;
}

/** 柜内可售库存；缺字段按 0，禁止演示默认放宽到 9。 */
function stockOf(p: DeviceProduct) {
  const raw = Number(p.quantity);
  if (Number.isFinite(raw) && raw >= 0) return Math.floor(raw);
  return 0;
}

function canAddProduct(p: DeviceProduct) {
  return selectedQty(p) < stockOf(p);
}

function addProduct(p: DeviceProduct) {
  if (!sessionActive.value) return;
  const max = stockOf(p);
  const cur = selected.value[p.skuId] || 0;
  if (max <= 0) {
    uni.showToast({ title: '暂无可选', icon: 'none' });
    return;
  }
  if (cur >= max) {
    uni.showToast({ title: '无法再加', icon: 'none' });
    return;
  }
  selected.value = { ...selected.value, [p.skuId]: cur + 1 };
}

function removeProduct(p: DeviceProduct) {
  const next = Math.max(0, (selected.value[p.skuId] || 0) - 1);
  selected.value = { ...selected.value, [p.skuId]: next };
}

const selectedCount = computed(() => Object.values(selected.value).reduce((sum, q) => sum + q, 0));

const liveCartQty = ref(0);
const liveCartAmountCents = ref(0);
const liveCartItems = ref<LiveCartSheetLine[]>([]);
const cartSheetVisible = ref(false);

const selectedAmountCents = computed(() => {
  const byId = new Map(products.value.map((p) => [p.skuId, p]));
  let total = 0;
  for (const [skuId, qty] of Object.entries(selected.value)) {
    const p = byId.get(skuId);
    if (p) total += p.priceCents * qty;
  }
  return total;
});

const selectedLines = computed<LiveCartSheetLine[]>(() => {
  const byId = new Map(products.value.map((p) => [p.skuId, p]));
  const lines: LiveCartSheetLine[] = [];
  for (const [skuId, qty] of Object.entries(selected.value)) {
    if (!qty) continue;
    const p = byId.get(skuId);
    if (!p) continue;
    lines.push({
      skuId,
      skuName: p.skuName,
      quantity: qty,
      unitPriceCents: p.priceCents,
      lineAmountCents: p.priceCents * qty
    });
  }
  return lines;
});

const shoppingCartQty = computed(() =>
  mockEnabled.value ? selectedCount.value : liveCartQty.value
);
const shoppingCartAmountCents = computed(() =>
  mockEnabled.value ? selectedAmountCents.value : liveCartAmountCents.value
);
const shoppingCartAmount = computed(() => fmtMoney(shoppingCartAmountCents.value));
const shoppingCartLines = computed(() =>
  mockEnabled.value ? selectedLines.value : liveCartItems.value
);
const shoppingCartLabel = computed(() => {
  if (mockEnabled.value) {
    return shoppingCartQty.value > 0 ? `模拟 ${shoppingCartQty.value} 件 · 明细` : '点看模拟清单';
  }
  return shoppingCartQty.value > 0 ? `识别中 ${shoppingCartQty.value} 件 · 明细` : '点看识别清单';
});

function openCartSheet() {
  if (state.value !== 'SHOPPING') return;
  cartSheetVisible.value = true;
}

function onCartBarInfoTap() {
  if (sessionActive.value && state.value === 'SHOPPING') openCartSheet();
}

async function refreshLiveCart() {
  if (!sessionId.value || state.value !== 'SHOPPING' || mockEnabled.value) {
    return;
  }
  try {
    const cart = await consumerApi.getLiveCart(sessionId.value);
    liveCartQty.value = Number(cart?.totalQty ?? 0);
    liveCartAmountCents.value = Number(cart?.totalAmountCents ?? 0);
    liveCartItems.value = (cart?.items || [])
      .filter((it) => Number(it.quantity) > 0)
      .map((it) => ({
        skuId: String(it.skuId),
        skuName: it.skuName,
        quantity: Number(it.quantity ?? 0),
        unitPriceCents: Number(it.unitPriceCents ?? 0),
        lineAmountCents: Number(it.lineAmountCents ?? 0)
      }));
  } catch {
    // 识别推送未就绪时忽略
  }
}

/** 演示关门：先把点选同步到会话购物车，再触发关门结算（后端 mockEnabled 才放行）。 */
async function closeDoorDemo() {
  if (!sessionId.value || closingDoor.value) return;
  closingDoor.value = true;
  const sid = sessionId.value;
  try {
    const byId = new Map(products.value.map((p) => [p.skuId, p]));
    let clamped = false;
    const items = Object.entries(selected.value)
      .map(([skuId, qty]) => {
        const p = byId.get(skuId);
        const max = p ? stockOf(p) : 0;
        const want = Math.max(0, qty || 0);
        const next = Math.min(want, max);
        if (next !== want) clamped = true;
        return { skuId, qty: next };
      })
      .filter((it) => it.qty > 0);
    if (clamped) {
      const nextSel: Record<string, number> = {};
      for (const it of items) nextSel[it.skuId] = it.qty;
      selected.value = nextSel;
      uni.showToast({ title: '已按库存调整数量', icon: 'none' });
    }
    // 始终同步点选（含空列表），避免上次点选残留导致误扣/进审单
    await consumerApi.updateSessionCart(sid, { items });
    const s = await consumerApi.demoCloseSession(sid);
    applySessionView(s);
    if (s.state === 'COMPLETED' || s.state === 'DISPUTED') {
      stopPoll();
      await finishSession(s.state, sid);
      return;
    }
    if (['FAILED', 'CANCELLED'].includes(s.state)) {
      stopPoll();
      clearActiveSession();
      clearOpenAttempt();
      clearSessionUi();
      uni.showToast({
        title: sessionStateHint(s.state) || '购物未完成',
        icon: 'none',
        duration: 2800
      });
      return;
    }
    startPoll();
    uni.showToast({ title: '已关门，结算中…', icon: 'none' });
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '关门失败，请重试', icon: 'none' });
  } finally {
    closingDoor.value = false;
  }
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
  if (finishingSession.value) return;
  finishingSession.value = true;
  try {
    clearActiveSession();
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
        totalCents = order?.totalAmountCents ?? 0;
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
      setReviewSession(sid);
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
  } finally {
    finishingSession.value = false;
  }
}

function applySessionView(s: SessionDto) {
  state.value = s.state;
  stateLabel.value = sessionStateLabel(s.state);
  stateHint.value = sessionStateHint(s.state);
  stateTone.value = sessionStateTone(s.state);
  if (s.deviceName) {
    deviceName.value = s.deviceName;
  }
  if (s.deviceId && !deviceId.value) {
    deviceId.value = s.deviceId;
  }
  if (s.state === 'OPENING' || s.state === 'CREATED') startOpeningCountdown(s.createdAt);
  else stopOpeningCountdown();
  if (['RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(s.state)) {
    startRecognitionTimer(s.closeTime || s.createdAt);
  } else {
    stopRecognitionTimer();
    recognitionDeferred.value = false;
  }
  // 状态一变就立刻改顶栏文案；勿走 30s 节流，否则会卡在「正在开门」
  if (s.state === 'SHOPPING') {
    opening.value = false;
    deviceStatusText.value = '门已开 · 购物中';
    deviceOffline.value = false;
  } else if (s.deviceId && deviceId.value) {
    refreshDeviceStatusThrottled(s.deviceId);
  }
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
  const saved = uni.getStorageSync(ACTIVE_SESSION_KEY);
  if (sessionId.value) return;
  try {
    const s = saved ? await consumerApi.getSession(saved) : await consumerApi.activeSession();
    if (!s) return;
    if (['COMPLETED', 'FAILED', 'CANCELLED', 'DISPUTED'].includes(s.state)) {
      clearActiveSession();
      clearOpenAttempt();
      if (s.state === 'DISPUTED') {
        setReviewSession(s.sessionId);
        void requestDisputeSubscribe();
      }
      return;
    }
    setActiveSession(s.sessionId);
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
      clearActiveSession();
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
      if (s.state === 'SHOPPING') {
        await refreshLiveCart();
      } else {
        liveCartQty.value = 0;
        liveCartAmountCents.value = 0;
        liveCartItems.value = [];
        cartSheetVisible.value = false;
      }
      if (s.state === 'COMPLETED' || s.state === 'DISPUTED') {
        stopPoll();
        const sid = sessionId.value;
        await finishSession(s.state, sid);
      } else if (['FAILED', 'CANCELLED'].includes(s.state)) {
        stopPoll();
        const hint =
          sessionStateHint(s.state) || (s.state === 'CANCELLED' ? '会话已取消' : '购物未完成');
        clearActiveSession();
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
  max-height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  overflow: hidden;
  background: #ffffff;
  position: relative;
}
.page-root.is-landing {
  background: var(--brand-deep, #064e3b);
}

.landing {
  position: relative;
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--brand-deep, #064e3b);
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
  /* 同一绿色仅调透明度，杜绝灰绿/青绿断层 */
  background: linear-gradient(
    180deg,
    rgba(6, 78, 59, 0.72) 0%,
    rgba(6, 78, 59, 0.45) 45%,
    rgba(6, 78, 59, 0.82) 100%
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
  font-size: 44rpx;
  font-weight: 800;
  color: #ffffff;
  display: block;
  letter-spacing: 2rpx;
}
.tagline {
  font-size: 26rpx;
  color: rgba(255, 255, 255, 0.9);
  margin-top: 8rpx;
  display: block;
}
.pay-badge {
  display: inline-flex;
  align-items: center;
  gap: 6rpx;
  margin-top: 12rpx;
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  background: rgba(6, 78, 59, 0.55);
  border: 1rpx solid rgba(255, 255, 255, 0.32);
}
.pay-badge-icon {
  color: #ffffff;
  font-size: 22rpx;
  font-weight: 700;
}
.pay-badge-text {
  color: #ffffff;
  font-size: 20rpx;
}

.resume-card {
  margin-top: 28rpx;
  width: 100%;
  max-width: 520rpx;
  background: rgba(255, 255, 255, 0.14);
  border-radius: 16rpx;
  padding: 16rpx 20rpx;
  border: 1rpx solid rgba(255, 255, 255, 0.28);
  box-sizing: border-box;
  text-align: center;
}
.resume-title {
  font-size: 26rpx;
  font-weight: 600;
  color: #ffffff;
  display: block;
  text-align: center;
}
.resume-sub {
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.78);
  margin-top: 2rpx;
  display: block;
  text-align: center;
}

.landing-action {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
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
}
.scan-circle::after {
  border: none;
}
.scan-circle-hover {
  opacity: 0.9;
  transform: scale(0.98);
}
.scan-circle-inner {
  width: 168rpx;
  height: 168rpx;
  border-radius: 50%;
  /* 与页面深绿统一，不再用白底 */
  background: linear-gradient(145deg, var(--brand, #047857), var(--brand-deep, #064e3b));
  border: 2rpx solid rgba(255, 255, 255, 0.22);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow:
    0 10rpx 28rpx rgba(6, 78, 59, 0.45),
    0 0 0 10rpx rgba(255, 255, 255, 0.1);
}
.scan-icon-box {
  width: 72rpx;
  height: 72rpx;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.scan-circle-text {
  margin-top: 16rpx;
  font-size: 30rpx;
  font-weight: 700;
  color: #ffffff;
}
.scan-tip {
  margin-top: 10rpx;
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.88);
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
  font-size: 22rpx;
  color: #ffffff;
  padding: 8rpx 20rpx;
  border-radius: 999rpx;
  background: rgba(6, 78, 59, 0.55);
  border: 1rpx solid rgba(255, 255, 255, 0.32);
}
.nearby-link {
  display: block;
  margin: 20rpx auto 0;
  text-align: center;
  font-size: 26rpx;
  color: rgba(255, 255, 255, 0.92);
  text-decoration: underline;
  text-underline-offset: 4rpx;
}
.btn-primary {
  margin: 0;
  width: 100%;
  background: linear-gradient(135deg, var(--brand, #047857), var(--brand, #047857));
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
  font-weight: 600;
  line-height: 1.2;
  min-height: 88rpx;
  height: 88rpx;
  box-shadow: 0 10rpx 28rpx rgba(5, 150, 105, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
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
  background: #ffffff;
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
  margin: 18rpx 20rpx 0;
  padding: 25rpx;
  background: #fff;
  border: 1rpx solid #edf2ef;
  border-radius: 22rpx;
  box-shadow: 0 9rpx 28rpx rgba(15, 23, 42, 0.055);
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
  color: var(--brand-wx, #07c160);
  display: block;
  margin-top: 7rpx;
  font-weight: 600;
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
  background: linear-gradient(135deg, var(--brand-soft, #ecfdf5), var(--brand-soft, #ecfdf5));
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
  color: var(--brand-deep, #064e3b);
}
.shopping-banner.wait .shopping-banner-title {
  color: #92400e;
}
.shopping-banner-sub {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: var(--brand, #047857);
  line-height: 1.4;
}
.shopping-banner.wait .shopping-banner-sub {
  color: #a16207;
}
.catalog-notice {
  margin: 14rpx 20rpx 0;
  padding: 18rpx 20rpx;
  background: #fffbeb;
  border: 1rpx solid #fde7a9;
  border-radius: 15rpx;
  font-size: 24rpx;
  color: #8c6d1f;
  line-height: 1.4;
}

.catalog-tools {
  padding: 0 16rpx 4rpx;
}
.search-box {
  position: relative;
  display: flex;
  align-items: center;
  height: 72rpx;
  padding: 0 28rpx;
  border-radius: 36rpx;
  background: #fff;
  border: 1rpx solid #e2e8f0;
  box-shadow: 0 4rpx 16rpx rgba(15, 118, 110, 0.06);
}
.search-input {
  flex: 1;
  min-width: 0;
  height: 100%;
  font-size: 26rpx;
  color: #0f172a;
}
.search-placeholder {
  color: #94a3b8;
}
.search-clear {
  padding: 6rpx 4rpx 6rpx 16rpx;
  color: #94a3b8;
  font-size: 34rpx;
  line-height: 1;
}
.category-row {
  display: flex;
  flex-wrap: nowrap;
  white-space: nowrap;
  margin-top: 16rpx;
  width: 100%;
  height: 64rpx;
  box-sizing: border-box;
}
.category-chip {
  display: inline-flex;
  align-items: center;
  height: 56rpx;
  padding: 0 26rpx;
  margin-right: 12rpx;
  border-radius: 28rpx;
  background: #f1f5f9;
  color: #475569;
  font-size: 24rpx;
  font-weight: 600;
  flex-shrink: 0;
}
.category-chip.active {
  background: #0f766e;
  color: #fff;
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
  color: var(--brand, #047857);
  font-weight: 650;
}

.product-grid {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  width: 100%;
  box-sizing: border-box;
  padding: 0 12rpx 12rpx;
}
.product-cell {
  flex: 0 0 33.333%;
  max-width: 33.333%;
  width: 33.333%;
  box-sizing: border-box;
  padding: 6rpx;
  background: transparent;
  border: none;
  border-radius: 0;
  display: flex;
  flex-direction: column;
  align-items: stretch;
}
.product-cell-inner {
  flex: 1;
  min-width: 0;
  background: #fff;
  border-radius: 14rpx;
  padding: 12rpx;
  box-sizing: border-box;
  border: 2rpx solid #eef2f0;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.05);
}
.product-cell.selected .product-cell-inner {
  border-color: var(--brand-wx, #07c160);
  background: #f4fef8;
}
.product-thumb {
  width: 100%;
  height: 148rpx;
  flex-shrink: 0;
  border-radius: 12rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  position: relative;
}
.product-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
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
  width: 64rpx;
  height: 64rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.72);
  color: var(--brand, #047857);
  font-size: 28rpx;
  font-weight: 800;
  line-height: 64rpx;
  text-align: center;
}
.product-badge {
  position: absolute;
  top: 6rpx;
  right: 6rpx;
  min-width: 32rpx;
  height: 32rpx;
  padding: 0 8rpx;
  border-radius: 16rpx;
  background: var(--brand-wx, #048746);
  color: #fff;
  font-size: 20rpx;
  font-weight: 700;
  line-height: 32rpx;
  text-align: center;
  box-shadow: 0 4rpx 12rpx rgba(7, 193, 96, 0.35);
}
.product-name {
  font-size: 22rpx;
  color: #26342d;
  line-height: 1.3;
  font-weight: 600;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 58rpx;
  margin-top: 8rpx;
}

.product-price {
  font-size: 26rpx;
  color: var(--brand, #047857);
  font-weight: 700;
  margin-top: 4rpx;
}
.product-cat {
  font-size: 18rpx;
  color: #94a3b8;
  margin-top: 2rpx;
  line-height: 1.2;
}
.product-stepper {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8rpx;
  margin-top: 6rpx;
  min-height: 72rpx;
}
.stepper-btn {
  flex: 0 0 72rpx;
  width: 72rpx;
  height: 72rpx;
  border-radius: 50%;
  background: #eef6f2;
  color: var(--brand, #047857);
  font-size: 32rpx;
  font-weight: 700;
  line-height: 72rpx;
  text-align: center;
}
.stepper-btn.plus {
  background: var(--brand, #047857);
  color: #fff;
}
.stepper-btn.plus.disabled {
  opacity: 0.35;
  pointer-events: none;
}
.stepper-qty {
  flex: 1;
  min-width: 0;
  text-align: center;
  font-size: 20rpx;
  font-weight: 700;
  color: #26342d;
}

.cart-bar {
  flex-shrink: 0;
  position: relative;
  z-index: 5;
  isolation: isolate;
  background: #fff;
  padding: 18rpx 24rpx;
  padding-bottom: calc(16rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-top: 0;
  box-shadow: 0 -10rpx 32rpx rgba(15, 23, 42, 0.08);
}
.cart-info {
  flex: 1;
  min-width: 0;
  padding-right: 16rpx;
}
.cart-info.tappable:active {
  opacity: 0.85;
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
  min-height: 80rpx;
  height: 80rpx;
  line-height: 1.2;
  background: linear-gradient(135deg, var(--brand, #047857), var(--brand, #047857));
  color: #fff;
  border-radius: 40rpx;
  font-size: 30rpx;
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  box-shadow: 0 8rpx 22rpx rgba(5, 150, 105, 0.22);
}
.cart-cta::after {
  border: none;
}
.cart-demo {
  display: flex;
  align-items: center;
  gap: 16rpx;
  flex-shrink: 0;
}
.cart-demo-info {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
.cart-demo-info.tappable:active {
  opacity: 0.85;
}
.cart-badge-row {
  display: flex;
  align-items: center;
  gap: 10rpx;
}
.cart-badge {
  min-width: 36rpx;
  height: 36rpx;
  padding: 0 8rpx;
  border-radius: 18rpx;
  background: #047857;
  color: #fff;
  font-size: 20rpx;
  font-weight: 700;
  line-height: 36rpx;
  text-align: center;
  box-sizing: border-box;
}
.cart-demo-label {
  font-size: 22rpx;
  color: #64748b;
}
.cart-demo-amt {
  font-size: 30rpx;
  font-weight: 800;
  color: var(--brand-wx, #07c160);
  margin-top: 4rpx;
}
.cart-close-btn {
  margin: 0;
  padding: 0 36rpx;
  min-height: 80rpx;
  height: 80rpx;
  line-height: 1.2;
  background: linear-gradient(135deg, var(--brand, #047857), var(--brand, #047857));
  color: #fff;
  border-radius: 40rpx;
  font-size: 28rpx;
  font-weight: 700;
  box-shadow: 0 8rpx 22rpx rgba(5, 150, 105, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.cart-status-chip.soft {
  background: #ecfdf5;
  color: #047857;
  border: 1rpx solid #a7f3d0;
}
.cart-close-btn::after {
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
  background: linear-gradient(135deg, var(--brand-soft, #ecfdf5), var(--brand-soft, #ecfdf5));
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
  background: #c2410c;
  font-weight: 800;
  font-size: 24rpx;
}
.review-icon.tone-wait {
  background: var(--brand, #047857);
}
.review-icon.tone-success {
  background: var(--brand, #047857);
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
  color: var(--brand, #047857);
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
  color: #047857;
  background: #e8f8ef;
}
.cart-status-chip.wait {
  color: #b45309;
  background: #fff7e6;
}
.cart-status-chip.active {
  color: #047857;
  background: #e8f8ef;
}
.cart-status-chip.error {
  color: #991b1b;
  background: #ffecec;
}

.flow-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: radial-gradient(circle at 50% 35%, var(--brand-soft, #ecfdf5), #fff 55%);
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
  background: var(--brand-soft, #ecfdf5);
}
.flow-overlay.error {
  background: #fff5f5;
}
.flow-spinner {
  width: 132rpx;
  height: 132rpx;
  border-radius: 50%;
  border: 10rpx solid #e8f8ef;
  border-top-color: var(--brand-wx, #07c160);
  margin-bottom: 40rpx;
  box-shadow: 0 16rpx 44rpx rgba(5, 150, 105, 0.13);
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
    border-top-color: var(--brand-wx, #07c160);
    opacity: 0.85;
  }
}
.flow-title {
  font-size: 44rpx;
  font-weight: 700;
  color: #173026;
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
  color: var(--brand-wx, #07c160);
  margin-top: 24rpx;
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: var(--brand-soft, #ecfdf5);
  font-weight: 600;
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
  min-height: 72rpx;
  height: 72rpx;
  line-height: 1.2;
  border-radius: 36rpx;
  background: #f2f3f5;
  color: #576b95;
  font-size: 26rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
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
  border-radius: 16rpx;
  background: var(--brand-deep, #064e3b);
  border: 1rpx solid rgba(255, 255, 255, 0.16);
  box-sizing: border-box;
}
.landing-error.kind-balance,
.landing-error.kind-device_not_found {
  background: var(--brand-deep, #064e3b);
  border-color: rgba(255, 255, 255, 0.16);
}
.landing-error.kind-balance .error-icon {
  background: #f59e0b;
}
.landing-error.kind-balance .error-title,
.landing-error.kind-balance .error-detail,
.landing-error.kind-device_not_found .error-title,
.landing-error.kind-device_not_found .error-detail {
  color: rgba(255, 255, 255, 0.92);
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
  color: #fff;
  background: #dc2626;
  font-weight: 800;
  font-size: 22rpx;
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
  color: #ffffff;
  font-size: 24rpx;
  font-weight: 700;
}
.error-detail {
  margin-top: 4rpx;
  color: rgba(255, 255, 255, 0.78);
  font-size: 22rpx;
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
  border-radius: 999rpx;
  border: 1rpx solid rgba(255, 255, 255, 0.32);
  color: #ffffff;
  font-size: 22rpx;
  background: rgba(6, 78, 59, 0.55);
}
.error-action.primary {
  border-color: rgba(255, 255, 255, 0.4);
  color: #ffffff;
  background: rgba(4, 120, 87, 0.55);
}
.error-close {
  padding: 0 4rpx;
  color: rgba(255, 255, 255, 0.7);
  font-size: 30rpx;
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
  border-radius: 20rpx;
  background: var(--brand-deep, #064e3b);
  border: 1rpx solid rgba(255, 255, 255, 0.16);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: stretch;
}
.landing-sheet-title {
  display: block;
  font-size: 30rpx;
  font-weight: 700;
  color: #ffffff;
  text-align: center;
}
.landing-sheet-body {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.78);
  line-height: 1.5;
}
.landing-sheet-label {
  display: block;
  margin-top: 22rpx;
  margin-bottom: 8rpx;
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.78);
  text-align: center;
}
.sheet-input {
  display: block;
  width: 100%;
  height: 88rpx;
  margin-bottom: 20rpx;
  padding: 0 24rpx;
  box-sizing: border-box;
  border-radius: 12rpx;
  background: var(--brand-ink, #043f32);
  border: 1rpx solid rgba(255, 255, 255, 0.18);
  font-size: 28rpx;
  color: #ffffff;
}
.sheet-ph {
  color: rgba(255, 255, 255, 0.4);
}
.landing-sheet-actions {
  display: flex;
  justify-content: flex-end;
  gap: 16rpx;
  margin-top: 24rpx;
}
.landing-sheet .btn-primary,
.landing-sheet uni-button.btn-primary {
  width: 100% !important;
  max-width: none !important;
  min-width: 0 !important;
  align-self: stretch !important;
  margin-left: 0 !important;
  margin-right: 0 !important;
}
.landing-sheet-btn {
  padding: 10rpx 22rpx;
  border-radius: 999rpx;
  border: 1rpx solid rgba(255, 255, 255, 0.28);
  color: #ffffff;
  font-size: 26rpx;
  background: var(--brand-ink, #043f32);
}
.landing-sheet-btn.primary {
  border-color: transparent;
  background: var(--brand, #047857);
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
  color: rgba(255, 255, 255, 0.78);
  font-size: 26rpx;
  padding: 8rpx 0;
  box-sizing: border-box;
}
</style>
