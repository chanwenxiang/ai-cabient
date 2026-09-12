<template>
  <view class="page">
    <app-nav-bar title="要货申请" />
    <view class="page-body">
      <view class="tabs">
        <view role="button" class="tab" :class="{ active: mode === 'create' }" @click="mode = 'create'"
          >发起要货</view
        >
        <view role="button" class="tab" :class="{ active: mode === 'list' }" @click="switchToList">我的申请</view>
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
            <text role="button" class="hint" @click="loadDraft">刷新建议</text>
          </view>
          <view v-if="draftLoading" class="empty-inline">{{ loadingLabel('建议') }}</view>
          <view v-else-if="!draftLines.length" class="empty-inline">
            该柜机暂无可要货商品（无绑定货道 SKU）
          </view>
          <view
            v-for="line in draftLines" role="button"
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
            <view role="button" class="qty-box" @click.stop>
              <text role="button" class="qty-btn" @click="adjustQty(line, -1)">−</text>
              <text class="qty-val">{{ line.qty }}</text>
              <text role="button" class="qty-btn" @click="adjustQty(line, 1)">+</text>
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

        <view class="card">
          <view class="row-between">
            <text class="label">现场照片（可选）</text>
            <text class="hint">{{ evidenceItems.length }}/5</text>
          </view>
          <text class="hint block-hint">缺货柜况、陈列等，便于运营审核</text>
          <view class="evidence-row">
            <view
              v-for="(item, idx) in evidenceItems" role="button"
              :key="item.fileId || item.localPath"
              class="evidence-thumb-wrap"
              @click="previewEvidence(idx)"
            >
              <image
                class="evidence-thumb"
                :src="item.localPath"
                mode="aspectFill"
                :aria-label="`现场照片 ${idx + 1}`"
              />
            </view>
            <view
              v-if="evidenceItems.length < 5" role="button"
              class="evidence-add"
              aria-label="添加现场照片"
              @click="addEvidence"
            >
              <text class="evidence-add-plus">+</text>
              <text class="evidence-add-label">拍照</text>
            </view>
          </view>
        </view>

        <app-button
          :disabled="submitting || !canSubmit"
          :loading="submitting"
          :label="submitting ? '提交中…' : `提交要货（${selectedCount} 种）`"
          @click="submit"
        />
        <text v-if="!canRequest" class="err">当前账号无要货权限</text>
      </view>

      <view v-else class="panel">
        <view class="filters">
          <view
            v-for="t in statusTabs" role="button"
            :key="t.value"
            class="filter"
            :class="{ active: listStatus === t.value }"
            @click="changeListStatus(t.value)"
            >{{ t.label }}</view
          >
        </view>
        <view v-if="listLoading" class="empty-inline">{{ UI_COPY.loading }}</view>
        <view v-else-if="listError" class="empty-inline err">{{ listError }}</view>
        <view v-else-if="!requests.length" class="empty-inline">暂无要货申请</view>
        <view
          v-for="req in requests" role="button"
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
          <text v-if="req.evidenceCount" class="notes">附图 {{ req.evidenceCount }} 张</text>
          <view v-if="req.status === 'ACCEPTED' && req.replenishmentTaskId" class="detail-btn app-link-chevron"
            >去补货</view
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
import {
  showError,
  showSuccess
} from '@/utils/notify';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { displayLabel } from '@aicabinet/shared-dict';
import { formatDateTimeShort } from '@aicabinet/shared-uni/format';
import { assertLocalImageSize } from '@aicabinet/shared-uni/upload-limits';
import {
  hasPerm,
  merchantApi,
  type MerchantReplenishmentRequest,
  type MerchantReplenishmentSuggest
} from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import type { DeviceInfo, DeviceSlot, MerchantMe } from '@aicabinet/shared-types';
import { UI_COPY, loadingLabel } from '@aicabinet/shared-uni/ui-copy';

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
const evidenceItems = ref<{ localPath: string; fileId?: number }[]>([]);

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
  bootstrap()
    .finally(() => uni.stopPullDownRefresh())
    .catch(() => {});
});

watch(selectedDeviceId, (id, prev) => {
  if (id && id !== prev && mode.value === 'create') void loadDraft();
});

async function bootstrap(preferDeviceId?: string) {
  try {
    await refreshMe();
  } catch {
    if (!uni.getStorageSync('merchant_token')) return;
    seedMerchantMeDisplayCache(me);
  }
  if (!canView.value) {
    showError('无补货查看权限');
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return;
  }
  try {
    devices.value = (await merchantApi.devices()) || [];
  } catch (e) {
    showError(e instanceof Error ? e.message : '柜机加载失败');
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
    qty: defaultQty,
    selected: defaultQty > 0
  });
  return skuId;
}

function appendOrphanSuggestions(
  bySku: Map<string, DraftLine>,
  suggestMap: Map<string, MerchantReplenishmentSuggest>
) {
  for (const [skuId, sug] of suggestMap) {
    if (bySku.has(skuId)) continue;
    const suggestQty = Number(sug.suggestQty) || 0;
    const qty = suggestQty > 0 ? suggestQty : 0;
    bySku.set(skuId, {
      skuId,
      skuName: skuId,
      currentQty: Number(sug.currentQty) || 0,
      capacity: Number(sug.capacity) || 0,
      suggestQty,
      soldQty7d: Number(sug.soldQty7d) || 0,
      suggestReason: String(sug.suggestReason || ''),
      qty,
      selected: suggestQty > 0
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
      const skuId = mergeSlotDraftLine(
        bySku,
        slot,
        suggestMap.get(String(slot.assignedSkuId || '').trim())
      );
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
    showError(e instanceof Error ? e.message : '建议加载失败');
  } finally {
    if (seq === draftSeq) draftLoading.value = false;
  }
}

function toggleLine(line: DraftLine) {
  line.selected = !line.selected;
  if (line.selected && line.qty <= 0) {
    line.qty = line.suggestQty > 0 ? line.suggestQty : 0;
    if (line.qty <= 0) {
      showError('请填写要货数量');
    }
  }
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
    showError('请选择要货商品');
    return;
  }
  submitting.value = true;
  try {
    const evidenceFileIds = evidenceItems.value
      .map((item) => item.fileId)
      .filter((id): id is number => typeof id === 'number' && id > 0);
    const created = await merchantApi.submitReplenishmentRequest({
      deviceId,
      notes: notes.value.trim() || undefined,
      lines,
      evidenceFileIds: evidenceFileIds.length ? evidenceFileIds : undefined
    });
    showSuccess(`已提交 #${created.requestId}`);
    notes.value = '';
    evidenceItems.value = [];
    mode.value = 'list';
    listStatus.value = 'SUBMITTED';
    await loadRequests();
  } catch (e) {
    showError(e instanceof Error ? e.message : '提交失败');
  } finally {
    submitting.value = false;
  }
}

async function addEvidence() {
  if (!canRequest.value) return;
  if (evidenceItems.value.length >= 5) {
    showError('最多 5 张');
    return;
  }
  const paths = await new Promise<string[]>((resolve) => {
    uni.chooseImage({
      count: 5 - evidenceItems.value.length,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const raw = res.tempFilePaths || [];
        resolve(Array.isArray(raw) ? raw : [raw]);
      },
      fail: () => resolve([])
    });
  });
  for (const path of paths) {
    try {
      await assertLocalImageSize(path);
      const uploaded = await merchantApi.uploadReplenishmentRequestEvidence(path);
      evidenceItems.value.push({ localPath: path, fileId: uploaded.fileId });
    } catch (e) {
      showError(e instanceof Error ? e.message : '上传失败');
      break;
    }
  }
}

function previewEvidence(index: number) {
  const urls = evidenceItems.value.map((i) => i.localPath).filter(Boolean);
  if (!urls.length) return;
  uni.previewImage({ urls, current: urls[index] || urls[0] });
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
  background: var(--card-bg, #ffffff);
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
  border-radius: var(--radius-pill);
  background: var(--card-bg, #fff);
  color: var(--text-muted);
  font-size: var(--font-size-body);
  border: 1rpx solid var(--color-border);
}
.tab.active {
  background: var(--brand-deep);
  color: var(--white);
  border-color: var(--brand-deep);
  font-weight: 600;
}
.panel {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.card {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-panel);
  padding: 22rpx;
  border: 1rpx solid var(--color-border);
}
.label {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  margin-bottom: 12rpx;
}
.picker {
  padding: 18rpx 20rpx;
  border-radius: var(--radius-control);
  background: var(--page-bg, #f8fafc);
  border: 1rpx solid var(--color-border);
  font-size: var(--font-size-md);
  color: var(--text-primary, #0f172a);
  overflow: hidden;
  max-height: 88rpx;
}
.hint {
  font-size: var(--font-size-sm);
  color: var(--brand);
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
  color: var(--text-subtle);
  font-size: var(--font-size-caption);
}
.trunc-hint {
  display: block;
  text-align: center;
  color: var(--text-subtle);
  font-size: var(--font-size-sm);
  padding: 8rpx 0 16rpx;
}
.line-row {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 16rpx 0;
  border-top: 1rpx solid var(--color-border-subtle, #f1f5f9);
}
.line-row:first-of-type {
  border-top: none;
}
.check {
  width: 36rpx;
  height: 36rpx;
  border-radius: var(--radius-tag);
  border: 2rpx solid var(--text-subtle, #cbd5e1);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-sm);
  color: var(--white);
  flex-shrink: 0;
}
.check.on {
  background: var(--brand);
  border-color: var(--brand);
}
.line-copy {
  flex: 1;
  min-width: 0;
}
.sku-name {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary, #0f172a);
}
.sku-meta {
  display: block;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
  margin-top: 4rpx;
}
.sku-reason {
  display: block;
  margin-top: 4rpx;
  color: var(--brand);
  font-size: var(--font-size-xs);
}
.qty-box {
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.qty-btn {
  width: 48rpx;
  height: 48rpx;
  border-radius: var(--radius-control);
  background: var(--brand-soft);
  color: var(--brand);
  text-align: center;
  line-height: 48rpx;
  font-size: var(--font-size-lg);
  font-weight: 600;
}
.qty-val {
  min-width: 40rpx;
  text-align: center;
  font-size: var(--font-size-md);
  font-weight: 600;
}
.input {
  display: block;
  width: 100%;
  height: 80rpx;
  min-height: 80rpx;
  line-height: 80rpx;
  padding: 0 18rpx;
  border-radius: var(--radius-control);
  background: var(--page-bg, #f8fafc);
  border: 1rpx solid var(--color-border);
  font-size: var(--font-size-body);
  box-sizing: border-box;
  color: var(--text-primary, #0f172a);
}
.app-btn .btn-label {
  display: block;
  width: 100%;
  text-align: center;
  color: var(--white);
  font-weight: 600;
  font-size: var(--font-size-lg);
  line-height: 1.2;
}
.app-btn.is-disabled {
  opacity: 0.45;
}
.err {
  color: var(--color-danger);
  font-size: var(--font-size-caption);
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
  border-radius: var(--radius-pill);
  background: var(--card-bg, #fff);
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  border: 1rpx solid var(--color-border);
}
.filter.active {
  background: var(--brand-mist);
  color: var(--brand);
  border-color: var(--brand-mist, #99f6e4);
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
  background: var(--page-bg, #f8fafc) !important;
}
.req-id {
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
.status {
  font-size: var(--font-size-sm);
  padding: 4rpx 12rpx;
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, var(--warning, #b45309) 14%, var(--white));
  color: var(--warning, #92400e);
}
.status.accepted {
  background: var(--brand-soft, #dcfce7);
  color: var(--brand-deep, #166534);
}
.status.rejected {
  background: color-mix(in srgb, var(--danger, #b91c1c) 12%, var(--white));
  color: var(--danger, #991b1b);
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
  font-size: var(--font-size-sm);
  background: var(--page-bg, #f0fdfa);
  color: var(--brand);
  padding: 6rpx 12rpx;
  border-radius: var(--radius-pill);
}
.reject {
  font-size: var(--font-size-sm);
  color: var(--color-danger);
}
.notes {
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.block-hint {
  display: block;
  margin-bottom: 12rpx;
}
.evidence-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.evidence-thumb-wrap,
.evidence-add {
  width: 128rpx;
  height: 128rpx;
  border-radius: var(--radius-control);
  overflow: hidden;
}
.evidence-thumb {
  width: 100%;
  height: 100%;
}
.evidence-add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 1rpx dashed var(--text-subtle, #cbd5e1);
  background: var(--page-bg, #f8fafc);
}
.evidence-add-plus {
  font-size: var(--font-size-h2);
  color: var(--text-muted);
  line-height: 1;
}
.evidence-add-label {
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  margin-top: 4rpx;
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
  border-radius: var(--radius-pill);
  background: var(--brand);
  color: var(--white);
  font-size: var(--font-size-caption);
  font-weight: 600;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
