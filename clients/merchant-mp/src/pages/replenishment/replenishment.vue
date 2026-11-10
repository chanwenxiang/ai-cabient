<template>
  <view class="page">
    <app-nav-bar title="补货任务" home-url="/pages/home/home" />
    <view class="page-body">
      <view class="hero">
        <!-- 2026-11-10 重排：eyebrow「现场补货」+ title「补货任务」与导航栏标题三重重复，删标题只留数字与流程提示 -->
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
        <text class="flow-hint">{{ heroSubtitle }}</text>
        <view class="hero-actions">
          <button class="scan-primary" :loading="scanning" @click="onScan">扫码找柜</button>
          <view class="clear-pill" role="button" hover-class="clear-pill-hover" @click="goRequest"
            >发起补货</view
          >
        </view>
        <!-- 筛选态说明行（2026-11-10 用户拍板撤掉常驻柜/清除筛选徽章——形态不好看）：
             仅深链 ?deviceId= 筛选时出现，「清除」为文字按钮；非筛选态零显示。 -->
        <view v-if="filterDeviceId" class="filter-row">
          <text class="filter-tip">
            当前筛选：{{ filterDeviceId }}
            <text v-if="filterDeviceId === preferredId">（常驻柜）</text>
          </text>
          <view
            class="filter-clear"
            role="button"
            aria-label="清除柜机筛选"
            hover-class="filter-clear-hover"
            @click="clearDeviceFilter"
            >清除</view
          >
        </view>
      </view>

      <view v-if="!loading && pendingCount === 0 && !loadError" class="idle-tip">
        <text class="idle-title">今日暂无待补货</text>
        <text class="idle-desc"
          >可扫码巡柜查看缺货，或切换「已完成」回顾记录；新任务由调度下发</text
        >
      </view>

      <view v-if="lowStockList.length" class="patrol-card">
        <view class="patrol-head">
          <view class="patrol-copy">
            <text class="patrol-title">缺货巡柜</text>
            <text class="patrol-sub">查看各柜缺口明细，可按缺口直接生成补货任务</text>
          </view>
          <text class="patrol-count">{{ lowStockList.length }} 台</text>
        </view>
        <view
          v-for="d in lowStockList"
          :key="d.deviceId"
          class="patrol-row"
          hover-class="patrol-row-hover"
          role="button"
          @click="goDeviceShortage(d.deviceId || '')"
        >
          <view class="patrol-name">
            <text class="device-name">{{ deviceName(d.deviceId) }}</text>
            <text class="device-code">{{ d.deviceId }}</text>
          </view>
          <view class="patrol-meta">
            <!-- 「项」兼容两种柜型：有货道柜=缺货货道数，开门柜=待补商品数（CB-076） -->
            <text class="patrol-badge">{{ d.shortageSlots }} 项缺货</text>
            <text class="patrol-shortage">缺口 {{ d.shortageQty }} 件</text>
          </view>
        </view>
      </view>

      <app-underline-tabs
        layout="auto"
        :items="statusTabItems"
        :value="status"
        @change="changeStatus"
      />

      <list-skeleton v-if="loading && !allTasks.length" :rows="4" label="补货任务" />
      <error-state
        v-else-if="loadError && !allTasks.length"
        :title="loadError"
        hint="请检查网络后重试"
        @retry="load"
      />
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
        <view class="task-kicker">
          <text>任务 {{ task.taskId }}</text>
          <text class="task-kicker-dot">·</text>
          <text>{{ formatTime(task.createdAt) }}</text>
        </view>
        <view class="task-head">
          <view class="task-head-main">
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
        <view
          v-if="
            routeLabel(task) ||
            task.plannedDate ||
            task.checkInAt ||
            task.outboundId ||
            evidenceCountOf(task.taskId) > 0 ||
            task.status === 'COMPLETED'
          "
          class="task-chips"
        >
          <text v-if="routeLabel(task)" class="task-chip">{{ routeLabel(task) }}</text>
          <text v-if="task.plannedDate" class="task-chip"
            >计划 {{ formatDateOnly(task.plannedDate) }}</text
          >
          <text v-if="task.checkInAt" class="task-chip">已签到</text>
          <text v-if="task.outboundId" class="task-chip">领货 {{ task.outboundId }}</text>
          <text v-if="evidenceCountOf(task.taskId) > 0" class="task-chip">
            凭证 {{ evidenceCountOf(task.taskId) }} 张
          </text>
          <text v-else-if="task.status === 'COMPLETED'" class="task-chip muted">无现场照片</text>
        </view>
        <view v-if="displayTaskNotes(task.notes)" class="task-note">{{
          displayTaskNotes(task.notes)
        }}</view>
        <view class="task-foot">
          <text v-if="lineSummaryOf(task.taskId)" class="task-lines">{{
            lineSummaryOf(task.taskId)
          }}</text>
          <view v-else class="task-lines-spacer" />
          <view class="detail-link app-link-chevron">{{ taskActionLabel(task) }}</view>
        </view>
      </view>

      <ReplenishDetailSheet
        :visible="detailVisible"
        :body-scroll="false"
        :fill-height="true"
        @close="closeDetail"
      >
        <view class="sheet-head recap">
          <view class="sheet-head-main">
            <view class="sheet-recap-stack">
              <text class="sheet-title">{{
                deviceName(selected?.deviceId, selected?.deviceName)
              }}</text>
              <text
                v-if="selected?.status"
                class="status"
                :class="(selected.status || '').toLowerCase()"
                >{{ displayLabel('replenishment_task_status', selected.status, '未知状态') }}</text
              >
            </view>
            <text class="device-code">任务 {{ selected?.taskId }} · {{ selected?.deviceId }}</text>
            <text
              v-if="selected && (routeLabel(selected) || selected.plannedDate)"
              class="device-code"
              >{{
                [
                  routeLabel(selected),
                  selected.plannedDate ? `计划 ${formatDateOnly(selected.plannedDate)}` : ''
                ]
                  .filter(Boolean)
                  .join(' · ')
              }}</text
            >
            <text v-if="selected && displayTaskNotes(selected.notes)" class="device-code">{{
              displayTaskNotes(selected.notes)
            }}</text>
          </view>
          <text class="close" role="button" aria-label="关闭" @click="closeDetail">×</text>
        </view>

        <scroll-view scroll-y class="sheet-scroll">
          <ReplenishCabinetCard
            :device-id="selected?.deviceId"
            :address-line="selected?.deviceId ? deviceAddressLine(selected.deviceId) : ''"
            :hide-verify="selected?.status === 'COMPLETED'"
            centered
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
              <view class="skip-loc-top">
                <text class="skip-loc-label">跳过定位采集</text>
                <text class="skip-loc-switch" :class="{ on: skipLocationCheck }">{{
                  skipLocationCheck ? '开' : '关'
                }}</text>
              </view>
              <text class="skip-loc-hint"
                >开发环境：不取 GPS，无坐标提交（正式环境服务端会拒签）</text
              >
            </view>
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

          <view class="sheet-actions">
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
            <app-button
              v-if="
                canRequest && selected?.status !== 'COMPLETED' && selected?.status !== 'CANCELLED'
              "
              data-testid="replenish-cancel"
              variant="outline"
              :disabled="submitting"
              label="取消任务"
              @click="cancelTask"
            />
          </view>
          <text v-if="!canRequest && selected?.status !== 'COMPLETED'" class="door-tip">
            只读查看，需补货操作权限方可签到/开门/{{ detailIsPullOff ? '下架' : '上架' }}
          </text>
          <text
            v-if="doorOpened && openSessionId && selected?.status !== 'COMPLETED'"
            class="door-tip"
          >
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

          <ReplenishVideoSection @open="openTaskVideos" />

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
            :is-pull-off-type="isPullOffType"
            @adjust-qty="adjustQty"
            @scan-product="scanProduct"
          />
        </scroll-view>

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
import { computed, ref, watch } from 'vue';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { displayLabel } from '@aicabinet/shared-dict';
import EmptyState from '@aicabinet/shared-uni/components/empty-state.vue';
import AppConfirmDialog from '@/components/AppConfirmDialog.vue';
import ReplenishActionDock from '@/components/ReplenishActionDock.vue';
import ReplenishCabinetCard from '@/components/ReplenishCabinetCard.vue';
import ReplenishDetailSheet from '@/components/ReplenishDetailSheet.vue';
import ReplenishEvidenceSection from '@/components/ReplenishEvidenceSection.vue';
import ReplenishLinesSection from '@/components/ReplenishLinesSection.vue';
import ReplenishStepBar from '@/components/ReplenishStepBar.vue';
import ReplenishVideoSection from '@/components/ReplenishVideoSection.vue';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
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
import { doorHeadroomForSku } from '@/utils/replenishment-headroom';
import type { OpenApiReplenishmentHeadroomDto } from '@aicabinet/shared-types';

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
  ensureSkuCatalog,
  loadError
} = useReplenishmentList({ preferredId });

const detailLoading = ref(false);
const submitting = ref(false);
const scanning = ref(false);
const focusTaskId = ref<number | null>(null);
const detailVisible = ref(false);
/** 避免「点卡片打开」同一轮点击落到遮罩上立刻关掉 */
const sheetCloseArmed = ref(false);
const selected = ref<Task | null>(null);
// 方案A 第一步：开门柜（将邑，gather bound=已绑定）按商品备货——任务行不再出现
// 货道角标/选货道 UI。判定走将邑采集状态接口，非将邑 404 → 软兜底 false。
const doorCabinetByDevice = ref<Record<string, boolean>>({});
const doorCabinet = computed(
  () => !!doorCabinetByDevice.value[String(selected.value?.deviceId || '').trim()]
);
watch(
  () => selected.value?.deviceId,
  (id) => {
    const key = String(id || '').trim();
    if (!key || doorCabinetByDevice.value[key] !== undefined) return;
    merchantApi
      .jiangyiGatherStatus(key)
      .then((s) => {
        doorCabinetByDevice.value[key] = !!s.bound;
      })
      .catch(() => {
        doorCabinetByDevice.value[key] = false;
      });
  },
  { immediate: true }
);
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
/** skuId -> { quantity, capacity }：开门柜补货上限数据源（台账），详情打开时加载 */
const inventoryCaps = ref<Record<string, { quantity: number; capacity: number }>>({});
/**
 * skuId -> 后端下发的可补量（D28-A5）。与提交封顶同一个数，
 * 录入 `+` 与详情收敛都从这里取，客户端不再自己算第二层。
 */
const headroomBySku = ref<Record<string, OpenApiReplenishmentHeadroomDto>>({});
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
  inventoryCaps,
  headroomBySku,
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
  formatLineSummary,
  isPullOffType,
  lineTypeLabel,
  lineStatusLabel,
  displayTaskNotes,
  taskActionLabel
} = useReplenishmentDisplay({ skus });

const {
  heroSubtitle,
  efficiencyRateText,
  detailIsPullOff,
  statusOptions,
  emptyHint,
  goRequest,
  goDeviceShortage,
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
  currentStep
} = useReplenishmentShell({
  me,
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

const statusTabItems = computed(() =>
  statusOptions.value.map((s) => ({ key: s.value, label: s.label }))
);

/** CB-078：补货录像回放。与订单购物视频共用播放页，只是数据源按 taskId 取台账。 */
function openTaskVideos() {
  const task = selected.value;
  if (!task?.taskId) return;
  const query = [
    `taskId=${encodeURIComponent(String(task.taskId))}`,
    task.deviceId ? `deviceId=${encodeURIComponent(String(task.deviceId))}` : ''
  ]
    .filter(Boolean)
    .join('&');
  uni.navigateTo({ url: `/pages/video/video?${query}` });
}

const { checkIn, openDoor, adjustQty, confirmLines, completeTask, cancelTask } =
  useReplenishmentFulfillment({
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
    isDoorCabinet: () => doorCabinet.value,
    inventoryCaps,
    headroomBySku,
    formatLineSummary
  });

/**
 * 存量任务行超过可补量时按上限收敛（2026-10-28 用户实测，D28-A5 改用后端下发口径）：
 * 任务 8 的行 quantity=9，而后端下发 上限 4 / 在架 2 / 他单已领未上柜 0 ⇒ 最多只能补 2，
 * 打开详情看到 9 会让人以为「根本没限制」。只处理开门柜、未确认、RESTOCK 行、
 * 有上限依据的行；只减不加（绝不把操作员手上的数改大），收敛后给一条提示。
 */
watch(
  [headroomBySku, inventoryCaps, doorCabinet, linesConfirmed, lines],
  () => {
    if (!doorCabinet.value || linesConfirmed.value) return;
    let clamped = 0;
    for (const line of lines.value) {
      if (isPullOffType(line.lineType)) continue;
      const headroom = doorHeadroomForSku(line.skuId, headroomBySku.value, inventoryCaps.value);
      if (!headroom) continue;
      if ((Number(line.quantity) || 0) > headroom.room) {
        line.quantity = headroom.room;
        clamped += 1;
      }
    }
    if (clamped > 0) {
      uni.showToast({ title: `已按台账可补量收敛 ${clamped} 个品项`, icon: 'none' });
    }
  },
  { deep: true }
);

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
// 2026-11-10：原先直接把 load 交给 onPullDownRefresh，没有调用 stopPullDownRefresh，
// 下拉后转圈会一直挂着不收回。stop 放 finally 保证失败也收起。
onPullDownRefresh(async () => {
  try {
    await load();
  } finally {
    uni.stopPullDownRefresh();
  }
});

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
