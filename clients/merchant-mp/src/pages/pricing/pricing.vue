<template>
  <view>
    <app-nav-bar title="点位定价" home-url="/pages/home/home" />
    <view class="page-body">
      <view v-if="!canView" class="card">
        <text class="err">当前账号无定价查看权限</text>
      </view>
      <template v-else>
        <view class="card">
          <view class="toolbar">
            <view class="toolbar-main">
              <picker :range="deviceOptions" range-key="label" @change="onDevicePick">
                <view class="picker">柜机：{{ selectedLabel }}</view>
              </picker>
            </view>
            <view role="button" class="history-btn" @click="openHistory">调价历史</view>
          </view>
          <text v-if="!canEdit" class="meta warn"
            >定价只读：请运营后台「商户」打开「商户改价」，且账号具备改价权限后重新进入本页</text
          >
          <text v-else class="meta tip">点右侧价格框改价，离开即保存；清空则恢复基准价</text>
        </view>

        <view v-if="loading && !rows.length" class="card">{{ UI_COPY.loading }}</view>
        <error-state v-else-if="error && !rows.length" :title="error" @retry="() => load(false)" />
        <view v-else>
          <view v-if="error" class="banner-err">
            <text>{{ error }}</text>
            <text role="button" aria-label="重试" class="banner-retry" @click="load(false)"
              >重试</text
            >
          </view>
          <view v-for="p in rows" :key="draftKey(p)" class="card row">
            <view class="row-main">
              <text class="name">{{ p.skuName }}</text>
              <text class="meta">{{ p.deviceName || p.deviceId }}</text>
              <text class="meta range"
                >基准 {{ money(p.basePriceCents) }}
                <text v-if="p.minPriceCents != null || p.maxPriceCents != null">
                  · 可改 {{ p.minPriceCents != null ? money(p.minPriceCents) : '未设' }}–{{
                    p.maxPriceCents != null ? money(p.maxPriceCents) : '未设'
                  }}
                </text>
              </text>
            </view>
            <view class="price-col">
              <text class="effective">{{ money(p.effectivePriceCents) }}</text>
              <view v-if="canEdit" class="price-edit">
                <text class="yen">¥</text>
                <input
                  v-model="draft[draftKey(p)]"
                  class="input"
                  type="digit"
                  :placeholder="pricePlaceholder(p)"
                  :disabled="savingKey === draftKey(p)"
                  @blur="savePrice(p)"
                />
              </view>
              <text v-if="canEdit" class="price-hint">{{
                savingKey === draftKey(p)
                  ? '保存中…'
                  : p.overridePriceCents != null
                    ? '已覆盖 · 清空恢复基准'
                    : '输入新价后离开保存'
              }}</text>
            </view>
          </view>
          <empty-state
            v-if="!rows.length"
            icon="/static/menu/pricing.png"
            title="暂无定价数据"
            hint="选择柜机后可查看 SKU 基准价与覆盖价"
          />
        </view>

        <AppSheet :visible="historyVisible" aria-label="调价历史" @close="historyVisible = false">
          <view class="dialog-head">
            <text class="dialog-title">调价历史</text>
            <text
              class="dialog-close"
              role="button"
              aria-label="关闭"
              @click="historyVisible = false"
              >×</text
            >
          </view>
          <view v-if="historyLoading" class="meta center">{{ UI_COPY.loading }}</view>
          <view v-else-if="!history.length" class="meta center"
            >暂无本商户柜机的调价记录；改价成功后会出现在这里</view
          >
          <view v-for="(h, i) in history" :key="i" class="history-row">
            <view class="history-main">
              <text class="history-sku">{{ historyProductName(h) }}</text>
              <text class="history-detail">{{ formatMerchantPriceHistoryDetail(h.detail) }}</text>
            </view>
            <view class="history-time">
              <text class="meta">{{ historyCabinet(h) }}</text>
              <text class="meta">{{ formatTime(h.changedAt) }}</text>
            </view>
          </view>
        </AppSheet>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { onShow } from '@dcloudio/uni-app';
import EmptyState from '@aicabinet/shared-uni/components/empty-state.vue';
import AppSheet from '@/components/AppSheet.vue';
import {
  yuanToCents,
  fmtMoney,
  formatSkuNameWithSpec,
  formatMerchantPriceHistoryDetail
} from '@aicabinet/shared-uni/format';
import { hasPerm, merchantApi, isMerchantLoggedIn } from '@/utils/merchant-api';
import {
  useMerchantMe,
  canEditPricingWithPerm,
  seedMerchantMeDisplayCache
} from '@/composables/useMerchantMe';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import type {
  MerchantMe,
  MerchantSkuPriceChange,
  MerchantSkuPricing
} from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const loading = ref(true);
const error = ref('');
const rows = ref<MerchantSkuPricing[]>([]);
const draft = ref<Record<string, string>>({});
const devices = ref<{ deviceId: string; deviceName?: string }[]>([]);
const selectedDeviceId = ref('');
const gated = ref(false);
const savingKey = ref('');
const historyVisible = ref(false);
const historyLoading = ref(false);
const history = ref<MerchantSkuPriceChange[]>([]);
/** 防止 refreshMe → me 变更 → 再次 load 的抖动循环 */
let loadSeq = 0;
let loadingInFlight = false;
let pendingReload: 'soft' | 'hard' | null = null;

const canView = computed(() => hasPerm(me.value, 'merchant:pricing:view'));
const canEdit = computed(() => canEditPricingWithPerm(me.value));

const deviceOptions = computed(() => [
  { deviceId: '', label: '全部柜机' },
  ...devices.value.map((d) => ({ deviceId: d.deviceId, label: d.deviceName || d.deviceId }))
]);

const selectedLabel = computed(() => {
  const hit = deviceOptions.value.find((d) => d.deviceId === selectedDeviceId.value);
  return hit?.label || '全部柜机';
});

function draftKey(p: { skuId: string; deviceId: string }) {
  return `${p.deviceId}::${p.skuId}`;
}

function money(cents?: number | null) {
  return fmtMoney(cents);
}

function pricePlaceholder(p: MerchantSkuPricing) {
  const cents = p.effectivePriceCents ?? p.basePriceCents;
  if (cents == null || !Number.isFinite(Number(cents))) return '0.00';
  return (Number(cents) / 100).toFixed(2);
}

function formatTime(iso?: string) {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function historyProductName(h: MerchantSkuPriceChange) {
  const hit = rows.value.find((r) => r.skuId === h.skuId);
  return formatSkuNameWithSpec(hit?.skuName, '', h.skuId);
}

function historyCabinet(h: MerchantSkuPriceChange) {
  if (!h.deviceId) return '';
  const hit =
    rows.value.find((r) => r.deviceId === h.deviceId) ||
    devices.value.find((d) => d.deviceId === h.deviceId);
  return hit?.deviceName || h.deviceId;
}

async function openHistory() {
  if (historyVisible.value) return;
  historyVisible.value = true;
  historyLoading.value = true;
  try {
    history.value = (await merchantApi.pricingHistory(selectedDeviceId.value || undefined)) || [];
  } catch (e) {
    history.value = [];
    showError(e instanceof Error ? e.message : '加载历史失败');
  } finally {
    historyLoading.value = false;
  }
}

function draftValueFor(p: MerchantSkuPricing) {
  return p.overridePriceCents == null ? '' : (p.overridePriceCents / 100).toFixed(2);
}

onShow(() => {
  void load(true);
});

function denyPricingAccess() {
  loading.value = false;
  if (!gated.value) {
    gated.value = true;
    showError('无定价查看权限');
    uni.switchTab({ url: '/pages/home/home' });
  }
}

function queuePricingReload(soft: boolean) {
  pendingReload = soft ? pendingReload || 'soft' : 'hard';
}

async function runPricingLoad(seq: number) {
  if (!(await ensurePricingAccess(seq))) return;
  const list = await fetchPricingRows(seq);
  if (!list || seq !== loadSeq) return;
  applyPricingRows(list);
}

async function load(soft = false) {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  if (loadingInFlight) {
    queuePricingReload(soft);
    return;
  }
  loadingInFlight = true;
  const seq = ++loadSeq;
  if (!soft || !rows.value.length) loading.value = true;
  error.value = '';
  try {
    await runPricingLoad(seq);
  } catch (e) {
    if (seq === loadSeq) {
      error.value = e instanceof Error ? e.message : '加载失败';
    }
  } finally {
    finishPricingLoad(seq);
  }
}

async function ensurePricingAccess(seq: number): Promise<boolean> {
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return false;
    seedMerchantMeDisplayCache(me);
  }
  if (seq !== loadSeq) return false;
  if (!canView.value) {
    denyPricingAccess();
    return false;
  }
  return true;
}

async function fetchPricingRows(seq: number) {
  if (!devices.value.length) {
    // 只保留有 deviceId 的柜机（生成类型里该字段可选）；缺 id 会污染「全部柜机」的空值项
    devices.value = (await merchantApi.devices()).flatMap((d) =>
      d.deviceId ? [{ deviceId: d.deviceId, deviceName: d.deviceName }] : []
    );
  }
  if (seq !== loadSeq) return null;
  return merchantApi.pricing(selectedDeviceId.value || undefined);
}

function applyPricingRows(list: MerchantSkuPricing[]) {
  rows.value = list;
  const next: Record<string, string> = {};
  for (const p of list) {
    next[draftKey(p)] = draftValueFor(p);
  }
  draft.value = next;
}

function finishPricingLoad(seq: number) {
  if (seq !== loadSeq) return;
  loading.value = false;
  loadingInFlight = false;
  const again = pendingReload;
  pendingReload = null;
  if (again) void load(again === 'soft');
}

function onDevicePick(e: { detail: { value: string } }) {
  const idx = Number(e.detail.value);
  selectedDeviceId.value = deviceOptions.value[idx]?.deviceId || '';
  void load(false);
}

async function savePrice(p: MerchantSkuPricing) {
  if (!canEdit.value || !p.deviceId) return;
  const key = draftKey(p);
  if (savingKey.value === key) return;
  const raw = (draft.value[key] || '').trim();
  const priceCents = raw === '' ? null : yuanToCents(raw);
  if (raw !== '' && (priceCents == null || priceCents < 0)) {
    showError('价格无效');
    return;
  }
  if (p.minPriceCents != null && priceCents != null && priceCents < p.minPriceCents) {
    showError(`不低于 ${fmtMoney(p.minPriceCents)}`);
    return;
  }
  if (p.maxPriceCents != null && priceCents != null && priceCents > p.maxPriceCents) {
    showError(`不高于 ${fmtMoney(p.maxPriceCents)}`);
    return;
  }
  const prev = draftValueFor(p);
  if (raw === prev) return;

  savingKey.value = key;
  try {
    const updated = await merchantApi.updatePricing(p.skuId, {
      deviceId: p.deviceId,
      priceCents,
      expectedVersion: p.priceVersion ?? 0
    });
    const idx = rows.value.findIndex((r) => draftKey(r) === key);
    if (idx >= 0) {
      rows.value[idx] = { ...rows.value[idx], ...updated };
      draft.value[key] = draftValueFor(rows.value[idx]);
    }
    showSuccess('已更新');
  } catch (e) {
    draft.value[key] = prev;
    const err = e as { status?: number; message?: string };
    const isConflict = err?.status === 409;
    showError(isConflict ? '他人已修改，请刷新' : e instanceof Error ? e.message : '保存失败');
    if (isConflict) {
      void load(false);
    }
  } finally {
    savingKey.value = '';
  }
}
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.toolbar-main {
  flex: 1;
  min-width: 0;
}

.history-btn {
  flex-shrink: 0;
  margin: 0;
  padding: 8rpx 20rpx;
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
  color: var(--brand);
  font-size: var(--font-size-caption);
  font-weight: 600;
  line-height: 1.4;
}

.dialog-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18rpx;
}
.dialog-title {
  font-size: var(--font-size-xl);
  font-weight: 700;
  color: var(--brand-deep);
}
.dialog-close {
  padding: 4rpx 10rpx;
  color: var(--text-muted);
  font-size: var(--font-size-h2);
}
.history-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 16rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f1f5f9);
}
.history-row:last-child {
  border-bottom: none;
}
.history-main {
  flex: 1;
  min-width: 0;
}
.history-time {
  flex-shrink: 0;
  text-align: right;
}
.history-sku {
  display: block;
  font-size: var(--font-size-body);
  font-weight: 650;
  color: var(--text-primary, #0f172a);
}
.history-detail {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
}
.center {
  text-align: center;
  padding: 30rpx 0;
}

.picker {
  padding: 8px 0;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-height: 48px;
}
.warn {
  color: var(--warning, #d97706);
  display: block;
  margin-top: 8rpx;
}
.tip {
  color: var(--brand, #0f766e);
  display: block;
  margin-top: 8rpx;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 24rpx;
}
.row-main {
  flex: 1;
  min-width: 0;
}
.name {
  font-weight: 600;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meta {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  display: block;
  margin-top: 4rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meta.range {
  color: var(--text-subtle);
}
.effective {
  font-size: var(--font-size-xl);
  font-weight: 700;
  color: var(--brand);
  display: block;
  line-height: 1.2;
}
.price-col {
  flex: 0 0 200rpx;
  width: 200rpx;
  text-align: right;
}
.price-edit {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4rpx;
  margin-top: 10rpx;
  height: 64rpx;
  padding: 0 16rpx;
  border-radius: 999rpx;
  background: var(--page-bg, #f8fafc);
  border: 1rpx solid var(--color-border);
  box-sizing: border-box;
}
.yen {
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--text-muted);
  line-height: 1;
}
.input {
  flex: 1;
  min-width: 0;
  height: 64rpx;
  min-height: 64rpx;
  line-height: 64rpx;
  padding: 0;
  margin: 0;
  border: none;
  background: transparent;
  text-align: right;
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary, #0f172a);
}
.price-hint {
  display: block;
  margin-top: 8rpx;
  font-size: 20rpx;
  color: var(--text-subtle);
  line-height: 1.3;
}
.banner-err {
  margin: 0 0 12rpx;
  padding: 16rpx 20rpx;
  border-radius: var(--radius-control);
  background: #f9eded;
  color: var(--color-danger);
  font-size: var(--font-size-caption);
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
}
.banner-retry {
  color: var(--brand);
  font-weight: 600;
}
.err {
  color: var(--color-danger);
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
