<template>
  <view class="page">
    <app-nav-bar title="补货任务" />
    <view class="page-body">
      <view class="hero">
        <view class="hero-orb orb-one" /><view class="hero-orb orb-two" />
        <view class="hero-head">
          <text class="eyebrow">现场补货</text>
          <text class="title">补货任务</text>
          <text class="subtitle">{{ heroSubtitle }}</text>
        </view>
        <view class="stats">
          <view class="stat">
            <text class="stat-value">{{ pendingCount }}</text>
            <text class="stat-label">待处理</text>
          </view>
          <view class="stat">
            <text class="stat-value">{{ completedCount }}</text>
            <text class="stat-label">已完成</text>
          </view>
          <view class="stat">
            <text class="stat-value">{{ efficiencyRateText }}</text>
            <text class="stat-label">今日完成率</text>
          </view>
        </view>
        <view class="hero-actions">
          <button class="scan-primary" :loading="scanning" @click="onScan">扫码找柜</button>
          <view class="hero-secondary">
            <view class="clear-pill" role="button" hover-class="clear-pill-hover" @click="goRequest"
              >要货</view
            >
            <view
              v-if="preferredId && filterDeviceId !== preferredId"
              class="clear-pill"
              role="button"
              hover-class="clear-pill-hover"
              @click="usePreferredDevice"
              >常驻柜</view
            >
            <view
              v-if="filterDeviceId"
              class="clear-pill"
              role="button"
              hover-class="clear-pill-hover"
              @click="clearDeviceFilter"
              >清除筛选</view
            >
          </view>
        </view>
        <text v-if="filterDeviceId" class="filter-tip">
          当前筛选：{{ filterDeviceId }}
          <text v-if="filterDeviceId === preferredId">（常驻柜）</text>
        </text>
        <text v-else-if="preferredId" class="filter-tip muted"
          >常驻柜 {{ preferredId }} · 点「常驻柜」快速筛选</text
        >
      </view>

      <view v-if="!loading && pendingCount === 0" class="idle-tip">
        <text class="idle-title">今日暂无待补货</text>
        <text class="idle-desc"
          >可扫码巡柜查看缺货，或切换「已完成」回顾记录；新任务由调度下发</text
        >
      </view>

      <view v-if="lowStockList.length" class="patrol-card">
        <view class="patrol-head">
          <view>
            <text class="patrol-title">缺货巡柜</text>
            <text class="patrol-sub">按缺货严重度推荐，点此发起要货</text>
          </view>
          <text class="patrol-count">{{ lowStockList.length }} 台</text>
        </view>
        <view
          v-for="d in lowStockList"
          :key="d.deviceId"
          class="patrol-row"
          hover-class="patrol-row-hover"
          role="button"
          @click="goRequestForDevice(d.deviceId)"
        >
          <view class="patrol-name">
            <text class="device-name">{{ deviceName(d.deviceId) }}</text>
            <text class="device-code">{{ d.deviceId }}</text>
          </view>
          <view class="patrol-meta">
            <text class="patrol-badge">{{ d.skuCount }} 个 SKU 缺货</text>
            <text class="patrol-shortage">缺口 {{ d.shortageQty }} 件</text>
          </view>
        </view>
      </view>

      <view class="filters tabs-pill">
        <text
          v-for="item in statusOptions"
          role="button"
          :key="item.value"
          class="filter-chip"
          :class="{ active: status === item.value }"
          @click="changeStatus(item.value)"
          >{{ item.label }}</text
        >
      </view>

      <view v-if="loading && !allTasks.length" class="empty">{{ loadingLabel('任务') }}</view>
      <empty-state
        v-else-if="!tasks.length"
        icon="/static/menu/replenish.png"
        :title="emptyHint"
        hint="扫码到柜可查看缺货；新任务由调度下发"
      >
        <view class="empty-actions-row">
          <button
            v-if="pendingCount === 0 && completedCount > 0 && status !== 'COMPLETED'"
            class="empty-scan"
            @click="changeStatus('COMPLETED')"
          >
            查看已完成
          </button>
          <button
            v-if="status && pendingCount === 0 && completedCount > 0"
            class="empty-scan ghost"
            @click="changeStatus('')"
          >
            查看全部
          </button>
          <button class="empty-scan" @click="onScan">扫码到柜</button>
        </view>
      </empty-state>
      <view
        v-for="task in tasks"
        :key="task.taskId"
        class="task-card"
        hover-class="task-card-hover"
        role="button"
        @click="openTask(task)"
      >
        <view class="task-accent" />
        <view class="task-head">
          <view>
            <text class="device-name">{{ deviceName(task.deviceId, task.deviceName) }}</text>
            <text class="device-code">{{ task.deviceId }}</text>
            <text v-if="deviceAddressLine(task.deviceId)" class="task-addr">{{
              deviceAddressLine(task.deviceId)
            }}</text>
          </view>
          <text class="status" :class="task.status.toLowerCase()">
            {{ displayLabel('replenishment_task_status', task.status, '未知状态') }}
          </text>
        </view>
        <view class="task-meta">
          <text>任务 #{{ task.taskId }}</text>
          <text>{{ formatTime(task.createdAt) }}</text>
        </view>
        <view class="task-meta soft">
          <text v-if="task.routeName || task.routeId">{{ routeLabel(task) }}</text>
          <text v-if="task.plannedDate">计划 {{ formatDateOnly(task.plannedDate) }}</text>
          <text v-if="task.checkInAt">已签到</text>
          <text v-if="task.outboundId">出库 #{{ task.outboundId }}</text>
          <text v-if="evidenceCountOf(task.taskId) > 0" class="evidence-badge"
            >凭证 {{ evidenceCountOf(task.taskId) }} 张</text
          >
          <text v-else-if="task.status === 'COMPLETED'" class="evidence-badge muted"
            >无现场照片</text
          >
        </view>
        <view v-if="lineSummaryOf(task.taskId)" class="task-lines">{{
          lineSummaryOf(task.taskId)
        }}</view>
        <view v-if="displayTaskNotes(task.notes)" class="task-note">{{
          displayTaskNotes(task.notes)
        }}</view>
        <view class="detail-btn">
          {{ taskActionLabel(task) }}
        </view>
      </view>

      <ReplenishDetailSheet :visible="detailVisible" @close="closeDetail">
          <view class="sheet-head">
            <view>
              <text class="sheet-title">{{
                deviceName(selected?.deviceId, selected?.deviceName)
              }}</text>
              <text class="device-code"
                >任务 #{{ selected?.taskId }} · {{ selected?.deviceId }}</text
              >
              <text
                v-if="selected && (selected.routeName || selected.routeId || selected.plannedDate)"
                class="device-code"
                >{{ routeLabel(selected)
                }}{{
                  selected.plannedDate ? ` · 计划 ${formatDateOnly(selected.plannedDate)}` : ''
                }}</text
              >
            </view>
            <text class="close" role="button" aria-label="关闭" @click="closeDetail">×</text>
          </view>

          <ReplenishCabinetCard
            :device-id="selected?.deviceId"
            :address-line="selected?.deviceId ? deviceAddressLine(selected.deviceId) : ''"
            @copy="copyDeviceId(selected?.deviceId)"
            @navigate="navigateToDevice(selected?.deviceId)"
            @verify-scan="verifyCabinetScan"
          />

          <ReplenishStepBar
            :current-step="currentStep()"
            :completed="selected?.status === 'COMPLETED'"
            :checked-in="!!selected?.checkInAt"
            :door-opened="doorOpened"
            :lines-confirmed="linesConfirmed"
            :pull-off="detailIsPullOff"
          />

          <view
            v-if="
              canSkipLocation &&
              requireReplenishmentCheckInLocation &&
              canRequest &&
              selected?.status !== 'COMPLETED' &&
              !selected?.checkInAt
            "
            class="skip-loc-row"
            role="switch"
            :aria-checked="skipLocationCheck"
            data-testid="skip-location-toggle"
            @click="toggleSkipLocation"
          >
            <view class="skip-loc-copy">
              <text class="skip-loc-label">跳过定位验证</text>
              <text class="skip-loc-hint">室内定位不准时可暂关；仍可能要求在柜前签到</text>
            </view>
            <text class="skip-loc-switch" :class="{ on: skipLocationCheck }">{{
              skipLocationCheck ? '开' : '关'
            }}</text>
          </view>
          <text
            v-if="
              !requireReplenishmentCheckInLocation &&
              canRequest &&
              selected?.status !== 'COMPLETED' &&
              !selected?.checkInAt
            "
            class="door-tip"
          >
            本柜可不校验定位签到{{
              checkInMaxDistanceM > 0 ? `（若上报坐标，须在柜前 ${checkInMaxDistanceM} 米内）` : ''
            }}
          </text>

          <app-button
            v-if="canRequest && selected?.status !== 'COMPLETED' && !selected?.checkInAt"
            data-testid="replenish-checkin"
            :disabled="submitting"
            label="现场签到"
            @click="checkIn"
          />
          <app-button
            v-if="canRequest && selected?.status !== 'COMPLETED' && selected?.checkInAt"
            data-testid="replenish-open-door"
            :disabled="submitting"
            :label="doorOpened ? '再次开门' : detailIsPullOff ? '下架开门' : '补货开门'"
            @click="openDoor"
          />
          <text v-if="!canRequest && selected?.status !== 'COMPLETED'" class="door-tip">
            只读查看，需补货操作权限方可签到/开门/{{ detailIsPullOff ? '下架' : '上架' }}
          </text>
          <text v-if="doorOpened && openSessionId" class="door-tip">
            已开门，关门后继续核对{{ detailIsPullOff ? '下架' : '上架' }}
          </text>

          <ReplenishEvidenceSection
            :items="evidenceItems"
            :checked-in="!!selected?.checkInAt"
            :completed="selected?.status === 'COMPLETED'"
            :can-interact="canRequest"
            :require-evidence="requireReplenishmentEvidence"
            @preview="previewEvidence"
            @add="addEvidence"
          />

          <ReplenishLinesSection
            :lines="lines"
            :detail-loading="detailLoading"
            :pull-off="detailIsPullOff"
            :outbound-id="selected?.outboundId"
            :can-edit="canRequest && !linesConfirmed"
            :completed="selected?.status === 'COMPLETED'"
            :scanning="scanning"
            :sku-name="skuName"
            :sku-thumb="skuThumb"
            :product-glyph="productGlyph"
            :line-type-label="lineTypeLabel"
            :line-status-label="lineStatusLabel"
            :stock-delta-text="stockDeltaText"
            :is-pull-off-type="isPullOffType"
            :slot-options-for="slotOptionsFor"
            :slot-hint="slotHint"
            :slot-headroom="slotHeadroom"
            @adjust-qty="adjustQty"
            @scan-product="scanProduct"
            @assign-slot="assignSlot"
          />

          <ReplenishActionDock
            :show-dock="canRequest && selected?.status !== 'COMPLETED' && !!selected?.checkInAt"
            :completed="selected?.status === 'COMPLETED'"
            :lines-confirmed="linesConfirmed"
            :has-lines="!!lines.length"
            :submitting="submitting"
            :pull-off="detailIsPullOff"
            @confirm-lines="confirmLines"
            @complete="completeTask"
          />
      </ReplenishDetailSheet>

      <!-- H5 可访问确认框：替代 uni.showModal，便于自动化与读屏点击 -->
      <AppConfirmDialog
        :visible="confirmDialog.visible"
        :title="confirmDialog.title"
        :content="confirmDialog.content"
        :confirm-text="confirmDialog.confirmText"
        :cancel-text="confirmDialog.cancelText"
        :remember-label="confirmDialog.rememberLabel"
        :remember-checked="confirmDialog.rememberChecked"
        @update:remember-checked="(v) => (confirmDialog.rememberChecked = v)"
        @confirm="resolveConfirm(true)"
        @cancel="resolveConfirm(false)"
      />
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { emptyDisplay, formatDateTimeShort } from '@aicabinet/shared-uni/format';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import { assertLocalImageSize } from '@aicabinet/shared-uni/upload-limits';
import EmptyState from '@/components/empty-state.vue';
import AppConfirmDialog from '@/components/AppConfirmDialog.vue';
import ReplenishActionDock from '@/components/ReplenishActionDock.vue';
import ReplenishCabinetCard from '@/components/ReplenishCabinetCard.vue';
import ReplenishDetailSheet from '@/components/ReplenishDetailSheet.vue';
import ReplenishEvidenceSection from '@/components/ReplenishEvidenceSection.vue';
import ReplenishLinesSection from '@/components/ReplenishLinesSection.vue';
import ReplenishStepBar from '@/components/ReplenishStepBar.vue';
import {
  hasPerm,
  isMerchantLoggedIn,
  merchantApi
} from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { useAppConfirmDialog } from '@/composables/useAppConfirmDialog';
import { useReplenishmentDoorState } from '@/composables/useReplenishmentDoorState';
import { useReplenishmentFulfillment } from '@/composables/useReplenishmentFulfillment';
import { useReplenishmentList } from '@/composables/useReplenishmentList';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import { promptText } from '@/utils/text-prompt';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import { getSkipCheckInLocation, setSkipCheckInLocation } from '@/utils/checkin-location-pref';
import { showDevTools } from '@/utils/runtime-flags';
import { API_BASE_URL } from '@/config/api';
import type { DeviceSlot } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canReplenish = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));
/** 系统参数控制；缺省 true（与后端一致） */
const requireReplenishmentEvidence = computed(
  () => me.value?.requireReplenishmentEvidence !== false
);
const requireReplenishmentDoor = computed(() => me.value?.requireReplenishmentDoor !== false);
const requireReplenishmentCheckInLocation = computed(
  () => me.value?.requireReplenishmentCheckInLocation !== false
);
const checkInMaxDistanceM = computed(() => {
  const n = me.value?.replenishmentCheckInMaxDistanceM;
  return typeof n === 'number' && Number.isFinite(n) ? n : 500;
});
const canSkipLocation = showDevTools();
if (!canSkipLocation) {
  setSkipCheckInLocation(false);
}
type Task = import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto;
type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;

const preferredId = ref(getPreferredDeviceId());
const {
  loading,
  allTasks,
  evidenceCountMap,
  lineSummaryMap,
  devices,
  skus,
  efficiency,
  lowStockList,
  status,
  filterDeviceId,
  tasks,
  pendingCount,
  completedCount,
  evidenceCountOf,
  lineSummaryOf,
  syncTaskInList,
  changeStatus,
  clearDeviceFilter: clearListDeviceFilter,
  emptyHintForDeviceFilter,
  emptyHintForStatusFilter,
  fetchList,
  isLatestLoad
} = useReplenishmentList({ preferredId });

const detailLoading = ref(false);
const submitting = ref(false);
const scanning = ref(false);
const focusTaskId = ref<number | null>(null);
/** Deep-link query applied once; cleared so onShow/load won't reopen the same task. */
let pendingDeepLink = false;
const detailVisible = ref(false);
/** 避免「点卡片打开」同一轮点击落到遮罩上立刻关掉 */
const sheetCloseArmed = ref(false);
const selected = ref<Task | null>(null);
const lines = ref<Line[]>([]);
const linesConfirmed = ref(false);
const evidenceItems = ref<{ localPath: string; fileId?: number }[]>([]);
const {
  doorOpened,
  openSessionId,
  restoreDoorState,
  syncDoorStateFromServer,
  persistDoorState,
  clearDoorState
} = useReplenishmentDoorState();
/** slotCode -> { maxLevel, bookQty } */
const slotCaps = ref<Record<string, { maxLevel: number; bookQty: number }>>({});
const deviceSlotsList = ref<DeviceSlot[]>([]);
const skipLocationCheck = ref(canSkipLocation && getSkipCheckInLocation());

const { confirmDialog, askConfirm, resolveConfirm } = useAppConfirmDialog({
  onRemember: () => {
    if (!canSkipLocation) return;
    skipLocationCheck.value = true;
    setSkipCheckInLocation(true);
  }
});

type DeviceMeta = {
  deviceId?: string;
  deviceName?: string;
  address?: string;
  routeCode?: string;
  latitude?: number;
  longitude?: number;
};

const heroSubtitle = computed(() => '扫码到柜 → 签到 → 开门 → 核对履约');
const efficiencyRateText = computed(() =>
  efficiency.value ? `${efficiency.value.completionRatePercent}%` : '暂无'
);
const detailIsPullOff = computed(() => {
  if (!lines.value.length) {
    const notes = String(selected.value?.notes || '');
    return /from-expiry|PULL_OFF|下架/i.test(notes);
  }
  return lines.value.every((l) => isPullOffType(l.lineType));
});

const { checkIn, openDoor, adjustQty, confirmLines, completeTask } = useReplenishmentFulfillment({
  selected,
  lines,
  linesConfirmed,
  evidenceItems,
  submitting,
  doorOpened,
  openSessionId,
  skipLocationCheck,
  lineSummaryMap,
  allTasks,
  canRequest,
  canSkipLocation,
  requireReplenishmentEvidence,
  requireReplenishmentDoor,
  requireReplenishmentCheckInLocation,
  checkInMaxDistanceM,
  detailIsPullOff,
  askConfirm,
  getSkipCheckInLocation,
  persistDoorState,
  clearDoorState,
  syncTaskInList,
  reloadList: load,
  addEvidence,
  isPullOffType,
  formatLineSummary,
  slotHeadroom
});

function isPullOffType(type?: string) {
  const code = String(type || 'RESTOCK').toUpperCase();
  return code === 'PULL_OFF' || code === 'REMOVE' || code === 'PULL';
}

function lineTypeLabel(type?: string) {
  return isPullOffType(type) ? '下架' : '上架';
}

function lineStatusLabel(line: Line) {
  if (line.applied) return isPullOffType(line.lineType) ? '已下架' : '已入柜';
  return isPullOffType(line.lineType) ? '待下架' : '待上架';
}

function taskLooksPullOff(task: Task) {
  return /from-expiry|PULL_OFF|下架|临期/i.test(String(task.notes || ''));
}

function knownTaskNoteLabel(raw: string): string {
  if (/from-expiry|NEAR_EXPIRY/i.test(raw)) return '临期商品下架';
  if (/PULL_OFF/i.test(raw) && !/[\u4e00-\u9fff]/.test(raw)) return '下架任务';
  return '';
}

function stripMachineTaskNoteTokens(raw: string): string {
  return raw
    .replaceAll(/from-expiry:\d+/gi, '')
    .replaceAll(/\bNEAR_EXPIRY\b/gi, '')
    .replaceAll(/\bPULL_OFF\b/gi, '')
    .replaceAll(/\bseq=\d+\b/gi, '')
    .replaceAll(/\bdist=\d+m?\b/gi, '')
    .replaceAll(/[|;,]+/g, ' ')
    .trim();
}

function isOpaqueMachineNote(cleaned: string): boolean {
  return !/[\u4e00-\u9fff]/.test(cleaned) && /^[\w:=\-.\s]+$/.test(cleaned);
}

/** 机器备注转可读文案；seq=/dist= 等内部字段不展示 */
function displayTaskNotes(notes?: string): string {
  const raw = String(notes || '').trim();
  if (!raw) return '';
  const known = knownTaskNoteLabel(raw);
  if (known) return known;
  const cleaned = stripMachineTaskNoteTokens(raw);
  if (!cleaned || isOpaqueMachineNote(cleaned)) return '';
  return cleaned;
}

function taskActionLabel(task: Task) {
  if (task.status === 'COMPLETED') return '查看完成明细';
  const pull = taskLooksPullOff(task);
  if (task.checkInAt) return pull ? '继续下架' : '继续补货';
  return pull ? '开始下架' : '开始补货';
}

const statusOptions = computed(() => [
  { value: '', label: '全部' },
  ...dictOptions('replenishment_task_status').filter((item) =>
    ['PENDING', 'IN_PROGRESS', 'COMPLETED'].includes(item.value)
  )
]);

const emptyHint = computed(() => {
  if (filterDeviceId.value) return emptyHintForDeviceFilter();
  return emptyHintForStatusFilter();
});

function applyRouteQuery(opts?: Record<string, string | undefined>) {
  const deviceId = opts?.deviceId || readHashQuery('deviceId');
  const taskIdRaw = opts?.taskId || readHashQuery('taskId');
  let changed = false;
  if (deviceId) {
    filterDeviceId.value = String(deviceId).trim().toUpperCase();
    changed = true;
  }
  if (taskIdRaw) {
    const id = Number(taskIdRaw);
    if (Number.isFinite(id) && id > 0) {
      focusTaskId.value = id;
      changed = true;
    }
  }
  if (deviceId || taskIdRaw) {
    status.value = '';
  }
  if (changed) pendingDeepLink = true;
}

function readHashQuery(key: string): string | undefined {
  if (typeof location === 'undefined') return undefined;
  const m = location.hash.match(new RegExp(`[?&]${key}=([^&]+)`));
  return m ? decodeURIComponent(m[1]) : undefined;
}

/** Strip deviceId/taskId from H5 hash so back/onShow won't re-apply the deep link. */
function clearDeepLinkQuery() {
  pendingDeepLink = false;
  focusTaskId.value = null;
  if (typeof location === 'undefined' || typeof history === 'undefined') return;
  const hash = location.hash || '';
  const qIndex = hash.indexOf('?');
  if (qIndex < 0) return;
  const path = hash.slice(0, qIndex);
  history.replaceState(null, '', `${location.pathname}${location.search}${path}`);
}

function usePreferredDevice() {
  const id = preferredId.value;
  if (!id) return;
  filterDeviceId.value = id.trim().toUpperCase();
  status.value = '';
  void load();
}

function goRequest() {
  const q = filterDeviceId.value ? `?deviceId=${encodeURIComponent(filterDeviceId.value)}` : '';
  uni.navigateTo({ url: `/pages/request/request${q}` });
}

function goRequestForDevice(deviceId: string) {
  uni.navigateTo({
    url: `/pages/request/request?deviceId=${encodeURIComponent(deviceId)}`
  });
}

onLoad((opts) => {
  applyRouteQuery(opts as Record<string, string | undefined>);
  preferredId.value = getPreferredDeviceId();
});

function deviceName(id?: string, snapshot?: string) {
  if (snapshot) return snapshot;
  const d = deviceMeta(id);
  return d?.deviceName || emptyDisplay(id, 'device');
}

function deviceMeta(id?: string): DeviceMeta | undefined {
  if (!id) return undefined;
  return devices.value.find((item) => item.deviceId === id) as DeviceMeta | undefined;
}

function deviceAddressLine(id?: string): string {
  const m = deviceMeta(id);
  if (!m) return '';
  const parts = [m.address, m.routeCode ? `线路 ${m.routeCode}` : ''].filter(Boolean);
  return parts.join(' · ');
}

function toggleSkipLocation() {
  if (!canSkipLocation) return;
  skipLocationCheck.value = !skipLocationCheck.value;
  setSkipCheckInLocation(skipLocationCheck.value);
}

function copyDeviceId(id?: string) {
  const code = String(id || selected.value?.deviceId || '').trim();
  if (!code) return;
  uni.setClipboardData({
    data: code,
    success: () => showSuccess('已复制柜机编号')
  });
}

function navigateToDevice(id?: string) {
  const m = deviceMeta(id || selected.value?.deviceId);
  if (!m?.latitude || !m?.longitude) {
    showError('暂无坐标，请按地址或编号找柜');
    return;
  }
  const name = encodeURIComponent(m.deviceName || m.deviceId || '柜机');
  // #ifdef H5
  if (typeof window !== 'undefined') {
    window.open(
      `https://uri.amap.com/marker?position=${m.longitude},${m.latitude}&name=${name}`,
      '_blank'
    );
    return;
  }
  // #endif
  uni.openLocation({
    latitude: Number(m.latitude),
    longitude: Number(m.longitude),
    name: m.deviceName || m.deviceId || '柜机',
    address: m.address || ''
  });
}

async function assertScannedDeviceAllowed(deviceId: string): Promise<boolean> {
  const id = String(deviceId || '')
    .trim()
    .toUpperCase();
  if (!id) return false;
  const localHit = devices.value.some(
    (d) =>
      String((d as DeviceMeta).deviceId || '')
        .trim()
        .toUpperCase() === id
  );
  if (localHit) return true;
  try {
    await merchantApi.assertReplenishmentDeviceAccess(id);
    return true;
  } catch (e) {
    showError(e instanceof Error ? e.message : '柜机不在您的管辖范围', 3200);
    return false;
  }
}

async function verifyCabinetScan() {
  if (scanning.value) return;
  scanning.value = true;
  try {
    const id = await scanCabinetDeviceId();
    if (!id) return;
    if (!(await assertScannedDeviceAllowed(id))) return;
    const expected = String(selected.value?.deviceId || '')
      .trim()
      .toUpperCase();
    const scanned = id.trim().toUpperCase();
    if (!expected) return;
    if (scanned !== expected) {
      await askConfirm({
        title: '柜机不符',
        content: `扫到 ${scanned}，本任务柜机为 ${expected}。请确认是否找错柜。`,
        confirmText: '知道了',
        cancelText: '关闭'
      });
      return;
    }
    showSuccess('柜机核对一致');
  } finally {
    scanning.value = false;
  }
}

function skuName(id: string) {
  const s = skus.value.find((item) => item.skuId === id) as { skuName?: string } | undefined;
  return s?.skuName || id;
}

/** 演示 SKU 本地兜底图；正式商品图由后台在商品管理上传，补货端与消费端、管理端共用同一 imageUrl */
const LOCAL_SKU_THUMBS: Record<string, string> = {
  'SKU-DEMO-001': '/static/sku/cola.jpg',
  'SKU-SODA-001': '/static/sku/sprite.jpg',
  'SKU-WATER-001': '/static/sku/water.jpg',
  'SKU-SNACK-001': '/static/sku/chips.jpg',
  'SKU-MILK-001': '/static/sku/milk.jpg',
  'SKU-NOODLE-001': '/static/sku/noodle.jpg'
};

function absoluteImageUrl(url?: string | null): string {
  const value = String(url || '').trim();
  if (!value) return '';
  if (/^https?:\/\//i.test(value) || value.startsWith('//')) return value;
  const base = (API_BASE_URL || '').replace(/\/$/, '');
  return `${base}${value.startsWith('/') ? value : '/' + value}`;
}

function skuThumb(id: string) {
  const s = skus.value.find((item) => item.skuId === id) as { imageUrl?: string } | undefined;
  return absoluteImageUrl(s?.imageUrl) || LOCAL_SKU_THUMBS[id] || '';
}

function productGlyph(id: string) {
  const name = String(skuName(id) || '').trim();
  return name ? name.slice(0, 1) : '货';
}

function formatTime(value?: string) {
  return formatDateTimeShort(value, '暂无');
}

function formatDateOnly(value?: string) {
  if (!value) return '';
  const raw = String(value).trim();
  if (/^\d{4}-\d{2}-\d{2}/.test(raw)) return raw.slice(0, 10);
  return formatDateTimeShort(raw, raw).slice(0, 10);
}

function routeLabel(task: { routeId?: number; routeName?: string }) {
  if (task.routeName && String(task.routeName).trim()) {
    return String(task.routeName).trim();
  }
  return task.routeId != null ? `线路 #${task.routeId}` : '';
}

async function ensureReplenishmentMe(seq: number): Promise<boolean> {
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return false;
    seedMerchantMeDisplayCache(me);
  }
  if (!isLatestLoad(seq)) return false;
  if (!me.value) {
    seedMerchantMeDisplayCache(me);
  }
  return true;
}

function findDeepLinkTaskById(): Task | undefined {
  if (!focusTaskId.value) return undefined;
  const open = allTasks.value.find(
    (t) => t.taskId === focusTaskId.value && t.status !== 'CANCELLED'
  );
  focusTaskId.value = null;
  return open;
}

function findDeepLinkTaskByDevice(): Task | undefined {
  if (detailVisible.value || !filterDeviceId.value) return undefined;
  const key = filterDeviceId.value.trim().toUpperCase();
  return allTasks.value.find(
    (t) =>
      String(t.deviceId || '')
        .trim()
        .toUpperCase() === key &&
      t.status !== 'COMPLETED' &&
      t.status !== 'CANCELLED'
  );
}

function resolveDeepLinkOpenTask(): Task | undefined {
  if (!pendingDeepLink) return undefined;
  return findDeepLinkTaskById() || findDeepLinkTaskByDevice();
}

async function handleDeepLinkAfterLoad(open: Task | undefined, wantedTaskId: number | null) {
  if (pendingDeepLink) {
    clearDeepLinkQuery();
  }
  if (open) {
    await openTask(open);
  } else if (wantedTaskId) {
    showError(`任务 #${wantedTaskId} 不可用或已取消`);
  }
}

async function load() {
  const result = await fetchList({
    ensureMe: ensureReplenishmentMe,
    canReplenish: canReplenish.value
  });
  if (!result || result.aborted) return;
  const wantedTaskId = focusTaskId.value;
  const open = resolveDeepLinkOpenTask();
  await handleDeepLinkAfterLoad(open, wantedTaskId);
}

function clearDeviceFilter() {
  clearListDeviceFilter();
  clearDeepLinkQuery();
}

function findActiveTaskForDevice(deviceKey: string): Task | undefined {
  return allTasks.value.find(
    (t) =>
      String(t.deviceId || '')
        .trim()
        .toUpperCase() === deviceKey &&
      t.status !== 'COMPLETED' &&
      t.status !== 'CANCELLED'
  );
}

async function onScan() {
  if (scanning.value) return;
  scanning.value = true;
  try {
    const id = await scanCabinetDeviceId();
    if (!id) return;
    if (!(await assertScannedDeviceAllowed(id))) return;
    const key = id.trim().toUpperCase();
    filterDeviceId.value = key;
    status.value = '';
    const open = findActiveTaskForDevice(key);
    if (open) {
      await openTask(open);
    } else {
      showError('该柜暂无任务，已筛选列表');
    }
  } finally {
    scanning.value = false;
  }
}

async function readProductBarcode(): Promise<string | null> {
  try {
    const res = await new Promise<{ result?: string }>((resolve, reject) => {
      uni.scanCode({
        onlyFromCamera: false,
        scanType: ['barCode', 'qrCode'],
        success: (r) => resolve(r as { result?: string }),
        fail: reject
      });
    });
    return String(res.result || '').trim() || null;
  } catch (err) {
    const msg = String((err as { errMsg?: string })?.errMsg || '');
    if (/cancel|取消/i.test(msg)) return null;
    return (
      String(
        (await promptText({
          title: '输入商品条码',
          placeholder: '扫描商品包装条码',
          required: true,
          requiredMessage: '条码无效',
          maxLength: 64,
          singleLine: true,
          testId: 'product-barcode-prompt'
        })) || ''
      ).trim() || null
    );
  }
}

function findSkuByBarcode(code: string) {
  const key = code.trim().toUpperCase();
  return skus.value.find(
    (s) =>
      String((s as { barcode?: string }).barcode || '')
        .trim()
        .toUpperCase() === key ||
      String((s as { skuId?: string }).skuId || '')
        .trim()
        .toUpperCase() === key
  ) as { skuId?: string; skuName?: string } | undefined;
}

function findMatchingTaskLine(skuId: string): Line | undefined {
  return lines.value.find(
    (l) => !l.applied && String(l.skuId).toUpperCase() === String(skuId).toUpperCase()
  );
}

/** 扫商品条码自动匹配任务明细并 +1；浏览器无法调起扫码时手输条码 */
async function scanProduct(line: Line) {
  if (!canRequest.value || linesConfirmed.value || line.applied || scanning.value) return;
  scanning.value = true;
  try {
    const code = await readProductBarcode();
    if (!code) return;
    const sku = findSkuByBarcode(code);
    if (!sku?.skuId) {
      showError('未匹配到商品条码');
      return;
    }
    const target = findMatchingTaskLine(sku.skuId);
    if (!target) {
      showError('本次任务不含该商品');
      return;
    }
    adjustQty(target, 1);
    showSuccess(`已扫 ${sku.skuName || target.skuId}`);
  } finally {
    scanning.value = false;
  }
}

function currentStep(): number {
  if (!selected.value) return 1;
  if (selected.value.status === 'COMPLETED') return 5;
  if (linesConfirmed.value) return 4;
  if (doorOpened.value) return 3;
  if (selected.value.checkInAt) return 2;
  return 1;
}

async function addEvidence() {
  if (!selected.value || !canRequest.value) return;
  if (!selected.value.checkInAt) {
    showError('请先签到再拍照');
    return;
  }
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
        // @dcloudio/types 中 tempFilePaths 声明为 string | string[]，统一归一化为数组
        const raw = res.tempFilePaths || [];
        resolve(Array.isArray(raw) ? raw : [raw]);
      },
      fail: () => resolve([])
    });
  });
  for (const path of paths) {
    try {
      await assertLocalImageSize(path);
      const uploaded = await merchantApi.uploadReplenishmentEvidence(selected.value.taskId, path);
      evidenceItems.value.push({ localPath: path, fileId: uploaded.fileId });
      if (selected.value?.taskId) {
        evidenceCountMap.value = {
          ...evidenceCountMap.value,
          [selected.value.taskId]: evidenceItems.value.length
        };
      }
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

function prepareTaskDetailSheet(task: Task) {
  const fromList = allTasks.value.find((t) => t.taskId === task.taskId);
  selected.value = { ...(fromList || task) };
  sheetCloseArmed.value = false;
  detailVisible.value = true;
  linesConfirmed.value = selected.value.status === 'COMPLETED';
  evidenceItems.value = [];
  restoreDoorState(selected.value.taskId);
  detailLoading.value = true;
  slotCaps.value = {};
  deviceSlotsList.value = [];
}

async function refreshSelectedTask(task: Task) {
  try {
    const latest = (await merchantApi.replenishmentTasks()) as Task[];
    allTasks.value = latest;
    const fresh = latest.find((t) => t.taskId === task.taskId);
    if (fresh) selected.value = { ...fresh };
  } catch {
    /* keep selected */
  }
}

async function mapEvidenceFiles(task: Task, evidence: { fileId?: number; url?: string }[]) {
  return Promise.all(
    (evidence || []).map(async (f) => {
      const fileId = f.fileId;
      if (!fileId) return { localPath: f.url || '', fileId };
      try {
        const localPath = await merchantApi.downloadReplenishmentEvidence(task.taskId, fileId);
        return { localPath, fileId };
      } catch {
        return { localPath: f.url || '', fileId };
      }
    })
  );
}

function buildSlotCapsFromSlots(slots: DeviceSlot[]) {
  const map: Record<string, { maxLevel: number; bookQty: number }> = {};
  for (const s of slots) {
    const code = String(s.slotCode || '').toUpperCase();
    if (!code) continue;
    map[code] = {
      maxLevel: Number(s.maxLevel) || 0,
      bookQty: Number(s.bookQty) || 0
    };
  }
  return map;
}

async function loadTaskDetailResources(task: Task) {
  const [taskLines, slots, evidence] = await Promise.all([
    merchantApi.replenishmentTaskLines(task.taskId) as Promise<Line[]>,
    merchantApi.deviceSlots(task.deviceId).catch(() => [] as DeviceSlot[]),
    merchantApi.listReplenishmentEvidence(task.taskId).catch(() => [])
  ]);
  lines.value = taskLines;
  deviceSlotsList.value = (slots || []) as DeviceSlot[];
  const mapped = await mapEvidenceFiles(task, evidence || []);
  evidenceItems.value = mapped;
  evidenceCountMap.value = {
    ...evidenceCountMap.value,
    [task.taskId]: mapped.length
  };
  slotCaps.value = buildSlotCapsFromSlots(deviceSlotsList.value);
  await syncDoorStateFromServer(task.taskId);
}

async function openTask(task: Task) {
  prepareTaskDetailSheet(task);
  await nextTick();
  setTimeout(() => {
    sheetCloseArmed.value = true;
  }, 280);
  try {
    await refreshSelectedTask(task);
    await loadTaskDetailResources(task);
  } catch (error) {
    showError(error instanceof Error ? error.message : '明细加载失败');
  } finally {
    detailLoading.value = false;
  }
}

function slotOptionsFor(line: Line) {
  return deviceSlotsList.value
    .filter((s) => s.enabled !== false)
    .filter((s) => !s.assignedSkuId || s.assignedSkuId === line.skuId)
    .map((s) => {
      const slotCode = String(s.slotCode || '').toUpperCase();
      const maxLevel = Number(s.maxLevel) || 0;
      const bookQty = Number(s.bookQty) || 0;
      const room = maxLevel > 0 ? Math.max(0, maxLevel - bookQty) : 99;
      return { slotCode, room, label: s.assignedSkuName || slotCode };
    })
    .filter((s) => !!s.slotCode)
    .sort((a, b) => b.room - a.room || a.slotCode.localeCompare(b.slotCode));
}

function assignSlot(line: Line, opt: { slotCode: string; room: number }) {
  if (!opt.slotCode) return;
  if (opt.room <= 0) {
    showError('该货道已满');
    return;
  }
  line.slotId = opt.slotCode;
  if ((Number(line.quantity) || 0) > opt.room) {
    line.quantity = opt.room;
  }
  linesConfirmed.value = false;
}

function slotHeadroom(line: Line): number {
  const code = String(line.slotId || '').toUpperCase();
  if (!code) {
    const rooms = slotOptionsFor(line)
      .map((o) => o.room)
      .filter((n) => n > 0);
    return rooms.length ? Math.max(...rooms) : 0;
  }
  const cap = slotCaps.value[code];
  if (!cap || cap.maxLevel <= 0) return 99;
  return Math.max(0, cap.maxLevel - cap.bookQty);
}

function slotHint(line: Line): string {
  if (isPullOffType(line.lineType)) return '';
  const code = String(line.slotId || '').toUpperCase();
  const cap = slotCaps.value[code];
  if (!cap || cap.maxLevel <= 0) return '';
  const room = slotHeadroom(line);
  if (room <= 0) return `货道已满（${cap.bookQty}/${cap.maxLevel}），请将数量调为 0 或换货道`;
  if (line.quantity > room)
    return `超出容量：最多再补 ${room}（已有 ${cap.bookQty}/${cap.maxLevel}）`;
  return `还可补 ${room}（已有 ${cap.bookQty}/${cap.maxLevel}）`;
}

function formatLineSummary(rows: Line[]): string {
  if (!rows.length) return '暂无明细行';
  const qty = rows.reduce((s, l) => s + Math.max(0, Number(l.quantity) || 0), 0);
  const pull = rows.filter((l) => isPullOffType(l.lineType)).length;
  const restock = rows.length - pull;
  const noExpiry = rows.filter((l) => !String(l.expiryDate || '').trim()).length;
  const noSlot = rows.filter(
    (l) => !String(l.slotId || '').trim() && !isPullOffType(l.lineType)
  ).length;
  const parts = [`${rows.length} 行`, `共 ${qty} 件`];
  if (restock > 0) parts.push(`补货 ${restock}`);
  if (pull > 0) parts.push(`下架 ${pull}`);
  if (noSlot > 0) parts.push(`${noSlot} 行待选货道`);
  if (noExpiry > 0) parts.push(`${noExpiry} 行缺效期`);
  return parts.join(' · ');
}

function stockDeltaText(line: Line): string {
  const code = String(line.slotId || '').toUpperCase();
  if (!code) return '';
  const cap = slotCaps.value[code];
  if (!cap) return '';
  const qty = Math.max(0, Number(line.quantity) || 0);
  if (isPullOffType(line.lineType)) {
    const after = Math.max(0, cap.bookQty - qty);
    const capacityHint = cap.maxLevel > 0 ? ` / 容量 ${cap.maxLevel}` : '';
    return `账面 ${cap.bookQty} → 下架后 ${after}${capacityHint}`;
  }
  const after = cap.bookQty + qty;
  const capacityHint = cap.maxLevel > 0 ? ` / 容量 ${cap.maxLevel}` : '';
  return `账面 ${cap.bookQty} → 补后 ${after}${capacityHint}`;
}

function closeDetail() {
  if (!sheetCloseArmed.value) return;
  if (!submitting.value) {
    detailVisible.value = false;
    sheetCloseArmed.value = false;
    clearDeepLinkQuery();
  }
}

onShow(() => {
  preferredId.value = getPreferredDeviceId();
  void load();
});
onPullDownRefresh(load);
</script>

<style scoped>
.page {
  min-height: 100%;
  padding: 0;
  background: var(--card-bg, #ffffff);
  box-sizing: border-box;
  overflow-x: hidden;
}
.hero {
  position: relative;
  overflow: hidden;
  margin: 20rpx 24rpx 0;
  padding: 36rpx 28rpx 32rpx;
  border-radius: var(--radius-card);
  color: var(--text-primary, #0f172a);
  background: linear-gradient(135deg, var(--brand-soft), var(--white));
  border: 1rpx solid var(--brand-soft, #d1fae5);
  box-shadow: none;
  text-align: center;
}
.hero-orb {
  display: none;
}
.orb-one,
.orb-two {
  display: none;
}
.hero-head {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.eyebrow,
.title,
.subtitle {
  display: block;
  position: relative;
  text-align: center;
}
.eyebrow {
  font-size: var(--font-size-sm);
  letter-spacing: 4rpx;
  padding: 6rpx 16rpx;
  border-radius: var(--radius-pill);
  background: var(--brand-soft, #f0fdf4);
  color: var(--brand);
}
.title {
  margin-top: 14rpx;
  font-size: var(--font-size-h2);
  font-weight: 800;
  color: var(--text-primary, #0f172a);
}
.subtitle {
  margin-top: 10rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  line-height: 1.55;
}
.stats {
  position: relative;
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 12rpx;
  margin-top: 28rpx;
  padding-top: 22rpx;
  border-top: 1rpx solid var(--brand-soft, #d1fae5);
}
.stat {
  flex: 1;
  min-width: 0;
  text-align: center;
}
.stat-value,
.stat-label {
  display: block;
  text-align: center;
}
.stat-value {
  font-size: var(--font-size-h2);
  font-weight: 800;
  color: var(--brand);
}
.stat-label {
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.hero-actions {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16rpx;
  margin-top: 26rpx;
}
.hero-secondary {
  display: flex;
  flex-wrap: wrap;
  width: 100%;
  gap: 14rpx;
}
.scan-primary {
  margin: 0;
  min-height: 88rpx;
  height: 88rpx;
  line-height: 1.2;
  border-radius: var(--radius-pill);
  background: linear-gradient(135deg, var(--brand-deep), var(--brand));
  color: var(--white);
  font-size: var(--font-size-md);
  font-weight: 700;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.22);
  text-align: center;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
.scan-primary::after {
  border: none;
}
/* 与主按钮同高同宽基线；多个次要操作时均分一行 */
.clear-pill {
  flex: 1 1 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  min-height: 88rpx;
  padding: 0 28rpx;
  border-radius: var(--radius-pill);
  background: var(--brand-soft, #f0fdf4);
  color: var(--brand);
  font-size: var(--font-size-md);
  font-weight: 600;
  box-sizing: border-box;
  border: 2rpx solid var(--brand-mist, #99f6e4);
}
.clear-pill-hover {
  opacity: 0.82;
}
.filter-tip {
  position: relative;
  display: block;
  margin-top: 16rpx;
  font-size: var(--font-size-sm);
  opacity: 0.85;
  text-align: center;
}
.filter-tip.muted {
  opacity: 0.7;
}
.idle-tip {
  margin: 18rpx 24rpx 0;
  padding: 28rpx 24rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  border: 1rpx solid rgba(15, 118, 110, 0.1);
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.06);
  text-align: center;
}
.idle-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--brand-deep);
  text-align: center;
}
.idle-desc {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  line-height: 1.5;
  text-align: center;
}

.patrol-card {
  margin: 22rpx 24rpx 4rpx;
  padding: 24rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  border: 1rpx solid var(--warning-soft);
  box-shadow: 0 8rpx 30rpx rgba(180, 83, 9, 0.08);
}
.patrol-head,
.patrol-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18rpx;
}
.patrol-title {
  display: block;
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--warning);
}
.patrol-sub {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--warning, #b45309);
}
.patrol-count {
  padding: 6rpx 14rpx;
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, var(--warning, #b45309) 8%, var(--white));
  color: var(--warning, #b45309);
  font-size: var(--font-size-sm);
  font-weight: 700;
}
.patrol-row {
  margin-top: 18rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: color-mix(in srgb, var(--warning, #b45309) 8%, var(--white));
  cursor: pointer;
}
.patrol-row-hover {
  background: color-mix(in srgb, var(--warning, #b45309) 14%, var(--white));
}
.patrol-name {
  flex: 1;
  min-width: 0;
}
.patrol-name .device-name {
  font-size: var(--font-size-md);
}
.patrol-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  flex-shrink: 0;
  margin-left: 16rpx;
}
.patrol-badge {
  padding: 6rpx 12rpx;
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, var(--warning, #b45309) 14%, var(--white));
  color: var(--warning, #b45309);
  font-size: var(--font-size-xs);
  font-weight: 700;
}
.patrol-shortage {
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--warning, #b45309);
  font-weight: 700;
}

.filters {
  display: flex;
  flex-wrap: nowrap;
  gap: 12rpx;
  margin: 24rpx 24rpx;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  padding-bottom: 4rpx;
}
.filters .filter-chip {
  flex-shrink: 0;
}

.task-card {
  position: relative;
  overflow: hidden;
  margin: 0 var(--page-gutter) 18rpx;
  padding: 26rpx;
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  border: 1rpx solid var(--color-border);
  box-shadow: 0 8rpx 30rpx rgba(15, 118, 110, 0.08);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.task-card-hover {
  background: var(--page-bg, #f8fafc) !important;
  opacity: 0.96;
}
.task-accent {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 6rpx;
  background: linear-gradient(var(--success), var(--brand));
  pointer-events: none;
}
.task-head,
.task-meta,
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18rpx;
}
.task-meta.soft {
  margin-top: 6rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  justify-content: flex-start;
  flex-wrap: wrap;
}
.task-lines {
  margin-top: 10rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted, #334155);
  line-height: 1.45;
  pointer-events: none;
}
.device-name,
.device-code,
.status,
.task-meta,
.task-lines,
.task-note {
  pointer-events: none;
}
.device-name,
.device-code {
  display: block;
}
.device-name {
  font-size: var(--font-size-lg);
  font-weight: 700;
  color: var(--text-primary, #0f172a);
}
.device-code {
  margin-top: 4rpx;
  color: var(--text-subtle);
  font-size: var(--font-size-sm);
}
.task-addr {
  display: block;
  margin-top: 6rpx;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.4;
  pointer-events: none;
}
.skip-loc-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin: 0 0 16rpx;
  padding: 18rpx 20rpx;
  border-radius: var(--radius-panel);
  background: var(--page-bg, #f8fafc);
  border: 1rpx solid var(--color-border);
}
.skip-loc-copy {
  flex: 1;
  min-width: 0;
}
.skip-loc-label {
  display: block;
  font-size: var(--font-size-body);
  font-weight: 600;
  color: var(--text-primary, #0f172a);
}
.skip-loc-hint {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.skip-loc-switch {
  flex-shrink: 0;
  min-width: 56rpx;
  padding: 8rpx 16rpx;
  border-radius: var(--radius-pill);
  text-align: center;
  font-size: var(--font-size-sm);
  font-weight: 700;
  color: var(--text-muted);
  background: var(--color-border);
}
.skip-loc-switch.on {
  color: var(--white);
  background: var(--brand);
}
.status {
  padding: 8rpx 16rpx;
  border-radius: var(--radius-pill);
  /* 默认=待处理：警告橙仅给 PENDING，避免 CANCELLED 误用警告色（R3-M01） */
  color: var(--warning, #92400e);
  background: color-mix(in srgb, var(--warning, #b45309) 14%, var(--white));
  font-size: var(--font-size-sm);
  font-weight: 600;
}
.status.pending {
  color: var(--warning, #92400e);
  background: color-mix(in srgb, var(--warning, #b45309) 14%, var(--white));
}
.status.in_progress {
  color: var(--brand-deep, #134e4a);
  background: color-mix(in srgb, var(--brand, #0f766e) 14%, var(--white));
}
.status.completed {
  color: var(--brand-deep, #166534);
  background: var(--brand-soft, #dcfce7);
}
.status.cancelled {
  color: var(--text-muted, #64748b);
  background: color-mix(in srgb, var(--text-muted, #64748b) 12%, var(--white));
}
.task-meta {
  margin-top: 16rpx;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
.task-note {
  margin-top: 16rpx;
  padding: 16rpx;
  border-radius: var(--radius-control);
  color: var(--text-muted, #475569);
  background: var(--page-bg, #f8fafc);
  font-size: var(--font-size-sm);
}
.detail-btn,
.app-btn,
.secondary-btn {
  margin-top: 22rpx;
  border: 0;
  border-radius: 18rpx;
  font-size: var(--font-size-md);
  font-weight: 700;
  min-height: 88rpx;
  line-height: 1.2;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.detail-btn {
  display: flex;
  padding: 0 22rpx;
  text-align: center;
  pointer-events: none;
}
.detail-btn,
.app-btn {
  color: var(--white);
  background: var(--brand);
}
.secondary-btn {
  color: var(--brand);
  background: var(--brand-mist);
}
.detail-btn::after,
.app-btn::after,
.secondary-btn::after {
  border: none;
}

.empty {
  padding: 80rpx 20rpx;
  margin-left: 24rpx;
  margin-right: 24rpx;
  text-align: center;
  color: var(--text-subtle);
  font-size: var(--font-size-md);
}
.empty.small {
  padding: 30rpx;
}
.empty-actions-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 16rpx;
  margin-top: 28rpx;
}
.empty-scan {
  margin: 0;
  width: auto;
  min-width: 200rpx;
  padding: 0 28rpx;
  height: 72rpx;
  line-height: 72rpx;
  border-radius: var(--radius-card);
  background: var(--brand);
  color: var(--white);
  font-size: var(--font-size-body);
}
.empty-scan.ghost {
  color: var(--brand);
  background: var(--brand-soft);
}
.empty-scan::after {
  border: none;
}

.sheet-title {
  display: block;
  font-size: var(--font-size-h3);
  font-weight: 800;
}
.close {
  padding: 10rpx;
  color: var(--text-muted);
  font-size: var(--font-size-h1);
}
.door-tip {
  display: block;
  margin-top: 12rpx;
  padding: 14rpx 16rpx;
  border-radius: var(--radius-control);
  background: var(--brand-soft);
  color: var(--brand);
  font-size: var(--font-size-sm);
  line-height: 1.4;
}
.evidence-badge {
  color: var(--brand);
  font-weight: 650;
}
.evidence-badge.muted {
  color: var(--text-subtle);
  font-weight: 500;
}
button[disabled] {
  opacity: 0.45;
}
.page-body {
  padding: 0 0 calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
:deep(.empty-state) {
  margin-left: 24rpx;
  margin-right: 24rpx;
}
</style>
