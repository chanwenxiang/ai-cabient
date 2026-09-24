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
                  role="button"
                  class="error-action primary"
                  @click="goRechargeFromError"
                  >去充值</text
                >
                <text
                  v-else-if="lastFailedDeviceId"
                  role="button"
                  class="error-action primary"
                  @click="retryLastOpen"
                  >重试开门</text
                >
                <text
                  v-if="landingErrorKind === 'device_not_found'"
                  role="button"
                  class="error-action"
                  @click="onScan"
                  >重新扫码</text
                >
                <text
                  role="button"
                  class="error-action"
                  @click="
                    landingError = '';
                    onScan();
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
          <!-- 扩展功能 consumer.coupon_entry.enabled：券包入口前置到首页；默认关闭 ⇒ 不渲染 -->
          <view
            v-if="couponEntryVisible"
            class="coupon-link"
            role="button"
            data-testid="landing-coupon-entry"
            aria-label="我的券包"
            @click="goCoupons"
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
        @click="dismissAuthPrompt"
      >
        <view role="button" class="landing-sheet" @click.stop="noop">
          <text class="landing-sheet-title">需要授权</text>
          <text class="landing-sheet-body">扫码开门需先完成微信授权</text>
          <view class="landing-sheet-actions">
            <text role="button" class="landing-sheet-btn" @click="dismissAuthPrompt">取消</text>
            <text role="button" class="landing-sheet-btn primary" @click="goLoginFromScan"
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
            @click="confirmDevice"
          />
          <view class="landing-sheet-cancel-wrap">
            <text role="button" class="landing-sheet-cancel" @click="showManual = false">取消</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 购物页：开门后展示参考价目 -->
    <view v-if="scanned" class="shop">
      <view class="device-bar">
        <view class="device-info">
          <text class="device-name">{{ deviceName || deviceId }}</text>
          <text
            class="device-status app-status"
            :class="{
              'is-offline': deviceOffline,
              'is-warn': !deviceOffline && deviceBusy,
              'is-online': !deviceOffline && !deviceBusy
            }"
          >
            <text class="app-status-dot" aria-hidden="true" />
            {{ deviceStatusText }}
          </text>
        </view>
        <view class="device-actions">
          <text class="device-report" role="button" aria-label="报修" @click="goReport">报修</text>
          <text class="device-change" role="button" aria-label="换一台柜机" @click="resetDevice"
            >换一台</text
          >
        </view>
      </view>

      <!--
        首页推广位（S1，扩展功能 `consumer.ad_banner.enabled`）。
        默认关 ⇒ 整块不渲染，与接入前逐字节一致（fail-closed）。
        开启后由组件按优先级择一渲染：自有投放 → 腾讯流量主广告 → 占位图。
        ⚠️ 本开关只管「位置可见性」；广告收益对账/入账属 F4 后续切片，不在其语义内。
      -->
      <DeviceAdBanner v-if="deviceId && adBannerVisible" :device-id="deviceId" />

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
            <text role="button" class="review-link primary" @click="goReviewDetail">查看详情</text>
            <text role="button" class="review-link" @click="goOrders">我的订单</text>
            <text role="button" class="review-link subtle" @click="dismissReview">知道了</text>
          </view>
        </view>
      </view>

      <view v-if="shoppingBannerVisible" class="shopping-banner" :class="stateTone">
        <text class="shopping-banner-title">{{ shoppingBannerTitle }}</text>
        <text class="shopping-banner-sub">{{ shoppingBannerSub }}</text>
      </view>
      <view v-if="catalogNotice" class="catalog-notice">
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
            <text
              v-if="searchKeyword"
              role="button"
              class="search-clear"
              @click="clearSearchKeyword"
              >×</text
            >
          </view>
          <scroll-view scroll-x class="category-row" :show-scrollbar="false">
            <view
              role="button"
              class="category-chip"
              :class="{ active: !activeCategory }"
              @click="clearCategory"
              >全部</view
            >
            <view
              v-for="cat in productCategories"
              role="button"
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
          ><text class="meta">{{ loadingLabel('商品') }}</text></view
        >
        <view v-else-if="!products.length" class="card loading-card catalog-empty">
          <text class="empty-title">本柜暂无上架商品</text>
          <text class="empty-hint">仍可开门购物；实付以关门识别为准。有疑问可故障报修或换一台</text>
          <view class="empty-actions">
            <text role="button" class="empty-link" @click="goReport">故障报修</text>
            <text role="button" class="empty-link" @click="resetDevice">换一台</text>
          </view>
        </view>
        <view v-else-if="!filteredProducts.length" class="card loading-card catalog-empty">
          <text class="empty-title">未找到匹配商品</text>
          <text class="empty-hint">换个关键词或分类试试</text>
          <view class="empty-actions">
            <text role="button" class="empty-link" @click="resetCatalogFilter">查看全部商品</text>
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
              <text
                v-if="detailVisible"
                role="button"
                class="product-detail-btn"
                :aria-label="`查看 ${p.skuName} 详情`"
                :data-testid="`product-detail-${p.skuId}`"
                :data-sku-id="p.skuId"
                @click.stop="onProductDetailTap"
                >详情</text
              >
              <view
                v-if="sessionActive && state === 'SHOPPING' && mockEnabled"
                role="button"
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
                  :aria-disabled="!canAddProduct(p) ? 'true' : 'false'"
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
        <template v-if="sessionActive && state === 'SHOPPING'">
          <view
            role="button"
            class="cart-shop-main"
            data-testid="open-live-cart-sheet"
            @click="openCartSheet"
          >
            <view class="cart-icon-wrap">
              <image class="cart-icon" src="/static/icon-cart.svg" mode="aspectFit" />
              <text class="cart-badge">{{ cartBadgeText }}</text>
            </view>
            <view class="cart-shop-text">
              <text class="cart-shop-label">{{ shoppingCartLabel }}</text>
              <text class="cart-shop-amt">{{ shoppingCartAmount }}</text>
            </view>
          </view>
          <text
            v-if="mockEnabled && shoppingCartQty > 0"
            role="button"
            class="cart-clear-btn"
            data-testid="cart-clear"
            aria-label="清空购物车"
            @click.stop="clearSelectedCart"
            >清空</text
          >
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
          <view v-else class="live-door-actions">
            <view class="cart-status-chip soft">请关门</view>
            <button
              class="cart-help-btn"
              hover-class="btn-hover"
              :loading="pollRefreshing"
              :disabled="pollRefreshing"
              @click.stop="refreshSessionNow"
            >
              刷新状态
            </button>
            <button
              class="cart-help-btn ghost"
              hover-class="btn-hover"
              @click.stop="onLiveNeedHelp"
            >
              未出账单？
            </button>
          </view>
        </template>
        <template v-else>
          <view class="cart-info">
            <text class="cart-hint">{{ cartBarHint }}</text>
            <text v-if="cartBarSub" class="cart-sub">{{ cartBarSub }}</text>
          </view>
          <view v-if="sessionActive" class="cart-status-chip" :class="stateTone">
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
        </template>
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
        v-if="pollError && sessionId"
        class="flow-cancel"
        :loading="pollRefreshing"
        :disabled="pollRefreshing"
        @click="refreshSessionNow"
      >
        刷新会话状态
      </button>
      <!-- M25：createSession 在途（openCreateInFlight 且未落号）时取消不可用，避免后端继续建会话开门 -->
      <button
        v-if="
          state === 'CREATED' ||
          state === 'OPENING' ||
          (opening && !sessionId && !openCreateInFlight)
        "
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

    <!-- 扩展功能：本柜商品详情弹层（consumer.product_detail.enabled，关时不渲染入口） -->
    <view
      v-if="detailProduct"
      role="button"
      aria-label="关闭商品详情"
      class="landing-mask"
      data-testid="product-detail-mask"
      @click="closeProductDetail"
    >
      <view
        role="button"
        class="landing-sheet"
        data-testid="product-detail-sheet"
        @click.stop="noop"
      >
        <text class="landing-sheet-title">{{ detailProduct.skuName }}</text>
        <view class="detail-hero">
          <image
            v-if="showThumb(detailProduct)"
            class="detail-img"
            :src="productThumb(detailProduct)"
            mode="aspectFill"
          />
          <text v-else class="detail-mark">{{ productGlyph(detailProduct) }}</text>
        </view>
        <view class="detail-rows">
          <view class="detail-row">
            <text class="detail-k">价格</text>
            <text class="detail-v">{{ fmtMoney(detailProduct.priceCents) }}</text>
          </view>
          <view v-if="detailProduct.category" class="detail-row">
            <text class="detail-k">分类</text>
            <text class="detail-v">{{ detailProduct.category }}</text>
          </view>
          <view class="detail-row">
            <text class="detail-k">在柜</text>
            <text class="detail-v">{{ stockOf(detailProduct) }} 件</text>
          </view>
        </view>
        <text v-if="detailProduct.description" class="detail-desc">{{
          detailProduct.description
        }}</text>
        <view class="landing-sheet-actions">
          <text role="button" class="landing-sheet-btn" @click="closeProductDetail">关闭</text>
        </view>
      </view>
    </view>

    <PrivacyConsentModal
      :visible="showPrivacy"
      policy-url="/pages/policy/detail?type=privacy"
      @accepted="onPrivacyAccepted"
      @declined="onPrivacyDeclined"
    />
  </view>
</template>

<script setup lang="ts">
import {
  onHide,
  onLoad,
  onReady,
  onShareAppMessage,
  onShareTimeline,
  onShow,
  onUnload
} from '@dcloudio/uni-app';
import { computed, ref, watch } from 'vue';
import OpenPrepDrawer from '@/components/open-prep-drawer.vue';
import DeviceAdBanner from '@/components/device-ad-banner.vue';
import LiveCartSheet, { type LiveCartSheetLine } from '@/components/live-cart-sheet.vue';
import PrivacyConsentModal from '@aicabinet/shared-uni/components/privacy-consent-modal.vue';
import { usePrivacyConsentModal } from '@aicabinet/shared-uni/use-privacy-consent';
import {
  clearOpenAttempt,
  consumerApi,
  ensureConsumerAuth,
  isConsumerLoggedIn
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
import { UI_COPY, loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import { resumePendingRechargeIfAny } from '@/utils/recharge';
import { resolveMockEnabled } from '@/utils/runtime-flags';
import {
  adBannerEnabled,
  couponEntryEnabled,
  productDetailEnabled,
  seedConsumerFlags
} from '@/utils/feature-flags';
import { isPayReady, resolveEntryChannel, type EntryChannel } from '@/utils/account';
import { productGlyph, productThumb } from '@/utils/product-thumb';
import { consumerDisputeReviewCopy } from '@/utils/dispute-copy';
import {
  delay,
  requestDisputeSubscribe,
  requestOrderSubscribe,
  showBillToast,
  showDisputeResolvedToast,
  showError,
  showSuccess,
  showConfirm
} from '@/utils/notify';
import type {
  AccountDto,
  DeviceProduct,
  DisputeTicketDto,
  SessionDto
} from '@aicabinet/shared-types';

const { showPrivacy, refreshPrivacyGate, onPrivacyAccepted, onPrivacyDeclined } =
  usePrivacyConsentModal();

/** 真机：标题从微信胶囊下方起排，避免顶穿状态栏 */
const landingHeadStyle = ref({
  paddingTop: getBelowCapsulePadPx(28) + 'px'
});
function refreshLandingPad() {
  landingHeadStyle.value = {
    paddingTop: getBelowCapsulePadPx(28) + 'px'
  };
}
/** H5 无可靠扫码时提供手输；微信小程序主路径仅扫码（对齐竞品，不展示开发入口） */
const isH5 = ref(false);
// #ifdef H5
isH5.value = true;
// #endif
const showManualEntry = computed(() => isH5.value);

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
/**
 * M25：createSession 在途标记。HTTP 请求无法中止——本地「取消」清掉 opening/loading 后，
 * 后端仍会建会话并真实开门（仅靠服务端 90s 超时清扫兜底）。因此在途期间必须禁用取消，
 * 只允许会话落号（sessionId 有值）后走取消接口，或等请求自然超时失败。
 */
const openCreateInFlight = ref(false);
const pollError = ref('');
const pollRefreshing = ref(false);
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
/**
 * 首页券包入口（扩展功能 `consumer.coupon_entry.enabled`）。
 * fail-closed：默认 false ⇒ 首页不渲染入口，与接入前完全一致。
 */
const couponEntryVisible = ref(false);
/** 扩展功能：商品详情入口/弹层（`consumer.product_detail.enabled`，默认关 ⇒ 入口不渲染）。 */
const detailVisible = ref(false);
/**
 * 首页广告位（扩展功能 `consumer.ad_banner.enabled`）。
 * fail-closed：默认 false ⇒ 首页不渲染广告位，与接入前完全一致。
 */
const adBannerVisible = ref(false);
/** 当前打开详情的商品（null = 弹层关闭）。 */
const detailProduct = ref<DeviceProduct | null>(null);
const openingSeconds = ref(90);
const brokenThumbs = ref<Record<string, boolean>>({});
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
/** 会话轮询进行中：弱网下 getSession 超时会超过 interval，跳过堆积请求 */
let pollInFlight = false;
/** 连续轮询失败次数；达到阈值后升级弱网提示 */
let pollFailStreak = 0;
const SESSION_POLL_MS = 2000;
const POLL_FAIL_WARN_AT = 3;
/**
 * C-2：开门超时后给「仍在途的 createSession」的宽限期，以及随后轮询 /sessions/active 的退避间隔。
 * 依据：request 层单次超时 12s + 内部失败重试 600ms + 再 12s ⇒ 最长约 24.6s 才有结论，
 * 而开门侧的 withTimeout 在 20s 就放弃了等待。
 */
const ORPHAN_GRACE_MS = 5000;
const ORPHAN_ADOPT_BACKOFF_MS: readonly number[] = [0, 1000, 2000];
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

/**
 * 会话「进行中」状态集合（非终态）。
 * 轮询恢复、孤儿会话接管、重复开门拦截共用同一份定义，避免多处字面量各写一份导致漂移
 * （C-1/C-2/C-3 三条缺陷都依赖「会话是否仍进行中」这个判据）。
 */
const SESSION_ACTIVE_STATES: readonly string[] = [
  'CREATED',
  'OPENING',
  'SHOPPING',
  'RECOGNIZING',
  'WAITING_UPLOAD',
  'SETTLING'
];

/**
 * 「柜机被他人占用」的展示文案（补货中 / 暂停营业 / 使用中）。
 *
 * ⚠️ 必须显式标注为 `readonly string[]`：字面量数组会被推断成联合字面量元组，
 * 于是 `string` 类型的 `deviceStatusText` 传进 `includes()` 会报 TS2345
 * （vue-tsc 的模板检查会命中这一点）。
 */
const DEVICE_BUSY_STATUS_TEXTS: readonly string[] = [
  UI_COPY.replenishing,
  UI_COPY.paused,
  UI_COPY.inUse
];

/** 是否处于「他人占用」态；模板里原本把这个判断写了两遍。 */
const deviceBusy = computed(() => DEVICE_BUSY_STATUS_TEXTS.includes(deviceStatusText.value));

const sessionActive = computed(
  () => !!sessionId.value && SESSION_ACTIVE_STATES.includes(state.value)
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

/*
 * F3 原生分享（竞品标配，本仓此前两端 0 命中）。
 * 刻意「不带 deviceId」：parseLaunchOptions 只要见到 deviceId 就把 autoOpen 判为 true
 * （shared-uni/qrcode.ts:199-202 用的是 `||` 短路，无法用 autoOpen=0 关掉），
 * 好友点开会被直接拽进开门流程 —— 而好友显然不在柜机前。
 * 故只分享首页入口：先把分享量数据拿到手，再谈「分享领券」那一档。
 */
const SHARE_TITLE = 'AI开门柜 · 扫码开门，拿了就走';
const SHARE_PATH = '/pages/index/index';

onShareAppMessage(() => ({ title: SHARE_TITLE, path: SHARE_PATH }));
onShareTimeline(() => ({ title: SHARE_TITLE }));

/** 对齐扫码开门竞品：落地页全屏沉浸隐藏底栏；进入柜机流程后再显示 */
function syncLandingTabBar() {
  if (showLanding.value) {
    uni.hideTabBar({ animation: false });
    // 隐藏底栏后视口变化/底部安全区露出的「窗口底色」改用品牌深色，避免落地页底部出现白条
    uni.setBackgroundColor({ backgroundColor: '#134e4a' });
  } else {
    uni.showTabBar({ animation: false });
    uni.setBackgroundColor({ backgroundColor: '#ffffff' });
  }
}

watch(showLanding, () => syncLandingTabBar(), { immediate: true });

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

/** SHOPPING 态隐藏大 banner：设备栏「门已开·购物中」已表达同义信息，页面上半部留白给商品列表（真机反馈信息过多） */
const shoppingBannerVisible = computed(() => state.value !== 'SHOPPING');

const shoppingBannerTitle = computed(() => {
  if (state.value === 'SHOPPING') return '柜门已开，请取货';
  if (state.value === 'OPENING' || state.value === 'CREATED') return '正在开门，请稍候';
  if (['RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(state.value)) return '正在识别结算';
  if (canReopen.value) return '本柜价目（浏览）';
  return '选好商品后请关好柜门';
});

const shoppingBannerSub = computed(() => {
  if (state.value === 'SHOPPING') {
    return mockEnabled.value ? '点选商品后可关门结算' : '取货后关门自动结算';
  }
  if (['RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING'].includes(state.value)) {
    return '可先离开，账单会在「订单」中展示';
  }
  if (canReopen.value) return '上一单已结束，再买请再次开门';
  return '实际扣款以识别结果为准';
});

const catalogNotice = computed(() => {
  // SHOPPING 态顶部 banner 已表达同一意思，不再重复一条橙色提示（真机反馈信息过多）
  if (mockEnabled.value && state.value === 'SHOPPING') {
    return '';
  }
  if (canReopen.value) {
    return '上一单已结束，再买请再次开门';
  }
  return '本柜价目仅供参考，实付以关门识别为准';
});

const cartBarHint = computed(() => {
  if (!sessionActive.value) return '浏览价目无需开门';
  return '关门后自动识别并扣款';
});

const cartBarSub = computed(() => {
  if (!sessionActive.value) return '可先看看本柜有什么';
  return '';
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
    if (launch.autoOpen || !isH5.value) {
      // 微信主路径：柜号深链直接进购物流；H5 无 autoOpen 时仍可手输确认
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
  // C-P2-10：结算跳转争议页期间禁止 reopen/restore 抢导航
  if (finishingSession.value) return;
  await resumePendingRechargeIfAny();
  await refreshReviewState();
  if (scanned.value && deviceId.value) refreshDeviceStatus();
  if (await resumeReopenDeviceFlow()) return;
  await resumeBrowseDeviceFlow();
  restoreActiveSession();
}

let showSeq = 0;

onShow(async () => {
  const seq = ++showSeq;
  refreshPrivacyGate();
  syncLandingTabBar();
  refreshLandingPad();
  await loadConsumerConfig();
  if (seq !== showSeq) return;
  await ensureConsumerAuth();
  if (seq !== showSeq) return;
  if (isConsumerLoggedIn()) {
    await onAuthenticatedShow();
  }
  if (seq !== showSeq) return;
  // C-1：onHide 已 stopPoll()，而 sessionId 非空时 restoreActiveSession() 会提前 return，
  // 因此返回首页时必须在此兜底重启轮询，否则关门永不被识别（不弹账单、不跳结果页）。
  // onAuthenticatedShow 内部还有 reopen/browse 等提前 return 分支，放在它之后才不会被跳过。
  resumeSessionPollingIfActive();
  startDevicePoll();
});

onHide(() => {
  stopDevicePoll();
  stopPoll();
  stopRecognitionTimer();
  // 切到订单/我的时务必显示底栏（hideTabBar 是全局的）
  uni.showTabBar({ animation: false });
});
onUnload(() => {
  stopPoll();
  stopDevicePoll();
  stopOpeningCountdown();
  stopRecognitionTimer();
  uni.showTabBar({ animation: false });
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

async function resetDevice() {
  if (sessionActive.value) {
    await showConfirm({
      title: '购物进行中',
      content: '请先关闭柜门完成结算，或等待当前购物流程结束',
      showCancel: false,
      confirmText: '我知道了'
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
    deviceStatusText.value = UI_COPY.offline;
  } else if (status.available === false && reason === 'LOCKED') {
    deviceStatusText.value = UI_COPY.paused;
  } else if (status.available === false && reason === 'REPLENISHMENT') {
    deviceStatusText.value = UI_COPY.replenishing;
  } else if (status.available === false || reason === 'SESSION') {
    deviceStatusText.value = UI_COPY.inUse;
  } else {
    deviceStatusText.value = UI_COPY.onlineReady;
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
  showError(err.toastTitle);
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
  showError(formatError(result.reason));
}

/** 接管服务端会话：写入活动会话、同步视图并启动轮询。 */
function adoptSession(s: SessionDto) {
  lastFailedDeviceId.value = '';
  lastFailedChannel.value = null;
  setActiveSession(s.sessionId);
  applySessionView(s);
  startPoll();
}

/** 该会话能否被本次开门接管：同柜机且处于非终态。 */
function isAdoptableSession(s: SessionDto | null | undefined, cabinetId: string): boolean {
  if (!s) return false;
  const same =
    String(s.deviceId || '')
      .trim()
      .toUpperCase() ===
    String(cabinetId || '')
      .trim()
      .toUpperCase();
  return same && SESSION_ACTIVE_STATES.includes(String(s.state || ''));
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** 在 ms 内 settle 则返回结果，否则返回 null（给在途请求一个宽限期）。 */
function settleWithin<T>(promise: Promise<T>, ms: number): Promise<T | null> {
  return Promise.race<T | null>([promise.catch(() => null), sleep(ms).then(() => null)]);
}

/**
 * C-2：开门请求「超时」不等于失败。
 * withTimeout 只放弃等待，底层 createSession 仍在途（request 层 12s 超时 + 内部 600ms 重试，
 * 最长约 24.6s），而服务端按 idempotencyKey 幂等回放同一会话。因此这里先给在途请求一段宽限期，
 * 再退避轮询 /sessions/active，最后才判失败——否则会出现「柜门已开、订单可能已产生、客户端毫无感知」
 * 的幽灵会话（唯一可能造成用户不知情被扣款的路径）。
 */
async function adoptOrphanSession(
  cabinetId: string,
  options: { pendingCreate?: Promise<SessionDto>; delays?: readonly number[] } = {}
): Promise<boolean> {
  const { pendingCreate, delays = ORPHAN_ADOPT_BACKOFF_MS } = options;
  if (pendingCreate) {
    const late = await settleWithin(pendingCreate, ORPHAN_GRACE_MS);
    if (late && isAdoptableSession(late, cabinetId)) {
      adoptSession(late);
      showSuccess('已恢复开门会话');
      return true;
    }
  }
  for (const delay of delays) {
    if (delay > 0) await sleep(delay);
    // 其它路径（轮询/恢复）已接管时无需重复查询
    if (sessionId.value) return true;
    try {
      const s = await consumerApi.activeSession();
      if (isAdoptableSession(s, cabinetId)) {
        adoptSession(s as SessionDto);
        showSuccess('已恢复开门会话');
        return true;
      }
    } catch {
      /* 查询失败：继续退避重试，全部失败才走原失败路径 */
    }
  }
  return false;
}

async function handleSessionOpenResult(
  cabinetId: string,
  sessionResult: PromiseSettledResult<SessionDto>,
  pendingCreate?: Promise<SessionDto>
): Promise<boolean> {
  if (sessionResult.status === 'fulfilled') {
    adoptSession(sessionResult.value);
    return true;
  }
  if (await adoptOrphanSession(cabinetId, { pendingCreate })) return true;
  markOpenFailed(cabinetId);
  const failReason = sessionResult.reason;
  const kind = classifyOpenError(failReason);
  setLandingError(formatError(failReason), kind);
  showError(landingError.value);
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

/**
 * C-3：已有进行中会话时禁止再次开门。
 * 服务端 ensureNoBlockingSession 只拦「同一柜机被占用」，跨柜机的用户级并发会话拦不住；
 * 若放行，开门失败的错误会展示在新柜机的落地页，而本地 sessionId/ACTIVE_SESSION_KEY 已被覆盖，
 * 原柜机的轮询彻底失联（叠加 C-1 后不可自愈），原账单只能靠订单页事后发现。
 * canReopen 要求 !sessionActive，因此「再次开门」按钮不会被本拦截误伤。
 */
function rejectEntryWhenSessionActive(cabinetId: string): boolean {
  if (!sessionActive.value) return false;
  const current = normalizeCabinetId(deviceId.value || '');
  if (current && current === cabinetId) {
    showError('当前柜机购物进行中，请先完成结算', 2400);
    return true;
  }
  setLandingError('你有一笔未完成的购物单，请先完成或取消后再开门。', 'device_busy');
  void showConfirm({
    title: '有未完成的购物单',
    content: '请先关闭当前柜门完成结算，或取消当前会话后再扫其他柜机。',
    confirmText: '查看订单',
    cancelText: '继续当前'
  }).then((ok) => {
    if (ok) goOrders();
  });
  return true;
}

function beginCabinetEntry(cabinetId: string, scanChannel?: string | null): boolean {
  if (!cabinetId || opening.value || enteringFlow.value) return false;
  if (rejectEntryWhenSessionActive(cabinetId)) return false;
  if (isCabinetIdInvalid(cabinetId)) {
    // 编号形态允许字母+连字符（CAB-001），文案写「数字编号」会与校验规则矛盾。
    setLandingError('柜机编号无效，请扫描柜门二维码或核对编号后重试。', 'device_not_found');
    lastFailedDeviceId.value = '';
    showError('柜机编号无效');
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
  // C-2 收口：柜机被占用时，若占用会话本身就是当前用户的（超时后落库的幽灵会话），
  // 直接接管继续轮询，而不是把用户挡在「柜机正在被使用」外面——否则他既进不去也退不出。
  // 此处没有在途请求，只需单次查询，不做退避以免拖慢「柜机正忙」的正常报错。
  if (avail.reason === 'SESSION' && (await adoptOrphanSession(cabinetId, { delays: [0] })))
    return false;
  return !rejectBlockedDevice(cabinetId, avail);
}

async function openDeviceSession(cabinetId: string) {
  productsLoading.value = true;
  // M25：createSession 即将发出，进入「取消不可用」窗口直至请求有结论
  openCreateInFlight.value = true;
  const OPEN_TIMEOUT_MS = 20000;
  // C-2：先留下 createSession 的 promise 引用——超时只代表「放弃等待」，它仍可能在途并最终成功
  const createSessionPromise = consumerApi.createSession(cabinetId, entryChannel.value);
  const [productsResult, sessionResult] = await Promise.allSettled([
    withTimeout(consumerApi.deviceProducts(cabinetId), OPEN_TIMEOUT_MS, '商品加载超时，请重试'),
    withTimeout(createSessionPromise, OPEN_TIMEOUT_MS, '开门请求超时，请检查网络后重试')
  ]);
  applyProductsResult(productsResult);
  await handleSessionOpenResult(cabinetId, sessionResult, createSessionPromise);
  openCreateInFlight.value = false;
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
    showError(formatError(e));
  } finally {
    productsLoading.value = false;
    opening.value = false;
    enteringFlow.value = false;
    // M25：兜底复位，防止 openDeviceSession 异常退出时残留「取消不可用」状态
    openCreateInFlight.value = false;
  }
}

function setLandingError(message: string, kind: OpenErrorKind = 'other') {
  landingError.value = message;
  landingErrorKind.value = kind;
}

function goRechargeFromError() {
  landingError.value = '';
  uni.navigateTo({ url: '/pages/recharge/recharge' });
}

/** 扩展功能：首页券包入口前置（`consumer.coupon_entry.enabled`）。 */
function goCoupons() {
  uni.navigateTo({ url: '/pages/coupons/coupons' });
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

async function contactOps() {
  await showConfirm({
    title: '联系运营',
    content: `请联系客服 ${servicePhone.value}，并提供审核编号：` + reviewSessionId.value,
    showCancel: false,
    confirmText: '我知道了'
  });
}

/** live 模式：硬件关门驱动结算；提供刷新与客服兜底，避免卡在「请关门」。 */
async function onLiveNeedHelp() {
  const refresh = await showConfirm({
    title: '关门后未出账单？',
    content: `请确认柜门已关好。点「刷新状态」查看结算进度；仍无结果可联系客服 ${servicePhone.value}，或到「订单」页查看。`,
    confirmText: '刷新状态',
    cancelText: '联系客服'
  });
  if (refresh) {
    await refreshSessionNow();
    return;
  }
  const phone = String(servicePhone.value || '').replace(/[^\d+]/g, '');
  if (!phone) {
    showError('暂无客服电话，请到帮助页查看');
    return;
  }
  uni.makePhoneCall({
    phoneNumber: phone,
    fail: () => showError(`请拨打 ${servicePhone.value}`)
  });
}

async function loadConsumerConfig() {
  try {
    const cfg = await consumerApi.consumerPublicConfig();
    // 顺手把公开配置喂给扩展功能开关缓存，免得为了读开关再发一次同样的请求。
    seedConsumerFlags(cfg);
    const phone = cfg?.servicePhone || cfg?.['consumer.service_phone'];
    if (phone) servicePhone.value = phone;
    mockEnabled.value = resolveMockEnabled(cfg?.mockEnabled);
    couponEntryVisible.value = couponEntryEnabled();
    detailVisible.value = productDetailEnabled();
    adBannerVisible.value = adBannerEnabled();
  } catch {
    /* 使用默认客服电话 */
  }
}

async function refreshReviewState() {
  const sid = String(uni.getStorageSync(REVIEW_SESSION_KEY) || '');
  if (!sid || !isConsumerLoggedIn()) {
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
      showError(formatError(e) || '账户信息加载失败');
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
        showError('未识别到有效内容，请对准柜门二维码');
        return;
      }
      const parsed = parseCabinetScan(raw);
      if (parsed.alipayOnly) {
        showError('请使用支付宝扫码');
        return;
      }
      if (!parsed.deviceId) {
        landingError.value = '无法识别柜机二维码，请扫描柜门上的专用码。';
        landingErrorKind.value = 'device_not_found';
        if (showManualEntry.value) showManual.value = true;
        showError('无法识别柜机二维码');
        return;
      }
      startShoppingFlow(parsed.deviceId, parsed.channel);
    },
    fail() {
      if (isH5.value) {
        showManual.value = true;
        showError('浏览器请手动输入柜机编号');
        return;
      }
      showError('扫码取消或失败');
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
    landingError.value = '请输入柜机编号。';
    landingErrorKind.value = 'device_not_found';
    showError('请输入柜机编号');
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
    showError(formatError(e));
  } finally {
    productsLoading.value = false;
  }
}

function normalizeProducts(list: DeviceProduct[] | null | undefined): DeviceProduct[] {
  // 用 for-of 而非 map+filter：`filter((p): p is DeviceProduct => ...)` 会因
  // `DeviceProduct.quantity` 可选而报 TS2677（谓词类型必须是入参类型的子类型）。
  const normalized: DeviceProduct[] = [];
  for (const p of list || []) {
    const n = Number(p.quantity);
    // C-22：非法库存不静默归 0，直接丢弃该行避免假库存
    if (!Number.isFinite(n)) continue;
    normalized.push({ ...p, quantity: Math.max(0, Math.floor(n)) });
  }
  return normalized;
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
      deviceStatusText.value = UI_COPY.offline;
    } else if (state.value === 'SHOPPING' || remoteState === 'SHOPPING') {
      deviceStatusText.value = UI_COPY.doorOpenShopping;
    } else if (state.value === 'CREATED' || state.value === 'OPENING') {
      deviceStatusText.value = UI_COPY.opening;
    } else if (unavailable && reason === 'LOCKED') {
      deviceStatusText.value = UI_COPY.paused;
    } else if (unavailable && reason === 'REPLENISHMENT') {
      deviceStatusText.value = UI_COPY.replenishing;
    } else if (unavailable || s.busy || reason === 'SESSION') {
      deviceStatusText.value = UI_COPY.inUse;
    } else {
      deviceStatusText.value = UI_COPY.onlineReady;
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
  } catch (e) {
    showError(formatError(e));
  } finally {
    productsLoading.value = false;
  }
}

async function cancelOpening() {
  if (cancelling.value) return;
  // M25：createSession 在途时取消必然「前端放弃、后端仍建会话并开门」（HTTP 无法中止，
  // 仅靠后端 90s 超时清扫兜底），故在途且会话未落号时直接忽略取消；
  // 会话已落号则走下方取消接口正常关闭。
  if (openCreateInFlight.value && !sessionId.value) return;
  // 尚无 session：仅取消本地开门等待
  if (!sessionId.value) {
    opening.value = false;
    enteringFlow.value = false;
    scanned.value = false;
    deviceId.value = '';
    products.value = [];
    resetCatalogFilter();
    showSuccess('已取消开门');
    return;
  }
  const confirmed = await showConfirm({
    title: '取消开门',
    content: '确定取消本次开门吗？已创建的会话将被关闭。',
    confirmText: '取消开门',
    cancelText: '继续等待'
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
    showSuccess('已取消本次开门');
  } catch (e) {
    showError(formatError(e));
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

/**
 * 从 uni-app 事件里取 `currentTarget.dataset[key]`。
 *
 * 跨端唯一稳定的取数位置就是 `currentTarget.dataset`（H5 是 DOM 事件、小程序是自定义对象，
 * 其余字段两端不一致）。原先每个 handler 各自声明一套窄类型
 * `{ currentTarget?: { dataset?: Record<string, string> } }`，与 Vue 给原生元素 `@click`
 * 推导出的 `PointerEvent` 参数不兼容 → vue-tsc 报 TS2345（共 3 处模板命中）。
 * 收口成 `unknown` 入参 + 单点断言后，既满足模板类型，也消掉了 4 份重复声明。
 */
function datasetOf(e: unknown, key: string): string {
  const target = (e as { currentTarget?: { dataset?: Record<string, unknown> } } | null)
    ?.currentTarget;
  const value = target?.dataset?.[key];
  return value == null ? '' : String(value);
}

function onCategoryChipTap(e: unknown) {
  const cat = datasetOf(e, 'cat');
  if (!cat) return;
  activeCategory.value = activeCategory.value === cat ? '' : cat;
}

function productBySkuId(skuId: string): DeviceProduct | undefined {
  return products.value.find((p) => p.skuId === skuId);
}

function onProductCellTap(e: unknown) {
  const skuId = datasetOf(e, 'skuId');
  const p = productBySkuId(skuId);
  if (p) addProduct(p);
}

/** 扩展功能：打开商品详情弹层（`consumer.product_detail.enabled`）。入口不存在时不会触发。 */
function onProductDetailTap(e: unknown) {
  const skuId = datasetOf(e, 'skuId');
  const p = productBySkuId(skuId);
  if (p) detailProduct.value = p;
}

function closeProductDetail() {
  detailProduct.value = null;
}

function onAddProductTap(e: unknown) {
  onProductCellTap(e);
}

function onRemoveProductTap(e: unknown) {
  const skuId = datasetOf(e, 'skuId');
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
    showError('暂无可选');
    return;
  }
  if (cur >= max) {
    showError('无法再加');
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
const cartBadgeText = computed(() => {
  const qty = shoppingCartQty.value;
  if (qty > 99) return '99+';
  return String(qty);
});
const shoppingCartLabel = computed(() =>
  shoppingCartQty.value > 0 ? `已选 ${shoppingCartQty.value} 件` : '查看清单'
);

function openCartSheet() {
  if (state.value !== 'SHOPPING') return;
  cartSheetVisible.value = true;
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

/** 清空本次点选清单（演示模式才有「点选」概念；真实识别模式不可清）。 */
function clearSelectedCart() {
  if (!selectedCount.value) return;
  uni.showModal({
    title: '清空购物车',
    content: '将移除本次已点选的全部商品',
    confirmText: '清空',
    cancelText: '取消',
    success: (r) => {
      if (r.confirm) {
        selected.value = {};
        uni.showToast({ title: '已清空', icon: 'none' });
      }
    }
  });
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
      showSuccess('已按库存调整数量');
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
      showError(sessionStateHint(s.state) || '购物未完成', 2800);
      return;
    }
    startPoll();
    showSuccess('已关门，结算中…');
  } catch (e) {
    showError(e instanceof Error ? e.message : '关门失败，请重试');
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
  showSuccess('可稍后在订单页查看');
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
      showError('识别完成，账单待人工确认');
      // C-P2-10：在 finishingSession 仍为 true 时完成导航，避免 onShow 与 600ms 定时器竞态
      await delay(400);
      await new Promise<void>((resolve) => {
        uni.navigateTo({
          url: `/pages/dispute/detail?sessionId=${encodeURIComponent(sid)}`,
          complete: () => resolve(),
          fail: () => resolve()
        });
      });
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
    deviceStatusText.value = UI_COPY.doorOpenShopping;
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

/**
 * C-1：恢复前台时重启会话轮询。
 * onHide 会 stopPoll()，而 restoreActiveSession() 在 sessionId 非空时提前 return，
 * 导致「SHOPPING 中跳帮助页/切 Tab 再返回」后轮询永久停摆、关门不再被识别。
 * startPoll() 自身先 stopPoll()，重复调用只是重启定时器，幂等安全。
 */
function resumeSessionPollingIfActive(): boolean {
  if (!sessionId.value) return false;
  if (!SESSION_ACTIVE_STATES.includes(state.value)) return false;
  startPoll();
  return true;
}

async function restoreActiveSession() {
  const saved = uni.getStorageSync(ACTIVE_SESSION_KEY);
  if (sessionId.value) {
    // C-1：本地已有进行中会话，只需恢复被 onHide 关掉的轮询，不必重新查询
    resumeSessionPollingIfActive();
    return;
  }
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
  pollError.value = '';
  pollFailStreak = 0;
  // 立即拉一次，避免弱网下再等一个 interval 才知道状态
  void tickPoll();
  pollTimer = setInterval(() => void tickPoll(), SESSION_POLL_MS);
}

/** 单次会话轮询：防并发堆积；连续失败升级弱网文案。 */
async function tickPoll() {
  if (!sessionId.value || pollInFlight) return;
  pollInFlight = true;
  try {
    await pollSessionOnce();
  } finally {
    pollInFlight = false;
  }
}

async function refreshSessionNow() {
  if (!sessionId.value || pollRefreshing.value) return;
  pollRefreshing.value = true;
  try {
    await tickPoll();
    if (!pollError.value) {
      showSuccess('状态已更新');
    }
  } finally {
    pollRefreshing.value = false;
  }
}

function isNetworkishError(e: unknown): boolean {
  const msg = formatError(e);
  return /超时|timeout|网络|无法连接|request:fail|ECONN|ENOTFOUND|abort/i.test(msg);
}

async function pollSessionOnce() {
  if (!sessionId.value) return;
  try {
    const s = await consumerApi.getSession(sessionId.value);
    applySessionView(s);
    pollFailStreak = 0;
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
      showError(hint, 2800);
    }
  } catch (e) {
    pollFailStreak += 1;
    if (pollFailStreak >= POLL_FAIL_WARN_AT) {
      pollError.value = isNetworkishError(e)
        ? '网络不稳定，正在自动重试。可点「刷新会话状态」或检查网络后再试。'
        : formatError(e);
    } else if (isNetworkishError(e)) {
      pollError.value = '网络波动，正在重试…';
    } else {
      pollError.value = formatError(e);
    }
  }
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
  pollFailStreak = 0;
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
  background: var(--card-bg, #ffffff);
  position: relative;
}
.page-root.is-landing {
  background: var(--brand-deep, #134e4a);
}

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

.shop {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
/* 顶部设备栏＝品牌绿头部（对齐参考竞品；与落地页同色系），文字转白 */
.device-bar {
  flex-shrink: 0;
  margin: 14rpx 24rpx 0;
  padding: 18rpx 22rpx;
  background: linear-gradient(135deg, var(--brand, #0f766e), var(--brand-deep, #134e4a));
  border: none;
  border-radius: var(--radius-card, 24rpx);
  box-shadow: 0 10rpx 26rpx rgba(19, 78, 74, 0.3);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.device-name {
  font-size: var(--font-size-lg);
  font-weight: 600;
  color: var(--white, #ffffff);
  display: block;
}
.device-status {
  font-size: var(--font-size-caption);
  display: inline-flex;
  margin-top: 7rpx;
  color: rgba(255, 255, 255, 0.92);
}
.device-status.is-offline,
.device-status.is-warn {
  color: #fde68a;
}
.device-status:not(.is-offline):not(.is-warn):not(.is-online) {
  color: var(--brand, #0f766e);
}
.device-actions {
  display: flex;
  align-items: center;
  gap: 20rpx;
  flex-shrink: 0;
}
.device-change {
  font-size: var(--font-size-body);
  color: rgba(255, 255, 255, 0.92);
  font-weight: 500;
}
.device-report {
  font-size: var(--font-size-body);
  color: rgba(255, 255, 255, 0.92);
  font-weight: 500;
}

.shopping-banner {
  margin: 12rpx 24rpx 0;
  padding: 22rpx 24rpx;
  border-radius: var(--radius-card, 24rpx);
  background: linear-gradient(135deg, var(--brand-soft, #ecfdf5), var(--brand-soft, #ecfdf5));
  border: 1rpx solid var(--brand-mist, #ccfbf1);
}
.shopping-banner.wait {
  background: color-mix(in srgb, var(--warning, #b45309) 8%, var(--white));
  border-color: color-mix(in srgb, var(--warning, #b45309) 28%, var(--white));
}
.shopping-banner-title {
  display: block;
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--brand-deep, #134e4a);
}
.shopping-banner.wait .shopping-banner-title {
  color: var(--warning, #b45309);
}
.shopping-banner-sub {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-caption);
  color: var(--brand, #0f766e);
  line-height: 1.4;
}
.shopping-banner.wait .shopping-banner-sub {
  color: var(--warning);
}
.catalog-notice {
  margin: 14rpx 20rpx 0;
  padding: 18rpx 20rpx;
  background: color-mix(in srgb, var(--warning, #b45309) 8%, var(--white));
  border: 1rpx solid color-mix(in srgb, var(--warning, #b45309) 28%, var(--white));
  border-radius: var(--radius-control, 12rpx);
  font-size: var(--font-size-caption);
  color: var(--warning, #b45309);
  line-height: 1.4;
}

.catalog-tools {
  padding: 0 24rpx 4rpx;
}
.search-box {
  position: relative;
  display: flex;
  align-items: center;
  height: 64rpx;
  padding: 0 28rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  border: 1rpx solid var(--color-border);
  box-shadow: 0 4rpx 16rpx rgba(15, 118, 110, 0.06);
}
.search-input {
  flex: 1;
  min-width: 0;
  height: 100%;
  font-size: var(--font-size-body);
  color: var(--text-primary, #0f172a);
}
.search-placeholder {
  color: var(--text-subtle);
}
.search-clear {
  padding: 6rpx 4rpx 6rpx 16rpx;
  color: var(--text-subtle);
  font-size: var(--font-size-h3);
  line-height: 1;
}
.category-row {
  display: flex;
  flex-wrap: nowrap;
  white-space: nowrap;
  margin-top: 12rpx;
  width: 100%;
  height: 60rpx;
  box-sizing: border-box;
}
.category-chip {
  display: inline-flex;
  align-items: center;
  height: 52rpx;
  padding: 0 22rpx;
  margin-right: 12rpx;
  border-radius: var(--radius-card);
  background: var(--color-border-subtle, #f1f5f9);
  color: var(--text-muted, #475569);
  font-size: var(--font-size-caption);
  font-weight: 600;
  flex-shrink: 0;
}
.category-chip.active {
  background: var(--brand);
  color: var(--white);
}

.product-scroll {
  flex: 1;
  height: 0;
  min-height: 0;
  margin-top: 8rpx;
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
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary, #223029);
}
.catalog-empty .empty-hint {
  display: block;
  margin-top: 10rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted, #849087);
  line-height: 1.5;
}
.catalog-empty .empty-actions {
  display: flex;
  justify-content: center;
  gap: 28rpx;
  margin-top: 20rpx;
}
.catalog-empty .empty-link {
  font-size: var(--font-size-body);
  color: var(--brand, #0f766e);
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
  background: var(--card-bg, #fff);
  border-radius: var(--radius-control);
  padding: 12rpx;
  box-sizing: border-box;
  border: 2rpx solid var(--color-border-subtle);
  display: flex;
  flex-direction: column;
  align-items: stretch;
  box-shadow: 0 6rpx 18rpx rgba(15, 23, 42, 0.05);
}
.product-cell.selected .product-cell-inner {
  border-color: var(--brand-wx, #07c160);
  background: var(--brand-soft);
}
.product-thumb {
  width: 100%;
  height: 148rpx;
  flex-shrink: 0;
  border-radius: var(--radius-control);
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
  background: linear-gradient(135deg, var(--info-soft), var(--info-soft));
}
.product-thumb.cat-snack {
  background: linear-gradient(135deg, var(--warning-soft), var(--warning-soft));
}
.product-thumb.cat-dairy {
  background: linear-gradient(135deg, #f9f0ff, #d3adf7);
}
.product-thumb.cat-food {
  background: linear-gradient(135deg, #fff2e8, #ffbb96);
}
.product-thumb.cat-default {
  background: var(--color-border-subtle);
}
.product-img {
  width: 100%;
  height: 100%;
}
.product-mark {
  width: 64rpx;
  height: 64rpx;
  border-radius: var(--radius-panel);
  background: rgba(255, 255, 255, 0.72);
  color: var(--brand, #0f766e);
  font-size: var(--font-size-md);
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
  border-radius: var(--radius-panel);
  background: var(--brand-wx, #048746);
  color: var(--white);
  font-size: var(--font-size-xs);
  font-weight: 700;
  line-height: 32rpx;
  text-align: center;
  box-shadow: 0 4rpx 12rpx rgba(7, 193, 96, 0.35);
}
.product-name {
  font-size: var(--font-size-sm);
  color: var(--text-primary);
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
  font-size: var(--font-size-body);
  color: var(--brand, #0f766e);
  font-weight: 700;
  margin-top: 4rpx;
}
.product-cat {
  font-size: 18rpx;
  color: var(--text-subtle);
  margin-top: 2rpx;
  line-height: 1.2;
}
/* 扩展功能：商品详情入口与弹层（consumer.product_detail.enabled，关时入口不渲染） */
.product-detail-btn {
  margin-top: 4rpx;
  padding: 2rpx 14rpx;
  border-radius: 999rpx;
  background: var(--color-border-subtle, #f1f5f9);
  color: var(--text-muted, #475569);
  font-size: 20rpx;
  line-height: 1.6;
}
.detail-hero {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 320rpx;
  margin: 16rpx 0;
  border-radius: var(--radius-card, 12rpx);
  background: var(--page-bg, #f6f7f9);
  overflow: hidden;
}
.detail-img {
  width: 100%;
  height: 100%;
}
.detail-mark {
  font-size: 96rpx;
  color: var(--text-muted, #94a3b8);
}
.detail-row {
  display: flex;
  justify-content: space-between;
  padding: 8rpx 0;
}
.detail-k {
  color: var(--text-secondary, #6b7280);
}
.detail-v {
  color: var(--text-primary, #111827);
}
.detail-desc {
  display: block;
  margin-top: 12rpx;
  color: var(--text-muted, #334155);
  line-height: 1.6;
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
  background: var(--brand-soft);
  color: var(--brand, #0f766e);
  font-size: var(--font-size-xl);
  font-weight: 700;
  line-height: 72rpx;
  text-align: center;
}
.stepper-btn.plus {
  background: var(--brand, #0f766e);
  color: var(--white);
}
.stepper-btn.plus.disabled {
  opacity: 0.35;
  pointer-events: none;
}
.stepper-qty {
  flex: 1;
  min-width: 0;
  text-align: center;
  font-size: var(--font-size-xs);
  font-weight: 700;
  color: var(--text-primary);
}

.cart-bar {
  flex-shrink: 0;
  position: relative;
  z-index: 5;
  isolation: isolate;
  background: var(--card-bg, #fff);
  padding: 16rpx 24rpx;
  padding-bottom: calc(16rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  border-top: 0;
  box-shadow: 0 -10rpx 32rpx rgba(15, 23, 42, 0.08);
}
.cart-info {
  flex: 1;
  min-width: 0;
  padding-right: 8rpx;
}
.cart-hint {
  font-size: var(--font-size-md);
  color: var(--text-primary, #1e293b);
  font-weight: 600;
  display: block;
}
.cart-sub {
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #888);
  display: block;
  margin-top: 4rpx;
}
.cart-shop-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 18rpx;
}
.cart-shop-main:active {
  opacity: 0.85;
}
.cart-icon-wrap {
  position: relative;
  width: 72rpx;
  height: 72rpx;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-card);
  background: var(--brand-soft);
}
.cart-icon {
  width: 40rpx;
  height: 40rpx;
}
.cart-badge {
  position: absolute;
  top: -4rpx;
  right: -8rpx;
  min-width: 32rpx;
  height: 32rpx;
  padding: 0 8rpx;
  border-radius: var(--radius-panel);
  background: var(--brand);
  color: var(--white);
  font-size: 18rpx;
  font-weight: 700;
  line-height: 32rpx;
  text-align: center;
  box-sizing: border-box;
  border: 2rpx solid var(--white);
}
.cart-shop-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4rpx;
}
.cart-shop-label {
  font-size: var(--font-size-body);
  color: var(--text-muted, #334155);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cart-shop-amt {
  font-size: var(--font-size-h3);
  font-weight: 800;
  color: var(--brand, #0f766e);
  line-height: 1.15;
}
.cart-cta {
  margin: 0;
  padding: 0 48rpx;
  min-height: 80rpx;
  height: 80rpx;
  line-height: 1.2;
  background: linear-gradient(135deg, var(--brand, #0f766e), var(--brand, #0f766e));
  color: var(--white);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-lg);
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  box-shadow: 0 8rpx 22rpx rgba(5, 150, 105, 0.22);
  flex-shrink: 0;
}
.cart-cta::after {
  border: none;
}
.cart-clear-btn {
  flex-shrink: 0;
  margin-right: 16rpx;
  padding: 10rpx 22rpx;
  font-size: var(--font-size-sm);
  color: var(--brand-deep, #134e4a);
  background: rgba(19, 78, 74, 0.08);
  border-radius: var(--radius-pill);
}
.cart-close-btn {
  margin: 0;
  padding: 0 36rpx;
  min-height: 80rpx;
  height: 80rpx;
  line-height: 1.2;
  background: linear-gradient(135deg, var(--brand, #0f766e), var(--brand, #0f766e));
  color: var(--white);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-md);
  font-weight: 700;
  box-shadow: 0 8rpx 22rpx rgba(5, 150, 105, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
  flex-shrink: 0;
}
.cart-status-chip.soft {
  background: var(--brand-soft);
  color: var(--brand);
  border: 1rpx solid var(--brand-mist, #a7f3d0);
}
.cart-close-btn::after {
  border: none;
}
.live-door-actions {
  display: flex;
  align-items: center;
  gap: 12rpx;
  flex-shrink: 0;
  max-width: 58%;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.cart-help-btn {
  margin: 0;
  padding: 0 22rpx;
  min-height: 64rpx;
  height: 64rpx;
  line-height: 1.2;
  background: var(--brand, #0f766e);
  color: var(--white);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-sm);
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
.cart-help-btn.ghost {
  background: transparent;
  color: var(--brand, #0f766e);
  border: 1rpx solid var(--brand-mist, #a7f3d0);
}
.cart-help-btn::after {
  border: none;
}
.settlement-review-card {
  display: flex;
  gap: 18rpx;
  margin: 14rpx 20rpx 0;
  padding: 22rpx;
  border: 1rpx solid color-mix(in srgb, var(--warning, #b45309) 28%, var(--white));
  border-radius: var(--radius-card);
  background: linear-gradient(
    135deg,
    var(--warning-soft),
    color-mix(in srgb, var(--warning, #b45309) 8%, var(--white))
  );
  box-shadow: 0 9rpx 26rpx rgba(194, 65, 12, 0.08);
}
.settlement-review-card.tone-success {
  border-color: var(--brand-mist);
  background: linear-gradient(135deg, var(--brand-soft, #ecfdf5), var(--brand-soft, #ecfdf5));
}
.settlement-review-card.tone-wait {
  border-color: color-mix(in srgb, var(--warning, #b45309) 28%, var(--white));
  background: linear-gradient(
    135deg,
    var(--warning-soft),
    color-mix(in srgb, var(--warning, #b45309) 8%, var(--white))
  );
}
.settlement-review-card.tone-warn {
  border-color: color-mix(in srgb, var(--danger, #b91c1c) 18%, var(--white));
  background: linear-gradient(135deg, var(--danger-soft), var(--danger-soft));
}
.review-icon {
  display: flex;
  flex: 0 0 42rpx;
  height: 42rpx;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--white);
  background: var(--accent-orange, #c2410c);
  font-weight: 800;
  font-size: var(--font-size-caption);
}
.review-icon.tone-wait {
  background: var(--brand, #0f766e);
}
.review-icon.tone-success {
  background: var(--brand, #0f766e);
}
.review-icon.tone-warn {
  background: var(--color-danger);
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
  color: var(--accent-orange, #9a3412);
  font-size: var(--font-size-body);
  font-weight: 750;
}
.review-detail {
  margin-top: 7rpx;
  color: var(--accent-orange);
  font-size: var(--font-size-sm);
  line-height: 1.55;
}
.review-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12rpx 28rpx;
  margin-top: 15rpx;
}
.review-link {
  color: var(--accent-orange, #c2410c);
  font-size: var(--font-size-caption);
  font-weight: 600;
  line-height: 1.3;
}
.review-link.primary {
  color: var(--brand, #0f766e);
}
.review-link.subtle {
  color: var(--text-subtle);
}
.cart-status-chip {
  padding: 0 32rpx;
  height: 80rpx;
  line-height: 80rpx;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--brand);
  background: var(--brand-soft, #e8f8ef);
}
.cart-status-chip.wait {
  color: var(--warning, #b45309);
  background: var(--warning-soft);
}
.cart-status-chip.active {
  color: var(--brand);
  background: var(--brand-soft, #e8f8ef);
}
.cart-status-chip.error {
  color: var(--danger, #991b1b);
  background: var(--danger-soft);
}

.flow-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: radial-gradient(circle at 50% 35%, var(--brand-soft, #ecfdf5), var(--white) 55%);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48rpx;
  box-sizing: border-box;
}
.flow-overlay.wait {
  background: var(--warning-soft);
}
.flow-overlay.active {
  background: var(--brand-soft, #ecfdf5);
}
.flow-overlay.error {
  background: var(--danger-soft);
}
.flow-spinner {
  width: 132rpx;
  height: 132rpx;
  border-radius: 50%;
  border: 10rpx solid var(--brand-soft, #e8f8ef);
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
  font-size: var(--font-size-h1);
  font-weight: 700;
  color: var(--text-primary);
  text-align: center;
}
.flow-hint {
  font-size: var(--font-size-md);
  color: var(--text-subtle, #888);
  margin-top: 16rpx;
  text-align: center;
  line-height: 1.5;
  max-width: 560rpx;
}
.flow-device {
  font-size: var(--font-size-body);
  color: var(--brand-wx, #07c160);
  margin-top: 24rpx;
  padding: 10rpx 18rpx;
  border-radius: var(--radius-pill);
  background: var(--brand-soft, #ecfdf5);
  font-weight: 600;
}
.flow-err {
  font-size: var(--font-size-body);
  color: var(--color-danger);
  margin-top: 16rpx;
  text-align: center;
}
.flow-cancel {
  margin-top: 40rpx;
  padding: 0 36rpx;
  min-height: 72rpx;
  height: 72rpx;
  line-height: 1.2;
  border-radius: var(--radius-card);
  background: var(--surface-muted);
  color: var(--color-link-secondary);
  font-size: var(--font-size-body);
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
  font-size: var(--font-size-caption);
  color: var(--text-subtle, #888);
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
