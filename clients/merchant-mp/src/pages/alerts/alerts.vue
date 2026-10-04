<template>
  <view class="alerts-page">
    <view class="nav-place" :style="navPlaceStyle">
      <app-nav-bar title="待办" home-url="/pages/home/home" />
    </view>
    <view v-if="deviceChips.length" class="device-tab-bar">
      <app-underline-tabs
        :items="deviceTabItems"
        :value="deviceFilter"
        layout="scroll"
        @change="setAlertDeviceFilter"
      />
    </view>
    <view class="kpi-grid">
      <view
        role="button"
        class="kpi-card dispute"
        :class="{ active: categoryFilter === 'dispute' }"
        hover-class="kpi-card-hover"
        aria-label="审核待办"
        @click="toggleKpi('dispute')"
      >
        <text class="n">{{ counts.disputes }}</text>
        <text class="l">审核</text>
      </view>
      <view
        role="button"
        class="kpi-card offline"
        :class="{ active: categoryFilter === 'offline' }"
        hover-class="kpi-card-hover"
        aria-label="故障待办"
        @click="toggleKpi('offline')"
      >
        <text class="n">{{ counts.offline }}</text>
        <text class="l">故障</text>
      </view>
      <view
        role="button"
        class="kpi-card stock"
        :class="{ active: categoryFilter === 'stock' }"
        hover-class="kpi-card-hover"
        aria-label="库存待办"
        @click="toggleKpi('stock')"
      >
        <text class="n">{{ counts.lowStock }}</text>
        <text class="l">库存</text>
      </view>
      <view
        role="button"
        class="kpi-card expiry"
        :class="{ active: categoryFilter === 'expiry' }"
        hover-class="kpi-card-hover"
        aria-label="临期待办"
        @click="toggleKpi('expiry')"
      >
        <text class="n">{{ counts.expiry }}</text>
        <text class="l">临期</text>
      </view>
    </view>

    <view v-if="loading && !items.length" class="card">{{ UI_COPY.loading }}</view>
    <error-state v-else-if="error && !items.length" :title="error" @retry="load" />
    <view v-else>
      <view
        v-for="(a, i) in visibleItems"
        :key="a.exceptionId || a.ticketId || `${a.type}-${a.deviceId}-${i}`"
        class="card alert-card"
        hover-class="alert-card-hover"
        role="button"
        @click="handleItem(a)"
      >
        <text class="tag" :class="tagClass(a.type)">{{ a.typeLabel }}</text>
        <text class="title">{{ sanitizeNotifyTitle(a.title) }}</text>
        <text v-if="a.deviceId" class="meta">柜机 {{ deviceLabel(a.deviceId) }}</text>
        <text v-if="a.detail" class="meta">{{ sanitizeNotifyTitle(a.detail) }}</text>
        <text v-if="a.dueAt" class="meta due" :class="{ overdue: isOverdue(a.dueAt) }">{{
          dueText(a.dueAt)
        }}</text>
        <text v-if="a.severity" class="meta sev">优先级 {{ severityText(a.severity) }}</text>
        <text v-if="actionHint(a)" class="action app-link-chevron">{{ actionHint(a) }}</text>
        <button
          v-if="canResolveInventory && a.exceptionId && isInventoryException(a.type)"
          class="resolve-btn"
          @click.stop="resolveInventory(a)"
        >
          完成库存核对
        </button>
      </view>
      <empty-state
        v-if="!visibleItems.length"
        kind="alerts"
        icon="/static/menu/check-circle.png"
        :title="categoryFilter ? '该类暂无待办' : '暂无待办事项'"
        :hint="
          categoryFilter
            ? '再点上方卡片可取消筛选'
            : '争议、离线、低库存与临期告警都会集中显示在这里'
        "
      >
        <app-button
          v-if="categoryFilter"
          label="查看全部"
          @click="categoryFilter = ''"
        />
        <app-button v-else label="查看柜机" @click="goDevices" />
      </empty-state>

      <view v-if="visibleSlotDiscrepancies.length" class="card section-card">
        <text class="section-title">货道差异（账实不符）</text>
        <view v-for="(s, i) in visibleSlotDiscrepancies" :key="i" class="slot-row">
          <view class="slot-main">
            <text class="slot-name">{{ s.deviceName || s.deviceId }} · {{ s.slotCode }}</text>
            <text class="slot-sku">{{ s.assignedSkuName || s.assignedSkuId || '未绑定商品' }}</text>
          </view>
          <text class="slot-diff"
            >账 {{ s.bookQty }} / 实 {{ s.physicalQty }} · 差 {{ s.qtyDiff }}</text
          >
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { showError, showSuccess } from '@/utils/notify';
import { computed, ref } from 'vue';
import EmptyState from '@aicabinet/shared-uni/components/empty-state.vue';
import { hasPerm, merchantApi, softFallback, isMerchantLoggedIn } from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { useAutoRefresh } from '@/composables/use-auto-refresh';
import { getPreferredDeviceId } from '@/utils/preferred-device';
import { promptText } from '@/utils/text-prompt';
import { setAlertsTabBadge } from '@/utils/todo-badge';
import {
  countTodoCategories,
  matchTodoKpiCategory,
  mergeTodoItems,
  type TodoKpiKey
} from '@/utils/todo-list';
import type { MerchantMe, OpenApiSlotDiscrepancyAlertDto } from '@aicabinet/shared-types';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import { sanitizeNotifyTitle } from '@aicabinet/shared-uni/format';
import { getCustomNavPlaceStyle } from '@aicabinet/shared-uni/status-bar';

const navPlaceStyle = getCustomNavPlaceStyle();
const { me, refresh: refreshMe } = useMerchantMe();
const canViewAlerts = computed(() => hasPerm(me.value, 'merchant:alerts:view'));
const canResolveInventory = computed(() => hasPerm(me.value, 'merchant:inventory:view'));

const loading = ref(true);
const error = ref('');
const preferredId = ref('');
const onlyPreferred = ref(false);
const slotDiscrepancies = ref<OpenApiSlotDiscrepancyAlertDto[]>([]);
type AlertRow = {
  type: string;
  typeLabel: string;
  title: string;
  detail: string;
  deviceId?: string;
  ticketId?: string;
  exceptionId?: string;
  dueAt?: string;
  severity?: string;
};
const items = ref<AlertRow[]>([]);
let loadSeq = 0;

const deviceFilter = ref('');
/** 点四卡筛下方列表；再点同一卡取消 */
const categoryFilter = ref<TodoKpiKey | ''>('');
const deviceNames = ref<Record<string, string>>({});

const deviceChips = computed(() => {
  const ids = new Set<string>();
  for (const a of items.value) {
    if (a.deviceId) ids.add(a.deviceId);
  }
  for (const s of slotDiscrepancies.value) {
    if (s.deviceId) ids.add(s.deviceId);
  }
  return [...ids].map((id) => ({ deviceId: id, label: deviceNames.value[id] || id }));
});

const deviceTabItems = computed(() => [
  { key: '', label: '全部' },
  ...deviceChips.value.map((c) => ({ key: c.deviceId, label: c.label }))
]);

function setAlertDeviceFilter(key: string) {
  deviceFilter.value = key;
}

function deviceLabel(deviceId?: string) {
  if (!deviceId) return '';
  return deviceNames.value[deviceId] || deviceId;
}

function matchDeviceFilter(deviceId?: string) {
  // 全部：不过滤；选中某柜：只计该柜（无柜归属只出现在「全部」，避免切换 chip 数字不动）
  if (!deviceFilter.value) return true;
  return !!deviceId && deviceId === deviceFilter.value;
}

const deviceFilteredItems = computed(() =>
  items.value.filter((a) => matchDeviceFilter(a.deviceId))
);

const visibleItems = computed(() => {
  const rows = deviceFilteredItems.value;
  if (!categoryFilter.value) return rows;
  return rows.filter((a) => matchTodoKpiCategory(a.type, categoryFilter.value as TodoKpiKey));
});

const visibleSlotDiscrepancies = computed(() => {
  // 货道差异归库存类；选了其它四卡时隐藏
  if (categoryFilter.value && categoryFilter.value !== 'stock') return [];
  return slotDiscrepancies.value.filter((s) => matchDeviceFilter(s.deviceId));
});

/** 四卡数字只跟柜机 chip，不跟类别筛选（避免点卡后其它卡变 0） */
const counts = computed(() => countTodoCategories(deviceFilteredItems.value));

function isOverdue(dueAt?: string) {
  if (!dueAt) return false;
  const t = new Date(dueAt).getTime();
  return Number.isFinite(t) && t < Date.now();
}

function dueText(dueAt?: string) {
  if (!dueAt) return '';
  const d = new Date(dueAt);
  if (Number.isNaN(d.getTime())) return `时限 ${dueAt}`;
  const p = (n: number) => String(n).padStart(2, '0');
  const label = `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  return isOverdue(dueAt) ? `已超时 · ${label}` : `截止 ${label}`;
}

function severityText(sev?: string) {
  const s = String(sev || '').toUpperCase();
  if (s === 'HIGH' || s === 'CRITICAL') return '高';
  if (s === 'MEDIUM') return '中';
  if (s === 'LOW') return '低';
  return sev || '';
}

function tagClass(type: string) {
  const t = String(type || '').toUpperCase();
  if (t === 'DISPUTE' || t.startsWith('RECOGNITION')) return 'dispute';
  if (
    t === 'DEVICE_OFFLINE' ||
    t === 'DEVICE_FAULT' ||
    t === 'SALES_LOCKED' ||
    t === 'UPLOAD_STUCK'
  )
    return 'offline';
  if (t === 'DOOR_OPEN_TOO_LONG') return 'offline';
  if (t === 'LOW_STOCK' || t === 'REPLENISHMENT' || t === 'REPLENISHMENT_REQUIRED') return 'stock';
  if (t === 'EXPIRY') return 'expiry';
  return 'default';
}

function actionHint(item: { type: string; deviceId?: string; ticketId?: string }) {
  const type = String(item.type || '').toUpperCase();
  // 「无权限=看不见」：无争议查看权限时入口不出现争议字样（点击走柜机详情）
  if (type === 'DISPUTE') {
    if (!hasPerm(me.value, 'merchant:disputes:view')) return item.deviceId ? '查看柜机' : '';
    return item.ticketId ? '去处理争议' : '查看争议';
  }
  if (type.startsWith('RECOGNITION')) {
    if (!hasPerm(me.value, 'merchant:disputes:view')) return item.deviceId ? '查看柜机' : '';
    return item.deviceId ? '查看柜机' : '查看争议';
  }
  if (type === 'EXPIRY') return '去处理临期任务';
  if (type === 'LOW_STOCK') return '去发起要货';
  if (type === 'REPLENISHMENT' || type === 'REPLENISHMENT_REQUIRED') return '去补货任务';
  if (type === 'DEVICE_OFFLINE' || type === 'DEVICE_FAULT' || type === 'SALES_LOCKED')
    return '查看柜机';
  if (item.deviceId) return '查看柜机';
  return '查看详情';
}

async function load() {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const seq = ++loadSeq;
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return;
    seedMerchantMeDisplayCache(me);
  }
  if (seq !== loadSeq) return;
  if (!me.value) {
    seedMerchantMeDisplayCache(me);
  }
  if (!canViewAlerts.value) {
    showError('无待办权限');
    uni.switchTab({ url: '/pages/home/home' });
    return;
  }
  preferredId.value = getPreferredDeviceId();
  // 已有列表时静默刷新，避免 Tab 切换时整页先缩成「加载中」再撑开（先小后大）
  if (!items.value.length) loading.value = true;
  error.value = '';
  try {
    const [wb, exceptionPage, expiryRows, slotRows, deviceRows] = await Promise.all([
      softFallback(
        merchantApi.workbench(),
        {
          offlineDevices: 0,
          openDisputes: 0,
          lowStockItems: 0,
          expiryAlerts: 0,
          slotDiscrepancies: 0,
          actionItems: [] as {
            type: string;
            title: string;
            detail?: string;
            deviceId?: string;
            ticketId?: string;
            exceptionId?: string;
          }[]
        },
        '工作台'
      ),
      softFallback(merchantApi.openExceptions(100), { items: [], total: 0 }, '异常列表'),
      softFallback(merchantApi.expiryAlerts(), [], '效期告警'),
      softFallback(
        merchantApi.slotDiscrepancies(),
        [] as OpenApiSlotDiscrepancyAlertDto[],
        '货道差异'
      ),
      // F1-UX：柜名映射——待办卡与筛选 chips 显示柜机名称而非 12 位编码
      softFallback(merchantApi.devices(), [], '柜机列表')
    ]);
    const nameMap: Record<string, string> = {};
    for (const d of deviceRows || []) {
      if (d.deviceId) nameMap[d.deviceId] = d.deviceName || d.deviceId;
    }
    deviceNames.value = nameMap;
    if (seq !== loadSeq) return;
    const deduped = mergeTodoItems({
      exceptions: exceptionPage.items || [],
      actionItems: (wb.actionItems || []).map((a) => ({
        type: String(a.type || ''),
        title: String(a.title || ''),
        detail: a.detail,
        deviceId: a.deviceId,
        ticketId: a.ticketId,
        exceptionId: (a as { exceptionId?: string }).exceptionId,
        dueAt: (a as { dueAt?: string }).dueAt,
        severity: (a as { severity?: string }).severity
      })),
      expiryRows: expiryRows || []
    });
    items.value = deduped;
    slotDiscrepancies.value = slotRows || [];
    // Tab 徽标始终按「全部」合计（与 useHomeWorkbench 同源函数），不随 chip 筛选缩小
    const all = countTodoCategories(deduped);
    setAlertsTabBadge(all.disputes + all.offline + all.lowStock + all.expiry);
  } catch (e) {
    if (seq !== loadSeq) return;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function handleItem(item: {
  type?: string;
  deviceId?: string;
  ticketId?: string;
  exceptionId?: string;
}) {
  const type = String(item.type || '').toUpperCase();
  const canDisputes = hasPerm(me.value, 'merchant:disputes:view');
  if (type === 'DISPUTE') {
    // 「无权限=看不见」：无争议权限时改走柜机详情（补货员可处理柜端）
    if (!canDisputes) {
      if (item.deviceId) {
        uni.navigateTo({
          url: `/pages/device-detail/device-detail?id=${encodeURIComponent(item.deviceId)}`
        });
      }
      return;
    }
    uni.navigateTo({ url: '/pages/disputes/disputes' });
    return;
  }
  if (type.startsWith('RECOGNITION')) {
    // 识别存疑：有柜机则看柜机详情；无柜机且有争议权限才进争议列表
    if (item.deviceId) {
      uni.navigateTo({
        url: `/pages/device-detail/device-detail?id=${encodeURIComponent(item.deviceId)}`
      });
      return;
    }
    if (canDisputes) {
      uni.navigateTo({ url: '/pages/disputes/disputes' });
    }
    return;
  }
  if (type === 'EXPIRY' || type === 'REPLENISHMENT' || type === 'REPLENISHMENT_REQUIRED') {
    const q = item.deviceId ? `?deviceId=${encodeURIComponent(item.deviceId)}` : '';
    uni.navigateTo({ url: `/pages/replenishment/replenishment${q}` });
    return;
  }
  if (type === 'LOW_STOCK') {
    const q = item.deviceId ? `?deviceId=${encodeURIComponent(item.deviceId)}` : '';
    uni.navigateTo({ url: `/pages/request/request${q}` });
    return;
  }
  if (item.deviceId) {
    uni.navigateTo({
      url: `/pages/device-detail/device-detail?id=${encodeURIComponent(item.deviceId)}`
    });
    return;
  }
  showError('暂无跳转目标');
}

function goDevices() {
  uni.switchTab({ url: '/pages/devices/devices' });
}

/** 点四卡：筛本页列表；再点取消。具体跳转仍点下方待办行。 */
function toggleKpi(key: TodoKpiKey) {
  categoryFilter.value = categoryFilter.value === key ? '' : key;
}

function isInventoryException(type: string) {
  return ['INVENTORY_MISMATCH', 'LOW_STOCK', 'REPLENISHMENT_REQUIRED'].includes(
    String(type || '').toUpperCase()
  );
}

async function resolveInventory(item: { exceptionId?: string; deviceId?: string }) {
  if (!item.exceptionId) return;
  if (!canResolveInventory.value) {
    showError('无库存处理权限');
    return;
  }
  const resolution = await promptText({
    title: '确认完成库存核对',
    hint: '请填写盘点结果或补货说明，便于后台留痕',
    placeholder: '填写盘点结果或补货说明',
    required: true,
    requiredMessage: '必须填写处理结果',
    maxLength: 200,
    testId: 'inventory-resolve-prompt'
  });
  if (resolution == null) return;
  try {
    await merchantApi.resolveInventoryException(item.exceptionId!, resolution);
    showSuccess('库存异常已处理');
    await load();
  } catch (e) {
    showError(e instanceof Error ? e.message : '处理失败');
  }
}

onShow(load);
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));

/**
 * 待办由系统持续派发（低库存 / 设备离线 / 盘点差异 / 争议），停在待办页时新任务
 * 不会自己出现 ⇒ 每 10 秒静默跟进一次。
 *
 * 与其它页不同，这里**没有「终态」可等**：空列表恰恰是「在等第一个待办」的状态，
 * 所以 shouldContinue 恒真，由 maxDurationMs 封顶（5 分钟）。切后台即停表，
 * 回前台重新计时 —— 避免用户挂机时无限烧接口。
 * load() 已有列表时不闪 loading，也不重置 onlyPreferred 本地筛选。
 */
useAutoRefresh({
  intervalMs: 10_000,
  load,
  shouldContinue: () => true,
  maxDurationMs: 300_000,
  canRefresh: () => !loading.value && isMerchantLoggedIn()
});
</script>

<style scoped>
.alerts-page {
  min-height: 100%;
  padding: 0 0 calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  background: var(--page-tint, #f0fdfa);
}
.nav-place {
  width: 100%;
}
.section-card {
  margin-top: 18rpx;
}
.section-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--brand-deep, #134e4a);
  margin-bottom: 12rpx;
}
.slot-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 14rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f1f5f9);
}
.slot-row:last-child {
  border-bottom: none;
}
.slot-main {
  flex: 1;
  min-width: 0;
}
.slot-name {
  display: block;
  font-size: var(--font-size-body);
  font-weight: 650;
}
.slot-sku {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
}
.slot-diff {
  font-size: var(--font-size-caption);
  font-weight: 700;
  color: var(--warning, #b45309);
}

.pref-bar {
  display: none;
}
.device-tab-bar {
  background: var(--card-bg, #fff);
}
.pref-toggle {
  color: var(--text-muted);
  text-decoration: underline;
}
.kpi-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12rpx;
  margin: 12rpx 24rpx 24rpx;
}
.kpi-card {
  border-radius: var(--radius-panel);
  padding: 22rpx 16rpx;
  text-align: center;
  background: var(--card-bg, #fff);
  border: 1rpx solid var(--color-border);
  box-shadow: 0 4rpx 14rpx rgba(15, 118, 110, 0.04);
}
.kpi-card-hover {
  opacity: 0.88;
  background: var(--page-bg, #f8fafc) !important;
}
.kpi-card.active {
  border-color: var(--brand, #0f766e);
  box-shadow: 0 0 0 2rpx var(--brand-mist, #99f6e4);
  background: var(--brand-soft, #ecfdf5);
}
.kpi-card .n {
  color: var(--brand-deep, #134e4a);
}
.kpi-card.dispute .n {
  color: var(--color-danger);
}
.kpi-card.offline .n {
  color: var(--text-muted, #475569);
}
.kpi-card.stock .n {
  color: var(--warning, #d97706);
}
.kpi-card.expiry .n {
  color: var(--brand, #0f766e);
}
.n {
  font-size: var(--font-size-h2);
  font-weight: 700;
  display: block;
}
.l {
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  margin-top: 4rpx;
  display: block;
}
/* 无 page-body：卡片水平 gutter 由页面承担（对齐 M02） */
.alerts-page > .card,
.alerts-page .alert-card {
  margin-left: var(--page-gutter, 24rpx);
  margin-right: var(--page-gutter, 24rpx);
  width: auto;
  max-width: none;
  box-sizing: border-box;
}
.alert-card {
  margin-top: 16rpx;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.alert-card:first-child {
  margin-top: 0;
}
.alert-card-hover {
  background: var(--page-bg, #f8fafc) !important;
  opacity: 0.96;
}
.tag {
  font-size: var(--font-size-xs);
  padding: 4rpx 12rpx;
  border-radius: var(--radius-tag);
  margin-right: 8rpx;
  pointer-events: none;
}
.tag.dispute {
  background: #f2d6d6;
  color: var(--color-danger);
}
.tag.offline {
  background: var(--color-border);
  color: var(--text-muted, #475569);
}
.tag.stock {
  background: var(--warning-soft);
  color: var(--warning, #d97706);
}
.tag.expiry {
  background: var(--brand-mist, #a7f3d0);
  color: var(--brand, #0f766e);
}
.tag.default {
  background: var(--color-border);
  color: var(--text-muted);
}
.title {
  font-weight: 600;
  display: block;
  margin-top: 8rpx;
  pointer-events: none;
}
.meta {
  display: block;
  margin-top: 6rpx;
  color: var(--text-muted);
  font-size: var(--font-size-caption);
  pointer-events: none;
}
.meta.due {
  color: var(--brand);
}
.meta.due.overdue {
  color: var(--color-danger);
  font-weight: 600;
}
.meta.sev {
  color: var(--warning, #b45309);
}
.action {
  color: var(--brand, #0f766e);
  font-size: var(--font-size-caption);
  display: block;
  margin-top: 12rpx;
  pointer-events: none;
}
.err {
  color: var(--color-danger);
  display: block;
}
.resolve-btn {
  margin-top: 14rpx;
  width: 100% !important;
  max-width: none !important;
  min-width: 0 !important;
  margin-left: 0 !important;
  margin-right: 0 !important;
  height: 72rpx;
  min-height: 72rpx;
  line-height: 1.2;
  border-radius: var(--radius-card);
  background: var(--brand, #0f766e);
  color: var(--white);
  border: 0;
  font-size: var(--font-size-body);
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
.resolve-btn::after {
  border: none;
}
</style>
