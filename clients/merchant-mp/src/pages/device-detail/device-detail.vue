<template>
  <view>
    <app-nav-bar title="柜机详情" home-url="/pages/home/home" />
    <view class="page-body">
      <view v-if="!canView" class="card"><text class="err">当前账号无柜机详情权限</text></view>
      <view v-else-if="loading && !deviceName" class="card">{{ UI_COPY.loading }}</view>
      <error-state
        v-else-if="error && !deviceName"
        :title="error"
        :hint="loadErrorHint"
        :show-retry="canRetryLoad"
        @retry="loadDetail"
      />
      <view v-else-if="deviceName || !loading">
        <view class="card">
          <image
            class="device-hero"
            src="/static/device-default.png"
            mode="aspectFit"
            aria-hidden="true"
          />
          <text class="title">{{ deviceName }}</text>
          <view class="meta-block">
            <text class="meta-line meta-status-row">
              <text>{{ deviceId }} · </text>
              <text class="app-status" :class="online ? 'is-online' : 'is-offline'">
                <text class="app-status-dot" aria-hidden="true" />
                {{ onlineLabel(online) }}
              </text>
              <text v-if="salesLocked"> · {{ UI_COPY.salesLocked }}中</text>
            </text>
            <text v-if="address" class="meta-line">{{ address }}</text>
            <text v-if="firmwareVersion" class="meta-line">固件 {{ firmwareVersion }}</text>
            <text v-if="routeCode || lifecycleLabel" class="meta-line">
              <template v-if="routeCode">线路 {{ routeCode }}</template>
              <template v-if="routeCode && lifecycleLabel"> · </template>
              <template v-if="lifecycleLabel">{{ lifecycleLabel }}</template>
            </text>
            <text class="meta-line">当前 {{ currentTemp }} · 目标 {{ targetTemp }}</text>
            <!-- CB-050：信息区扩展（场景/分配时间/最近上下线/信号强度），数据来自 deviceDetail 的 AdminDeviceDto -->
            <text class="meta-line">设备编号 {{ deviceId }}</text>
            <text v-if="sceneText" class="meta-line">投放场景 {{ sceneText }}</text>
            <text v-if="assignedAtText" class="meta-line">分配时间 {{ assignedAtText }}</text>
            <text class="meta-line"
              >最近上线 {{ lastOnlineText }} · 最近离线 {{ lastOfflineText }}</text
            >
            <text v-if="signalText" class="meta-line">信号强度 {{ signalText }}</text>
            <text v-if="slotStockHint" class="meta-line stock-warn">{{ slotStockHint }}</text>
          </view>
          <text v-if="salesLocked" class="locked-banner"
            >柜机已锁机停售，消费者无法开门；补货仍可按任务操作{{
              salesLockReason ? `（${salesLockReason}）` : ''
            }}</text
          >
          <view
            role="button"
            class="pref-row"
            :aria-label="isPreferred ? '取消常驻柜' : '设为常驻柜'"
            @click="togglePreferred"
          >
            <text class="pref-star" :class="{ on: isPreferred }">★</text>
            <text>{{ isPreferred ? '常驻柜（点击取消）' : '设为常驻柜' }}</text>
          </view>
          <view class="action-row">
            <app-button
              v-if="latitude != null && longitude != null"
              class="app-btn-flex"
              :block="false"
              compact
              label="导航到柜"
              @click="openNav"
            />
          </view>
        </view>

        <!-- CB-050：KPI 条（今日销售额/今日订单量/本月销售额），无 analytics 权限整卡隐藏 -->
        <view v-if="canAnalytics" class="card kpi-card">
          <view class="kpi-item">
            <text class="kpi-num">{{ todaySalesYuan }}</text>
            <text class="kpi-label">今日销售额(元)</text>
          </view>
          <view class="kpi-item">
            <text class="kpi-num">{{ todayOrdersText }}</text>
            <text class="kpi-label">今日订单量</text>
          </view>
          <view class="kpi-item">
            <text class="kpi-num">{{ monthSalesYuan }}</text>
            <text class="kpi-label">本月销售额(元)</text>
          </view>
        </view>

        <!-- CB-050：快捷操作宫格（收拢散落入口） -->
        <view class="card">
          <text class="section">快捷操作</text>
          <view class="action-grid">
            <view
              class="action-cell"
              role="button"
              aria-label="查看在售商品"
              @click="scrollToSlots"
            >
              <text>在售商品</text>
            </view>
            <view class="action-cell" role="button" aria-label="查看货柜订单" @click="goOrders">
              <text>货柜订单</text>
            </view>
            <view
              v-if="canReplenishView"
              class="action-cell"
              role="button"
              aria-label="查看补货任务"
              @click="goReplenishment"
            >
              <text>补货任务</text>
            </view>
            <view
              v-if="canRequest"
              class="action-cell"
              role="button"
              aria-label="发起补货"
              @click="goRequest"
            >
              <text>发起补货</text>
            </view>
            <view
              v-if="canStocktake"
              class="action-cell"
              role="button"
              aria-label="柜机盘点"
              @click="goDeviceStocktake"
            >
              <text>柜机盘点</text>
            </view>
            <view class="action-cell" role="button" aria-label="理货记录" @click="goTallyRecords">
              <text>理货记录</text>
            </view>
          </view>
        </view>

        <!-- CB-050：设备控制（merchant:device:control；白名单 停售/恢复售卖/重启，指令留平台审计） -->
        <view v-if="canControl" class="card">
          <text class="section">设备控制</text>
          <text class="meta">远程开门请走补货任务；指令将记入平台审计日志</text>
          <view class="action-row">
            <app-button
              v-if="!salesLocked"
              class="app-btn-flex"
              :block="false"
              compact
              variant="danger"
              :loading="cmdBusy"
              label="停售"
              @click="sendCmd('LOCK')"
            />
            <app-button
              v-else
              class="app-btn-flex"
              :block="false"
              compact
              :loading="cmdBusy"
              label="恢复售卖"
              @click="sendCmd('UNLOCK')"
            />
            <app-button
              class="app-btn-flex"
              :block="false"
              compact
              :loading="cmdBusy"
              label="重启柜机"
              @click="sendCmd('REBOOT')"
            />
          </view>
        </view>

        <view v-if="canEditDevice" class="card">
          <text class="section">柜机设置</text>
          <view class="field">
            <text class="field-label">显示名称</text>
            <input v-model="formName" class="input" placeholder="请输入柜机显示名称" />
          </view>
          <view class="field">
            <text class="field-label">目标温度(°C)</text>
            <input v-model="formTargetTemp" class="input" type="number" placeholder="例如 5" />
          </view>
          <view class="field">
            <text class="field-label">备注</text>
            <input v-model="formRemark" class="input" placeholder="选填运维备注" />
          </view>
          <app-button
            :loading="saving"
            :label="saving ? '保存中…' : '保存设置'"
            @click="saveSettings"
          />
        </view>

        <!-- 将邑采集模式（CB-023 商户端补充）：仅将邑绑定柜机显示；409 营业闸门与 admin 共用 -->
        <view v-if="jiangyiGather.bound" class="card">
          <view class="row">
            <text class="section">将邑识别采集</text>
            <text class="meta" :class="{ 'gather-on': jiangyiGather.gatherLocked }">
              {{ jiangyiGather.gatherLocked ? '采集中 · 停止营业' : '营业中' }}
            </text>
          </view>
          <text class="gather-tip">
            {{
              jiangyiGather.gatherLocked
                ? '采集模式下顾客无法开门购物。采集批次操作在将邑商户 App 完成，完成后退出采集模式恢复营业。'
                : '上新商品需柜内采集学习：进入采集模式后柜机停止营业，放入实物由柜机摄像头自动拍摄，无需人工上传图片。'
            }}
          </text>
          <view v-if="canEditDevice && !jiangyiGather.gatherLocked" class="field">
            <text class="field-label">门位（选填）</text>
            <input v-model="gatherDoorPosition" class="input" placeholder="例如 1" />
          </view>
          <app-button
            v-if="canEditDevice"
            :variant="jiangyiGather.gatherLocked ? 'primary' : 'danger'"
            :loading="gatherBusy"
            :label="jiangyiGather.gatherLocked ? '退出采集模式（恢复营业）' : '进入采集模式'"
            @click="toggleGatherMode"
          />
        </view>

        <view id="slotCard" class="card">
          <view class="row">
            <text class="section">货道</text>
            <text v-if="!canEditSlots" class="meta">暂无货道编辑权限</text>
          </view>
          <view class="slot-grid">
            <view v-for="s in slots" :key="s.slotCode" class="slot-cell">
              <text class="slot-code">{{ s.slotCode }}</text>
              <text class="slot-sku">{{ s.assignedSkuName || '空' }}</text>
              <text class="meta slot-stock"
                >库存 {{ s.bookQty }}/{{ s.maxLevel || s.parLevel || '未设上限' }}</text
              >
              <input
                v-if="canEditSlots"
                v-model="slotPar[s.slotCode]"
                class="input-sm"
                type="number"
                placeholder="目标库存"
              />
            </view>
          </view>
          <view v-if="canEditSlots" class="slot-save-row">
            <app-button
              :loading="savingSlots"
              :label="savingSlots ? '保存中…' : '保存货道'"
              @click="saveSlots"
            />
          </view>
        </view>

        <view v-if="canAnalytics && velocity.length" class="card">
          <view class="row">
            <text class="section">商品动销 / 补货点</text>
            <text class="meta">近 14 日</text>
          </view>
          <view v-for="v in velocity" :key="v.skuId" class="velocity-row">
            <view class="velocity-main">
              <text class="sku-name">{{ v.skuName }}</text>
              <text class="meta">{{ v.skuId }}</text>
            </view>
            <view class="velocity-data">
              <text>7日 {{ v.soldQty7d }}</text>
              <text>14日 {{ v.soldQty14d }}</text>
              <text>日均 {{ Number(v.avgDailySales).toFixed(1) }}</text>
              <text class="rop">补货点 {{ v.ropPoint }}</text>
            </view>
          </view>
        </view>
      </view>

      <!-- H5 可访问确认框：进入/退出采集模式前确认（退出营业影响大） -->
      <AppConfirmDialog
        :visible="confirmDialog.visible"
        :title="confirmDialog.title"
        :content="confirmDialog.content"
        :confirm-text="confirmDialog.confirmText"
        :cancel-text="confirmDialog.cancelText"
        @confirm="resolveConfirm(true)"
        @cancel="resolveConfirm(false)"
      />
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app';
import { showError, showSuccess } from '@/utils/notify';
import { computed, ref } from 'vue';
import { dictLabel } from '@aicabinet/shared-dict';
import { merchantApi, hasPerm, softFallback, isMerchantLoggedIn } from '@/utils/merchant-api';
import {
  useMerchantMe,
  canEditPlanogramForMerchant,
  seedMerchantMeDisplayCache
} from '@/composables/useMerchantMe';
import {
  clearPreferredDeviceId,
  getPreferredDeviceId,
  setPreferredDeviceId
} from '@/utils/preferred-device';
import { confirmOpenDeviceNavigation } from '@/utils/open-device-navigation';
import { resolveMerchantIdForDevice } from '@/utils/device-settings';
import { UI_COPY, onlineLabel } from '@aicabinet/shared-uni/ui-copy';
import { centsToYuanDigits, formatDateTime } from '@aicabinet/shared-uni/format';
import AppConfirmDialog from '@/components/AppConfirmDialog.vue';
import { useAppConfirmDialog } from '@/composables/useAppConfirmDialog';
import type {
  DeviceSlot,
  MerchantDeviceInfo,
  MerchantSkuVelocity,
  OpenApiMerchantDeviceSettingsDto,
  OpenApiUpdateMerchantDeviceSettingsRequest
} from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const loading = ref(true);
const error = ref('');
let loadSeq = 0;
const deviceId = ref('');
const merchantId = ref('');
const deviceName = ref('');
const online = ref(false);
const salesLocked = ref(false);
const salesLockReason = ref('');
const address = ref('');
const routeCode = ref('');
const lifecycleStatus = ref('');
const firmwareVersion = ref('');
const latitude = ref<number | null>(null);
const longitude = ref<number | null>(null);
const currentTemp = ref('暂无');
const targetTemp = ref('未设置');
const formName = ref('');
const formTargetTemp = ref('');
const formRemark = ref('');
const saving = ref(false);
const savingSlots = ref(false);
const slots = ref<DeviceSlot[]>([]);
const slotPar = ref<Record<string, string>>({});
const velocity = ref<MerchantSkuVelocity[]>([]);
const isPreferred = ref(false);

// CB-050：信息区扩展字段（来自 deviceDetail.device=AdminDeviceDto）+ KPI 汇总 + 设备控制
const sceneType = ref('');
const deployedAt = ref('');
const lastOnlineAt = ref('');
const lastOfflineAt = ref('');
const signalStrength = ref<number | null>(null);
const summary = ref<import('@aicabinet/shared-types').OpenApiMerchantDeviceSalesSummaryDto | null>(
  null
);
const cmdBusy = ref(false);

// 将邑采集模式（CB-023 商户端补充）：非将邑绑定柜机 bound=false → 卡片整体隐藏
const jiangyiGather = ref({ bound: false, gatherLocked: false });
const gatherDoorPosition = ref('');
const gatherBusy = ref(false);
const { confirmDialog, askConfirm, resolveConfirm } = useAppConfirmDialog();

const lifecycleLabel = computed(() =>
  lifecycleStatus.value
    ? dictLabel('device_lifecycle', lifecycleStatus.value) || lifecycleStatus.value
    : ''
);
const slotStockHint = computed(() => {
  const oos = slots.value.filter((s) => Number(s.bookQty || 0) <= 0 && !!s.assignedSkuId).length;
  const low = slots.value.filter((s) => {
    const qty = Number(s.bookQty || 0);
    const min = Number(s.minLevel || 0);
    return !!s.assignedSkuId && qty > 0 && min > 0 && qty <= min;
  }).length;
  if (oos > 0 && low > 0) return `缺货 ${oos} 个货道 · 低库存 ${low} 个货道`;
  if (oos > 0) return `缺货 ${oos} 个货道`;
  if (low > 0) return `低库存 ${low} 个货道`;
  return '';
});

const canView = computed(() => hasPerm(me.value, 'merchant:devices:detail'));
const canEditDevice = computed(() => hasPerm(me.value, 'merchant:devices:edit'));
const canEditSlots = computed(() => canEditPlanogramForMerchant(me.value, merchantId.value));
const canReplenishView = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));
const canAnalytics = computed(() => hasPerm(me.value, 'merchant:analytics:view'));
const canControl = computed(() => hasPerm(me.value, 'merchant:device:control'));
const canStocktake = computed(() => hasPerm(me.value, 'merchant:slots:edit'));

/** CB-050 扩展：快捷操作入口 */
function goDeviceStocktake() {
  uni.navigateTo({
    url: `/pages/device-stocktake/device-stocktake?deviceId=${encodeURIComponent(deviceId.value)}`
  });
}
function goTallyRecords() {
  uni.navigateTo({
    url: `/pages/tally-records/tally-records?deviceId=${encodeURIComponent(deviceId.value)}&name=${encodeURIComponent(deviceName.value)}`
  });
}

const sceneText = computed(() =>
  sceneType.value ? dictLabel('device_scene_type', sceneType.value) || sceneType.value : ''
);
const assignedAtText = computed(() => (deployedAt.value ? formatDateTime(deployedAt.value) : ''));
const lastOnlineText = computed(() =>
  lastOnlineAt.value ? formatDateTime(lastOnlineAt.value) : '无'
);
const lastOfflineText = computed(() =>
  lastOfflineAt.value ? formatDateTime(lastOfflineAt.value) : '无'
);
const SIGNAL_LABELS = ['无信号', '很弱', '一般', '好', '很好'];
const signalText = computed(() => {
  const v = signalStrength.value;
  if (v == null || v < 0 || v > 4) return '';
  return SIGNAL_LABELS[v];
});
/** 列头已写「(元)」，所以这里要的是无符号数字位，不是 fmtMoney */
const fenToYuanText = (cents?: number | null) => (cents == null ? '--' : centsToYuanDigits(cents));
const todaySalesYuan = computed(() => fenToYuanText(summary.value?.todaySalesCents));
const todayOrdersText = computed(() => (summary.value ? String(summary.value.todayOrders) : '--'));
const monthSalesYuan = computed(() => fenToYuanText(summary.value?.monthSalesCents));

/**
 * deviceId 缺失属于「入口参数问题」，不是网络故障：
 *   - 旧行为：走 error-state 默认 hint「请检查网络后重试」+ 显示「重试」按钮，
 *     但重试读的是同一个空 deviceId ⇒ **永远不可能成功**，文案与按钮都在骗用户。
 *   - 现行为：指路到柜机列表，并撤掉无效的重试按钮；真实网络失败仍保留重试。
 */
const missingDeviceId = computed(() => !deviceId.value);
const loadErrorHint = computed(() =>
  missingDeviceId.value ? '请从柜机列表重新进入' : '请检查网络后重试'
);
const canRetryLoad = computed(() => !missingDeviceId.value);

onLoad((opts) => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  try {
    deviceId.value = decodeURIComponent((opts?.id as string) || '');
  } catch {
    deviceId.value = String(opts?.id || '');
  }
  if (!deviceId.value) {
    error.value = '柜机不存在';
    loading.value = false;
    return;
  }
  loadDetail();
});

function formatTempDisplay(value: number | null | undefined, emptyLabel: string): string {
  return value == null ? emptyLabel : `${value}°C`;
}

function readExtendedDeviceFields(settings: OpenApiMerchantDeviceSettingsDto) {
  return {
    salesLocked: !!settings.salesLocked,
    salesLockReason: String(settings.salesLockReason || ''),
    address: String(settings.address || ''),
    routeCode: String(settings.routeCode || ''),
    lifecycleStatus: String(settings.lifecycleStatus || ''),
    firmwareVersion: String(settings.firmwareVersion || ''),
    latitude:
      settings.latitude == null || Number.isNaN(Number(settings.latitude))
        ? null
        : Number(settings.latitude),
    longitude:
      settings.longitude == null || Number.isNaN(Number(settings.longitude))
        ? null
        : Number(settings.longitude)
  };
}

function applyDeviceSettings(settings: OpenApiMerchantDeviceSettingsDto) {
  const ext = readExtendedDeviceFields(settings);
  deviceName.value = settings.deviceName || deviceId.value;
  online.value = String(settings.onlineStatus || '').toUpperCase() === 'ONLINE';
  salesLocked.value = ext.salesLocked;
  salesLockReason.value = ext.salesLockReason;
  address.value = ext.address;
  routeCode.value = ext.routeCode;
  lifecycleStatus.value = ext.lifecycleStatus;
  firmwareVersion.value = ext.firmwareVersion;
  latitude.value = ext.latitude;
  longitude.value = ext.longitude;
  currentTemp.value = formatTempDisplay(settings.currentTempC, '暂无');
  targetTemp.value = formatTempDisplay(settings.targetTempC, '未设置');
  formName.value = settings.deviceName || '';
  formTargetTemp.value = settings.targetTempC == null ? '' : String(settings.targetTempC);
  formRemark.value = settings.opsRemark || '';
}

function applySlotParLevels(list: DeviceSlot[]) {
  const par: Record<string, string> = {};
  list.forEach((s) => {
    par[s.slotCode] = s.parLevel == null ? '' : String(s.parLevel);
  });
  slotPar.value = par;
}

function syncPreferredFlag() {
  isPreferred.value =
    String(getPreferredDeviceId() || '')
      .trim()
      .toUpperCase() ===
    String(deviceId.value || '')
      .trim()
      .toUpperCase();
}

async function loadDeviceExtras(seq: number) {
  // 「无权限=不展示」：无 analytics:view 时不发 velocity 请求（避免控制台 403 噪音）
  const allowVelocity = hasPerm(me.value, 'merchant:analytics:view');
  // 采集状态只读权限 = 柜机详情权限（canView 已在此处为真）；非将邑柜机接口回 404 → 软兜底隐藏
  const [list, vel, gather, detail, kpi] = await Promise.all([
    softFallback(merchantApi.deviceSlots(deviceId.value), [] as DeviceSlot[], '货道'),
    allowVelocity
      ? softFallback(merchantApi.skuVelocity(deviceId.value), [] as MerchantSkuVelocity[], '动销')
      : Promise.resolve([] as MerchantSkuVelocity[]),
    softFallback(
      merchantApi.jiangyiGatherStatus(deviceId.value),
      {
        bound: false,
        gatherLocked: false
      } as import('@aicabinet/shared-types').OpenApiMerchantGatherStatusDto,
      '采集状态'
    ),
    // CB-050：详情 device（AdminDeviceDto）带场景/分配时间/最近上下线/信号强度
    softFallback(
      merchantApi.deviceDetail(deviceId.value),
      null as import('@aicabinet/shared-types').OpenApiDeviceDetailDto | null,
      '详情'
    ),
    allowVelocity
      ? softFallback(
          merchantApi.deviceSalesSummary(deviceId.value),
          null as import('@aicabinet/shared-types').OpenApiMerchantDeviceSalesSummaryDto | null,
          '销售汇总'
        )
      : Promise.resolve(
          null as import('@aicabinet/shared-types').OpenApiMerchantDeviceSalesSummaryDto | null
        )
  ]);
  if (seq !== loadSeq) return;
  slots.value = list;
  velocity.value = vel;
  jiangyiGather.value = { bound: !!gather.bound, gatherLocked: !!gather.gatherLocked };
  const deviceFields = (detail as { device?: { [k: string]: unknown } } | null)?.device;
  if (deviceFields) {
    sceneType.value = String(deviceFields.sceneType || '');
    deployedAt.value = String(deviceFields.deployedAt || '');
    lastOnlineAt.value = String(deviceFields.lastOnlineAt || '');
    lastOfflineAt.value = String(deviceFields.lastOfflineAt || '');
    signalStrength.value =
      deviceFields.signalStrength == null ? null : Number(deviceFields.signalStrength);
  }
  if (kpi) summary.value = kpi;
  applySlotParLevels(list);
  syncPreferredFlag();
}

/** CB-050：设备控制指令（停售/恢复售卖/重启）。二次确认后下发，成功后整页刷新以服务端为准。 */
async function sendCmd(command: 'LOCK' | 'UNLOCK' | 'REBOOT') {
  if (cmdBusy.value) return;
  const copy: Record<string, { title: string; content: string }> = {
    LOCK: { title: '停售锁机', content: '停售后消费者无法开门购物；补货不受影响。确认执行？' },
    UNLOCK: { title: '恢复售卖', content: '恢复后消费者可正常开门购物。确认执行？' },
    REBOOT: { title: '重启柜机', content: '重启期间柜机短暂离线，确认下发？' }
  };
  const c = copy[command];
  const ok = await askConfirm({ title: c.title, content: c.content, confirmText: '确认下发' });
  if (!ok) return;
  cmdBusy.value = true;
  try {
    const res = await merchantApi.deviceCommand(deviceId.value, {
      command,
      reason: 'merchant-portal'
    });
    showSuccess(res.message || '指令已下发');
    await loadDetail();
  } catch (e) {
    showError(e instanceof Error ? e.message : '指令下发失败');
  } finally {
    cmdBusy.value = false;
  }
}

/** 快捷操作：滚到货道（在售商品）卡 */
function scrollToSlots() {
  uni
    .createSelectorQuery()
    .select('#slotCard')
    .boundingClientRect((rect) => {
      const top = (rect as { top?: number } | null)?.top;
      if (top == null) return;
      uni.pageScrollTo({ scrollTop: top + 2, duration: 200 });
    })
    .exec();
}

/** 快捷操作：跳货柜订单（订单页支持 deviceId 预置筛选） */
function goOrders() {
  uni.navigateTo({
    url: `/pages/orders/orders?deviceId=${encodeURIComponent(deviceId.value)}`
  });
}

/**
 * 进入/退出采集模式。进入会停止营业（顾客开门 409），必须二次确认；
 * 退出即恢复营业。成功后刷新状态（以服务端回包为准，不乐观更新）。
 */
async function toggleGatherMode() {
  if (gatherBusy.value) return;
  const entering = !jiangyiGather.value.gatherLocked;
  const ok = await askConfirm({
    title: entering ? '进入采集模式' : '退出采集模式',
    content: entering
      ? '进入后柜机停止营业，顾客无法开门购物，直到手动退出。确定继续？'
      : '退出后恢复营业。确定继续？',
    confirmText: entering ? '进入采集模式' : '退出'
  });
  if (!ok) return;
  gatherBusy.value = true;
  try {
    if (entering) {
      await merchantApi.jiangyiGatherStart(
        deviceId.value,
        gatherDoorPosition.value.trim() || undefined
      );
      showSuccess('已进入采集模式，柜机暂停营业');
    } else {
      await merchantApi.jiangyiGatherExit(deviceId.value);
      showSuccess('已退出采集模式，恢复营业');
    }
    jiangyiGather.value.gatherLocked = entering;
  } catch (e) {
    showError(e instanceof Error ? e.message : '操作失败');
  } finally {
    gatherBusy.value = false;
  }
}

async function refreshDeviceDetailMe(seq: number): Promise<boolean> {
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return false;
    seedMerchantMeDisplayCache(me);
  }
  return seq === loadSeq;
}

function denyDeviceDetailAccess() {
  loading.value = false;
  showError('无柜机详情权限');
  uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) });
}

async function loadDetail() {
  const seq = ++loadSeq;
  if (!(await refreshDeviceDetailMe(seq))) return;
  if (!canView.value) {
    denyDeviceDetailAccess();
    return;
  }
  if (!deviceName.value) loading.value = true;
  error.value = '';
  try {
    const [settings, devices] = await Promise.all([
      merchantApi.deviceSettings(deviceId.value),
      softFallback(merchantApi.devices(), [] as MerchantDeviceInfo[], '柜机列表')
    ]);
    if (seq !== loadSeq) return;
    merchantId.value = resolveMerchantIdForDevice({
      deviceId: deviceId.value,
      devices,
      me: me.value
    });
    applyDeviceSettings(settings);
    await loadDeviceExtras(seq);
  } catch (e) {
    if (seq !== loadSeq) return;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function togglePreferred() {
  if (!deviceId.value) return;
  if (isPreferred.value) {
    clearPreferredDeviceId();
    isPreferred.value = false;
    showSuccess('已取消常驻');
    return;
  }
  setPreferredDeviceId(deviceId.value);
  isPreferred.value = true;
  showSuccess('已设为常驻柜');
}

function openNav() {
  void confirmOpenDeviceNavigation({
    latitude: latitude.value,
    longitude: longitude.value,
    name: deviceName.value,
    address: address.value,
    deviceId: deviceId.value
  });
}

function goReplenishment() {
  uni.navigateTo({
    url: `/pages/replenishment/replenishment?deviceId=${encodeURIComponent(deviceId.value)}`
  });
}

function goRequest() {
  uni.navigateTo({
    url: `/pages/self-replenish/self-replenish?deviceId=${encodeURIComponent(deviceId.value)}`
  });
}

async function saveSettings() {
  if (saving.value) return;
  const body: OpenApiUpdateMerchantDeviceSettingsRequest = {
    deviceName: formName.value.trim() || undefined,
    opsRemark: formRemark.value.trim() || undefined
  };
  if (formTargetTemp.value !== '') {
    const temp = Number(formTargetTemp.value);
    // 用 Number + Number.isInteger 替代 parseInt，避免 "25.5" 被静默截断为 25
    if (!Number.isInteger(temp)) {
      showError('目标温度须为整数');
      return;
    }
    body.targetTempC = temp;
  }
  saving.value = true;
  try {
    await merchantApi.updateDeviceSettings(deviceId.value, body);
    showSuccess('已保存');
    await loadDetail();
  } catch (e) {
    showError(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function saveSlots() {
  if (savingSlots.value) return;
  const body: {
    slotCode: string;
    rowNo: number;
    colNo: number;
    slotType: string;
    assignedSkuId?: string;
    parLevel: number;
    minLevel?: number;
    maxLevel?: number;
    enabled?: boolean;
  }[] = [];
  for (const s of slots.value) {
    const par = Number(slotPar.value[s.slotCode] || s.parLevel);
    if (!Number.isInteger(par) || par < 0) {
      showError(`货道 ${s.slotCode} 容量无效`);
      return;
    }
    body.push({
      slotCode: s.slotCode,
      rowNo: s.rowNo,
      colNo: s.colNo,
      slotType: s.slotType || '',
      assignedSkuId: s.assignedSkuId,
      parLevel: par,
      minLevel: s.minLevel,
      maxLevel: s.maxLevel,
      enabled: s.enabled
    });
  }
  savingSlots.value = true;
  try {
    await merchantApi.upsertSlots(deviceId.value, body);
    showSuccess('货道已保存');
    await loadDetail();
  } catch (e) {
    showError(e instanceof Error ? e.message : '保存失败');
  } finally {
    savingSlots.value = false;
  }
}
</script>

<style scoped>
.velocity-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 16rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f1f5f9);
}
.velocity-row:last-child {
  border-bottom: none;
}
.velocity-main {
  flex: 1;
  min-width: 0;
}
.velocity-main .sku-name {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.velocity-data {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8rpx 16rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.velocity-data .rop {
  color: var(--warning, #b45309);
  font-weight: 700;
  background: #f9f1eb;
  padding: 2rpx 10rpx;
  border-radius: var(--radius-pill);
}

.device-hero {
  width: 100%;
  height: 260rpx;
  border-radius: 18rpx;
  background: var(--brand-soft);
  margin-bottom: 16rpx;
  display: block;
}
.title {
  font-size: var(--font-size-xl);
  font-weight: 600;
  display: block;
}
.meta-block {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  margin-top: 10rpx;
  gap: 8rpx;
}
.meta-line {
  display: block;
  color: var(--text-muted);
  font-size: var(--font-size-caption);
  line-height: 1.45;
  margin-top: 0;
}
.meta-line + .meta-line {
  margin-top: 8rpx;
}
.locked-banner {
  display: block;
  margin-top: 12rpx;
  padding: 12rpx 16rpx;
  border-radius: var(--radius-control);
  background: #f5e7dd;
  color: var(--warning, #92400e);
  font-size: var(--font-size-caption);
  line-height: 1.4;
}
.meta-line.stock-warn,
.meta.stock-warn {
  color: var(--warning, #b45309);
  font-weight: 600;
}
.section {
  font-weight: 600;
  display: block;
  margin-bottom: 8rpx;
}
.meta.gather-on {
  color: var(--warning, #b45309);
  font-weight: 600;
}
.gather-tip {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--text-muted, #475569);
  line-height: 1.5;
  margin-bottom: 12rpx;
}
.field {
  margin: 12rpx 0;
}
.field-label {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--text-muted, #475569);
  margin-bottom: 6rpx;
  font-weight: 550;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12rpx;
}
.pref-row {
  margin-top: 16rpx;
  display: flex;
  align-items: center;
  gap: 8rpx;
  font-size: var(--font-size-body);
  color: var(--brand);
}
.pref-star {
  color: var(--text-subtle, #cbd5e1);
  font-size: var(--font-size-xl);
}
.pref-star.on {
  color: var(--warning, #f59e0b);
}
.input {
  display: block;
  width: 100%;
  height: 80rpx;
  min-height: 80rpx;
  line-height: 80rpx;
  box-sizing: border-box;
  background: var(--page-bg, #ededed);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 0 12px;
  margin: 0;
  font-size: var(--font-size-md);
  color: var(--text-primary, #0f172a);
}
.input-sm {
  display: block;
  width: 100%;
  height: 64rpx;
  min-height: 64rpx;
  line-height: 64rpx;
  box-sizing: border-box;
  font-size: var(--font-size-sm);
  margin-top: 6rpx;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  padding: 0 6px;
  color: var(--text-primary, #0f172a);
}
.slot-code {
  font-weight: 600;
  display: block;
  font-size: var(--font-size-caption);
}
.slot-sku {
  display: block;
  font-size: var(--font-size-xs);
  color: var(--text-primary, #0f172a);
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.slot-stock {
  display: block;
  margin-top: 2rpx;
}
.action-row {
  display: flex;
  gap: 16rpx;
  margin-top: 16px;
  width: 100%;
  box-sizing: border-box;
}
/* 宿主选择器与 `:deep()` 双写：只有**前者**在真机生效（`:deep()` 因组件样式隔离永不命中，
 * lessons #288）。保留 `:deep()` 是为了 H5 端表现一致，不要当成"两处都有效"。 */
.action-btn,
.app-btn-flex,
:deep(.action-btn),
:deep(.app-btn-flex) {
  flex: 1 1 0;
  width: 0 !important;
  min-width: 0 !important;
  max-width: none !important;
  margin: 0 !important;
  align-self: stretch !important;
  padding-left: 16rpx !important;
  padding-right: 16rpx !important;
  font-size: var(--font-size-md) !important;
  box-sizing: border-box !important;
}
.err {
  color: var(--color-danger);
}
.page-body {
  padding: 24rpx var(--page-gutter, 24rpx) calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
/* 保存货道与货道网格的间距：margin 写在自定义组件标签上小程序端不生效，改用页面容器 */
.slot-save-row {
  padding-top: 24rpx;
}
/* CB-050：KPI 条与快捷操作宫格 */
.kpi-card {
  display: flex;
  align-items: stretch;
}
.kpi-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;
}
.kpi-num {
  font-size: 36rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.kpi-label {
  font-size: 22rpx;
  color: var(--color-text-secondary, #909399);
}
.action-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 12rpx;
}
.action-cell {
  flex: 1 1 40%;
  min-height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1rpx solid var(--color-border, #ebeef5);
  border-radius: 12rpx;
  background: var(--color-fill, #f7f8fa);
  font-size: 28rpx;
}
</style>
