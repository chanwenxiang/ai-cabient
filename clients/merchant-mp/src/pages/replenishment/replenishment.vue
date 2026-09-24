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
  background: #f9f1eb;
  color: var(--warning, #b45309);
  font-size: var(--font-size-sm);
  font-weight: 700;
}
.patrol-row {
  margin-top: 18rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: #f9f1eb;
  cursor: pointer;
}
.patrol-row-hover {
  background: #f5e7dd;
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
  background: #f5e7dd;
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
  background: #f5e7dd;
  font-size: var(--font-size-sm);
  font-weight: 600;
}
.status.pending {
  color: var(--warning, #92400e);
  background: #f5e7dd;
}
.status.in_progress {
  color: var(--brand-deep, #134e4a);
  background: #ddeceb;
}
.status.completed {
  color: var(--brand-deep, #166534);
  background: var(--brand-soft, #dcfce7);
}
.status.cancelled {
  color: var(--text-muted, #64748b);
  background: #eceef1;
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
