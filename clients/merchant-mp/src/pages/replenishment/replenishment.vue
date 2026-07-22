<template>
  <view class="page">
    <view class="hero">
      <view class="hero-orb orb-one" /><view class="hero-orb orb-two" />
      <text class="eyebrow">现场补货</text>
      <text class="title">补货任务</text>
      <text class="subtitle">{{ heroSubtitle }}</text>
      <view class="stats">
        <view>
          <text class="stat-value">{{ pendingCount }}</text>
          <text class="stat-label">待处理</text>
        </view>
        <view>
          <text class="stat-value">{{ completedCount }}</text>
          <text class="stat-label">已完成</text>
        </view>
      </view>
      <view class="hero-actions">
        <button class="scan-pill" :loading="scanning" @click="onScan">扫码找柜</button>
        <button class="clear-pill" @click="goRequest">要货</button>
        <button
          v-if="preferredId && filterDeviceId !== preferredId"
          class="clear-pill"
          @click="usePreferredDevice"
        >常驻柜</button>
        <button v-if="filterDeviceId" class="clear-pill" @click="clearDeviceFilter">清除筛选</button>
      </view>
      <text v-if="filterDeviceId" class="filter-tip">
        当前筛选：{{ filterDeviceId }}
        <text v-if="filterDeviceId === preferredId">（常驻柜）</text>
      </text>
      <text v-else-if="preferredId" class="filter-tip muted">常驻柜 {{ preferredId }} · 点「常驻柜」快速筛选</text>
      <view v-if="!loading && pendingCount === 0" class="idle-tip">
        <text class="idle-title">今日暂无待补货</text>
        <text class="idle-desc">可扫码巡柜查看缺货，或切换「已完成」回顾记录；新任务由调度下发</text>
      </view>
    </view>

    <view class="filters">
      <view
        v-for="item in statusOptions"
        :key="item.value"
        class="filter"
        :class="{ active: status === item.value }"
        @click="changeStatus(item.value)"
      >{{ item.label }}</view>
    </view>

    <view v-if="loading" class="empty">任务加载中…</view>
    <view v-else-if="!tasks.length" class="empty">
      <text>{{ emptyHint }}</text>
      <view class="empty-actions-row">
        <button
          v-if="pendingCount === 0 && completedCount > 0 && status !== 'COMPLETED'"
          class="empty-scan"
          @click="changeStatus('COMPLETED')"
        >查看已完成</button>
        <button
          v-if="status && pendingCount === 0 && completedCount > 0"
          class="empty-scan ghost"
          @click="changeStatus('')"
        >查看全部</button>
        <button class="empty-scan" @click="onScan">扫码到柜</button>
      </view>
    </view>
    <view v-for="task in tasks" :key="task.taskId" class="task-card">
      <view class="task-accent" />
      <view class="task-head">
        <view>
          <text class="device-name">{{ deviceName(task.deviceId) }}</text>
          <text class="device-code">{{ task.deviceId }}</text>
        </view>
        <text class="status" :class="task.status.toLowerCase()">
          {{ dictLabel('replenishment_task_status', task.status) }}
        </text>
      </view>
      <view class="task-meta">
        <text>任务 #{{ task.taskId }}</text>
        <text>{{ formatTime(task.createdAt) }}</text>
      </view>
      <view v-if="task.notes" class="task-note">{{ task.notes }}</view>
      <button class="detail-btn" @click="openTask(task)">
        {{ taskActionLabel(task) }}
      </button>
    </view>

    <view v-if="detailVisible" class="mask" @click.self="closeDetail">
      <view class="sheet">
        <view class="sheet-handle" />
        <view class="sheet-head">
          <view>
            <text class="sheet-title">{{ deviceName(selected?.deviceId) }}</text>
            <text class="device-code">任务 #{{ selected?.taskId }} · {{ selected?.deviceId }}</text>
          </view>
          <text class="close" @click="closeDetail">×</text>
        </view>
        <view class="step-row four">
          <view class="step" :class="stepClass(1)">
            <text class="step-num">{{ selected?.status === 'COMPLETED' || selected?.checkInAt ? '✓' : '1' }}</text>
            <text class="step-label">签到</text>
          </view>
          <view class="step" :class="stepClass(2)">
            <text class="step-num">{{ selected?.status === 'COMPLETED' || doorOpened ? '✓' : '2' }}</text>
            <text class="step-label">开门</text>
          </view>
          <view class="step" :class="stepClass(3)">
            <text class="step-num">{{ selected?.status === 'COMPLETED' || linesConfirmed ? '✓' : '3' }}</text>
            <text class="step-label">核对</text>
          </view>
          <view class="step" :class="stepClass(4)">
            <text class="step-num">{{ selected?.status === 'COMPLETED' ? '✓' : '4' }}</text>
            <text class="step-label">{{ detailIsPullOff ? '下架' : '上架' }}</text>
          </view>
        </view>

        <button
          v-if="canRequest && selected?.status !== 'COMPLETED' && !selected?.checkInAt"
          class="primary-btn"
          role="button"
          data-testid="replenish-checkin"
          :disabled="submitting"
          @click="checkIn"
        >现场签到</button>
        <button
          v-if="canRequest && selected?.status !== 'COMPLETED' && selected?.checkInAt"
          class="primary-btn"
          role="button"
          data-testid="replenish-open-door"
          :disabled="submitting"
          @click="openDoor"
        >{{ doorOpened ? '再次开门' : detailIsPullOff ? '下架开门' : '补货开门' }}</button>
        <text v-if="!canRequest && selected?.status !== 'COMPLETED'" class="door-tip">
          只读查看 — 需补货操作权限方可签到/开门/{{ detailIsPullOff ? '下架' : '上架' }}
        </text>
        <text v-if="doorOpened && openSessionId" class="door-tip">
          已开门 · 会话 {{ openSessionId }} · 关门后继续核对{{ detailIsPullOff ? '下架' : '上架' }}
        </text>

        <view class="section-heading">
          <view>
            <text class="section-title">现场照片</text>
            <text class="section-subtitle">{{ selected?.checkInAt ? '最多 5 张，便于后台核对履约' : '签到后可拍照留存，最多 5 张' }}</text>
          </view>
          <text class="line-count">{{ evidenceItems.length }} 张</text>
        </view>
        <view class="evidence-row">
          <image
            v-for="(item, idx) in evidenceItems"
            :key="item.fileId || item.localPath || idx"
            class="evidence-thumb"
            :src="item.localPath"
            mode="aspectFill"
            @click="previewEvidence(idx)"
          />
          <view
            v-if="canRequest && selected?.status !== 'COMPLETED' && selected?.checkInAt && evidenceItems.length < 5"
            class="evidence-add"
            @click="addEvidence"
          >+</view>
          <text
            v-else-if="canRequest && selected?.status !== 'COMPLETED' && !selected?.checkInAt"
            class="evidence-hint"
          >签到后可拍照</text>
        </view>

        <view class="section-heading">
          <view>
            <text class="section-title">{{ detailIsPullOff ? '本次下架商品' : '本次补货商品' }}</text>
            <text class="section-subtitle">{{
              detailIsPullOff
                ? '请逐项核对下架数量与批次'
                : selected?.outboundId
                  ? `仓配出库 #${selected.outboundId} · 核对后完成将签收在途`
                  : '请逐项核对商品、批次和货道'
            }}</text>
          </view>
          <text class="line-count">{{ lines.length }} 项</text>
        </view>
        <view v-if="detailLoading" class="empty small">明细加载中…</view>
        <view v-else-if="!lines.length" class="empty small lines-empty">
          <view class="lines-empty-title">{{ detailIsPullOff ? '暂无下架明细' : '暂无补货明细' }}</view>
          <view class="lines-empty-tip">
            {{ detailIsPullOff ? '可先开门执行下架；有任务明细时会显示在此核对' : '可先开门上架；有出库明细时会显示在此核对' }}
          </view>
        </view>
        <view
          v-for="line in lines"
          :key="line.lineId || `${line.skuId}-${line.batchNo}-${line.slotId}`"
          class="line-card"
        >
          <view class="line-main">
            <view class="product-thumb">{{ productIcon(line.skuId) }}</view>
            <view class="product-copy">
              <text class="sku-name">{{ skuName(line.skuId) }}</text>
              <text class="device-code">{{ line.skuId }}</text>
            </view>
            <view
              v-if="canRequest && selected?.status !== 'COMPLETED' && !linesConfirmed && !line.applied"
              class="qty-stepper"
            >
              <text class="qty-btn" @click="adjustQty(line, -1)">−</text>
              <text class="qty">{{ line.quantity }}</text>
              <text class="qty-btn" @click="adjustQty(line, 1)">+</text>
            </view>
            <text v-else class="qty">× {{ line.quantity }}</text>
          </view>
          <view class="line-meta">
            <text>批次 {{ line.batchNo || '-' }}</text>
            <text>货道 {{ line.slotId || '待分配' }}</text>
            <text class="line-type">{{ lineTypeLabel(line.lineType) }}</text>
          </view>
          <view
            v-if="
              canRequest
                && selected?.status !== 'COMPLETED'
                && !linesConfirmed
                && !line.applied
                && !isPullOffType(line.lineType)
                && !line.slotId
            "
            class="slot-pick"
          >
            <text class="slot-pick-label">选择货道</text>
            <view v-if="slotOptionsFor(line).length" class="slot-chips">
              <text
                v-for="opt in slotOptionsFor(line)"
                :key="opt.slotCode"
                class="slot-chip"
                :class="{ disabled: opt.room <= 0, active: line.slotId === opt.slotCode }"
                @click="assignSlot(line, opt)"
              >{{ opt.slotCode }} · 余{{ opt.room }}</text>
            </view>
            <text v-else class="slot-empty">暂无可用货道，请先腾出容量或将数量调为 0</text>
          </view>
          <view class="line-meta">
            <text>到期 {{ line.expiryDate || '-' }}</text>
            <text>{{ lineStatusLabel(line) }}</text>
          </view>
          <view
            v-if="selected?.status !== 'COMPLETED' && line.slotId && slotHint(line)"
            class="line-cap"
            :class="{ full: slotHeadroom(line) <= 0, warn: slotHeadroom(line) > 0 && line.quantity > slotHeadroom(line) }"
          >{{ slotHint(line) }}</view>
        </view>

        <view v-if="canRequest && selected?.status !== 'COMPLETED' && selected?.checkInAt" class="action-dock">
          <button
            v-if="!linesConfirmed"
            class="secondary-btn"
            role="button"
            data-testid="replenish-confirm-lines"
            :disabled="submitting || !lines.length"
            @click="confirmLines"
          >确认商品与数量</button>
          <button
            class="primary-btn"
            role="button"
            data-testid="replenish-complete"
            :disabled="submitting || !lines.length || !linesConfirmed"
            @click="completeTask"
          >{{ detailIsPullOff ? '确认全部下架' : '确认全部上架' }}</button>
        </view>
        <view v-if="selected?.status === 'COMPLETED'" class="complete-banner">
          {{
            detailIsPullOff
              ? '任务已完成，下架库存已同步更新'
              : '任务已完成，商品库存和在途状态已同步更新'
          }}
        </view>
      </view>
    </view>

    <!-- H5 可访问确认框：替代 uni.showModal，便于自动化与读屏点击 -->
    <view
      v-if="confirmDialog.visible"
      class="confirm-mask"
      role="dialog"
      aria-modal="true"
      :aria-label="confirmDialog.title"
      data-testid="confirm-dialog"
      @click.self="resolveConfirm(false)"
      @touchmove.stop.prevent
    >
      <view class="confirm-card" @click.stop>
        <text class="confirm-title">{{ confirmDialog.title }}</text>
        <text class="confirm-body">{{ confirmDialog.content }}</text>
        <view class="confirm-actions">
          <button
            type="button"
            class="confirm-btn cancel"
            role="button"
            :aria-label="confirmDialog.cancelText"
            data-testid="confirm-cancel"
            @click.stop="resolveConfirm(false)"
          >{{ confirmDialog.cancelText }}</button>
          <button
            type="button"
            class="confirm-btn ok"
            role="button"
            :aria-label="confirmDialog.confirmText"
            data-testid="confirm-ok"
            @click.stop="resolveConfirm(true)"
          >{{ confirmDialog.confirmText }}</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { formatDateTimeShort } from '@aicabinet/shared-uni/format';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import type { DeviceSlot } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canReplenish = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));
const preferredId = ref(getPreferredDeviceId());

type Task = {
  taskId: number;
  deviceId: string;
  status: string;
  notes?: string;
  outboundId?: number;
  checkInAt?: string;
  createdAt?: string;
};
type Line = {
  lineId?: number;
  lineType: string;
  skuId: string;
  batchNo?: string;
  productionDate?: string;
  expiryDate?: string;
  quantity: number;
  slotId?: string;
  applied: boolean;
};

const loading = ref(false);
const detailLoading = ref(false);
const submitting = ref(false);
const scanning = ref(false);
const status = ref('');
const filterDeviceId = ref('');
const focusTaskId = ref<number | null>(null);
const allTasks = ref<Task[]>([]);
const devices = ref<Record<string, unknown>[]>([]);
const skus = ref<Record<string, unknown>[]>([]);
const detailVisible = ref(false);
const selected = ref<Task | null>(null);
const lines = ref<Line[]>([]);
const linesConfirmed = ref(false);
const evidenceItems = ref<{ localPath: string; fileId?: number }[]>([]);
const doorOpened = ref(false);
const openSessionId = ref('');
/** slotCode -> { maxLevel, bookQty } */
const slotCaps = ref<Record<string, { maxLevel: number; bookQty: number }>>({});
const deviceSlotsList = ref<DeviceSlot[]>([]);

const heroSubtitle = computed(() => '扫码到柜 → 签到 → 开门 → 核对履约');
const detailIsPullOff = computed(() => {
  if (!lines.value.length) {
    const notes = String(selected.value?.notes || '');
    return /from-expiry|PULL_OFF|下架/i.test(notes);
  }
  return lines.value.every((l) => isPullOffType(l.lineType));
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
  return /from-expiry|PULL_OFF|下架/i.test(String(task.notes || ''));
}

function taskActionLabel(task: Task) {
  if (task.status === 'COMPLETED') return '查看完成明细';
  const pull = taskLooksPullOff(task);
  if (task.checkInAt) return pull ? '继续下架' : '继续补货';
  return pull ? '开始下架' : '开始补货';
}

type ConfirmDialogState = {
  visible: boolean;
  title: string;
  content: string;
  confirmText: string;
  cancelText: string;
  resolve: ((ok: boolean) => void) | null;
};
const confirmDialog = ref<ConfirmDialogState>({
  visible: false,
  title: '',
  content: '',
  confirmText: '确定',
  cancelText: '取消',
  resolve: null
});

function askConfirm(opts: {
  title: string;
  content: string;
  confirmText?: string;
  cancelText?: string;
}): Promise<boolean> {
  return new Promise((resolve) => {
    if (confirmDialog.value.visible && confirmDialog.value.resolve) {
      confirmDialog.value.resolve(false);
    }
    confirmDialog.value = {
      visible: true,
      title: opts.title,
      content: opts.content,
      confirmText: opts.confirmText || '确定',
      cancelText: opts.cancelText || '取消',
      resolve
    };
  });
}

function resolveConfirm(ok: boolean) {
  const resolver = confirmDialog.value.resolve;
  confirmDialog.value = {
    visible: false,
    title: '',
    content: '',
    confirmText: '确定',
    cancelText: '取消',
    resolve: null
  };
  resolver?.(ok);
}

const statusOptions = computed(() => [
  { value: '', label: '全部' },
  ...dictOptions('replenishment_task_status').filter((item) =>
    ['PENDING', 'IN_PROGRESS', 'COMPLETED'].includes(item.value)
  )
]);

const tasks = computed(() => {
  let rows = allTasks.value.filter((t) => t.status !== 'CANCELLED');
  if (filterDeviceId.value) {
    rows = rows.filter((t) => t.deviceId === filterDeviceId.value);
  }
  if (status.value) {
    rows = rows.filter((t) => t.status === status.value);
  }
  return rows;
});

const pendingCount = computed(
  () => allTasks.value.filter((item) => item.status !== 'COMPLETED' && item.status !== 'CANCELLED').length
);
const completedCount = computed(
  () => allTasks.value.filter((item) => item.status === 'COMPLETED').length
);
const emptyHint = computed(() => {
  if (filterDeviceId.value) {
    return status.value
      ? `该柜机暂无「${dictLabel('replenishment_task_status', status.value) || status.value}」任务`
      : '该柜机暂无补货任务';
  }
  if (status.value === 'IN_PROGRESS' && pendingCount.value === 0 && completedCount.value > 0) {
    return '暂无进行中的任务，可查看已完成记录';
  }
  if (status.value) {
    return `暂无「${dictLabel('replenishment_task_status', status.value) || status.value}」任务`;
  }
  return '当前没有补货任务';
});

function applyRouteQuery(opts?: Record<string, string | undefined>) {
  const deviceId = opts?.deviceId || readHashQuery('deviceId');
  const taskIdRaw = opts?.taskId || readHashQuery('taskId');
  if (deviceId) {
    filterDeviceId.value = String(deviceId).trim().toUpperCase();
  }
  if (taskIdRaw) {
    const id = Number(taskIdRaw);
    if (Number.isFinite(id) && id > 0) focusTaskId.value = id;
  }
  if (deviceId || taskIdRaw) {
    status.value = '';
  }
}

function readHashQuery(key: string): string | undefined {
  if (typeof location === 'undefined') return undefined;
  const m = location.hash.match(new RegExp(`[?&]${key}=([^&]+)`));
  return m ? decodeURIComponent(m[1]) : undefined;
}

function usePreferredDevice() {
  const id = preferredId.value;
  if (!id) return;
  filterDeviceId.value = id.trim().toUpperCase();
  status.value = '';
  void load();
}

function goRequest() {
  const q = filterDeviceId.value
    ? `?deviceId=${encodeURIComponent(filterDeviceId.value)}`
    : '';
  uni.navigateTo({ url: `/pages/request/request${q}` });
}

onLoad((opts) => {
  applyRouteQuery(opts as Record<string, string | undefined>);
  // 无深链柜机时，默认筛常驻柜（仅首次进入，不覆盖用户「清除筛选」）
  if (!filterDeviceId.value) {
    const preferred = getPreferredDeviceId();
    if (preferred) filterDeviceId.value = preferred.trim().toUpperCase();
  }
});

function deviceName(id?: string) {
  const d = devices.value.find((item) => item.deviceId === id) as { deviceName?: string } | undefined;
  return d?.deviceName || id || '未知柜机';
}

function skuName(id: string) {
  const s = skus.value.find((item) => item.skuId === id) as { skuName?: string } | undefined;
  return s?.skuName || id;
}

function productIcon(id: string) {
  if (id.includes('WATER')) return '💧';
  if (id.includes('MILK')) return '🥛';
  if (id.includes('NOODLE')) return '🍜';
  if (id.includes('SNACK')) return '🥔';
  return '🥤';
}

function formatTime(value?: string) {
  return formatDateTimeShort(value);
}

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  try {
    await refreshMe();
  } catch {
    me.value = (uni.getStorageSync('merchant_me') as import('@aicabinet/shared-types').MerchantMe) || null;
  }
  if (!canReplenish.value) {
    uni.showToast({ title: '无补货权限', icon: 'none' });
    uni.switchTab({ url: '/pages/home/home' });
    return;
  }
  // H5 同页改 query 时 onLoad 不重跑，每次刷新都同步深链
  applyRouteQuery();
  loading.value = true;
  try {
    const [taskRows, deviceRows, skuRows] = await Promise.all([
      merchantApi.replenishmentTasks(),
      merchantApi.devices(),
      merchantApi.pricing().catch(() => [] as Record<string, unknown>[])
    ]);
    allTasks.value = taskRows as Task[];
    devices.value = deviceRows as Record<string, unknown>[];
    skus.value = (skuRows || []) as Record<string, unknown>[];

    // 深链 / 扫柜进入：优先按 taskId 打开（即使详情已打开也要切换）
    let open: Task | undefined;
    const wantedTaskId = focusTaskId.value;
    if (focusTaskId.value) {
      open = allTasks.value.find(
        (t) => t.taskId === focusTaskId.value && t.status !== 'CANCELLED'
      );
      focusTaskId.value = null;
    } else if (!detailVisible.value && filterDeviceId.value) {
      open = allTasks.value.find(
        (t) =>
          t.deviceId === filterDeviceId.value &&
          t.status !== 'COMPLETED' &&
          t.status !== 'CANCELLED'
      );
    }
    if (open) {
      await openTask(open);
    } else if (wantedTaskId) {
      uni.showToast({ title: `任务 #${wantedTaskId} 不可用或已取消`, icon: 'none' });
    }
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '加载失败', icon: 'none' });
  } finally {
    loading.value = false;
    uni.stopPullDownRefresh();
  }
}

function changeStatus(value: string) {
  status.value = value;
}

function clearDeviceFilter() {
  filterDeviceId.value = '';
}

async function onScan() {
  if (scanning.value) return;
  scanning.value = true;
  try {
    const id = await scanCabinetDeviceId();
    if (!id) return;
    filterDeviceId.value = id;
    status.value = '';
    const open = allTasks.value.find(
      (t) => t.deviceId === id && t.status !== 'COMPLETED' && t.status !== 'CANCELLED'
    );
    if (open) {
      await openTask(open);
    } else {
      uni.showToast({ title: '该柜暂无任务，已筛选列表', icon: 'none' });
    }
  } finally {
    scanning.value = false;
  }
}

function doorCacheKey(taskId: number) {
  return `replenish_door_${taskId}`;
}

function restoreDoorState(taskId: number) {
  try {
    const raw = uni.getStorageSync(doorCacheKey(taskId));
    if (!raw) {
      doorOpened.value = false;
      openSessionId.value = '';
      return;
    }
    const cached = typeof raw === 'string' ? JSON.parse(raw) : raw;
    doorOpened.value = !!cached?.sessionId;
    openSessionId.value = cached?.sessionId || '';
  } catch {
    doorOpened.value = false;
    openSessionId.value = '';
  }
}

function persistDoorState(taskId: number, sessionId: string) {
  uni.setStorageSync(doorCacheKey(taskId), { sessionId, at: Date.now() });
}

function currentStep(): number {
  if (!selected.value) return 1;
  if (selected.value.status === 'COMPLETED') return 5;
  if (linesConfirmed.value) return 4;
  if (doorOpened.value) return 3;
  if (selected.value.checkInAt) return 2;
  return 1;
}

function stepClass(step: number) {
  if (selected.value?.status === 'COMPLETED') {
    return { done: true, current: false };
  }
  const cur = currentStep();
  return { done: step < cur, current: step === cur };
}

function syncTaskInList(task: Task) {
  const idx = allTasks.value.findIndex((t) => t.taskId === task.taskId);
  if (idx >= 0) {
    allTasks.value[idx] = { ...allTasks.value[idx], ...task };
  }
}

async function addEvidence() {
  if (!selected.value || !canRequest.value) return;
  if (!selected.value.checkInAt) {
    uni.showToast({ title: '请先签到再拍照', icon: 'none' });
    return;
  }
  if (evidenceItems.value.length >= 5) {
    uni.showToast({ title: '最多 5 张', icon: 'none' });
    return;
  }
  const paths = await new Promise<string[]>((resolve) => {
    uni.chooseImage({
      count: 5 - evidenceItems.value.length,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => resolve(res.tempFilePaths || []),
      fail: () => resolve([])
    });
  });
  for (const path of paths) {
    try {
      const uploaded = await merchantApi.uploadReplenishmentEvidence(selected.value.taskId, path);
      evidenceItems.value.push({ localPath: path, fileId: uploaded.fileId });
    } catch (e) {
      uni.showToast({
        title: e instanceof Error ? e.message : '上传失败',
        icon: 'none'
      });
      break;
    }
  }
}

function previewEvidence(index: number) {
  const urls = evidenceItems.value.map((i) => i.localPath).filter(Boolean);
  if (!urls.length) return;
  uni.previewImage({ urls, current: urls[index] || urls[0] });
}

async function openTask(task: Task) {
  // 打开前用列表最新状态（签到后避免仍用旧 checkInAt）
  const fromList = allTasks.value.find((t) => t.taskId === task.taskId);
  selected.value = { ...(fromList || task) };
  detailVisible.value = true;
  linesConfirmed.value = selected.value.status === 'COMPLETED';
  evidenceItems.value = [];
  restoreDoorState(selected.value.taskId);
  detailLoading.value = true;
  slotCaps.value = {};
  deviceSlotsList.value = [];
  try {
    // 再拉一次任务列表，确保签到/状态与明细一致
    try {
      const latest = (await merchantApi.replenishmentTasks()) as Task[];
      allTasks.value = latest;
      const fresh = latest.find((t) => t.taskId === task.taskId);
      if (fresh) selected.value = { ...fresh };
    } catch {
      /* keep selected */
    }
    const [taskLines, slots, evidence] = await Promise.all([
      merchantApi.replenishmentTaskLines(task.taskId) as Promise<Line[]>,
      merchantApi.deviceSlots(task.deviceId).catch(() => [] as DeviceSlot[]),
      merchantApi.listReplenishmentEvidence(task.taskId).catch(() => [])
    ]);
    lines.value = taskLines;
    deviceSlotsList.value = (slots || []) as DeviceSlot[];
    const mapped = await Promise.all(
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
    evidenceItems.value = mapped;
    const map: Record<string, { maxLevel: number; bookQty: number }> = {};
    for (const s of deviceSlotsList.value) {
      const code = String(s.slotCode || '').toUpperCase();
      if (!code) continue;
      map[code] = {
        maxLevel: Number(s.maxLevel) || 0,
        bookQty: Number(s.bookQty) || 0
      };
    }
    slotCaps.value = map;
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '明细加载失败', icon: 'none' });
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
    uni.showToast({ title: '该货道已满', icon: 'none' });
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
    const rooms = slotOptionsFor(line).map((o) => o.room).filter((n) => n > 0);
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
  if (line.quantity > room) return `超出容量：最多再补 ${room}（已有 ${cap.bookQty}/${cap.maxLevel}）`;
  return `还可补 ${room}（已有 ${cap.bookQty}/${cap.maxLevel}）`;
}

function closeDetail() {
  if (!submitting.value) detailVisible.value = false;
}

async function checkIn() {
  if (!selected.value || submitting.value) return;
  if (!canRequest.value) {
    uni.showToast({ title: '无补货操作权限', icon: 'none' });
    return;
  }
  submitting.value = true;
  let body: Record<string, number> = {};
  let locationOk = false;
  try {
    const location = await new Promise<UniApp.GetLocationSuccess>((resolve, reject) =>
      uni.getLocation({ type: 'gcj02', success: resolve, fail: reject })
    );
    body = { latitude: location.latitude, longitude: location.longitude };
    locationOk = true;
  } catch {
    const cont = await askConfirm({
      title: '定位失败',
      content: '无法获取当前位置，仍可继续签到，但无法校验是否到店。是否继续？',
      confirmText: '继续签到',
      cancelText: '取消'
    });
    if (!cont) {
      submitting.value = false;
      return;
    }
  }
  try {
    selected.value = (await merchantApi.checkInReplenishmentTask(selected.value.taskId, body)) as Task;
    syncTaskInList(selected.value);
    uni.showToast({
      title: locationOk ? '签到成功' : '已签到（未带定位）',
      icon: locationOk ? 'success' : 'none'
    });
  } catch (error) {
    const msg = error instanceof Error ? error.message : '签到失败';
    if (locationOk && (msg.includes('签到位置') || msg.includes('超出') || msg.includes('米'))) {
      const retry = await askConfirm({
        title: '距离柜机过远',
        content: `${msg}\n\n若你已在柜前（定位漂移），可改为不校验距离继续签到。`,
        confirmText: '继续签到',
        cancelText: '取消'
      });
      if (retry) {
        try {
          selected.value = (await merchantApi.checkInReplenishmentTask(selected.value.taskId, {})) as Task;
          syncTaskInList(selected.value);
          uni.showToast({ title: '已签到（未校验距离）', icon: 'none' });
        } catch (e2) {
          uni.showToast({
            title: e2 instanceof Error ? e2.message : '签到失败',
            icon: 'none',
            duration: 3600
          });
        }
      }
    } else {
      uni.showToast({ title: msg, icon: 'none', duration: 3600 });
    }
  } finally {
    submitting.value = false;
  }
}

async function openDoor() {
  if (!selected.value || submitting.value) return;
  if (!canRequest.value) {
    uni.showToast({ title: '无补货操作权限', icon: 'none' });
    return;
  }
  if (!selected.value.checkInAt) {
    uni.showToast({ title: '请先现场签到', icon: 'none' });
    return;
  }
  const ok = await askConfirm({
    title: doorOpened.value ? '再次开门' : detailIsPullOff.value ? '下架开门' : '补货开门',
    content: '将下发开门指令，本次为补货会话，不会按购物扣款。请确认人在柜前。',
    confirmText: '开门',
    cancelText: '取消'
  });
  if (!ok) return;
  submitting.value = true;
  try {
    const session = await merchantApi.openReplenishmentDoor(selected.value.taskId);
    doorOpened.value = true;
    openSessionId.value = session.sessionId || '';
    if (session.sessionId) persistDoorState(selected.value.taskId, session.sessionId);
    selected.value = {
      ...selected.value,
      status: selected.value.status === 'PENDING' ? 'IN_PROGRESS' : selected.value.status
    };
    uni.showToast({ title: '开门指令已下发', icon: 'success' });
    await load();
    const fresh = allTasks.value.find((t) => t.taskId === selected.value?.taskId);
    if (fresh) selected.value = { ...fresh };
  } catch (error) {
    const msg = error instanceof Error ? error.message : '开门失败';
    uni.showToast({ title: msg, icon: 'none', duration: 3200 });
  } finally {
    submitting.value = false;
  }
}

function adjustQty(line: Line, delta: number) {
  if (!canRequest.value) return;
  if (linesConfirmed.value || line.applied || selected.value?.status === 'COMPLETED') return;
  const cur = Number(line.quantity) || 0;
  if (delta > 0) {
    if (!isPullOffType(line.lineType)) {
      const room = slotHeadroom(line);
      if (cur >= room) {
        uni.showToast({
          title: room <= 0 ? '货道已满，无法再加' : `最多再补 ${room}`,
          icon: 'none'
        });
        return;
      }
      line.quantity = Math.min(room, cur + delta);
      return;
    }
    line.quantity = cur + delta;
    return;
  }
  line.quantity = Math.max(0, cur + delta);
}

function clampLinesToCapacity() {
  let changed = false;
  for (const line of lines.value) {
    if (line.applied || isPullOffType(line.lineType)) continue;
    const room = slotHeadroom(line);
    const qty = Number(line.quantity) || 0;
    if (qty > room) {
      line.quantity = room;
      changed = true;
    }
  }
  return changed;
}

async function confirmLines() {
  if (!selected.value || submitting.value) return;
  if (!canRequest.value) {
    uni.showToast({ title: '无补货操作权限', icon: 'none' });
    return;
  }
  const over = lines.value.filter(
    (l) => !l.applied && !isPullOffType(l.lineType) && (Number(l.quantity) || 0) > slotHeadroom(l)
  );
  if (over.length) {
    const ok = await askConfirm({
      title: '货道容量不足',
      content: `${over.map((l) => `${l.slotId || '?'} 最多再补 ${slotHeadroom(l)}`).join('；')}。是否自动调低数量后继续？`,
      confirmText: '自动调低',
      cancelText: '手动改'
    });
    if (!ok) return;
    clampLinesToCapacity();
  }
  const positive = lines.value.filter((l) => (Number(l.quantity) || 0) > 0);
  if (!positive.length) {
    uni.showToast({ title: '调低后无有效数量，请换货道或取消该行', icon: 'none' });
    return;
  }
  const unassigned = positive.filter((l) => !isPullOffType(l.lineType) && !String(l.slotId || '').trim());
  if (unassigned.length) {
    uni.showToast({ title: '请先为待分配行选择货道', icon: 'none' });
    return;
  }
  submitting.value = true;
  try {
    lines.value = (await merchantApi.confirmReplenishmentLines(
      selected.value.taskId,
      positive.map(({ lineId, ...line }) => ({
        ...line,
        lineType: line.lineType || 'RESTOCK'
      }))
    )) as Line[];
    linesConfirmed.value = true;
    uni.showToast({ title: '清单已确认', icon: 'success' });
  } catch (error) {
    const msg = error instanceof Error ? error.message : '确认失败';
    if (msg.includes('容量不足')) {
      const auto = await askConfirm({
        title: '确认失败',
        content: `${msg}\n\n是否按货道余量自动调低？`,
        confirmText: '自动调低',
        cancelText: '知道了'
      });
      if (auto) clampLinesToCapacity();
    } else {
      uni.showToast({ title: msg, icon: 'none', duration: 3600 });
    }
  } finally {
    submitting.value = false;
  }
}

async function completeTask() {
  if (!selected.value || submitting.value) return;
  if (!canRequest.value) {
    uni.showToast({ title: '无补货操作权限', icon: 'none' });
    return;
  }
  if (!linesConfirmed.value) {
    uni.showToast({ title: '请先确认商品与数量', icon: 'none' });
    return;
  }
  if (!doorOpened.value) {
    const cont = await askConfirm({
      title: '尚未开门',
      content: detailIsPullOff.value
        ? '还未下发下架开门。若已现场开门完成下架，仍可继续确认完成。'
        : '还未下发补货开门。若已现场开门完成上架，仍可继续确认完成。',
      confirmText: '继续完成',
      cancelText: '去开门'
    });
    if (!cont) return;
  }
  if (evidenceItems.value.length === 0) {
    const photoOk = await askConfirm({
      title: '未上传照片',
      content: detailIsPullOff.value
        ? '建议先拍照留存下架证据，确认仍要完成任务？'
        : '建议先拍照留存补货证据，确认仍要完成任务？',
      confirmText: '仍完成',
      cancelText: '去拍照'
    });
    if (!photoOk) return;
  }
  const ok = await askConfirm({
    title: detailIsPullOff.value ? '确认全部下架' : '确认全部上架',
    content: detailIsPullOff.value
      ? '完成后将扣减柜机库存，请确认下架商品、批次和数量无误。'
      : '完成后将更新柜机库存并签收在途商品，请确认商品、批次和货道无误。',
    confirmText: '确认完成',
    cancelText: '取消'
  });
  if (!ok) return;
  submitting.value = true;
  try {
    const taskId = selected.value.taskId;
    selected.value = (await merchantApi.completeReplenishmentTask(taskId)) as Task;
    lines.value = lines.value.map((line) => ({ ...line, applied: true }));
    try {
      uni.removeStorageSync(doorCacheKey(taskId));
    } catch {
      /* ignore */
    }
    doorOpened.value = false;
    openSessionId.value = '';
    uni.showToast({ title: detailIsPullOff.value ? '下架完成' : '补货完成', icon: 'success' });
    await load();
    const fresh = allTasks.value.find((t) => t.taskId === taskId);
    if (fresh) selected.value = { ...fresh };
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '完成失败', icon: 'none' });
  } finally {
    submitting.value = false;
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
  min-height: 100vh;
  padding: 24rpx;
  background: linear-gradient(180deg, #ecfdf5 0, #f8fafc 320rpx, #f8fafc 100%);
  box-sizing: border-box;
}
.hero {
  position: relative;
  overflow: hidden;
  padding: 34rpx;
  border-radius: 28rpx;
  color: #fff;
  background: linear-gradient(145deg, #064e3b, #0f766e 58%, #14b8a6);
  box-shadow: 0 18rpx 40rpx rgba(15, 118, 110, 0.2);
}
.hero-orb {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.09);
  pointer-events: none;
}
.orb-one { width: 160rpx; height: 160rpx; right: -54rpx; top: -68rpx; }
.orb-two { width: 88rpx; height: 88rpx; right: 80rpx; bottom: -55rpx; }
.eyebrow, .title, .subtitle { display: block; position: relative; }
.eyebrow {
  font-size: 22rpx;
  opacity: 0.75;
  letter-spacing: 4rpx;
  width: max-content;
  padding: 6rpx 12rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.12);
}
.title { margin-top: 12rpx; font-size: 42rpx; font-weight: 800; }
.subtitle { margin-top: 10rpx; font-size: 24rpx; opacity: 0.82; line-height: 1.55; }
.stats {
  position: relative;
  display: flex;
  gap: 60rpx;
  margin-top: 28rpx;
  padding-top: 22rpx;
  border-top: 1rpx solid rgba(255, 255, 255, 0.16);
}
.stat-value, .stat-label { display: block; }
.stat-value { font-size: 40rpx; font-weight: 800; }
.stat-label { font-size: 22rpx; opacity: 0.75; }
.hero-actions {
  position: relative;
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;
}
.scan-pill, .clear-pill {
  margin: 0;
  height: 72rpx;
  line-height: 72rpx;
  padding: 0 28rpx;
  border-radius: 36rpx;
  font-size: 26rpx;
  font-weight: 600;
}
.scan-pill { background: #fff; color: #0f766e; }
.clear-pill { background: rgba(255, 255, 255, 0.18); color: #fff; }
.scan-pill::after, .clear-pill::after { border: none; }
.filter-tip {
  position: relative;
  display: block;
  margin-top: 16rpx;
  font-size: 22rpx;
  opacity: 0.85;
}
.filter-tip.muted { opacity: 0.7; }
.idle-tip {
  position: relative;
  margin-top: 18rpx;
  padding: 16rpx 18rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.14);
}
.idle-title { display: block; font-size: 24rpx; font-weight: 700; }
.idle-desc { display: block; margin-top: 6rpx; font-size: 22rpx; opacity: 0.88; line-height: 1.4; }

.filters { display: flex; gap: 12rpx; margin: 24rpx 0; overflow-x: auto; }
.filter {
  padding: 14rpx 24rpx;
  border-radius: 999rpx;
  color: #64748b;
  background: #fff;
  font-size: 24rpx;
  white-space: nowrap;
  border: 1rpx solid #e2e8f0;
}
.filter.active { color: #fff; background: #0f766e; border-color: #0f766e; }

.task-card {
  position: relative;
  overflow: hidden;
  margin-bottom: 18rpx;
  padding: 26rpx;
  border-radius: 24rpx;
  background: #fff;
  border: 1rpx solid #e2e8f0;
  box-shadow: 0 8rpx 30rpx rgba(15, 118, 110, 0.08);
}
.task-accent {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 6rpx;
  background: linear-gradient(#10b981, #0d9488);
}
.task-head, .task-meta, .line-main, .line-meta, .sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18rpx;
}
.device-name, .device-code { display: block; }
.device-name { font-size: 30rpx; font-weight: 700; color: #0f172a; }
.device-code { margin-top: 4rpx; color: #94a3b8; font-size: 21rpx; }
.status {
  padding: 8rpx 16rpx;
  border-radius: 999rpx;
  color: #92400e;
  background: #fef3c7;
  font-size: 22rpx;
  font-weight: 600;
}
.status.completed { color: #166534; background: #dcfce7; }
.task-meta, .line-meta { margin-top: 16rpx; color: #64748b; font-size: 22rpx; }
.line-type {
  color: #0f766e;
  background: #ecfdf5;
  padding: 2rpx 10rpx;
  border-radius: 999rpx;
  font-size: 20rpx;
}
.line-cap {
  margin-top: 12rpx;
  padding: 10rpx 14rpx;
  border-radius: 12rpx;
  font-size: 22rpx;
  color: #0f766e;
  background: #ecfdf5;
}
.line-cap.warn { color: #b45309; background: #fffbeb; }
.line-cap.full { color: #b91c1c; background: #fef2f2; }
.slot-pick { margin-top: 12rpx; }
.slot-pick-label { display: block; font-size: 22rpx; color: #0f766e; margin-bottom: 8rpx; font-weight: 600; }
.slot-chips { display: flex; flex-wrap: wrap; gap: 10rpx; }
.slot-chip {
  padding: 8rpx 16rpx; border-radius: 999rpx; background: #ecfdf5; color: #0f766e;
  font-size: 22rpx; border: 1rpx solid #99f6e4;
}
.slot-chip.active { background: #0f766e; color: #fff; border-color: #0f766e; }
.slot-chip.disabled { opacity: 0.4; background: #f1f5f9; color: #94a3b8; border-color: #e2e8f0; }
.slot-empty { font-size: 22rpx; color: #b91c1c; }
.task-note {
  margin-top: 16rpx;
  padding: 16rpx;
  border-radius: 14rpx;
  color: #475569;
  background: #f8fafc;
  font-size: 22rpx;
}
.detail-btn, .primary-btn, .secondary-btn {
  margin-top: 22rpx;
  border: 0;
  border-radius: 18rpx;
  font-size: 27rpx;
  font-weight: 700;
}
.detail-btn, .primary-btn { color: #fff; background: #0f766e; }
.secondary-btn { color: #0f766e; background: #ccfbf1; }
.detail-btn::after, .primary-btn::after, .secondary-btn::after { border: none; }

.empty {
  padding: 80rpx 20rpx;
  text-align: center;
  color: #94a3b8;
  font-size: 28rpx;
}
.empty.small { padding: 30rpx; }
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
  border-radius: 36rpx;
  background: #0f766e;
  color: #fff;
  font-size: 26rpx;
}
.empty-scan.ghost {
  color: #0f766e;
  background: #ecfdf5;
}
.empty-scan::after { border: none; }

.mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  /* 加深遮罩，避免列表文字从弹层边缘透出 */
  background: rgba(15, 23, 42, 0.62);
}
.sheet {
  width: 100%;
  max-width: 520px;
  max-height: 88vh;
  padding: 30rpx 26rpx calc(30rpx + env(safe-area-inset-bottom));
  border-radius: 32rpx 32rpx 0 0;
  background: #fff;
  overflow-y: auto;
  box-sizing: border-box;
  /* 实心底 + 顶部分隔，杜绝背后列表透视 */
  isolation: isolate;
  box-shadow: 0 -12rpx 40rpx rgba(15, 23, 42, 0.18);
}
.sheet-handle {
  width: 64rpx;
  height: 8rpx;
  margin: 0 auto 16rpx;
  border-radius: 4rpx;
  background: #cbd5e1;
}
.sheet-title { display: block; font-size: 34rpx; font-weight: 800; }
.close { padding: 10rpx; color: #64748b; font-size: 46rpx; }
.step-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12rpx;
  margin: 26rpx 0;
  padding: 16rpx 8rpx;
  border-radius: 18rpx;
  background: #f8fafc;
}
.step-row.four { grid-template-columns: repeat(4, 1fr); }
.door-tip {
  display: block;
  margin-top: 12rpx;
  padding: 14rpx 16rpx;
  border-radius: 12rpx;
  background: #ecfdf5;
  color: #047857;
  font-size: 22rpx;
  line-height: 1.4;
}
.step { text-align: center; color: #94a3b8; font-size: 21rpx; }
.step-num {
  display: flex;
  width: 44rpx;
  height: 44rpx;
  margin: 0 auto 8rpx;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #64748b;
  background: #e2e8f0;
  font-size: 24rpx;
}
.step-label { display: block; }
.step.done { color: #0f766e; }
.step.done .step-num { color: #fff; background: #0f766e; }
.step.current { color: #0f766e; font-weight: 600; }
.step.current .step-num {
  color: #fff;
  background: #0f766e;
  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.2);
}
.lines-empty { display: flex; flex-direction: column; gap: 6px; }
.lines-empty-title { font-size: 13px; color: #64748b; }
.lines-empty-tip { font-size: 12px; color: #94a3b8; line-height: 1.4; }
.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin: 28rpx 0 14rpx;
}
.section-title { display: block; font-size: 28rpx; font-weight: 700; }
.section-subtitle { display: block; margin-top: 4rpx; color: #94a3b8; font-size: 22rpx; }
.line-count {
  padding: 6rpx 12rpx;
  border-radius: 999rpx;
  color: #0f766e;
  background: #ccfbf1;
  font-size: 22rpx;
  font-weight: 700;
}
.line-card {
  margin-bottom: 14rpx;
  padding: 20rpx;
  border: 1rpx solid #e2e8f0;
  border-radius: 18rpx;
}
.sku-name { display: block; font-size: 27rpx; font-weight: 700; }
.qty { color: #0f766e; font-size: 30rpx; font-weight: 800; min-width: 40rpx; text-align: center; }
.qty-stepper {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 4rpx 8rpx;
  border-radius: 999rpx;
  background: #ecfdf5;
}
.qty-btn {
  width: 48rpx;
  height: 48rpx;
  line-height: 48rpx;
  text-align: center;
  border-radius: 50%;
  background: #fff;
  color: #0f766e;
  font-size: 32rpx;
  font-weight: 700;
  box-shadow: 0 2rpx 8rpx rgba(15, 118, 110, 0.12);
}
.product-thumb {
  display: flex;
  width: 72rpx;
  height: 72rpx;
  align-items: center;
  justify-content: center;
  border-radius: 16rpx;
  background: #ecfdf5;
  font-size: 32rpx;
  margin-right: 16rpx;
}
.product-copy { flex: 1; min-width: 0; }
.evidence-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-bottom: 20rpx;
}
.evidence-thumb,
.evidence-add {
  width: 140rpx;
  height: 140rpx;
  border-radius: 16rpx;
  background: #ecfdf5;
}
.evidence-add {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2rpx dashed #99f6e4;
  color: #0f766e;
  font-size: 48rpx;
  font-weight: 600;
}
.evidence-hint {
  align-self: center;
  font-size: 22rpx;
  color: #94a3b8;
}
.action-dock {
  position: sticky;
  bottom: 0;
  z-index: 2;
  margin-top: 22rpx;
  padding: 16rpx 0 calc(8rpx + env(safe-area-inset-bottom));
  background: #fff;
  box-shadow: 0 -8rpx 20rpx rgba(255, 255, 255, 0.95);
}
.complete-banner {
  margin-top: 22rpx;
  padding: 22rpx;
  border-radius: 18rpx;
  color: #166534;
  background: #dcfce7;
  text-align: center;
  font-size: 24rpx;
}
.confirm-mask {
  position: fixed;
  inset: 0;
  z-index: 10050;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48rpx;
  background: rgba(15, 23, 42, 0.55);
  box-sizing: border-box;
  pointer-events: auto;
}
.confirm-card {
  width: 100%;
  max-width: 620rpx;
  padding: 36rpx 32rpx 28rpx;
  border-radius: 24rpx;
  background: #fff;
  box-shadow: 0 24rpx 48rpx rgba(15, 23, 42, 0.18);
}
.confirm-title {
  display: block;
  color: #0f172a;
  font-size: 32rpx;
  font-weight: 700;
}
.confirm-body {
  display: block;
  margin-top: 16rpx;
  color: #475569;
  font-size: 26rpx;
  line-height: 1.55;
  white-space: pre-wrap;
}
.confirm-actions {
  display: flex;
  gap: 16rpx;
  margin-top: 32rpx;
}
.confirm-btn {
  flex: 1;
  margin: 0;
  border: none;
  border-radius: 14rpx;
  font-size: 28rpx;
  font-weight: 600;
  line-height: 1.2;
  padding: 22rpx 12rpx;
}
.confirm-btn.cancel {
  color: #334155;
  background: #f1f5f9;
}
.confirm-btn.ok {
  color: #fff;
  background: linear-gradient(135deg, #0f766e, #14b8a6);
}
button[disabled] { opacity: 0.45; }
</style>
