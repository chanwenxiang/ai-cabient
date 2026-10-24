<template>
  <view class="page">
    <app-nav-bar title="发起补货" home-url="/pages/home/home" />
    <view class="page-body">
      <view class="panel">
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
            <text class="label">补货明细</text>
            <text role="button" class="hint" @click="loadDraft">刷新建议</text>
          </view>
          <text class="hint block-hint">已按缺货与销量建议预填数量，可直接调整</text>
          <view v-if="draftLoading" class="empty-inline">{{ loadingLabel('建议') }}</view>
          <view v-else-if="!draftLines.length" class="empty-inline">
            该柜机暂无可补商品（无绑定货道 SKU）
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
              <text class="sku-name">{{
                formatSkuNameWithSpec(line.skuName, line.spec, line.skuId)
              }}</text>
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

        <app-button
          :disabled="submitting || !canSubmit"
          :loading="submitting"
          :label="submitting ? '提交中…' : `发起补货（${selectedCount} 种）`"
          @click="submit"
        />
        <text class="submit-hint">提交后直接生成分仓出库与补货任务，无需审批</text>
        <text v-if="!canRequest" class="err">当前账号无补货权限</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { showError, showSuccess, showLongError } from '@/utils/notify';
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app';
import { formatSkuNameWithSpec } from '@aicabinet/shared-uni/format';
import { isRetriableMpTransportError } from '@aicabinet/shared-uni/request';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import { hasPerm, merchantApi, softFallback, isMerchantLoggedIn } from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import type {
  DeviceSlot,
  MerchantDeviceInfo,
  OpenApiReplenishmentSuggestDto
} from '@aicabinet/shared-types';
import {
  appendOrphanSuggestions,
  buildSuggestMap,
  mergeSlotDraftLine,
  pickDefaultDeviceIndex,
  sortDraftLines,
  suggestReasonLabel,
  type ReplenishDraftLine
} from '@/utils/self-replenish-draft';
import {
  applyAdjustDraftQty,
  applyToggleDraftLine,
  buildSelfReplenishBody,
  canStartSelfReplenish,
  newSelfReplenishRequestNo,
  nextRequestNoAfterSubmit,
  requestActionErrorMessage,
  selectedReplenishLines
} from '@/utils/self-replenish-submit';

type DraftLine = ReplenishDraftLine;

const { me, refresh: refreshMe } = useMerchantMe();
const preferredId = ref(getPreferredDeviceId());
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));

const devices = ref<MerchantDeviceInfo[]>([]);
const deviceIndex = ref(0);
const selectedDeviceId = computed(() => devices.value[deviceIndex.value]?.deviceId || '');
const deviceLabels = computed(() =>
  devices.value.map((d) => {
    const name = d.deviceName || d.deviceId;
    return preferredId.value && d.deviceId === preferredId.value ? `${name}（常驻）` : name;
  })
);

const draftLoading = ref(false);
let draftSeq = 0;
const draftLines = ref<DraftLine[]>([]);
const notes = ref('');
const submitting = ref(false);
// V401 幂等键：同一提交意图一次一键；传输类错误保留键重放，成功/业务失败清空。
let clientRequestNo = '';

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
  void bootstrap(deviceId);
});

onPullDownRefresh(() => {
  void bootstrap().finally(() => uni.stopPullDownRefresh());
});

watch(selectedDeviceId, (id, prev) => {
  if (id && id !== prev) void loadDraft();
});

async function bootstrap(preferDeviceId?: string) {
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return;
    seedMerchantMeDisplayCache(me);
  }
  if (!canRequest.value) {
    showError('无补货权限');
    uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
    return;
  }
  // 保留下拉刷新前用户手选的柜机（2026-10-23 第五轮审计 L-2）：旧逻辑一律回落
  // 常驻柜，手动选过柜机 B 的用户一刷新选择就被重置、草稿勾选一并丢失
  const keepDeviceId = devices.value[deviceIndex.value]?.deviceId || '';
  try {
    devices.value = (await merchantApi.devices()) || [];
  } catch (e) {
    showError(e instanceof Error ? e.message : '柜机加载失败');
    devices.value = [];
  }
  const prefer = preferDeviceId || keepDeviceId || preferredId.value;
  deviceIndex.value = pickDefaultDeviceIndex(devices.value, prefer);
  await loadDraft();
}

function onDevicePick(e: { detail: { value: string } }) {
  deviceIndex.value = Number(e.detail.value) || 0;
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
  const lines = selectedReplenishLines(draftLines.value);
  const gate = canStartSelfReplenish({
    canSubmit: canSubmit.value,
    submitting: submitting.value,
    lineCount: lines.length
  });
  if (gate === 'blocked') return;
  if (gate === 'no_lines') {
    showError('请选择补货商品');
    return;
  }
  const deviceId = selectedDeviceId.value;
  // V401 幂等契约：同一提交意图一次一键；传输类错误（超时）后保留键重试 ⇒ 服务端幂等返回原任务，
  // 不再重复出库；成功/业务失败清空 ⇒ 下次提交是新意图。对齐提现 requestNo 模式（2026-10-20）。
  if (!clientRequestNo) clientRequestNo = newSelfReplenishRequestNo();
  submitting.value = true;
  let outcome: 'success' | 'business_error' | 'transport_error' = 'business_error';
  try {
    const task = await merchantApi.selfInitiateReplenishment(
      buildSelfReplenishBody({ deviceId, notes: notes.value, lines, clientRequestNo })
    );
    outcome = 'success';
    showSuccess(`已生成补货任务 ${task.taskId}`);
    notes.value = '';
    // 修复 2026-10-14：清空勾选与数量，防止从任务页返回后一键重复下单（该接口直接生效）。
    draftLines.value = draftLines.value.map((line) => ({ ...line, selected: false, qty: 0 }));
    uni.navigateTo({ url: `/pages/replenishment/replenishment?taskId=${task.taskId}` });
  } catch (e) {
    // 接口层审计（2026-10-20）H2：超时=结果未知（服务端可能已生成出库+任务）；
    // 已带 clientRequestNo ⇒ 直接重试也幂等；文案仍引导核对，避免用户重复走整个表单。
    // 审计 2026-10-23 M3：后端业务拒绝（容量/限额说明）是长文案，走 showLongError 全文。
    const base = requestActionErrorMessage(e, '提交失败');
    const retriable = isRetriableMpTransportError(e);
    if (retriable) outcome = 'transport_error';
    showLongError(
      retriable ? base + '；提交结果未知，请先到「补货任务」列表核对是否已生成' : e,
      '提交失败'
    );
  } finally {
    clientRequestNo = nextRequestNoAfterSubmit(clientRequestNo, outcome);
    submitting.value = false;
  }
}
</script>

<style scoped src="./self-replenish.page.css"></style>
