<template>
  <view class="page">
    <app-nav-bar title="要货申请" />
    <view class="page-body">
      <view class="tabs">
        <view
          role="button"
          class="tab"
          :class="{ active: mode === 'create' }"
          @click="mode = 'create'"
          >发起要货</view
        >
        <view role="button" class="tab" :class="{ active: mode === 'list' }" @click="switchToList"
          >我的申请</view
        >
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
            v-for="line in draftLines"
            role="button"
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
              v-for="(item, idx) in evidenceItems"
              role="button"
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
              v-if="evidenceItems.length < 5"
              role="button"
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
            v-for="t in statusTabs"
            role="button"
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
          v-for="req in requests"
          role="button"
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
          <view
            v-if="req.status === 'ACCEPTED' && req.replenishmentTaskId"
            class="detail-btn app-link-chevron"
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
import { showError, showSuccess } from '@/utils/notify';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { useAutoRefresh } from '@/composables/use-auto-refresh';
import { displayLabel } from '@aicabinet/shared-dict';
import { formatDateTimeShort } from '@aicabinet/shared-uni/format';
import { assertLocalImageSize } from '@aicabinet/shared-uni/upload-limits';
import { hasPerm, merchantApi, softFallback, isMerchantLoggedIn } from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import type {
  DeviceInfo,
  DeviceSlot,
  MerchantMe,
  OpenApiMerchantReplenishmentRequestDto,
  OpenApiReplenishmentSuggestDto
} from '@aicabinet/shared-types';
import { UI_COPY, loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import {
  appendOrphanSuggestions,
  buildSuggestMap,
  mergeSlotDraftLine,
  sortDraftLines,
  suggestReasonLabel,
  type RequestDraftLine
} from '@/utils/request-draft';
import {
  REQUEST_EVIDENCE_MAX,
  applyAdjustDraftQty,
  applyToggleDraftLine,
  buildSubmitReplenishmentRequestBody,
  canAddRequestEvidence,
  canGoReplenishFromRequest,
  canStartRequestSubmit,
  evidencePreviewUrls,
  remainingEvidenceSlots,
  requestActionErrorMessage,
  selectedRequestLines
} from '@/utils/request-submit';

type DraftLine = RequestDraftLine;

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
const requests = ref<OpenApiMerchantReplenishmentRequestDto[]>([]);

const selectedCount = computed(
  () => draftLines.value.filter((l) => l.selected && l.qty > 0).length
);
const canSubmit = computed(
  () => canRequest.value && !!selectedDeviceId.value && selectedCount.value > 0
);

onLoad((opts) => {
  if (!isMerchantLoggedIn()) {
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
  void bootstrap().finally(() => uni.stopPullDownRefresh());
});

/**
 * 补货申请的审核（接单 / 驳回）由运营侧异步处理：列表里还有 SUBMITTED 时
 * 每 10 秒静默跟进一次，全部有结论即停表 —— 商户不必手动下拉才知道过没过。
 */
useAutoRefresh({
  intervalMs: 10_000,
  load: () => (mode.value === 'list' ? loadRequests() : Promise.resolve()),
  shouldContinue: () =>
    requests.value.some((r) => String(r.status || '').toUpperCase() === 'SUBMITTED'),
  maxDurationMs: 300_000,
  canRefresh: () => mode.value === 'list' && !listLoading.value && !submitting.value
});

watch(selectedDeviceId, (id, prev) => {
  if (id && id !== prev && mode.value === 'create') void loadDraft();
});

async function bootstrap(preferDeviceId?: string) {
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return;
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
      softFallback(
        merchantApi.replenishmentSuggestions(deviceId),
        [] as OpenApiReplenishmentSuggestDto[],
        '补货建议'
      ),
      softFallback(merchantApi.deviceSlots(deviceId), [] as DeviceSlot[], '货道')
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
    draftLines.value = sortDraftLines([...bySku.values()]);
  } catch (e) {
    if (seq !== draftSeq) return;
    draftLines.value = [];
    showError(e instanceof Error ? e.message : '建议加载失败');
  } finally {
    if (seq === draftSeq) draftLoading.value = false;
  }
}

function toggleLine(line: DraftLine) {
  const toast = applyToggleDraftLine(line);
  if (toast) showError(toast);
}

function adjustQty(line: DraftLine, delta: number) {
  applyAdjustDraftQty(line, delta);
}

async function submit() {
  const lines = selectedRequestLines(draftLines.value);
  const gate = canStartRequestSubmit({
    canSubmit: canSubmit.value,
    submitting: submitting.value,
    lineCount: lines.length
  });
  if (gate === 'blocked') return;
  if (gate === 'no_lines') {
    showError('请选择要货商品');
    return;
  }
  const deviceId = selectedDeviceId.value;
  submitting.value = true;
  try {
    const created = await merchantApi.submitReplenishmentRequest(
      buildSubmitReplenishmentRequestBody({
        deviceId,
        notes: notes.value,
        lines,
        evidenceItems: evidenceItems.value
      })
    );
    showSuccess(`已提交 #${created.requestId}`);
    notes.value = '';
    evidenceItems.value = [];
    mode.value = 'list';
    listStatus.value = 'SUBMITTED';
    await loadRequests();
  } catch (e) {
    showError(requestActionErrorMessage(e, '提交失败'));
  } finally {
    submitting.value = false;
  }
}

async function addEvidence() {
  const gate = canAddRequestEvidence({
    canRequest: canRequest.value,
    currentCount: evidenceItems.value.length
  });
  if (gate === 'denied') return;
  if (gate === 'full') {
    showError(`最多 ${REQUEST_EVIDENCE_MAX} 张`);
    return;
  }
  const paths = await new Promise<string[]>((resolve) => {
    uni.chooseImage({
      count: remainingEvidenceSlots(evidenceItems.value.length),
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
      showError(requestActionErrorMessage(e, '上传失败'));
      break;
    }
  }
}

function previewEvidence(index: number) {
  const urls = evidencePreviewUrls(evidenceItems.value);
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
    listError.value = requestActionErrorMessage(e, '加载失败');
    requests.value = [];
  } finally {
    if (seq === listSeq) listLoading.value = false;
  }
}

function formatTime(value?: string) {
  return formatDateTimeShort(value, '暂无');
}

function canGoReplenish(req: OpenApiMerchantReplenishmentRequestDto) {
  return canGoReplenishFromRequest(req);
}

function onRequestCard(req: OpenApiMerchantReplenishmentRequestDto) {
  if (!canGoReplenish(req)) return;
  goReplenish(req);
}

function goReplenish(req: OpenApiMerchantReplenishmentRequestDto) {
  if (!req.replenishmentTaskId) return;
  uni.navigateTo({
    url: `/pages/replenishment/replenishment?taskId=${req.replenishmentTaskId}`
  });
}
</script>

<style scoped src="./request.page.css"></style>
