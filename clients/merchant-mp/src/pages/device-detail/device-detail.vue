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
          <view class="head-main">
            <image
              class="device-thumb"
              src="/static/device-default.png"
              mode="aspectFill"
              aria-hidden="true"
            />
            <view class="head-id">
              <text class="title">{{ deviceName }}</text>
              <text class="meta-line meta-status-row">
                <text :user-select="true">{{ deviceId }}</text>
                <text> · </text>
                <text class="app-status" :class="online ? 'is-online' : 'is-offline'">
                  <text class="app-status-dot" aria-hidden="true" />
                  {{ onlineLabel(online) }}
                </text>
                <text v-if="lifecycleLabel"> · {{ lifecycleLabel }}</text>
                <text v-if="salesLocked"> · {{ UI_COPY.salesLocked }}中</text>
              </text>
              <text v-if="address" class="meta-line">{{ address }}</text>
            </view>
          </view>
          <!-- CB-050 信息区收成两列键值格（2026-10-30 用户要求重新排版）；编号已在状态行，不再重复单列 -->
          <view class="info-grid">
            <view v-if="sceneText" class="info-cell">
              <text class="info-label">投放场景</text>
              <text class="info-val">{{ sceneText }}</text>
            </view>
            <view v-if="routeCode" class="info-cell">
              <text class="info-label">线路</text>
              <text class="info-val">{{ routeCode }}</text>
            </view>
            <view v-if="firmwareVersion" class="info-cell">
              <text class="info-label">固件</text>
              <text class="info-val">{{ firmwareVersion }}</text>
            </view>
            <view v-if="assignedAtText" class="info-cell">
              <text class="info-label">分配时间</text>
              <text class="info-val">{{ assignedAtText }}</text>
            </view>
            <view class="info-cell">
              <text class="info-label">最近上线</text>
              <text class="info-val">{{ lastOnlineText }}</text>
            </view>
            <view class="info-cell">
              <text class="info-label">最近离线</text>
              <text class="info-val">{{ lastOfflineText }}</text>
            </view>
            <view v-if="signalText" class="info-cell">
              <text class="info-label">信号强度</text>
              <text class="info-val">{{ signalText }}</text>
            </view>
          </view>
          <text v-if="goodsStockHint" class="meta-line stock-warn">{{ goodsStockHint }}</text>
          <text v-if="salesLocked" class="locked-banner"
            >柜机已锁机停售，消费者无法开门；补货仍可按任务操作{{
              salesLockReason ? `（${salesLockReason}）` : ''
            }}</text
          >
          <view class="head-actions">
            <view
              role="button"
              class="pref-row"
              :aria-label="isPreferred ? '取消常驻柜' : '设为常驻柜'"
              @click="togglePreferred"
            >
              <text class="pref-star" :class="{ on: isPreferred }">★</text>
              <text>{{ isPreferred ? '常驻柜（点击取消）' : '设为常驻柜' }}</text>
            </view>
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

        <!-- 选项式详情页签（2026-10-27 用户拍板）：概览 / 商品 / 设置 -->
        <!-- 2026-10-28：改用全局 app-underline-tabs，自带选中态/下划线样式；此前 .detail-tabs
             只有模板没有对应样式，渲染成无选中态、无下划线的纯文本。 -->
        <app-underline-tabs
          layout="auto"
          :items="detailTabs"
          :value="activeTab"
          @change="onDetailTab"
        />

        <!-- CB-050：快捷操作宫格（收拢散落入口）。
             2026-11-10 重排：删「在售商品」入口（与「商品」页签语义完全重复），
             5 格 2/2/1 换行 → 4 格 2×2；补货双入口放前（本页高频动作）。 -->
        <view class="card" v-show="activeTab === 'overview'">
          <text class="section">快捷操作</text>
          <view class="action-grid">
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
            <view class="action-cell" role="button" aria-label="查看货柜订单" @click="goOrders">
              <text>货柜订单</text>
            </view>
            <view class="action-cell" role="button" aria-label="理货记录" @click="goTallyRecords">
              <text>理货记录</text>
            </view>
          </view>
        </view>

        <!-- CB-050：设备控制（merchant:device:control；白名单 停售/恢复售卖/重启，指令留平台审计） -->
        <view v-if="canControl" class="card" v-show="activeTab === 'settings'">
          <text class="section">设备控制</text>
          <text class="meta">远程开门请走补货任务；指令将记入平台审计日志</text>
          <view class="action-row">
            <app-button
              v-if="!salesLocked"
              class="app-btn-flex"
              :block="false"
              compact
              variant="ghost"
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

        <view v-if="canEditDevice" class="card" v-show="activeTab === 'settings'">
          <text class="section">柜机设置</text>
          <!-- 字段拼排（用户 2026-10-30）：显示名称 + 备注同一行两列，不一行一个 -->
          <view class="settings-grid">
            <view class="field">
              <text class="field-label">显示名称</text>
              <input v-model="formName" class="input" placeholder="如：1号柜" />
            </view>
            <view class="field">
              <text class="field-label">备注</text>
              <input v-model="formRemark" class="input" placeholder="选填运维备注" />
            </view>
          </view>
          <app-button
            :loading="saving"
            :label="saving ? '保存中…' : '保存设置'"
            @click="saveSettings"
          />
        </view>

        <!-- 将邑商品采集入口（第七章新流程）：仅将邑绑定柜机显示；旧采集模式/学习进度已退场 -->
        <view v-if="jiangyiBound" class="card" v-show="activeTab === 'goods'">
          <text class="section">将邑商品采集</text>
          <text class="meta capture-tip">
            新商品采集走手机拍照：在本小程序「商品采集」页按条码拍摄提交，由将邑审图；柜内采集在运营后台设备将邑卡操作。
          </text>
          <app-button v-if="canEditDevice" label="商品采集（手机拍照）" @click="goJiangyiCapture" />
        </view>

        <view class="card" v-show="activeTab === 'goods'">
          <view class="row">
            <text class="section">在架商品</text>
            <text class="meta">按商品备货，库存为台账在架数</text>
          </view>
          <!-- 在架商品走台账（skuInventory：商品名+库存/容量，与建议、补货同一口径） -->
          <!-- 2026-10-28 用户拍板：在架商品每种独占一行并带商品图（原 3 列格子太挤、没图）。
               图来自 SKU 目录 imageUrl（DeviceInventoryDto 顺路带出）；缺图用名称首字兜底。 -->
          <view class="goods-list">
            <view v-for="s in ledgerGoods" :key="s.skuId" class="goods-row">
              <image
                v-if="s.imageUrl"
                class="goods-img"
                :src="s.imageUrl"
                mode="aspectFill"
                lazy-load
              />
              <view v-else class="goods-img goods-img-fallback">
                <text class="goods-img-fallback-text">{{
                  (s.skuName || s.skuId || '?').slice(0, 1)
                }}</text>
              </view>
              <view class="goods-info">
                <view class="goods-title-row">
                  <text class="goods-name">{{ s.skuName || s.skuId }}</text>
                  <text v-if="stockLevelOf(s) === 'OOS'" class="goods-badge goods-badge-oos"
                    >缺货</text
                  >
                  <text v-else-if="stockLevelOf(s) === 'LOW'" class="goods-badge goods-badge-low"
                    >低库存</text
                  >
                </view>
                <text class="meta">库存 {{ s.quantity }}/{{ s.capacity || '未设上限' }}</text>
              </view>
            </view>
            <text v-if="!ledgerGoods.length" class="meta">暂无在架商品</text>
          </view>
        </view>

        <view v-if="canAnalytics && velocity.length" class="card" v-show="activeTab === 'overview'">
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

        <!-- CB-077：设备侧故障记录（固件自报，如摄像头打开失败）。有故障才显示——
             没故障时不占版面；跨重启重复出现=硬件级，提示联系平台/厂商检修。 -->
        <view v-if="faults.length" class="card" v-show="activeTab === 'overview'">
          <view class="row">
            <text class="section">故障记录（设备自报）</text>
            <text class="meta">最近 {{ faults.length }} 条</text>
          </view>
          <view v-for="f in faults" :key="f.id" class="velocity-row">
            <view class="velocity-main">
              <text class="sku-name" :class="{ 'fault-critical': isCriticalFault(f) }">{{
                f.errorMsg || '（无描述）'
              }}</text>
              <text class="meta">{{ formatFaultTime(f.reportedAt) }}</text>
            </view>
            <view class="velocity-data">
              <text v-if="f.errorCode">{{ f.errorCode }}</text>
              <text v-if="f.orderNo" class="meta">单号 {{ f.orderNo }}</text>
            </view>
          </view>
          <view class="fault-hint">
            <text>故障由柜机自动上报；同一故障反复出现请反馈平台安排现场检修。</text>
          </view>
        </view>
      </view>

      <!-- H5 可访问确认框：设备控制指令下发前确认 -->
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
import type { DeviceFaultOrderItem } from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import {
  clearPreferredDeviceId,
  getPreferredDeviceId,
  setPreferredDeviceId
} from '@/utils/preferred-device';
import { confirmOpenDeviceNavigation } from '@/utils/open-device-navigation';
import { resolveMerchantIdForDevice } from '@/utils/device-settings';
import { UI_COPY, onlineLabel } from '@aicabinet/shared-uni/ui-copy';
import { centsToYuanDigits, formatDateTime } from '@aicabinet/shared-uni/format';
import { absoluteImageUrl, thumbUrl } from '@aicabinet/shared-uni/product-image';
import AppConfirmDialog from '@/components/AppConfirmDialog.vue';
import { useAppConfirmDialog } from '@/composables/useAppConfirmDialog';
import type {
  MerchantDeviceInfo,
  MerchantSkuVelocity,
  OpenApiMerchantDeviceSettingsDto,
  OpenApiUpdateMerchantDeviceSettingsRequest
} from '@aicabinet/shared-types';

const { me, meFromServer, refresh: refreshMe } = useMerchantMe();
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
const formName = ref('');
const formRemark = ref('');
const saving = ref(false);
const velocity = ref<MerchantSkuVelocity[]>([]);
// CB-077：设备侧故障记录（固件自报）。有故障才渲染卡片。
const faults = ref<DeviceFaultOrderItem[]>([]);
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

// 将邑绑定判定（开门柜判据，与盘点/补货同源）：非将邑柜 bound=false → 采集入口卡隐藏。
// 旧采集模式（进出/学习进度）已随旧学习链退场，这里只读 bound。
const jiangyiBound = ref(false);

function goJiangyiCapture() {
  uni.navigateTo({ url: '/pages/jiangyi-capture/jiangyi-capture' });
}

// ===== 选项式详情（2026-10-27 用户拍板）：概览 / 商品 / 设置（旧「采集」页签随旧学习链退场） =====
type DetailTab = 'overview' | 'goods' | 'settings';
const activeTab = ref<DetailTab>('overview');
function onDetailTab(key: string) {
  const t = key as DetailTab;
  if (t === 'overview' || t === 'goods' || t === 'settings') {
    activeTab.value = t;
  }
}
const detailTabs = computed(() => {
  // 页签文本按等分格宽度收短，全称留在 ariaLabel 与卡片内一级标题里
  const tabs: { key: DetailTab; label: string; ariaLabel: string }[] = [
    { key: 'overview', label: '概览', ariaLabel: '概览' },
    { key: 'goods', label: '商品', ariaLabel: '在架商品' },
    { key: 'settings', label: '设置', ariaLabel: '设置' }
  ];
  return tabs;
});
type LedgerGood = {
  skuId: string;
  skuName?: string | null;
  quantity?: number;
  capacity?: number;
  lowThreshold?: number;
  imageUrl?: string | null;
};
const ledgerGoods = ref<LedgerGood[]>([]);
/**
 * 缺货/低库存两档（CB-076，与后台 SkuStockLevel.of 同一条判据）：
 * 缺货 = 可售 ≤ 0；低库存 = 0 < 可售 ≤ 该商品低库存阈值。
 */
function stockLevelOf(g: { quantity?: number; lowThreshold?: number }): 'OOS' | 'LOW' | 'OK' {
  const qty = Number(g.quantity ?? 0);
  if (qty <= 0) return 'OOS';
  return qty <= Math.max(Number(g.lowThreshold ?? 0), 0) ? 'LOW' : 'OK';
}
const { confirmDialog, askConfirm, resolveConfirm } = useAppConfirmDialog();

const lifecycleLabel = computed(() =>
  lifecycleStatus.value
    ? dictLabel('device_lifecycle', lifecycleStatus.value) || lifecycleStatus.value
    : ''
);
const goodsStockHint = computed(() => {
  // 提示由台账商品算，口径与后台设备详情 KPI 一致
  const goodsOos = ledgerGoods.value.filter((g) => stockLevelOf(g) === 'OOS').length;
  const goodsLow = ledgerGoods.value.filter((g) => stockLevelOf(g) === 'LOW').length;
  if (goodsOos > 0 && goodsLow > 0) return `缺货 ${goodsOos} 个商品 · 低库存 ${goodsLow} 个商品`;
  if (goodsOos > 0) return `缺货 ${goodsOos} 个商品`;
  if (goodsLow > 0) return `低库存 ${goodsLow} 个商品`;
  return '';
});

const canView = computed(() => hasPerm(me.value, 'merchant:devices:detail'));
const canEditDevice = computed(() => hasPerm(me.value, 'merchant:devices:edit'));
const canReplenishView = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));
const canAnalytics = computed(() => hasPerm(me.value, 'merchant:analytics:view'));
const canControl = computed(() => hasPerm(me.value, 'merchant:device:control'));

/** CB-050 扩展：快捷操作入口 */
function goTallyRecords() {
  uni.navigateTo({
    url: `/pages/tally-records/tally-records?deviceId=${encodeURIComponent(deviceId.value)}&name=${encodeURIComponent(deviceName.value)}`
  });
}

const sceneText = computed(() =>
  sceneType.value ? dictLabel('device_scene_type', sceneType.value) || sceneType.value : ''
);
const assignedAtText = computed(() => (deployedAt.value ? formatDateTime(deployedAt.value) : ''));

// ===== CB-077：故障记录辅助 =====
/** 摄像头类故障标红：识别开门柜摄像头故障=柜子不可用（2026-10-30 CQYB10720 实锤）。 */
function isCriticalFault(f: DeviceFaultOrderItem): boolean {
  return /摄像头|camera/i.test(f.errorMsg ?? '');
}
function formatFaultTime(reportedAt: string): string {
  try {
    return formatDateTime(reportedAt);
  } catch {
    return reportedAt;
  }
}
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
  formName.value = settings.deviceName || '';
  formRemark.value = settings.opsRemark || '';
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
  // 将邑绑定判定只读（canView 已在此处为真）；非将邑柜机接口回 404 → 软兜底隐藏采集入口
  const [vel, gather, detail, kpi, faultRows] = await Promise.all([
    allowVelocity
      ? softFallback(merchantApi.skuVelocity(deviceId.value), [] as MerchantSkuVelocity[], '动销')
      : Promise.resolve([] as MerchantSkuVelocity[]),
    softFallback(
      merchantApi.jiangyiGatherStatus(deviceId.value),
      {
        bound: false,
        gatherLocked: false
      } as import('@aicabinet/shared-types').OpenApiMerchantGatherStatusDto,
      '将邑绑定状态'
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
        ),
    // CB-077：故障单（固件自报）。接口失败软兜底为空数组=卡片不显示，不阻塞详情页
    softFallback(
      merchantApi.deviceFaultOrders(deviceId.value),
      [] as DeviceFaultOrderItem[],
      '故障记录'
    )
  ]);
  if (seq !== loadSeq) return;
  velocity.value = vel;
  faults.value = faultRows;
  jiangyiBound.value = !!gather.bound;
  const inventoryRows = (
    detail as {
      skuInventory?: LedgerGood[];
    } | null
  )?.skuInventory;
  ledgerGoods.value = (Array.isArray(inventoryRows) ? inventoryRows : []).map((g) => ({
    ...g,
    // 后台存的是 /api/v2/media/... 相对路径，<image> 不吃相对地址（同 device-stocktake）
    imageUrl: thumbUrl(g.imageUrl, 200) || null
  }));
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

/** 快捷操作：跳货柜订单（订单页支持 deviceId 预置筛选） */
function goOrders() {
  uni.navigateTo({
    url: `/pages/orders/orders?deviceId=${encodeURIComponent(deviceId.value)}`
  });
}

async function refreshDeviceDetailMe(seq: number): Promise<boolean> {
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return false;
    seedMerchantMeDisplayCache(me);
  }
  if (seq !== loadSeq) return false;
  // R3-B-R3-4/R4-B-新3：弱网不可知 ≠「无柜机详情权限」
  if (!meFromServer.value) {
    loading.value = false;
    error.value = '网络异常，暂时无法确认权限，请重试';
    return false;
  }
  return true;
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
  // 目标温度已下线（控制不了温度，2026-10-28 用户拍板）——不再提交 targetTempC。
  const body: OpenApiUpdateMerchantDeviceSettingsRequest = {
    deviceName: formName.value.trim() || undefined,
    opsRemark: formRemark.value.trim() ?? undefined
  };
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

/* CB-077：故障记录。摄像头类标红 + 提示行，间距放卡片自身（铁律 39）。 */
.velocity-main .sku-name.fault-critical {
  color: var(--danger, #dc2626);
}
.fault-hint {
  margin-top: 12rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  line-height: 1.6;
}

.head-main {
  display: flex;
  align-items: flex-start;
  gap: 20rpx;
}
.device-thumb {
  width: 132rpx;
  height: 132rpx;
  border-radius: 16rpx;
  background: var(--brand-soft);
  flex-shrink: 0;
}
.head-id {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8rpx;
}
.title {
  font-size: var(--font-size-xl);
  font-weight: 600;
  display: block;
}
.info-grid {
  display: flex;
  flex-wrap: wrap;
  row-gap: 14rpx;
  margin-top: 20rpx;
  padding-top: 18rpx;
  border-top: 1rpx solid var(--color-border);
}
.info-cell {
  width: 50%;
  display: flex;
  flex-direction: column;
  gap: 2rpx;
}
.info-label {
  font-size: var(--font-size-caption);
  color: var(--text-subtle);
}
.info-val {
  font-size: var(--font-size-sm);
  color: var(--text-primary, #0f172a);
}
.head-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-top: 18rpx;
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
.meta-line.stock-warn {
  margin-top: 16rpx;
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
.capture-tip {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--text-muted, #475569);
  line-height: 1.5;
  margin-bottom: 12rpx;
}
.field {
  margin: 12rpx 0;
}
/* 柜机设置：显示名称 + 备注拼排两列 */
.settings-grid {
  display: flex;
  gap: 16rpx;
}
.settings-grid .field {
  flex: 1 1 0;
  min-width: 0;
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
/* 表单输入统一（88rpx + --radius-control + #f1f5f9），与 purchase/tax/team 等页一致 */
.input {
  display: block;
  width: 100%;
  height: 88rpx;
  min-height: 88rpx;
  line-height: 88rpx;
  box-sizing: border-box;
  background: #f1f5f9;
  border: 1rpx solid var(--color-border);
  border-radius: var(--radius-control, 12px);
  padding: 0 24rpx;
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
/* 在架商品行式列表（2026-10-28）：每商品独占一行 + 商品图；只服务开门柜分支 */
.goods-list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-top: 12rpx;
}
.goods-row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  border: 1px solid var(--card-border, #e2e8f0);
  border-radius: 12rpx;
  padding: 16rpx;
  background: var(--white, #ffffff);
  box-sizing: border-box;
}
.goods-img {
  width: 96rpx;
  height: 96rpx;
  border-radius: 8rpx;
  flex-shrink: 0;
  background: var(--bg-secondary, #f1f5f9);
}
.goods-img-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
}
.goods-img-fallback-text {
  font-size: 40rpx;
  color: var(--text-muted, #64748b);
}
.goods-info {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  min-width: 0;
  flex: 1;
}
.goods-title-row {
  display: flex;
  align-items: center;
  gap: 8rpx;
  min-width: 0;
}
.goods-badge {
  flex-shrink: 0;
  font-size: var(--font-size-xs, 22rpx);
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
  line-height: 1.4;
}
.goods-badge-oos {
  background: #fee2e2;
  color: #b91c1c;
}
.goods-badge-low {
  background: #fef3c7;
  color: #b45309;
}
.goods-name {
  font-size: var(--font-size-body, 28rpx);
  color: var(--text-primary, #0f172a);
  line-height: 1.3;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

/* —— 统一卡片度量（与 shared-uni/theme.css 的 ui-card 同口径；只动圆角/阴影） —— */
.card {
  border-radius: 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(15, 23, 42, 0.04);
}
</style>
