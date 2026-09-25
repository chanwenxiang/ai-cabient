<template>
  <view class="page">
    <app-nav-bar title="补货任务" />
    <view class="page-body">
      <view class="hero">
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
          <text class="status" :class="(task.status || '').toLowerCase()">
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
            <text class="device-code">任务 #{{ selected?.taskId }} · {{ selected?.deviceId }}</text>
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

        <!-- dev-only（showDevTools）。deviceCoordsMissing 时必须隐藏：那种柜机服务端已 fail-closed 拒签，
             再摆一个「跳过定位」开关会与上方「本柜尚未录入点位坐标」提示自相矛盾。 -->
        <view
          v-if="
            canSkipLocation &&
            requireReplenishmentCheckInLocation &&
            canRequest &&
            !deviceCoordsMissing &&
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
            <text class="skip-loc-label">跳过定位采集</text>
            <text class="skip-loc-hint"
              >不获取 GPS，直接不带坐标提交；服务端要求定位时会拒签并给出原因</text
            >
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

        <text
          v-if="
            canRequest &&
            deviceCoordsMissing &&
            selected?.status !== 'COMPLETED' &&
            !selected?.checkInAt
          "
          class="door-tip"
          data-testid="replenish-no-coords-tip"
        >
          {{ DEVICE_COORDS_MISSING_HINT }}
        </text>

        <app-button
          v-if="canRequest && selected?.status !== 'COMPLETED' && !selected?.checkInAt"
          data-testid="replenish-checkin"
          :disabled="submitting || deviceCoordsMissing"
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
import { computed, ref } from 'vue';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import { displayLabel } from '@aicabinet/shared-dict';
import EmptyState from '@/components/empty-state.vue';
import AppConfirmDialog from '@/components/AppConfirmDialog.vue';
import ReplenishActionDock from '@/components/ReplenishActionDock.vue';
import ReplenishCabinetCard from '@/components/ReplenishCabinetCard.vue';
import ReplenishDetailSheet from '@/components/ReplenishDetailSheet.vue';
import ReplenishEvidenceSection from '@/components/ReplenishEvidenceSection.vue';
import ReplenishLinesSection from '@/components/ReplenishLinesSection.vue';
import ReplenishStepBar from '@/components/ReplenishStepBar.vue';
import { hasPerm } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { useAutoRefresh } from '@/composables/use-auto-refresh';
import { useAppConfirmDialog } from '@/composables/useAppConfirmDialog';
import { useReplenishmentDoorState } from '@/composables/useReplenishmentDoorState';
import { useReplenishmentDetail } from '@/composables/useReplenishmentDetail';
import { useReplenishmentDisplay } from '@/composables/useReplenishmentDisplay';
import {
  DEVICE_COORDS_MISSING_HINT,
  isDeviceCoordsMissing,
  useReplenishmentFulfillment
} from '@/composables/useReplenishmentFulfillment';
import { useReplenishmentList } from '@/composables/useReplenishmentList';
import { useReplenishmentScan } from '@/composables/useReplenishmentScan';
import { useReplenishmentShell } from '@/composables/useReplenishmentShell';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import { getSkipCheckInLocation, setSkipCheckInLocation } from '@/utils/checkin-location-pref';
import { showDevTools } from '@/utils/runtime-flags';
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
  isLatestLoad,
  ensureSkuCatalog
} = useReplenishmentList({ preferredId });

const detailLoading = ref(false);
const submitting = ref(false);
const scanning = ref(false);
const focusTaskId = ref<number | null>(null);
const detailVisible = ref(false);
/** 避免「点卡片打开」同一轮点击落到遮罩上立刻关掉 */
const sheetCloseArmed = ref(false);
const selected = ref<Task | null>(null);
/**
 * 柜机是否明确未录点位坐标。为 true 时签到**必被服务端拒**（fail-closed），
 * 所以直接在按钮上禁用并给出指路提示，而不是让补货员到柜前吃 400。
 */
const deviceCoordsMissing = computed(() => isDeviceCoordsMissing(selected.value));
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

const {
  applyRouteQuery,
  clearDeepLinkQuery,
  resolveDeepLinkOpenTask,
  handleDeepLinkAfterLoad,
  openTask,
  addEvidence,
  previewEvidence,
  closeDetail
} = useReplenishmentDetail({
  allTasks,
  evidenceCountMap,
  selected,
  lines,
  linesConfirmed,
  evidenceItems,
  detailVisible,
  sheetCloseArmed,
  detailLoading,
  submitting,
  slotCaps,
  deviceSlotsList,
  focusTaskId,
  filterDeviceId,
  status,
  canRequest,
  restoreDoorState,
  syncDoorStateFromServer,
  ensureSkuCatalog
});

const {
  skuName,
  skuThumb,
  productGlyph,
  slotOptionsFor,
  slotHeadroom,
  slotHint,
  formatLineSummary,
  stockDeltaText,
  isPullOffType,
  lineTypeLabel,
  lineStatusLabel,
  displayTaskNotes,
  taskActionLabel
} = useReplenishmentDisplay({
  skus,
  slotCaps,
  deviceSlotsList
});

const {
  heroSubtitle,
  efficiencyRateText,
  detailIsPullOff,
  statusOptions,
  emptyHint,
  usePreferredDevice,
  goRequest,
  goRequestForDevice,
  deviceName,
  deviceAddressLine,
  toggleSkipLocation,
  copyDeviceId,
  navigateToDevice,
  formatTime,
  formatDateOnly,
  routeLabel,
  load,
  clearDeviceFilter,
  currentStep,
  assignSlot
} = useReplenishmentShell({
  me,
  preferredId,
  filterDeviceId,
  status,
  selected,
  lines,
  linesConfirmed,
  doorOpened,
  skipLocationCheck,
  focusTaskId,
  canSkipLocation,
  canReplenish,
  devices,
  efficiency,
  emptyHintForDeviceFilter,
  emptyHintForStatusFilter,
  clearListDeviceFilter,
  clearDeepLinkQuery,
  fetchList,
  isLatestLoad,
  refreshMe,
  resolveDeepLinkOpenTask,
  handleDeepLinkAfterLoad
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

const { verifyCabinetScan, onScan, scanProduct } = useReplenishmentScan({
  devices,
  skus,
  allTasks,
  selected,
  lines,
  linesConfirmed,
  scanning,
  filterDeviceId,
  status,
  canRequest,
  askConfirm,
  openTask,
  adjustQty,
  ensureSkuCatalog
});

onLoad((opts) => {
  applyRouteQuery(opts as Record<string, string | undefined>);
  preferredId.value = getPreferredDeviceId();
});

onShow(() => {
  preferredId.value = getPreferredDeviceId();
  void load();
});
onPullDownRefresh(load);

/**
 * 补货任务的「待处理」计数由后端推进（提交后等审核 / 等他人处理）：
 * 还有未完成任务时每 10 秒静默跟进，全部完成即停表。
 * canRefresh 排除执行中的交互（提交 / 扫码 / 详情弹层），避免刷新把用户正在填的表单冲掉。
 */
useAutoRefresh({
  intervalMs: 10_000,
  load,
  shouldContinue: () => pendingCount.value > 0,
  maxDurationMs: 300_000,
  canRefresh: () => !loading.value && !submitting.value && !scanning.value && !detailVisible.value
});
</script>

<style scoped src="./replenishment.page.css"></style>
