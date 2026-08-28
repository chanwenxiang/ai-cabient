<template>
  <view class="page">
    <app-nav-bar title="要货申请" />
    <view class="page-body">
      <view class="tabs">
        <view class="tab" :class="{ active: mode === 'create' }" @click="mode = 'create'"
          >发起要货</view
        >
        <view class="tab" :class="{ active: mode === 'list' }" @click="switchToList">我的申请</view>
      </view>

      <view v-if="mode === 'create'" class="panel">
        <view class="card">
          <text class="label">目标柜机</text>
          <picker :range="deviceLabels" :value="deviceIndex" @change="onDevicePick">
            <view class="picker">{{ deviceLabels[deviceIndex] || '请选择柜机' }}</view>
          </picker>
          <text v-if="preferredId && selectedDeviceId === preferredId" class="hint"
            >当前为常驻柜</text
          >
        </view>

        <view class="card">
          <view class="row-between">
            <text class="label">要货明细</text>
            <text class="hint" @click="loadDraft">刷新建议</text>
          </view>
          <view v-if="draftLoading" class="empty-inline">加载建议中…</view>
          <view v-else-if="!draftLines.length" class="empty-inline">
            该柜机暂无可要货商品（无绑定货道 SKU）
          </view>
          <view
            v-for="line in draftLines"
            :key="line.skuId"
            class="line-row"
            @click="toggleLine(line)"
          >
            <view class="check" :class="{ on: line.selected }">{{ line.selected ? '✓' : '' }}</view>
            <view class="line-copy">
              <text class="sku-name">{{ line.skuName }}</text>
              <text class="sku-meta">
                {{ line.skuId }} · 库存 {{ line.currentQty }}/{{ line.capacity }}
                <text v-if="line.suggestQty > 0"> · 建议 {{ line.suggestQty }}</text>
                <text v-if="line.soldQty7d > 0"> · 近7日销 {{ line.soldQty7d }}</text>
              </text>
              <text v-if="suggestReasonLabel(line.suggestReason)" class="sku-reason">{{
                suggestReasonLabel(line.suggestReason)
              }}</text>
            </view>
            <view class="qty-box" @click.stop>
              <text class="qty-btn" @click="adjustQty(line, -1)">−</text>
              <text class="qty-val">{{ line.qty }}</text>
              <text class="qty-btn" @click="adjustQty(line, 1)">+</text>
            </view>
          </view>
        </view>

        <view class="card">
          <text class="label">备注（可选）</text>
          <input
            v-model="notes"
            class="input"
            placeholder="如：周末客流大，优先补可乐"
            maxlength="80"
          />
        </view>

        <view
          class="btn-primary btn-block"
          :class="{ disabled: submitting || !canSubmit }"
          @click="submit"
        >
          <text class="btn-label">{{
            submitting ? '提交中…' : `提交要货（${selectedCount} 种）`
          }}</text>
        </view>
        <text v-if="!canRequest" class="err">当前账号无要货权限</text>
      </view>

      <view v-else class="panel">
        <view class="filters">
          <view
            v-for="t in statusTabs"
            :key="t.value"
            class="filter"
            :class="{ active: listStatus === t.value }"
            @click="changeListStatus(t.value)"
            >{{ t.label }}</view
          >
        </view>
        <view v-if="listLoading" class="empty-inline">加载中…</view>
        <view v-else-if="listError" class="empty-inline err">{{ listError }}</view>
        <view v-else-if="!requests.length" class="empty-inline">暂无要货申请</view>
        <view
          v-for="req in requests"
          :key="req.requestId"
          class="card req-card"
          :class="{ clickable: canGoReplenish(req) }"
          :hover-class="canGoReplenish(req) ? 'req-card-hover' : ''"
          @click="onRequestCard(req)"
        >
          <view class="row-between">
            <text class="req-id">申请号 {{ req.requestId }}</text>
            <text class="status" :class="(req.status || '').toLowerCase()">
              {{ displayLabel('replenishment_request_status', req.status) }}
            </text>
          </view>
          <text class="sku-name">{{ req.deviceName || req.deviceId }}</text>
          <text class="sku-meta">{{ req.deviceId }} · {{ formatTime(req.submittedAt) }}</text>
          <view v-if="req.lines?.length" class="lines">
            <text v-for="l in req.lines" :key="l.lineId || l.skuId" class="line-chip">
              {{ l.skuName || l.skuId }} ×{{ l.requestedQty }}
            </text>
          </view>
          <text v-if="req.reviewedAt" class="sku-meta">审核 {{ formatTime(req.reviewedAt) }}</text>
          <text v-if="req.rejectReason" class="reject">驳回：{{ req.rejectReason }}</text>
          <text v-if="req.notes" class="notes">备注：{{ req.notes }}</text>
          <view v-if="req.status === 'ACCEPTED' && req.replenishmentTaskId" class="detail-btn"
            >去补货 ›</view
          >
        </view>
        <text v-if="requests.length >= 100" class="trunc-hint"
          >已加载 {{ requests.length }} 条申请</text
        >
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { displayLabel } from '@aicabinet/shared-dict';
import { formatDateTimeShort } from '@aicabinet/shared-uni/format';
import {
  hasPerm,
  merchantApi,
  type MerchantReplenishmentRequest,
  type MerchantReplenishmentSuggest
} from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import type { DeviceInfo, DeviceSlot, MerchantMe } from '@aicabinet/shared-types';

type DraftLine = {
  skuId: string;
  skuName: string;
  currentQty: number;
  capacity: number;
  suggestQty: number;
  soldQty7d: number;
  suggestReason: string;
  qty: number;
  selected: boolean;
};

const { me, refresh: refreshMe } = useMerchantMe();
const preferredId = ref(getPreferredDeviceId());
const canView = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));

const mode = ref<'create' | 'list'>('create');
const devices = ref<DeviceInfo[]>([]);
const deviceIndex = ref(0);
const selectedDeviceId = computed(() => devices.value[deviceIndex.value]?.deviceId || '');
const deviceLabels = computed(() =>
  devices.value.map((d) => {
    const name = d.deviceName || d.deviceId;
    return preferredId.value && d.deviceId === preferredId.value ? `${name}（常驻）` : name;
  })
);

const draftLoading = ref(false);
const listLoading = ref(false);
let draftSeq = 0;
let listSeq = 0;
const draftLines = ref<DraftLine[]>([]);
const notes = ref('');
const submitting = ref(false);

const statusTabs = [
  { value: '', label: '全部' },
  { value: 'SUBMITTED', label: '待审核' },
  { value: 'ACCEPTED', label: '已接单' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'REJECTED', label: '已驳回' }
];
const listStatus = ref('');
const listError = ref('');
const requests = ref<MerchantReplenishmentRequest[]>([]);

const selectedCount = computed(
  () => draftLines.value.filter((l) => l.selected && l.qty > 0).length
);
const canSubmit = computed(
  () => canRequest.value && !!selectedDeviceId.value && selectedCount.value > 0
);

/** 补货建议理由：PAR=目标库存，ROP=销量再订货点 */
function suggestReasonLabel(code?: string) {
  const c = String(code || '').toUpperCase();
  if (!c || c === 'PAR') return '';
  if (c === 'ROP') return '按销量补货';
  if (c === 'PAR+ROP') return '目标库存+销量';
  return code || '';
}

onLoad((opts) => {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const deviceId = typeof opts?.deviceId === 'string' ? decodeURIComponent(opts.deviceId) : '';
  const tab = typeof opts?.tab === 'string' ? opts.tab : '';
  if (tab === 'list') mode.value = 'list';
  void bootstrap(deviceId);
});

onShow(() => {
  preferredId.value = getPreferredDeviceId();
  if (mode.value === 'list') void loadRequests();
});

onPullDownRefresh(() => {
  bootstrap().finally(() => uni.stopPullDownRefresh()).catch(() => {});
});

watch(selectedDeviceId, (id, prev) => {
  if (id && id !== prev && mode.value === 'create') void loadDraft();
});

async function bootstrap(preferDeviceId?: string) {
  try {
    await refreshMe();
  } catch {
    if (!uni.getStorageSync('merchant_token')) return;
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  if (!canView.value) {
    uni.showToast({ title: '无补货查看权限', icon: 'none' });
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return;
  }
  try {
    devices.value = (await merchantApi.devices()) || [];
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '柜机加载失败', icon: 'none' });
    devices.value = [];
  }
  const prefer = preferDeviceId || preferredId.value;
  const preferKey = String(prefer || '')
    .trim()
    .toUpperCase();
  const idx = preferKey
    ? devices.value.findIndex(
        (d) =>
          String(d.deviceId || '')
            .trim()
            .toUpperCase() === preferKey
      )
    : -1;
  deviceIndex.value = Math.max(0, idx);
  if (mode.value === 'create') await loadDraft();
  else await loadRequests();
}

function onDevicePick(e: { detail: { value: string } }) {
  deviceIndex.value = Number(e.detail.value) || 0;
}

function switchToList() {
  mode.value = 'list';
  void loadRequests();
}

function changeListStatus(status: string) {
  listStatus.value = status;
  void loadRequests();
}

function buildSuggestMap(items: MerchantReplenishmentSuggest[]) {
  const suggestMap = new Map<string, MerchantReplenishmentSuggest>();
  for (const s of items || []) {
    if (!s?.skuId) continue;
    const prev = suggestMap.get(s.skuId);
    if (!prev || (s.suggestQty || 0) > (prev.suggestQty || 0)) suggestMap.set(s.skuId, s);
  }
  return suggestMap;
}

function mergeSlotDraftLine(
  bySku: Map<string, DraftLine>,
  slot: DeviceSlot,
  sug: MerchantReplenishmentSuggest | undefined
) {
  const skuId = String(slot.assignedSkuId || '').trim();
  if (!skuId) return;
  const book = Number(slot.bookQty) || 0;
  const capacity = Number(slot.maxLevel ?? slot.parLevel) || 0;
  const suggestQty = Number(sug?.suggestQty) || 0;
  const soldQty7d = Number(sug?.soldQty7d) || 0;
  const suggestReason = String(sug?.suggestReason || '');
  const existing = bySku.get(skuId);
  if (existing) {
    existing.currentQty += book;
    existing.capacity += capacity;
    existing.suggestQty = Math.max(existing.suggestQty, suggestQty);
    existing.soldQty7d = Math.max(existing.soldQty7d, soldQty7d);
    if (suggestReason) existing.suggestReason = suggestReason;
    return skuId;
  }
  const defaultQty = suggestQty > 0 ? suggestQty : Math.max(0, (Number(slot.parLevel) || 0) - book);
  bySku.set(skuId, {
    skuId,
    skuName: String(slot.assignedSkuName || skuId),
    currentQty: book,
    capacity,
    suggestQty,
    soldQty7d,
    suggestReason,
    qty: defaultQty > 0 ? defaultQty : 1,
    selected: suggestQty > 0 || defaultQty > 0
  });
  return skuId;
}

function appendOrphanSuggestions(
  bySku: Map<string, DraftLine>,
  suggestMap: Map<string, MerchantReplenishmentSuggest>
) {
  for (const [skuId, sug] of suggestMap) {
    if (bySku.has(skuId)) continue;
    const qty = Math.max(1, Number(sug.suggestQty) || 1);
    bySku.set(skuId, {
      skuId,
      skuName: skuId,
      currentQty: Number(sug.currentQty) || 0,
      capacity: Number(sug.capacity) || 0,
      suggestQty: Number(sug.suggestQty) || 0,
      soldQty7d: Number(sug.soldQty7d) || 0,
      suggestReason: String(sug.suggestReason || ''),
      qty,
      selected: (sug.suggestQty || 0) > 0
    });
  }
}

async function loadDraft() {
  const deviceId = selectedDeviceId.value;
  if (!deviceId) {
    draftLines.value = [];
    return;
  }
  const seq = ++draftSeq;
  draftLoading.value = true;
  try {
    const [suggest, slots] = await Promise.all([
      merchantApi
        .replenishmentSuggestions(deviceId)
        .catch(() => [] as MerchantReplenishmentSuggest[]),
      merchantApi.deviceSlots(deviceId).catch(() => [] as DeviceSlot[])
    ]);
    if (seq !== draftSeq) return;
    const suggestMap = buildSuggestMap(suggest);
    const bySku = new Map<string, DraftLine>();
    for (const slot of slots || []) {
      const skuId = mergeSlotDraftLine(bySku, slot, suggestMap.get(String(slot.assignedSkuId || '').trim()));
      if (skuId) suggestMap.delete(skuId);
    }
    appendOrphanSuggestions(bySku, suggestMap);
    if (seq !== draftSeq) return;
    draftLines.value = [...bySku.values()].sort((a, b) => {
      if (a.selected !== b.selected) return a.selected ? -1 : 1;
      return b.suggestQty - a.suggestQty;
    });
  } catch (e) {
    if (seq !== draftSeq) return;
    draftLines.value = [];
    uni.showToast({ title: e instanceof Error ? e.message : '建议加载失败', icon: 'none' });
  } finally {
    if (seq === draftSeq) draftLoading.value = false;
  }
}

function toggleLine(line: DraftLine) {
  line.selected = !line.selected;
  if (line.selected && line.qty <= 0) line.qty = Math.max(1, line.suggestQty || 1);
}

function adjustQty(line: DraftLine, delta: number) {
  const next = Math.max(0, (line.qty || 0) + delta);
  line.qty = next;
  if (next > 0) line.selected = true;
  else line.selected = false;
}

async function submit() {
  if (!canSubmit.value || submitting.value) return;
  const deviceId = selectedDeviceId.value;
  const lines = draftLines.value
    .filter((l) => l.selected && l.qty > 0)
    .map((l) => ({ skuId: l.skuId, requestedQty: l.qty }));
  if (!lines.length) {
    uni.showToast({ title: '请选择要货商品', icon: 'none' });
    return;
  }
  submitting.value = true;
  try {
    const created = await merchantApi.submitReplenishmentRequest({
      deviceId,
      notes: notes.value.trim() || undefined,
      lines
    });
    uni.showToast({ title: `已提交 #${created.requestId}`, icon: 'success' });
    notes.value = '';
    mode.value = 'list';
    listStatus.value = 'SUBMITTED';
    await loadRequests();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '提交失败', icon: 'none' });
  } finally {
    submitting.value = false;
  }
}

async function loadRequests() {
  if (!canView.value) return;
  const seq = ++listSeq;
  listLoading.value = true;
  listError.value = '';
  try {
    const rows = (await merchantApi.replenishmentRequests(listStatus.value || undefined)) || [];
    if (seq !== listSeq) return;
    requests.value = rows;
  } catch (e) {
    if (seq !== listSeq) return;
    listError.value = e instanceof Error ? e.message : '加载失败';
    requests.value = [];
  } finally {
    if (seq === listSeq) listLoading.value = false;
  }
}

function formatTime(value?: string) {
  return formatDateTimeShort(value, '暂无');
}

function canGoReplenish(req: MerchantReplenishmentRequest) {
  return req.status === 'ACCEPTED' && !!req.replenishmentTaskId;
}

function onRequestCard(req: MerchantReplenishmentRequest) {
  if (!canGoReplenish(req)) return;
  goReplenish(req);
}

function goReplenish(req: MerchantReplenishmentRequest) {
  if (!req.replenishmentTaskId) return;
  uni.navigateTo({
    url: `/pages/replenishment/replenishment?taskId=${req.replenishmentTaskId}`
  });
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 0;
  background: #ffffff;
}
.tabs {
  display: flex;
  gap: 12rpx;
  margin-bottom: 16rpx;
}
.tab {
  flex: 1;
  text-align: center;
  padding: 18rpx 0;
  border-radius: 999rpx;
  background: #fff;
  color: #64748b;
  font-size: 26rpx;
  border: 1rpx solid #e2e8f0;
}
.tab.active {
  background: #134e4a;
  color: #fff;
  border-color: #134e4a;
  font-weight: 600;
}
.panel {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 22rpx;
  border: 1rpx solid #e2e8f0;
}
.label {
  display: block;
  font-size: 24rpx;
  color: #64748b;
  margin-bottom: 12rpx;
}
.picker {
  padding: 18rpx 20rpx;
  border-radius: 12rpx;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  font-size: 28rpx;
  color: #0f172a;
  overflow: hidden;
  max-height: 88rpx;
}
.hint {
  font-size: 22rpx;
  color: #0f766e;
  margin-top: 10rpx;
}
.row-between {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.empty-inline {
  padding: 24rpx 0;
  text-align: center;
  color: #94a3b8;
  font-size: 24rpx;
}
.trunc-hint {
  display: block;
  text-align: center;
  color: #94a3b8;
  font-size: 22rpx;
  padding: 8rpx 0 16rpx;
}
.line-row {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 16rpx 0;
  border-top: 1rpx solid #f1f5f9;
}
.line-row:first-of-type {
  border-top: none;
}
.check {
  width: 36rpx;
  height: 36rpx;
  border-radius: 8rpx;
  border: 2rpx solid #cbd5e1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22rpx;
  color: #fff;
  flex-shrink: 0;
}
.check.on {
  background: #0f766e;
  border-color: #0f766e;
}
.line-copy {
  flex: 1;
  min-width: 0;
}
.sku-name {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #0f172a;
}
.sku-meta {
  display: block;
  font-size: 22rpx;
  color: #94a3b8;
  margin-top: 4rpx;
}
.sku-reason {
  display: block;
  margin-top: 4rpx;
  color: #0f766e;
  font-size: 20rpx;
}
.qty-box {
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.qty-btn {
  width: 48rpx;
  height: 48rpx;
  border-radius: 12rpx;
  background: #ecfdf5;
  color: #0f766e;
  text-align: center;
  line-height: 48rpx;
  font-size: 30rpx;
  font-weight: 600;
}
.qty-val {
  min-width: 40rpx;
  text-align: center;
  font-size: 28rpx;
  font-weight: 600;
}
.input {
  display: block;
  width: 100%;
  height: 80rpx;
  min-height: 80rpx;
  line-height: 80rpx;
  padding: 0 18rpx;
  border-radius: 12rpx;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  font-size: 26rpx;
  box-sizing: border-box;
  color: #0f172a;
}
.btn-primary {
  margin-top: 16rpx;
  background: linear-gradient(135deg, #134e4a, #0f766e);
  color: #fff;
  border-radius: 44rpx;
  padding: 0 40rpx;
  min-height: 88rpx;
  line-height: 1.2;
  text-align: center;
  font-weight: 600;
  font-size: 30rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.22);
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
.btn-primary .btn-label {
  display: block;
  width: 100%;
  text-align: center;
  color: #fff;
  font-weight: 600;
  font-size: 30rpx;
  line-height: 1.2;
}
.btn-primary::after {
  border: none;
}
.btn-primary.disabled {
  opacity: 0.45;
}
.err {
  color: #b91c1c;
  font-size: 24rpx;
  text-align: center;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
  margin-bottom: 4rpx;
}
.filter {
  padding: 10rpx 20rpx;
  border-radius: 999rpx;
  background: #fff;
  color: #64748b;
  font-size: 22rpx;
  border: 1rpx solid #e2e8f0;
}
.filter.active {
  background: #ccfbf1;
  color: #0f766e;
  border-color: #99f6e4;
  font-weight: 600;
}
.req-card {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.req-card.clickable {
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.req-card-hover {
  background: #f8fafc !important;
}
.req-id {
  font-size: 22rpx;
  color: #94a3b8;
}
.status {
  font-size: 22rpx;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
  background: #fef3c7;
  color: #92400e;
}
.status.accepted {
  background: #dcfce7;
  color: #166534;
}
.status.rejected {
  background: #fee2e2;
  color: #991b1b;
}
.status.completed {
  background: #e0e7ff;
  color: #3730a3;
}
.lines {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  margin-top: 8rpx;
}
.line-chip {
  font-size: 22rpx;
  background: #f0fdfa;
  color: #0f766e;
  padding: 6rpx 12rpx;
  border-radius: 999rpx;
}
.reject {
  font-size: 22rpx;
  color: #b91c1c;
}
.notes {
  font-size: 22rpx;
  color: #64748b;
}
.req-card.clickable .req-id,
.req-card.clickable .status,
.req-card.clickable .sku-name,
.req-card.clickable .sku-meta,
.req-card.clickable .lines,
.req-card.clickable .reject,
.req-card.clickable .notes,
.req-card.clickable .detail-btn {
  pointer-events: none;
}
.detail-btn {
  margin-top: 12rpx;
  align-self: flex-start;
  padding: 12rpx 28rpx;
  border-radius: 999rpx;
  background: #0f766e;
  color: #fff;
  font-size: 24rpx;
  font-weight: 600;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
