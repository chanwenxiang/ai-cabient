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
          :key="item.value"
          class="filter-chip"
          :class="{ active: status === item.value }"
          @click="changeStatus(item.value)"
          >{{ item.label }}</text
        >
      </view>

      <view v-if="loading && !allTasks.length" class="empty">任务加载中…</view>
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

      <view v-if="detailVisible" class="mask" @click.self="closeDetail" @touchmove.stop.prevent>
        <view class="sheet" @click.stop>
          <view class="sheet-handle" />
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

          <view v-if="selected?.deviceId" class="cabinet-card">
            <text class="cabinet-addr">{{
              deviceAddressLine(selected.deviceId) || '暂无点位地址，请对照编号或扫码核对柜机'
            }}</text>
            <view class="cabinet-actions">
              <view
                class="cabinet-chip"
                role="button"
                data-testid="copy-device-id"
                @click.stop="copyDeviceId(selected.deviceId)"
                >复制编号</view
              >
              <view
                class="cabinet-chip"
                role="button"
                data-testid="navigate-device"
                @click.stop="navigateToDevice(selected.deviceId)"
                >导航</view
              >
              <view
                class="cabinet-chip primary"
                role="button"
                data-testid="verify-cabinet-scan"
                @click.stop="verifyCabinetScan"
                >扫码核对</view
              >
            </view>
          </view>

          <view class="step-row four">
            <view class="step" :class="stepClass(1)">
              <text class="step-num">{{
                selected?.status === 'COMPLETED' || selected?.checkInAt ? '✓' : '1'
              }}</text>
              <text class="step-label">签到</text>
            </view>
            <view class="step" :class="stepClass(2)">
              <text class="step-num">{{
                selected?.status === 'COMPLETED' || doorOpened || currentStep() > 2 ? '✓' : '2'
              }}</text>
              <text class="step-label">开门</text>
            </view>
            <view class="step" :class="stepClass(3)">
              <text class="step-num">{{
                selected?.status === 'COMPLETED' || linesConfirmed || currentStep() > 3 ? '✓' : '3'
              }}</text>
              <text class="step-label">核对</text>
            </view>
            <view class="step" :class="stepClass(4)">
              <text class="step-num">{{ selected?.status === 'COMPLETED' ? '✓' : '4' }}</text>
              <text class="step-label">{{ detailIsPullOff ? '下架' : '上架' }}</text>
            </view>
          </view>

          <view
            v-if="canRequest && selected?.status !== 'COMPLETED' && !selected?.checkInAt"
            class="skip-loc-row"
            role="switch"
            :aria-checked="skipLocationCheck"
            data-testid="skip-location-toggle"
            @click="toggleSkipLocation"
          >
            <view class="skip-loc-copy">
              <text class="skip-loc-label">跳过定位验证</text>
              <text class="skip-loc-hint">室内 / H5 / 无 GPS 时可直接签到</text>
            </view>
            <text class="skip-loc-switch" :class="{ on: skipLocationCheck }">{{
              skipLocationCheck ? '开' : '关'
            }}</text>
          </view>

          <button
            v-if="canRequest && selected?.status !== 'COMPLETED' && !selected?.checkInAt"
            class="primary-btn"
            data-testid="replenish-checkin"
            :disabled="submitting"
            @click="checkIn"
          >
            现场签到
          </button>
          <button
            v-if="canRequest && selected?.status !== 'COMPLETED' && selected?.checkInAt"
            class="primary-btn"
            data-testid="replenish-open-door"
            :disabled="submitting"
            @click="openDoor"
          >
            {{ doorOpened ? '再次开门' : detailIsPullOff ? '下架开门' : '补货开门' }}
          </button>
          <text v-if="!canRequest && selected?.status !== 'COMPLETED'" class="door-tip">
            只读查看，需补货操作权限方可签到/开门/{{ detailIsPullOff ? '下架' : '上架' }}
          </text>
          <text v-if="doorOpened && openSessionId" class="door-tip">
            已开门 · 会话 {{ emptyDisplay(openSessionId, 'session') }} · 关门后继续核对{{
              detailIsPullOff ? '下架' : '上架'
            }}
          </text>

          <view class="section-heading">
            <view>
              <text class="section-title">现场照片</text>
              <text class="section-subtitle">{{
                selected?.checkInAt
                  ? evidenceItems.length
                    ? `已上传 ${evidenceItems.length}/5 · 点图可放大核对`
                    : '建议拍柜内/货道全景，最多 5 张'
                  : '签到后可拍照留存，最多 5 张'
              }}</text>
            </view>
            <text
              class="line-count"
              :class="{ warn: evidenceItems.length === 0 && !!selected?.checkInAt }"
            >
              {{ evidenceItems.length }} 张
            </text>
          </view>
          <view class="evidence-row">
            <view
              v-for="(item, idx) in evidenceItems"
              :key="item.fileId || item.localPath || idx"
              class="evidence-thumb-wrap"
            >
              <image
                class="evidence-thumb"
                :src="item.localPath"
                mode="aspectFill"
                :aria-label="`现场照片 ${idx + 1}`"
                @click="previewEvidence(idx)"
              />
              <text class="evidence-caption">凭证 {{ idx + 1 }}</text>
            </view>
            <view
              v-if="
                canRequest &&
                selected?.status !== 'COMPLETED' &&
                selected?.checkInAt &&
                evidenceItems.length < 5
              "
              class="evidence-add"
              role="button"
              aria-label="添加现场照片"
              @click="addEvidence"
            >
              <text class="evidence-add-plus">+</text>
              <text class="evidence-add-label">拍照</text>
            </view>
            <view
              v-else-if="!evidenceItems.length"
              class="evidence-empty"
              role="button"
              :aria-label="selected?.checkInAt ? '添加现场照片' : '请先签到'"
              @click="
                selected?.checkInAt && canRequest && selected?.status !== 'COMPLETED'
                  ? addEvidence()
                  : undefined
              "
            >
              <text class="evidence-empty-title">{{
                selected?.status === 'COMPLETED' ? '本次未留存照片' : '暂无现场照片'
              }}</text>
              <text class="evidence-empty-tip">{{
                selected?.checkInAt
                  ? selected?.status === 'COMPLETED'
                    ? '完成后不可再补传'
                    : '点击拍照或从相册上传'
                  : '签到后可拍照'
              }}</text>
            </view>
          </view>

          <view class="section-heading">
            <view>
              <text class="section-title">{{
                detailIsPullOff ? '本次下架商品' : '本次补货商品'
              }}</text>
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
            <view class="lines-empty-title">{{
              detailIsPullOff ? '暂无下架明细' : '暂无补货明细'
            }}</view>
            <view class="lines-empty-tip">
              {{
                detailIsPullOff
                  ? '可先开门执行下架；有任务明细时会显示在此核对'
                  : '可先开门上架；有出库明细时会显示在此核对'
              }}
            </view>
          </view>
          <view
            v-for="line in lines"
            :key="line.lineId || `${line.skuId}-${line.batchNo}-${line.slotId}`"
            class="line-card"
          >
            <view class="line-main">
              <view class="product-thumb">
                <image
                  v-if="skuThumb(line.skuId)"
                  class="product-thumb-img"
                  :src="skuThumb(line.skuId)"
                  mode="aspectFill"
                />
                <text v-else class="product-mark">{{ productGlyph(line.skuId) }}</text>
              </view>
              <view class="product-copy">
                <text class="sku-name">{{ skuName(line.skuId) }}</text>
                <text class="device-code">{{ line.skuId }}</text>
              </view>
              <view
                v-if="
                  canRequest && selected?.status !== 'COMPLETED' && !linesConfirmed && !line.applied
                "
                class="qty-actions"
              >
                <view class="qty-stepper">
                  <text
                    class="qty-btn"
                    role="button"
                    aria-label="减少数量"
                    @click="adjustQty(line, -1)"
                    >−</text
                  >
                  <text class="qty">{{ line.quantity }}</text>
                  <text
                    class="qty-btn"
                    role="button"
                    aria-label="增加数量"
                    @click="adjustQty(line, 1)"
                    >+</text
                  >
                </view>
                <button
                  class="scan-line"
                  :disabled="scanning"
                  data-testid="scan-product-line"
                  @click="scanProduct(line)"
                >
                  扫码
                </button>
              </view>
              <text v-else class="qty">× {{ line.quantity }}</text>
            </view>
            <view class="line-meta">
              <text>批次 {{ line.batchNo || '无批次' }}</text>
              <text>货道 {{ line.slotId || '待分配' }}</text>
              <text class="line-type">{{ lineTypeLabel(line.lineType) }}</text>
            </view>
            <view class="line-meta soft">
              <text>生产 {{ line.productionDate || '未填' }}</text>
              <text>到期 {{ line.expiryDate || '未填' }}</text>
              <text>{{ lineStatusLabel(line) }}</text>
            </view>
            <view class="line-stock" :class="{ muted: !stockDeltaText(line) }">{{
              stockDeltaText(line) ||
              (line.slotId
                ? '货道容量待同步'
                : isPullOffType(line.lineType)
                  ? '选货道后显示账面 → 下架后数量'
                  : '选货道后显示账面 → 补后数量')
            }}</view>
            <view
              v-if="
                canRequest &&
                selected?.status !== 'COMPLETED' &&
                !linesConfirmed &&
                !line.applied &&
                !isPullOffType(line.lineType) &&
                !line.slotId
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
                  >{{ opt.slotCode }} · 余{{ opt.room }}</text
                >
              </view>
              <text v-else class="slot-empty">暂无可用货道，请先腾出容量或将数量调为 0</text>
            </view>
            <view
              v-if="selected?.status !== 'COMPLETED' && line.slotId && slotHint(line)"
              class="line-cap"
              :class="{
                full: slotHeadroom(line) <= 0,
                warn: slotHeadroom(line) > 0 && line.quantity > slotHeadroom(line)
              }"
              >{{ slotHint(line) }}</view
            >
          </view>

          <view
            v-if="canRequest && selected?.status !== 'COMPLETED' && selected?.checkInAt"
            class="action-dock"
          >
            <button
              v-if="!linesConfirmed"
              class="secondary-btn"
              data-testid="replenish-confirm-lines"
              :disabled="submitting || !lines.length"
              @click="confirmLines"
            >
              确认商品与数量
            </button>
            <button
              class="primary-btn"
              data-testid="replenish-complete"
              :disabled="submitting || !lines.length || !linesConfirmed"
              @click="completeTask"
            >
              {{ detailIsPullOff ? '确认全部下架' : '确认全部上架' }}
            </button>
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
          <view
            v-if="confirmDialog.rememberLabel"
            class="confirm-remember"
            role="checkbox"
            :aria-checked="confirmDialog.rememberChecked"
            data-testid="confirm-remember"
            @click.stop="confirmDialog.rememberChecked = !confirmDialog.rememberChecked"
          >
            <text class="remember-box">{{ confirmDialog.rememberChecked ? '☑' : '☐' }}</text>
            <text>{{ confirmDialog.rememberLabel }}</text>
          </view>
          <view class="confirm-actions">
            <button
              type="button"
              class="confirm-btn cancel"
              :aria-label="confirmDialog.cancelText"
              data-testid="confirm-cancel"
              @click.stop="resolveConfirm(false)"
            >
              {{ confirmDialog.cancelText }}
            </button>
            <button
              type="button"
              class="confirm-btn ok"
              :aria-label="confirmDialog.confirmText"
              data-testid="confirm-ok"
              @click.stop="resolveConfirm(true)"
            >
              {{ confirmDialog.confirmText }}
            </button>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { emptyDisplay, formatDateTimeShort } from '@aicabinet/shared-uni/format';
import EmptyState from '@/components/empty-state.vue';
import {
  hasPerm,
  merchantApi,
  type DeviceLowStockItem,
  type MerchantReplenishmentEfficiency
} from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import { promptText } from '@/utils/text-prompt';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import { getSkipCheckInLocation, setSkipCheckInLocation } from '@/utils/checkin-location-pref';
import { API_BASE_URL } from '@/config/api';
import type { DeviceSlot } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canReplenish = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));
const preferredId = ref(getPreferredDeviceId());

type Task = {
  taskId: number;
  deviceId: string;
  deviceName?: string;
  status: string;
  notes?: string;
  routeId?: number;
  routeName?: string;
  /** 线路计划日 YYYY-MM-DD */
  plannedDate?: string;
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
let loadSeq = 0;
const detailLoading = ref(false);
const submitting = ref(false);
const scanning = ref(false);
const efficiency = ref<MerchantReplenishmentEfficiency | null>(null);
const lowStockList = ref<{ deviceId: string; skuCount: number; shortageQty: number }[]>([]);
const status = ref('');
const filterDeviceId = ref('');
const focusTaskId = ref<number | null>(null);
/** Deep-link query applied once; cleared so onShow/load won't reopen the same task. */
let pendingDeepLink = false;
const allTasks = ref<Task[]>([]);
/** taskId → 现场照片张数（列表徽标） */
const evidenceCountMap = ref<Record<number, number>>({});
const lineSummaryMap = ref<Record<number, string>>({});
const devices = ref<Record<string, unknown>[]>([]);
const skus = ref<Record<string, unknown>[]>([]);
const detailVisible = ref(false);
/** 避免「点卡片打开」同一轮点击落到遮罩上立刻关掉 */
const sheetCloseArmed = ref(false);
const selected = ref<Task | null>(null);
const lines = ref<Line[]>([]);
const linesConfirmed = ref(false);
const evidenceItems = ref<{ localPath: string; fileId?: number }[]>([]);
const doorOpened = ref(false);
const openSessionId = ref('');
/** slotCode -> { maxLevel, bookQty } */
const slotCaps = ref<Record<string, { maxLevel: number; bookQty: number }>>({});
const deviceSlotsList = ref<DeviceSlot[]>([]);
const skipLocationCheck = ref(getSkipCheckInLocation());

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

type ConfirmDialogState = {
  visible: boolean;
  title: string;
  content: string;
  confirmText: string;
  cancelText: string;
  rememberLabel?: string;
  rememberChecked: boolean;
  resolve: ((ok: boolean) => void) | null;
};
const confirmDialog = ref<ConfirmDialogState>({
  visible: false,
  title: '',
  content: '',
  confirmText: '确定',
  cancelText: '取消',
  rememberLabel: undefined,
  rememberChecked: false,
  resolve: null
});

function askConfirm(opts: {
  title: string;
  content: string;
  confirmText?: string;
  cancelText?: string;
  rememberLabel?: string;
  rememberDefault?: boolean;
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
      rememberLabel: opts.rememberLabel,
      rememberChecked: opts.rememberDefault ?? false,
      resolve
    };
  });
}

function resolveConfirm(ok: boolean) {
  const resolver = confirmDialog.value.resolve;
  if (ok && confirmDialog.value.rememberLabel && confirmDialog.value.rememberChecked) {
    skipLocationCheck.value = true;
    setSkipCheckInLocation(true);
  }
  confirmDialog.value = {
    visible: false,
    title: '',
    content: '',
    confirmText: '确定',
    cancelText: '取消',
    rememberLabel: undefined,
    rememberChecked: false,
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

function filterTasksByDevice(rows: Task[], deviceKey: string) {
  return rows.filter(
    (t) =>
      String(t.deviceId || '')
        .trim()
        .toUpperCase() === deviceKey
  );
}

function sortTasksByPreferred(rows: Task[], preferred: string) {
  if (!preferred) return rows;
  return [...rows].sort((a, b) => {
    if (a.deviceId === preferred) return -1;
    if (b.deviceId === preferred) return 1;
    return 0;
  });
}

const tasks = computed(() => {
  let rows = allTasks.value.filter((t) => t.status !== 'CANCELLED');
  if (filterDeviceId.value) {
    rows = filterTasksByDevice(rows, filterDeviceId.value.trim().toUpperCase());
  }
  if (status.value) {
    rows = rows.filter((t) => t.status === status.value);
  }
  if (filterDeviceId.value || !preferredId.value) return rows;
  return sortTasksByPreferred(rows, preferredId.value);
});

const pendingCount = computed(
  () =>
    allTasks.value.filter((item) => item.status !== 'COMPLETED' && item.status !== 'CANCELLED')
      .length
);
const completedCount = computed(
  () => allTasks.value.filter((item) => item.status === 'COMPLETED').length
);

function emptyHintForDeviceFilter(): string {
  return status.value
    ? `该柜机暂无「${displayLabel('replenishment_task_status', status.value, '该状态')}」任务`
    : '该柜机暂无补货任务';
}

function emptyHintForStatusFilter(): string {
  if (status.value === 'IN_PROGRESS' && pendingCount.value === 0 && completedCount.value > 0) {
    return '暂无进行中的任务，可查看已完成记录';
  }
  if (status.value) {
    return `暂无「${displayLabel('replenishment_task_status', status.value, '该状态')}」任务`;
  }
  return '当前没有补货任务';
}

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

/** 按柜聚合低库存明细：缺货 SKU 数 + 缺口件数，按严重度排序取前 5。 */
function aggregateLowStock(items: DeviceLowStockItem[]) {
  const map = new Map<string, { skuCount: number; shortageQty: number }>();
  for (const row of items || []) {
    const key = String(row.deviceId || '')
      .trim()
      .toUpperCase();
    if (!key) continue;
    const cur = map.get(key) || { skuCount: 0, shortageQty: 0 };
    cur.skuCount += 1;
    cur.shortageQty += Math.max(0, (Number(row.lowThreshold) || 0) - (Number(row.quantity) || 0));
    map.set(key, cur);
  }
  return [...map.entries()]
    .map(([deviceId, v]) => ({ deviceId, ...v }))
    .sort((a, b) => b.skuCount - a.skuCount || b.shortageQty - a.shortageQty)
    .slice(0, 5);
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
  skipLocationCheck.value = !skipLocationCheck.value;
  setSkipCheckInLocation(skipLocationCheck.value);
}

function copyDeviceId(id?: string) {
  const code = String(id || selected.value?.deviceId || '').trim();
  if (!code) return;
  uni.setClipboardData({
    data: code,
    success: () => uni.showToast({ title: '已复制柜机编号', icon: 'none' })
  });
}

function navigateToDevice(id?: string) {
  const m = deviceMeta(id || selected.value?.deviceId);
  if (!m?.latitude || !m?.longitude) {
    uni.showToast({ title: '暂无坐标，请按地址或编号找柜', icon: 'none' });
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

async function verifyCabinetScan() {
  if (scanning.value) return;
  scanning.value = true;
  try {
    const id = await scanCabinetDeviceId();
    if (!id) return;
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
    uni.showToast({ title: '柜机核对一致', icon: 'success' });
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
    if (!uni.getStorageSync('merchant_token')) return false;
    me.value =
      me.value ||
      (uni.getStorageSync('merchant_me') as import('@aicabinet/shared-types').MerchantMe) ||
      null;
  }
  if (seq !== loadSeq) return false;
  if (!me.value) {
    me.value =
      (uni.getStorageSync('merchant_me') as import('@aicabinet/shared-types').MerchantMe) || null;
  }
  return true;
}

function applyReplenishmentListData(
  taskRows: Record<string, unknown>[],
  deviceRows: Record<string, unknown>[],
  skuRows: Record<string, unknown>[],
  eff: MerchantReplenishmentEfficiency | null,
  lowStockRows: DeviceLowStockItem[]
) {
  allTasks.value = taskRows as Task[];
  devices.value = deviceRows;
  skus.value = (skuRows || []) as Record<string, unknown>[];
  efficiency.value = eff;
  lowStockList.value = aggregateLowStock(lowStockRows || []);
  void refreshEvidenceCounts(allTasks.value);
  void refreshLineSummaries(allTasks.value);
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
    uni.showToast({ title: `任务 #${wantedTaskId} 不可用或已取消`, icon: 'none' });
  }
}

async function load() {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const seq = ++loadSeq;
  if (!(await ensureReplenishmentMe(seq))) return;
  if (!canReplenish.value) {
    uni.showToast({ title: '无补货权限', icon: 'none' });
    uni.switchTab({ url: '/pages/home/home' });
    return;
  }
  if (!allTasks.value.length) loading.value = true;
  try {
    const [taskRows, deviceRows, skuRows, eff, lowStockRows] = await Promise.all([
      merchantApi.replenishmentTasks().catch(() => [] as Record<string, unknown>[]),
      merchantApi.devices().catch(() => [] as Record<string, unknown>[]),
      merchantApi.pricing().catch(() => [] as Record<string, unknown>[]),
      merchantApi.myReplenishmentEfficiency().catch(() => null),
      merchantApi.lowStockDevices().catch(() => [] as DeviceLowStockItem[])
    ]);
    if (seq !== loadSeq) return;
    applyReplenishmentListData(taskRows, deviceRows, skuRows, eff, lowStockRows);
    const wantedTaskId = focusTaskId.value;
    const open = resolveDeepLinkOpenTask();
    await handleDeepLinkAfterLoad(open, wantedTaskId);
  } catch (error) {
    if (seq !== loadSeq) return;
    uni.showToast({ title: error instanceof Error ? error.message : '加载失败', icon: 'none' });
  } finally {
    if (seq === loadSeq) {
      loading.value = false;
      uni.stopPullDownRefresh();
    }
  }
}

function changeStatus(value: string) {
  status.value = value;
}

function clearDeviceFilter() {
  filterDeviceId.value = '';
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
    const key = id.trim().toUpperCase();
    filterDeviceId.value = key;
    status.value = '';
    const open = findActiveTaskForDevice(key);
    if (open) {
      await openTask(open);
    } else {
      uni.showToast({ title: '该柜暂无任务，已筛选列表', icon: 'none' });
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
      uni.showToast({ title: '未匹配到商品条码', icon: 'none' });
      return;
    }
    const target = findMatchingTaskLine(sku.skuId);
    if (!target) {
      uni.showToast({ title: '本次任务不含该商品', icon: 'none' });
      return;
    }
    adjustQty(target, 1);
    uni.showToast({ title: `已扫 ${sku.skuName || target.skuId}`, icon: 'none' });
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
      const uploaded = await merchantApi.uploadReplenishmentEvidence(selected.value.taskId, path);
      evidenceItems.value.push({ localPath: path, fileId: uploaded.fileId });
      if (selected.value?.taskId) {
        evidenceCountMap.value = {
          ...evidenceCountMap.value,
          [selected.value.taskId]: evidenceItems.value.length
        };
      }
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

function evidenceCountOf(taskId?: number) {
  if (!taskId) return 0;
  return Number(evidenceCountMap.value[taskId] || 0);
}

function lineSummaryOf(taskId?: number) {
  if (!taskId) return '';
  return String(lineSummaryMap.value[taskId] || '');
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

async function refreshLineSummaries(taskRows: Task[]) {
  const ids = (taskRows || [])
    .map((t) => t.taskId)
    .filter((id) => Number.isFinite(id) && id > 0)
    .slice(0, 40);
  if (!ids.length) {
    lineSummaryMap.value = {};
    return;
  }
  const entries = await Promise.all(
    ids.map(async (id) => {
      try {
        const raw = (await merchantApi.replenishmentTaskLines(id)) as Line[];
        return [id, formatLineSummary(raw || [])] as const;
      } catch {
        return [id, lineSummaryMap.value[id] || ''] as const;
      }
    })
  );
  const next: Record<number, string> = { ...lineSummaryMap.value };
  for (const [id, text] of entries) next[id] = text;
  lineSummaryMap.value = next;
}

async function refreshEvidenceCounts(taskRows: Task[]) {
  const ids = (taskRows || [])
    .map((t) => t.taskId)
    .filter((id) => Number.isFinite(id) && id > 0)
    .slice(0, 40);
  if (!ids.length) {
    evidenceCountMap.value = {};
    return;
  }
  const entries = await Promise.all(
    ids.map(async (id) => {
      try {
        const list = await merchantApi.listReplenishmentEvidence(id);
        return [id, (list || []).length] as const;
      } catch {
        return [id, evidenceCountMap.value[id] || 0] as const;
      }
    })
  );
  const next: Record<number, number> = { ...evidenceCountMap.value };
  for (const [id, n] of entries) next[id] = n;
  evidenceCountMap.value = next;
}

function closeDetail() {
  if (!sheetCloseArmed.value) return;
  if (!submitting.value) {
    detailVisible.value = false;
    sheetCloseArmed.value = false;
    clearDeepLinkQuery();
  }
}

/** H5 浏览器常挂起权限弹窗；小程序偶发超时 — 超时后走无定位签到 */
function getLocationWithTimeout(timeoutMs = 5000): Promise<UniApp.GetLocationSuccess> {
  return new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(new Error('定位超时'));
    }, timeoutMs);
    uni.getLocation({
      type: 'gcj02',
      success(res) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(res);
      },
      fail(err) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(
          err instanceof Error
            ? err
            : new Error(String((err as { errMsg?: string })?.errMsg || '定位失败'))
        );
      }
    });
  });
}

function isDistanceCheckError(msg: string): boolean {
  return msg.includes('签到位置') || msg.includes('超出') || msg.includes('米');
}

async function obtainCheckInLocation(): Promise<{
  body: Record<string, number>;
  locationOk: boolean;
} | null> {
  if (skipLocationCheck.value || getSkipCheckInLocation()) {
    skipLocationCheck.value = true;
    return { body: {}, locationOk: false };
  }
  try {
    const location = await getLocationWithTimeout(5000);
    return {
      body: { latitude: location.latitude, longitude: location.longitude },
      locationOk: true
    };
  } catch {
    const cont = await askConfirm({
      title: '定位失败',
      content:
        '无法获取当前位置，仍可继续签到。补货柜机见上方地址/编号，建议先「扫码核对」确认到柜。',
      confirmText: '继续签到',
      cancelText: '取消',
      rememberLabel: '本机不再获取定位，直接签到',
      rememberDefault: false
    });
    return cont ? { body: {}, locationOk: false } : null;
  }
}

async function submitCheckIn(body: Record<string, number>, locationOk: boolean) {
  if (!selected.value) return;
  selected.value = (await merchantApi.checkInReplenishmentTask(
    selected.value.taskId,
    body
  )) as Task;
  syncTaskInList(selected.value);
  uni.showToast({
    title: locationOk ? '签到成功' : '已签到（未带定位）',
    icon: locationOk ? 'success' : 'none'
  });
}

async function retryCheckInWithoutDistance() {
  if (!selected.value) return;
  selected.value = (await merchantApi.checkInReplenishmentTask(selected.value.taskId, {})) as Task;
  syncTaskInList(selected.value);
  uni.showToast({ title: '已签到（未校验距离）', icon: 'none' });
}

async function handleCheckInDistanceFailure(msg: string) {
  const retry = await askConfirm({
    title: '距离柜机过远',
    content: `${msg}\n\n若你已在柜前（定位漂移），可改为不校验距离继续签到。也可先「扫码核对」确认柜机。`,
    confirmText: '继续签到',
    cancelText: '取消',
    rememberLabel: '本机不再获取定位，直接签到',
    rememberDefault: skipLocationCheck.value
  });
  if (!retry) return;
  try {
    await retryCheckInWithoutDistance();
  } catch (error_) {
    uni.showToast({
      title: error_ instanceof Error ? error_.message : '签到失败',
      icon: 'none',
      duration: 3600
    });
  }
}

async function checkIn() {
  if (!selected.value || submitting.value) return;
  if (!canRequest.value) {
    uni.showToast({ title: '无补货操作权限', icon: 'none' });
    return;
  }
  submitting.value = true;
  const location = await obtainCheckInLocation();
  if (!location) {
    submitting.value = false;
    return;
  }
  try {
    await submitCheckIn(location.body, location.locationOk);
  } catch (error) {
    const msg = error instanceof Error ? error.message : '签到失败';
    if (location.locationOk && isDistanceCheckError(msg)) {
      await handleCheckInDistanceFailure(msg);
    } else {
      uni.showToast({ title: msg, icon: 'none', duration: 3600 });
    }
  } finally {
    submitting.value = false;
  }
}

function openDoorConfirmTitle(): string {
  if (doorOpened.value) return '再次开门';
  if (detailIsPullOff.value) return '下架开门';
  return '补货开门';
}

async function applyOpenDoorSession(session: { sessionId?: string }) {
  if (!selected.value) return;
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
    title: openDoorConfirmTitle(),
    content: '将下发开门指令，本次为补货会话，不会按购物扣款。请确认人在柜前。',
    confirmText: '开门',
    cancelText: '取消'
  });
  if (!ok) return;
  submitting.value = true;
  try {
    const session = await merchantApi.openReplenishmentDoor(selected.value.taskId);
    await applyOpenDoorSession(session);
  } catch (error) {
    const msg = error instanceof Error ? error.message : '开门失败';
    uni.showToast({ title: msg, icon: 'none', duration: 3200 });
  } finally {
    submitting.value = false;
  }
}

function canAdjustLineQty(line: Line): boolean {
  if (!canRequest.value) return false;
  if (linesConfirmed.value || line.applied || selected.value?.status === 'COMPLETED') return false;
  return true;
}

function increaseLineQty(line: Line, delta: number) {
  const cur = Number(line.quantity) || 0;
  if (isPullOffType(line.lineType)) {
    line.quantity = cur + delta;
    return;
  }
  const room = slotHeadroom(line);
  if (cur >= room) {
    uni.showToast({
      title: room <= 0 ? '货道已满，无法再加' : `最多再补 ${room}`,
      icon: 'none'
    });
    return;
  }
  line.quantity = Math.min(room, cur + delta);
}

function decreaseLineQty(line: Line, delta: number) {
  const cur = Number(line.quantity) || 0;
  line.quantity = Math.max(0, cur + delta);
}

function adjustQty(line: Line, delta: number) {
  if (!canAdjustLineQty(line)) return;
  if (delta > 0) increaseLineQty(line, delta);
  else decreaseLineQty(line, delta);
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

function buildConfirmLinePayload(line: Line) {
  return {
    skuId: line.skuId,
    quantity: line.quantity,
    lineType: line.lineType || 'RESTOCK',
    batchNo: line.batchNo,
    productionDate: line.productionDate,
    expiryDate: line.expiryDate,
    slotId: line.slotId
  };
}

async function ensureLinesWithinCapacity(): Promise<boolean> {
  const over = lines.value.filter(
    (l) => !l.applied && !isPullOffType(l.lineType) && (Number(l.quantity) || 0) > slotHeadroom(l)
  );
  if (!over.length) return true;
  const overSummary = over.map((l) => `${l.slotId || '?'} 最多再补 ${slotHeadroom(l)}`).join('；');
  const ok = await askConfirm({
    title: '货道容量不足',
    content: `${overSummary}。是否自动调低数量后继续？`,
    confirmText: '自动调低',
    cancelText: '手动改'
  });
  if (!ok) return false;
  clampLinesToCapacity();
  return true;
}

function validatePositiveLines(): Line[] | null {
  const positive = lines.value.filter((l) => (Number(l.quantity) || 0) > 0);
  if (!positive.length) {
    uni.showToast({ title: '调低后无有效数量，请换货道或取消该行', icon: 'none' });
    return null;
  }
  const unassigned = positive.filter(
    (l) => !isPullOffType(l.lineType) && !String(l.slotId || '').trim()
  );
  if (unassigned.length) {
    uni.showToast({ title: '请先为待分配行选择货道', icon: 'none' });
    return null;
  }
  return positive;
}

async function handleConfirmLinesFailure(msg: string) {
  if (!msg.includes('容量不足')) {
    uni.showToast({ title: msg, icon: 'none', duration: 3600 });
    return;
  }
  const auto = await askConfirm({
    title: '确认失败',
    content: `${msg}\n\n是否按货道余量自动调低？`,
    confirmText: '自动调低',
    cancelText: '知道了'
  });
  if (auto) clampLinesToCapacity();
}

async function confirmLines() {
  if (!selected.value || submitting.value) return;
  if (!canRequest.value) {
    uni.showToast({ title: '无补货操作权限', icon: 'none' });
    return;
  }
  if (!(await ensureLinesWithinCapacity())) return;
  const positive = validatePositiveLines();
  if (!positive) return;
  submitting.value = true;
  try {
    lines.value = (await merchantApi.confirmReplenishmentLines(
      selected.value.taskId,
      positive.map(buildConfirmLinePayload)
    )) as Line[];
    linesConfirmed.value = true;
    lineSummaryMap.value = {
      ...lineSummaryMap.value,
      [selected.value.taskId]: formatLineSummary(lines.value)
    };
    uni.showToast({ title: '清单已确认', icon: 'success' });
  } catch (error) {
    const msg = error instanceof Error ? error.message : '确认失败';
    await handleConfirmLinesFailure(msg);
  } finally {
    submitting.value = false;
  }
}

function pullOffCopy(restockText: string, pullOffText: string): string {
  return detailIsPullOff.value ? pullOffText : restockText;
}

async function confirmDoorOpenedIfNeeded(): Promise<boolean> {
  if (doorOpened.value) return true;
  const cont = await askConfirm({
    title: '尚未开门',
    content: pullOffCopy(
      '还未下发补货开门。若已现场开门完成上架，仍可继续确认完成。',
      '还未下发下架开门。若已现场开门完成下架，仍可继续确认完成。'
    ),
    confirmText: '继续完成',
    cancelText: '去开门'
  });
  return cont;
}

async function confirmEvidenceIfNeeded(): Promise<boolean> {
  if (evidenceItems.value.length > 0) return true;
  const photoOk = await askConfirm({
    title: '缺少现场凭证',
    content: pullOffCopy(
      '建议先拍照留存补货证据，再完成任务，便于后台抽检。',
      '建议先拍照留存下架证据，再完成任务，便于后台抽检。'
    ),
    confirmText: '去拍照',
    cancelText: '仍完成'
  });
  if (!photoOk) return true;
  if (selected.value?.checkInAt) void addEvidence();
  else uni.showToast({ title: '请先签到再拍照', icon: 'none' });
  return false;
}

async function confirmCompleteAction(): Promise<boolean> {
  return askConfirm({
    title: pullOffCopy('确认全部上架', '确认全部下架'),
    content: pullOffCopy(
      '完成后将更新柜机库存并签收在途商品，请确认商品、批次和货道无误。',
      '完成后将扣减柜机库存，请确认下架商品、批次和数量无误。'
    ),
    confirmText: '确认完成',
    cancelText: '取消'
  });
}

async function finalizeCompletedTask(taskId: number) {
  selected.value = (await merchantApi.completeReplenishmentTask(taskId)) as Task;
  lines.value = lines.value.map((line) => ({ ...line, applied: true }));
  try {
    uni.removeStorageSync(doorCacheKey(taskId));
  } catch {
    /* ignore */
  }
  doorOpened.value = false;
  openSessionId.value = '';
  uni.showToast({
    title: detailIsPullOff.value ? '下架完成' : '补货完成',
    icon: 'success'
  });
  await load();
  const fresh = allTasks.value.find((t) => t.taskId === taskId);
  if (fresh) selected.value = { ...fresh };
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
  if (!(await confirmDoorOpenedIfNeeded())) return;
  if (!(await confirmEvidenceIfNeeded())) return;
  if (!(await confirmCompleteAction())) return;
  submitting.value = true;
  try {
    await finalizeCompletedTask(selected.value.taskId);
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
  min-height: 100%;
  padding: 0;
  background: #ffffff;
  box-sizing: border-box;
  overflow-x: hidden;
}
.hero {
  position: relative;
  overflow: hidden;
  margin: 20rpx 24rpx 0;
  padding: 36rpx 28rpx 32rpx;
  border-radius: 24rpx;
  color: #0f172a;
  background: linear-gradient(135deg, #ecfdf5, #fff);
  border: 1rpx solid #d1fae5;
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
  font-size: 22rpx;
  letter-spacing: 4rpx;
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  background: #f0fdf4;
  color: #0f766e;
}
.title {
  margin-top: 14rpx;
  font-size: 42rpx;
  font-weight: 800;
  color: #0f172a;
}
.subtitle {
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #64748b;
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
  border-top: 1rpx solid #d1fae5;
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
  font-size: 40rpx;
  font-weight: 800;
  color: #0f766e;
}
.stat-label {
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #64748b;
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
  border-radius: 44rpx;
  background: linear-gradient(135deg, #134e4a, #0f766e);
  color: #fff;
  font-size: 28rpx;
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
  border-radius: 44rpx;
  background: #f0fdf4;
  color: #0f766e;
  font-size: 28rpx;
  font-weight: 600;
  box-sizing: border-box;
  border: 2rpx solid #99f6e4;
}
.clear-pill-hover {
  opacity: 0.82;
}
.filter-tip {
  position: relative;
  display: block;
  margin-top: 16rpx;
  font-size: 22rpx;
  opacity: 0.85;
  text-align: center;
}
.filter-tip.muted {
  opacity: 0.7;
}
.idle-tip {
  margin: 18rpx 24rpx 0;
  padding: 28rpx 24rpx;
  border-radius: 20rpx;
  background: #fff;
  border: 1rpx solid rgba(15, 118, 110, 0.1);
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.06);
  text-align: center;
}
.idle-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: #134e4a;
  text-align: center;
}
.idle-desc {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #64748b;
  line-height: 1.5;
  text-align: center;
}

.patrol-card {
  margin: 22rpx 24rpx 4rpx;
  padding: 24rpx;
  border-radius: 24rpx;
  background: #fff;
  border: 1rpx solid #fcd34d;
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
  font-size: 30rpx;
  font-weight: 700;
  color: #78350f;
}
.patrol-sub {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #b45309;
}
.patrol-count {
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  background: #fffbeb;
  color: #b45309;
  font-size: 22rpx;
  font-weight: 700;
}
.patrol-row {
  margin-top: 18rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: #fffbeb;
  cursor: pointer;
}
.patrol-row-hover {
  background: #fef3c7;
}
.patrol-name {
  flex: 1;
  min-width: 0;
}
.patrol-name .device-name {
  font-size: 27rpx;
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
  border-radius: 999rpx;
  background: #fef3c7;
  color: #b45309;
  font-size: 20rpx;
  font-weight: 700;
}
.patrol-shortage {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #b45309;
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
  margin: 0 24rpx 18rpx;
  padding: 26rpx;
  border-radius: 24rpx;
  background: #fff;
  border: 1rpx solid #e2e8f0;
  box-shadow: 0 8rpx 30rpx rgba(15, 118, 110, 0.08);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.task-card-hover {
  background: #f8fafc !important;
  opacity: 0.96;
}
.task-accent {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 6rpx;
  background: linear-gradient(#10b981, #0d9488);
  pointer-events: none;
}
.task-head,
.task-meta,
.line-main,
.line-meta,
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18rpx;
}
.task-meta.soft {
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #64748b;
  justify-content: flex-start;
  flex-wrap: wrap;
}
.task-lines {
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #334155;
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
  font-size: 30rpx;
  font-weight: 700;
  color: #0f172a;
}
.device-code {
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 21rpx;
}
.task-addr {
  display: block;
  margin-top: 6rpx;
  color: #64748b;
  font-size: 21rpx;
  line-height: 1.4;
  pointer-events: none;
}
.cabinet-card {
  margin: 0 0 20rpx;
  padding: 20rpx 22rpx;
  border-radius: 18rpx;
  background: #f0fdf4;
  border: 1rpx solid #99f6e4;
}
.cabinet-addr {
  display: block;
  color: #134e4a;
  font-size: 24rpx;
  line-height: 1.5;
}
.cabinet-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 16rpx;
}
.cabinet-chip {
  padding: 10rpx 20rpx;
  border-radius: 999rpx;
  background: #fff;
  border: 1rpx solid #cbd5e1;
  color: #334155;
  font-size: 22rpx;
  font-weight: 600;
}
.cabinet-chip.primary {
  background: #ecfdf5;
  border-color: #0f766e;
  color: #0f766e;
}
.skip-loc-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin: 0 0 16rpx;
  padding: 18rpx 20rpx;
  border-radius: 16rpx;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
}
.skip-loc-copy {
  flex: 1;
  min-width: 0;
}
.skip-loc-label {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: #0f172a;
}
.skip-loc-hint {
  display: block;
  margin-top: 4rpx;
  font-size: 21rpx;
  color: #64748b;
}
.skip-loc-switch {
  flex-shrink: 0;
  min-width: 56rpx;
  padding: 8rpx 16rpx;
  border-radius: 999rpx;
  text-align: center;
  font-size: 22rpx;
  font-weight: 700;
  color: #64748b;
  background: #e2e8f0;
}
.skip-loc-switch.on {
  color: #fff;
  background: #0f766e;
}
.status {
  padding: 8rpx 16rpx;
  border-radius: 999rpx;
  color: #92400e;
  background: #fef3c7;
  font-size: 22rpx;
  font-weight: 600;
}
.status.completed {
  color: #166534;
  background: #dcfce7;
}
.task-meta,
.line-meta {
  margin-top: 16rpx;
  color: #64748b;
  font-size: 22rpx;
}
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
.line-cap.warn {
  color: #b45309;
  background: #fffbeb;
}
.line-cap.full {
  color: #b91c1c;
  background: #fef2f2;
}
.slot-pick {
  margin-top: 12rpx;
}
.slot-pick-label {
  display: block;
  font-size: 22rpx;
  color: #0f766e;
  margin-bottom: 8rpx;
  font-weight: 600;
}
.slot-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
}
.slot-chip {
  padding: 8rpx 16rpx;
  border-radius: 999rpx;
  background: #ecfdf5;
  color: #0f766e;
  font-size: 22rpx;
  border: 1rpx solid #99f6e4;
}
.slot-chip.active {
  background: #0f766e;
  color: #fff;
  border-color: #0f766e;
}
.slot-chip.disabled {
  background: #e2e8f0;
  color: #475569;
  border-color: #cbd5e1;
}
.slot-empty {
  font-size: 22rpx;
  color: #b91c1c;
}
.task-note {
  margin-top: 16rpx;
  padding: 16rpx;
  border-radius: 14rpx;
  color: #475569;
  background: #f8fafc;
  font-size: 22rpx;
}
.detail-btn,
.primary-btn,
.secondary-btn {
  margin-top: 22rpx;
  border: 0;
  border-radius: 18rpx;
  font-size: 27rpx;
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
.primary-btn {
  color: #fff;
  background: #0f766e;
}
.secondary-btn {
  color: #0f766e;
  background: #ccfbf1;
}
.detail-btn::after,
.primary-btn::after,
.secondary-btn::after {
  border: none;
}

.empty {
  padding: 80rpx 20rpx;
  margin-left: 24rpx;
  margin-right: 24rpx;
  text-align: center;
  color: #94a3b8;
  font-size: 28rpx;
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
  border-radius: 36rpx;
  background: #0f766e;
  color: #fff;
  font-size: 26rpx;
}
.empty-scan.ghost {
  color: #0f766e;
  background: #ecfdf5;
}
.empty-scan::after {
  border: none;
}

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
  overscroll-behavior: contain;
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
.sheet-title {
  display: block;
  font-size: 34rpx;
  font-weight: 800;
}
.close {
  padding: 10rpx;
  color: #64748b;
  font-size: 46rpx;
}
.step-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12rpx;
  margin: 26rpx 0;
  padding: 16rpx 8rpx;
  border-radius: 18rpx;
  background: #f8fafc;
}
.step-row.four {
  grid-template-columns: repeat(4, 1fr);
}
@media (max-width: 380px) {
  .step-row.four {
    grid-template-columns: repeat(2, 1fr);
    gap: 12rpx 8rpx;
  }
}
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
.step {
  text-align: center;
  color: #94a3b8;
  font-size: 21rpx;
}
.step-num {
  display: flex;
  width: 44rpx;
  height: 44rpx;
  margin: 0 auto 8rpx;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #475569;
  background: #e2e8f0;
  font-size: 24rpx;
}
.step-label {
  display: block;
}
.step.done {
  color: #0f766e;
}
.step.done .step-num {
  color: #fff;
  background: #0f766e;
}
.step.current {
  color: #0f766e;
  font-weight: 600;
}
.step.current .step-num {
  color: #fff;
  background: #0f766e;
  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.2);
}
.lines-empty {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.lines-empty-title {
  font-size: 13px;
  color: #64748b;
}
.lines-empty-tip {
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.4;
}
.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin: 28rpx 0 14rpx;
}
.section-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
}
.section-subtitle {
  display: block;
  margin-top: 4rpx;
  color: #94a3b8;
  font-size: 22rpx;
}
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
.sku-name {
  display: block;
  font-size: 27rpx;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 360rpx;
}
.qty {
  color: #0f766e;
  font-size: 30rpx;
  font-weight: 800;
  min-width: 40rpx;
  text-align: center;
}
.qty-stepper {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 4rpx 8rpx;
  border-radius: 999rpx;
  background: #ecfdf5;
}
.qty-actions {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.scan-line {
  margin: 0;
  padding: 0 20rpx;
  height: 52rpx;
  line-height: 52rpx;
  border-radius: 999rpx;
  background: #0f766e;
  color: #fff;
  font-size: 24rpx;
  font-weight: 600;
}
.scan-line[disabled] {
  opacity: 0.5;
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
  position: relative;
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
.product-thumb-img {
  width: 100%;
  height: 100%;
  border-radius: 16rpx;
  background: #ecfdf5;
}
.product-mark {
  font-size: 28rpx;
  font-weight: 700;
  color: #0f766e;
}
.product-copy {
  flex: 1;
  min-width: 0;
}
.evidence-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-bottom: 20rpx;
}
.evidence-thumb-wrap {
  width: 140rpx;
}
.evidence-thumb,
.evidence-add {
  width: 140rpx;
  height: 140rpx;
  border-radius: 16rpx;
  background: #ecfdf5;
}
.evidence-thumb {
  display: block;
}
.evidence-caption {
  display: block;
  margin-top: 6rpx;
  font-size: 20rpx;
  color: #64748b;
  text-align: center;
}
.evidence-add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 2rpx dashed #99f6e4;
  color: #0f766e;
  gap: 4rpx;
}
.evidence-add-plus {
  font-size: 40rpx;
  font-weight: 600;
  line-height: 1;
}
.evidence-add-label {
  font-size: 20rpx;
}
.evidence-empty {
  width: 100%;
  min-height: 140rpx;
  height: auto;
  padding: 24rpx 20rpx;
  box-sizing: border-box;
  border: 2rpx dashed #cbd5e1;
  background: #f8fafc;
  border-radius: 16rpx;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8rpx;
}
.evidence-empty-title {
  font-size: 26rpx;
  font-weight: 650;
  color: #334155;
}
.evidence-empty-tip {
  font-size: 22rpx;
  color: #94a3b8;
}
.evidence-hint {
  align-self: center;
  font-size: 22rpx;
  color: #94a3b8;
}
.evidence-badge {
  color: #0f766e;
  font-weight: 650;
}
.evidence-badge.muted {
  color: #94a3b8;
  font-weight: 500;
}
.line-count.warn {
  color: #b45309;
}
.line-stock {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #0f766e;
  background: #ecfdf5;
  border-radius: 10rpx;
  padding: 8rpx 12rpx;
}
.line-stock.muted {
  color: #64748b;
  background: #f8fafc;
}
.line-meta.soft {
  color: #64748b;
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
  background: rgba(15, 23, 42, 0.62);
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
.confirm-remember {
  display: flex;
  align-items: flex-start;
  gap: 12rpx;
  margin-top: 20rpx;
  padding: 16rpx 14rpx;
  border-radius: 12rpx;
  background: #f8fafc;
  color: #334155;
  font-size: 24rpx;
  line-height: 1.45;
}
.remember-box {
  flex-shrink: 0;
  color: #0f766e;
  font-size: 28rpx;
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
