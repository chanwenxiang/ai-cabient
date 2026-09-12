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

      <view
        v-if="detailVisible"
        role="button"
        aria-label="关闭"
        class="mask"
        @click.self="closeDetail"
        @touchmove.stop.prevent
      >
        <view role="button" class="sheet" @click.stop>
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

          <view class="section-heading">
            <view>
              <text class="section-title">现场照片</text>
              <text class="section-subtitle">{{
                selected?.checkInAt
                  ? evidenceItems.length
                    ? `已上传 ${evidenceItems.length}/5 · 点图可放大核对`
                    : requireReplenishmentEvidence
                      ? '须至少 1 张现场照片，最多 5 张'
                      : '选填：建议拍柜内/货道全景，最多 5 张'
                  : '签到后可拍照留存，最多 5 张'
              }}</text>
            </view>
            <text
              class="line-count"
              :class="{
                warn:
                  requireReplenishmentEvidence &&
                  evidenceItems.length === 0 &&
                  !!selected?.checkInAt
              }"
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
                    : requireReplenishmentEvidence
                      ? '完成前须上传 · 点击拍照或从相册上传'
                      : '可选上传 · 点击拍照或从相册上传'
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
          <view v-if="detailLoading" class="empty small">{{ loadingLabel('明细') }}</view>
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
                  role="button"
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
            <app-button
              v-if="!linesConfirmed"
              variant="outline"
              data-testid="replenish-confirm-lines"
              :disabled="submitting || !lines.length"
              label="确认商品与数量"
              @click="confirmLines"
            />
            <app-button
              data-testid="replenish-complete"
              :disabled="submitting || !lines.length || !linesConfirmed"
              :label="detailIsPullOff ? '确认全部下架' : '确认全部上架'"
              @click="completeTask"
            />
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
        <view role="button" class="confirm-card" @click.stop>
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
import { showError, showSuccess } from '@/utils/notify';
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { emptyDisplay, formatDateTimeShort } from '@aicabinet/shared-uni/format';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import { assertLocalImageSize } from '@aicabinet/shared-uni/upload-limits';
import EmptyState from '@/components/empty-state.vue';
import {
  hasPerm,
  merchantApi,
  type DeviceLowStockItem,
  type MerchantReplenishmentEfficiency
} from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
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
const preferredId = ref(getPreferredDeviceId());

type Task = import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto;
type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;

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
const skipLocationCheck = ref(canSkipLocation && getSkipCheckInLocation());

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
  if (
    ok &&
    canSkipLocation &&
    confirmDialog.value.rememberLabel &&
    confirmDialog.value.rememberChecked
  ) {
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
  if (!canSkipLocation) return;
  skipLocationCheck.value = !skipLocationCheck.value;
  setSkipCheckInLocation(skipLocationCheck.value);
}

function copyDeviceId(id?: string) {
  const code = String(id || selected.value?.deviceId || '').trim();
  if (!code) return;
  uni.setClipboardData({
    data: code,
    success: () => showError('已复制柜机编号')
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
    if (!uni.getStorageSync('merchant_token')) return false;
    seedMerchantMeDisplayCache(me);
  }
  if (seq !== loadSeq) return false;
  if (!me.value) {
    seedMerchantMeDisplayCache(me);
  }
  return true;
}

function applyReplenishmentListData(
  taskRows: Task[],
  deviceRows: Record<string, unknown>[],
  skuRows: Record<string, unknown>[],
  eff: MerchantReplenishmentEfficiency | null,
  lowStockRows: DeviceLowStockItem[]
) {
  allTasks.value = taskRows || [];
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
    showError(`任务 #${wantedTaskId} 不可用或已取消`);
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
    showError('无补货权限');
    uni.switchTab({ url: '/pages/home/home' });
    return;
  }
  if (!allTasks.value.length) loading.value = true;
  try {
    const [taskRows, deviceRows, skuRows, eff, lowStockRows] = await Promise.all([
      merchantApi.replenishmentTasks().catch(() => [] as Task[]),
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
    showError(error instanceof Error ? error.message : '加载失败');
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
    showError(`已扫 ${sku.skuName || target.skuId}`);
  } finally {
    scanning.value = false;
  }
}

function doorCacheKey(taskId: number) {
  return `replenish_door_${taskId}`;
}

/** 本地开门缓存结构校验（M-24） */
function parseDoorCache(raw: unknown): { sessionId: string } | null {
  let cached: unknown = raw;
  if (typeof raw === 'string') {
    try {
      cached = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  if (!cached || typeof cached !== 'object') return null;
  const sessionId = String((cached as { sessionId?: unknown }).sessionId ?? '').trim();
  if (!sessionId) return null;
  return { sessionId };
}

function restoreDoorState(taskId: number) {
  try {
    const raw = uni.getStorageSync(doorCacheKey(taskId));
    if (!raw) {
      doorOpened.value = false;
      openSessionId.value = '';
      return;
    }
    const cached = parseDoorCache(raw);
    if (!cached) {
      doorOpened.value = false;
      openSessionId.value = '';
      try {
        uni.removeStorageSync(doorCacheKey(taskId));
      } catch {
        /* ignore */
      }
      return;
    }
    doorOpened.value = true;
    openSessionId.value = cached.sessionId;
  } catch {
    doorOpened.value = false;
    openSessionId.value = '';
  }
}

/** 以服务端补货会话覆盖本地开门缓存（M-12） */
async function syncDoorStateFromServer(taskId: number) {
  try {
    const info = await merchantApi.replenishmentDoorSession(taskId);
    if (info?.doorOpened && info.sessionId) {
      doorOpened.value = true;
      openSessionId.value = String(info.sessionId);
      persistDoorState(taskId, String(info.sessionId));
      return;
    }
    doorOpened.value = false;
    openSessionId.value = '';
    try {
      uni.removeStorageSync(doorCacheKey(taskId));
    } catch {
      /* ignore */
    }
  } catch {
    // 网络失败时保留本地乐观状态，完成任务仍由服务端门禁兜底
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
  if (!requireReplenishmentCheckInLocation.value) {
    return { body: {}, locationOk: false };
  }
  if (canSkipLocation && (skipLocationCheck.value || getSkipCheckInLocation())) {
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
        checkInMaxDistanceM.value > 0
          ? `无法获取当前位置。请开启定位权限后重试；柜机已配置坐标时须在约 ${checkInMaxDistanceM.value} 米内签到。`
          : '无法获取当前位置。请开启定位权限后重试；本柜签到需带定位。',
      confirmText: '重试',
      cancelText: '取消'
    });
    if (!cont) return null;
    try {
      const location = await getLocationWithTimeout(8000);
      return {
        body: { latitude: location.latitude, longitude: location.longitude },
        locationOk: true
      };
    } catch {
      showError('仍无法定位，请到柜前开启 GPS 后重试');
      return null;
    }
  }
}

async function submitCheckIn(body: Record<string, number>, locationOk: boolean) {
  if (!selected.value) return;
  selected.value = (await merchantApi.checkInReplenishmentTask(
    selected.value.taskId,
    body
  )) as Task;
  syncTaskInList(selected.value);
  const skipTitle = !requireReplenishmentCheckInLocation.value
    ? '已签到（未校验定位）'
    : '签到成功';
  if (locationOk) showSuccess('签到成功');
  else showError(skipTitle);
}

async function handleCheckInDistanceFailure(msg: string) {
  showError(msg || '距离过远，请到柜前再签到', 3600);
}

async function checkIn() {
  if (!selected.value || submitting.value) return;
  if (!canRequest.value) {
    showError('无补货操作权限');
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
      showError(msg, 3600);
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
  showSuccess('开门指令已下发');
  await load();
  const fresh = allTasks.value.find((t) => t.taskId === selected.value?.taskId);
  if (fresh) selected.value = { ...fresh };
}

async function openDoor() {
  if (!selected.value || submitting.value) return;
  if (!canRequest.value) {
    showError('无补货操作权限');
    return;
  }
  if (!selected.value.checkInAt) {
    showError('请先现场签到');
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
    showError(msg, 3200);
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
    showError(room <= 0 ? '货道已满，无法再加' : `最多再补 ${room}`);
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
    showError('调低后无有效数量，请换货道或取消该行');
    return null;
  }
  const unassigned = positive.filter(
    (l) => !isPullOffType(l.lineType) && !String(l.slotId || '').trim()
  );
  if (unassigned.length) {
    showError('请先为待分配行选择货道');
    return null;
  }
  return positive;
}

async function handleConfirmLinesFailure(msg: string) {
  if (!msg.includes('容量不足')) {
    showError(msg, 3600);
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
    showError('无补货操作权限');
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
    showSuccess('清单已确认');
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
  if (!requireReplenishmentDoor.value) return true;
  if (doorOpened.value || openSessionId.value) return true;
  await askConfirm({
    title: '尚未开门',
    content: pullOffCopy(
      '请先下发补货开门，到柜完成后再确认任务。',
      '请先下发下架开门，到柜完成后再确认任务。'
    ),
    confirmText: '去开门',
    cancelText: '关闭'
  });
  return false;
}

async function confirmEvidenceIfNeeded(): Promise<boolean> {
  if (!requireReplenishmentEvidence.value) return true;
  if (evidenceItems.value.length > 0) return true;
  const goPhoto = await askConfirm({
    title: '缺少现场凭证',
    content: pullOffCopy(
      '请至少上传 1 张补货现场照片，便于后台抽检。',
      '请至少上传 1 张下架现场照片，便于后台抽检。'
    ),
    confirmText: '去拍照',
    cancelText: '关闭'
  });
  if (goPhoto) {
    if (selected.value?.checkInAt) void addEvidence();
    else showError('请先签到再拍照');
  }
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
  showSuccess(detailIsPullOff.value ? '下架完成' : '补货完成');
  await load();
  const fresh = allTasks.value.find((t) => t.taskId === taskId);
  if (fresh) selected.value = { ...fresh };
}

async function completeTask() {
  if (!selected.value || submitting.value) return;
  if (!canRequest.value) {
    showError('无补货操作权限');
    return;
  }
  if (!linesConfirmed.value) {
    showError('请先确认商品与数量');
    return;
  }
  if (!(await confirmDoorOpenedIfNeeded())) return;
  if (!(await confirmEvidenceIfNeeded())) return;
  if (!(await confirmCompleteAction())) return;
  submitting.value = true;
  try {
    await finalizeCompletedTask(selected.value.taskId);
  } catch (error) {
    showError(error instanceof Error ? error.message : '完成失败');
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
.cabinet-card {
  margin: 0 0 20rpx;
  padding: 20rpx 22rpx;
  border-radius: 18rpx;
  background: var(--brand-soft, #f0fdf4);
  border: 1rpx solid var(--brand-mist, #99f6e4);
}
.cabinet-addr {
  display: block;
  color: var(--brand-deep);
  font-size: var(--font-size-caption);
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
  border-radius: var(--radius-pill);
  background: var(--card-bg, #fff);
  border: 1rpx solid var(--text-subtle, #cbd5e1);
  color: var(--text-muted, #334155);
  font-size: var(--font-size-sm);
  font-weight: 600;
}
.cabinet-chip.primary {
  background: var(--brand-soft);
  border-color: var(--brand);
  color: var(--brand);
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
.task-meta,
.line-meta {
  margin-top: 16rpx;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
.line-type {
  color: var(--brand);
  background: var(--brand-soft);
  padding: 2rpx 10rpx;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
}
.line-cap {
  margin-top: 12rpx;
  padding: 10rpx 14rpx;
  border-radius: var(--radius-control);
  font-size: var(--font-size-sm);
  color: var(--brand);
  background: var(--brand-soft);
}
.line-cap.warn {
  color: var(--warning, #b45309);
  background: color-mix(in srgb, var(--warning, #b45309) 8%, var(--white));
}
.line-cap.full {
  color: var(--color-danger);
  background: color-mix(in srgb, var(--danger, #b91c1c) 8%, var(--white));
}
.slot-pick {
  margin-top: 12rpx;
}
.slot-pick-label {
  display: block;
  font-size: var(--font-size-sm);
  color: var(--brand);
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
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
  color: var(--brand);
  font-size: var(--font-size-sm);
  border: 1rpx solid var(--brand-mist, #99f6e4);
}
.slot-chip.active {
  background: var(--brand);
  color: var(--white);
  border-color: var(--brand);
}
.slot-chip.disabled {
  background: var(--color-border);
  color: var(--text-muted, #475569);
  border-color: var(--text-subtle, #cbd5e1);
}
.slot-empty {
  font-size: var(--font-size-sm);
  color: var(--color-danger);
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
.secondary-btn,
.action-dock .app-btn {
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
  border-radius: var(--radius-card) 32rpx 0 0;
  background: var(--card-bg, #fff);
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
  background: var(--text-subtle, #cbd5e1);
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
.step-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12rpx;
  margin: 26rpx 0;
  padding: 16rpx 8rpx;
  border-radius: 18rpx;
  background: var(--page-bg, #f8fafc);
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
  border-radius: var(--radius-control);
  background: var(--brand-soft);
  color: var(--brand);
  font-size: var(--font-size-sm);
  line-height: 1.4;
}
.step {
  text-align: center;
  color: var(--text-subtle);
  font-size: var(--font-size-sm);
}
.step-num {
  display: flex;
  width: 44rpx;
  height: 44rpx;
  margin: 0 auto 8rpx;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-muted, #475569);
  background: var(--color-border);
  font-size: var(--font-size-caption);
}
.step-label {
  display: block;
}
.step.done {
  color: var(--brand);
}
.step.done .step-num {
  color: var(--white);
  background: var(--brand);
}
.step.current {
  color: var(--brand);
  font-weight: 600;
}
.step.current .step-num {
  color: var(--white);
  background: var(--brand);
  box-shadow: 0 0 0 3px rgba(15, 118, 110, 0.2);
}
.lines-empty {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.lines-empty-title {
  font-size: 13px;
  color: var(--text-muted);
}
.lines-empty-tip {
  font-size: 12px;
  color: var(--text-subtle);
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
  font-size: var(--font-size-md);
  font-weight: 700;
}
.section-subtitle {
  display: block;
  margin-top: 4rpx;
  color: var(--text-subtle);
  font-size: var(--font-size-sm);
}
.line-count {
  padding: 6rpx 12rpx;
  border-radius: var(--radius-pill);
  color: var(--brand);
  background: var(--brand-mist);
  font-size: var(--font-size-sm);
  font-weight: 700;
}
.line-card {
  margin-bottom: 14rpx;
  padding: 20rpx;
  border: 1rpx solid var(--color-border);
  border-radius: 18rpx;
}
.sku-name {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 360rpx;
}
.qty {
  color: var(--brand);
  font-size: var(--font-size-lg);
  font-weight: 800;
  min-width: 40rpx;
  text-align: center;
}
.qty-stepper {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 4rpx 8rpx;
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
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
  border-radius: var(--radius-pill);
  background: var(--brand);
  color: var(--white);
  font-size: var(--font-size-caption);
  font-weight: 600;
}
.scan-line[disabled] {
  opacity: 0.5;
}
.qty-btn {
  width: 88rpx;
  height: 88rpx;
  line-height: 88rpx;
  text-align: center;
  border-radius: 50%;
  background: var(--card-bg, #fff);
  color: var(--brand);
  font-size: var(--font-size-xl);
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
  border-radius: var(--radius-panel);
  background: var(--brand-soft);
  font-size: var(--font-size-xl);
  margin-right: 16rpx;
}
.product-thumb-img {
  width: 100%;
  height: 100%;
  border-radius: var(--radius-panel);
  background: var(--brand-soft);
}
.product-mark {
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--brand);
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
  border-radius: var(--radius-panel);
  background: var(--brand-soft);
}
.evidence-thumb {
  display: block;
}
.evidence-caption {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-xs);
  color: var(--text-muted);
  text-align: center;
}
.evidence-add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 2rpx dashed var(--brand-mist, #99f6e4);
  color: var(--brand);
  gap: 4rpx;
}
.evidence-add-plus {
  font-size: var(--font-size-h2);
  font-weight: 600;
  line-height: 1;
}
.evidence-add-label {
  font-size: var(--font-size-xs);
}
.evidence-empty {
  width: 100%;
  min-height: 140rpx;
  height: auto;
  padding: 24rpx 20rpx;
  box-sizing: border-box;
  border: 2rpx dashed var(--text-subtle, #cbd5e1);
  background: var(--page-bg, #f8fafc);
  border-radius: var(--radius-panel);
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8rpx;
}
.evidence-empty-title {
  font-size: var(--font-size-body);
  font-weight: 650;
  color: var(--text-muted, #334155);
}
.evidence-empty-tip {
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
.evidence-hint {
  align-self: center;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
.evidence-badge {
  color: var(--brand);
  font-weight: 650;
}
.evidence-badge.muted {
  color: var(--text-subtle);
  font-weight: 500;
}
.line-count.warn {
  color: var(--warning, #b45309);
}
.line-stock {
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--brand);
  background: var(--brand-soft);
  border-radius: var(--radius-tag);
  padding: 8rpx 12rpx;
}
.line-stock.muted {
  color: var(--text-muted);
  background: var(--page-bg, #f8fafc);
}
.line-meta.soft {
  color: var(--text-muted);
}
/* 非 sticky：避免滚动选择货道时底栏遮挡操作区（P0-27） */
.action-dock {
  position: relative;
  z-index: 1;
  margin-top: 22rpx;
  padding: 16rpx 0 calc(8rpx + env(safe-area-inset-bottom));
  background: var(--color-bg-card, #fff);
  border-top: 1rpx solid var(--color-border, #e2e8f0);
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.action-dock .app-btn {
  margin-top: 0;
}
.complete-banner {
  margin-top: 22rpx;
  padding: 22rpx;
  border-radius: 18rpx;
  color: var(--brand-deep, #166534);
  background: var(--brand-soft, #dcfce7);
  text-align: center;
  font-size: var(--font-size-caption);
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
  border-radius: var(--radius-card);
  background: var(--card-bg, #fff);
  box-shadow: 0 24rpx 48rpx rgba(15, 23, 42, 0.18);
}
.confirm-title {
  display: block;
  color: var(--text-primary, #0f172a);
  font-size: var(--font-size-xl);
  font-weight: 700;
}
.confirm-body {
  display: block;
  margin-top: 16rpx;
  color: var(--text-muted, #475569);
  font-size: var(--font-size-body);
  line-height: 1.55;
  white-space: pre-wrap;
}
.confirm-remember {
  display: flex;
  align-items: flex-start;
  gap: 12rpx;
  margin-top: 20rpx;
  padding: 16rpx 14rpx;
  border-radius: var(--radius-control);
  background: var(--page-bg, #f8fafc);
  color: var(--text-muted, #334155);
  font-size: var(--font-size-caption);
  line-height: 1.45;
}
.remember-box {
  flex-shrink: 0;
  color: var(--brand);
  font-size: var(--font-size-md);
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
  border-radius: var(--radius-control);
  font-size: var(--font-size-md);
  font-weight: 600;
  line-height: 1.2;
  padding: 22rpx 12rpx;
}
.confirm-btn.cancel {
  color: var(--text-muted, #334155);
  background: var(--color-border-subtle, #f1f5f9);
}
.confirm-btn.ok {
  color: var(--white);
  background: linear-gradient(135deg, var(--brand), var(--brand));
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
