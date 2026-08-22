<template>
  <view>
    <app-nav-bar title="点位定价" />
    <view class="page-body">
      <view v-if="!canView" class="card">
        <text class="err">当前账号无定价查看权限</text>
      </view>
      <template v-else>
        <view class="card">
          <picker :range="deviceOptions" range-key="label" @change="onDevicePick">
            <view class="picker">柜机：{{ selectedLabel }}</view>
          </picker>
          <view class="history-btn" @click="openHistory">调价历史</view>
          <text v-if="!canEdit" class="meta warn"
            >定价只读，需平台开启「允许商户改价」且具备改价权限</text
          >
        </view>

        <view v-if="loading && !rows.length" class="card">加载中…</view>
        <view v-else-if="error && !rows.length" class="card"
          ><text class="err">{{ error }}</text></view
        >
        <view v-else>
          <view v-if="error" class="banner-err">
            <text>{{ error }}</text>
            <text class="banner-retry" @click="load(false)">重试</text>
          </view>
          <view v-for="p in rows" :key="draftKey(p)" class="card row">
            <view class="row-main">
              <text class="name">{{ p.skuName }}</text>
              <text class="meta"
                >{{ p.deviceName || p.deviceId }} · {{ p.skuId
                }}{{ p.quantity != null ? ` · 库存 ${p.quantity}` : '' }}</text
              >
              <text class="meta"
                >基准 {{ money(p.basePriceCents)
                }}{{
                  p.overridePriceCents != null
                    ? ` · 覆盖 ${money(p.overridePriceCents)}`
                    : ' · 无覆盖'
                }}</text
              >
              <text v-if="p.minPriceCents != null || p.maxPriceCents != null" class="meta range">
                可改 {{ p.minPriceCents != null ? money(p.minPriceCents) : '未设' }}–{{
                  p.maxPriceCents != null ? money(p.maxPriceCents) : '未设'
                }}
              </text>
            </view>
            <view class="price-col">
              <text class="effective">{{ money(p.effectivePriceCents) }}</text>
              <text
                v-if="p.overridePriceCents != null"
                class="override-tag"
                >已覆盖</text
              >
              <input
                v-if="canEdit"
                v-model="draft[draftKey(p)]"
                class="input"
                type="digit"
                placeholder="覆盖价(元)"
                :disabled="savingKey === draftKey(p)"
                @blur="savePrice(p)"
              />
              <text v-if="canEdit && savingKey === draftKey(p)" class="saving">保存中…</text>
            </view>
          </view>
          <empty-state
            v-if="!rows.length"
            icon="/static/menu/pricing.png"
            title="暂无定价数据"
            hint="选择柜机后可查看 SKU 基准价与覆盖价"
          />
        </view>

        <view v-if="historyVisible" class="mask" @click="historyVisible = false">
          <view class="dialog" @click.stop>
            <view class="dialog-head">
              <text class="dialog-title">调价历史</text>
              <text class="dialog-close" role="button" @click="historyVisible = false">×</text>
            </view>
            <view v-if="historyLoading" class="meta center">加载中…</view>
            <view v-else-if="!history.length" class="meta center">暂无调价记录</view>
            <view v-for="(h, i) in history" :key="i" class="history-row">
              <view class="history-main">
                <text class="history-sku">{{ h.skuId }}</text>
                <text class="history-detail">{{ h.detail || '暂无明细' }}</text>
              </view>
              <text class="meta">{{ formatTime(h.changedAt) }}</text>
            </view>
          </view>
        </view>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import EmptyState from '@/components/empty-state.vue';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe, canEditPricingWithPerm } from '@/composables/useMerchantMe';
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
  if (cents == null || Number.isNaN(Number(cents))) return '暂无';
  return `¥${(Number(cents) / 100).toFixed(2)}`;
}

function formatTime(iso?: string) {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

async function openHistory() {
  if (historyVisible.value) return;
  historyVisible.value = true;
  historyLoading.value = true;
  try {
    history.value = (await merchantApi.pricingHistory(selectedDeviceId.value || undefined)) || [];
  } catch (e) {
    history.value = [];
    uni.showToast({ title: e instanceof Error ? e.message : '加载历史失败', icon: 'none' });
  } finally {
    historyLoading.value = false;
  }
}

function draftValueFor(p: MerchantSkuPricing) {
  return p.overridePriceCents != null ? (p.overridePriceCents / 100).toFixed(2) : '';
}

onShow(() => {
  void load(true);
});

async function load(soft = false) {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  // 进行中再来一次：排队，结束后用最新柜机重拉，避免切换柜机丢请求
  if (loadingInFlight) {
    pendingReload = soft ? pendingReload || 'soft' : 'hard';
    return;
  }
  loadingInFlight = true;
  const seq = ++loadSeq;
  if (!soft || !rows.value.length) loading.value = true;
  error.value = '';
  try {
    try {
      await refreshMe();
    } catch {
      if (!uni.getStorageSync('merchant_token')) return;
      me.value = me.value || (uni.getStorageSync('merchant_me') as MerchantMe) || null;
    }
    if (seq !== loadSeq) return;
    if (!canView.value) {
      loading.value = false;
      if (!gated.value) {
        gated.value = true;
        uni.showToast({ title: '无定价查看权限', icon: 'none' });
        uni.switchTab({ url: '/pages/home/home' });
      }
      return;
    }
    if (!devices.value.length) {
      devices.value = await merchantApi.devices();
    }
    if (seq !== loadSeq) return;
    const list = await merchantApi.pricing(selectedDeviceId.value || undefined);
    if (seq !== loadSeq) return;
    rows.value = list;
    const next: Record<string, string> = {};
    for (const p of list) {
      next[draftKey(p)] = draftValueFor(p);
    }
    draft.value = next;
  } catch (e) {
    if (seq === loadSeq) {
      error.value = e instanceof Error ? e.message : '加载失败';
    }
  } finally {
    if (seq === loadSeq) {
      loading.value = false;
      loadingInFlight = false;
      const again = pendingReload;
      pendingReload = null;
      if (again) void load(again === 'soft');
    }
  }
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
  const priceCents = raw === '' ? null : Math.round(parseFloat(raw) * 100);
  if (raw !== '' && (Number.isNaN(priceCents!) || priceCents! < 0)) {
    uni.showToast({ title: '价格无效', icon: 'none' });
    return;
  }
  if (p.minPriceCents != null && priceCents != null && priceCents < p.minPriceCents) {
    uni.showToast({
      title: `不低于 ¥${(p.minPriceCents / 100).toFixed(2)}`,
      icon: 'none'
    });
    return;
  }
  if (p.maxPriceCents != null && priceCents != null && priceCents > p.maxPriceCents) {
    uni.showToast({
      title: `不高于 ¥${(p.maxPriceCents / 100).toFixed(2)}`,
      icon: 'none'
    });
    return;
  }
  const prev = draftValueFor(p);
  if (raw === prev) return;

  savingKey.value = key;
  try {
    const updated = await merchantApi.updatePricing(p.skuId, {
      deviceId: p.deviceId,
      priceCents
    });
    const idx = rows.value.findIndex((r) => draftKey(r) === key);
    if (idx >= 0) {
      rows.value[idx] = { ...rows.value[idx], ...updated };
      draft.value[key] = draftValueFor(rows.value[idx]);
    }
    uni.showToast({ title: '已更新', icon: 'success' });
  } catch (e) {
    draft.value[key] = prev;
    uni.showToast({ title: e instanceof Error ? e.message : '保存失败', icon: 'none' });
  } finally {
    savingKey.value = '';
  }
}
</script>

<style scoped>
.history-btn {
  display: inline-block;
  margin: 12rpx 0 4rpx;
  padding: 10rpx 24rpx;
  border-radius: 999rpx;
  background: #ecfdf5;
  color: #0f766e;
  font-size: 24rpx;
  font-weight: 600;
}

.mask {
  position: fixed;
  inset: 0;
  z-index: 30;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: flex-end;
}
.dialog {
  width: 100%;
  max-height: 75vh;
  overflow-y: auto;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 30rpx 28rpx calc(28rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.dialog-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18rpx;
}
.dialog-title {
  font-size: 32rpx;
  font-weight: 700;
  color: #134e4a;
}
.dialog-close {
  padding: 4rpx 10rpx;
  color: #64748b;
  font-size: 40rpx;
}
.history-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f1f5f9;
}
.history-row:last-child {
  border-bottom: none;
}
.history-main {
  flex: 1;
  min-width: 0;
}
.history-sku {
  display: block;
  font-size: 26rpx;
  font-weight: 650;
  color: #0f172a;
}
.history-detail {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  color: #64748b;
}
.center {
  text-align: center;
  padding: 30rpx 0;
}

.picker {
  padding: 8px 0;
  font-weight: 600;
  overflow: hidden;
  max-height: 48px;
}
.warn {
  color: #d97706;
  display: block;
  margin-top: 8rpx;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16rpx;
}
.row-main {
  flex: 1;
  min-width: 0;
}
.name {
  font-weight: 600;
  display: block;
}
.meta {
  color: #64748b;
  font-size: 22rpx;
  display: block;
  margin-top: 4rpx;
}
.meta.range {
  color: #94a3b8;
}
.effective {
  font-size: 32rpx;
  font-weight: 700;
  color: #0f766e;
  display: block;
}
.override-tag {
  display: inline-block;
  margin-top: 6rpx;
  font-size: 20rpx;
  color: #b45309;
  background: #fef3c7;
  padding: 2rpx 10rpx;
  border-radius: 8rpx;
}
.price-col {
  text-align: right;
  min-width: 160rpx;
}
.input {
  display: block;
  width: 180rpx;
  height: 64rpx;
  min-height: 64rpx;
  line-height: 64rpx;
  box-sizing: border-box;
  text-align: right;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 0 10px;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #0f172a;
}
.saving {
  display: block;
  margin-top: 6rpx;
  font-size: 20rpx;
  color: #0f766e;
}
.banner-err {
  margin: 0 0 12rpx;
  padding: 16rpx 20rpx;
  border-radius: 12rpx;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 24rpx;
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
}
.banner-retry {
  color: #0f766e;
  font-weight: 600;
}
.err {
  color: #ef4444;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
