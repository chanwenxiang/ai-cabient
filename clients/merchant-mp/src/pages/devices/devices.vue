<template>
  <view class="page-root devices-page">
    <view class="nav-place" :style="navPlaceStyle">
      <app-nav-bar title="柜机" home-url="/pages/home/home" />
    </view>
    <view class="toolbar">
      <view
        class="tool-item is-primary"
        role="button"
        aria-label="扫码到柜"
        hover-class="tool-item-hover"
        :class="{ 'is-busy': scanning }"
        @click="onScan"
      >
        <text class="tool-label">{{ scanning ? '扫码中…' : '扫码到柜' }}</text>
      </view>
      <view
        v-if="canReplenishment"
        class="tool-item"
        role="button"
        aria-label="补货任务"
        hover-class="tool-item-hover"
        @click="goReplenishment"
      >
        <text class="tool-label">补货任务</text>
      </view>
    </view>
    <app-underline-tabs
      layout="auto"
      :items="deviceStatusTabs"
      :value="filter"
      @change="setDeviceFilter"
    />
    <view class="filters">
      <input
        v-model="keyword"
        class="search"
        confirm-type="search"
        aria-label="搜索柜机名称或编号"
        placeholder="搜索柜机名称或编号…"
      />
      <view v-if="preferredId" class="pref-hint">
        <text>常驻：{{ preferredLabel }}</text>
        <text role="button" aria-label="清除常驻柜" class="pref-clear" @click="clearPreferred"
          >清除</text
        >
      </view>
    </view>
    <list-skeleton v-if="loading && !devices.length" :rows="3" label="柜机" />
    <error-state
      v-else-if="error && !devices.length"
      :title="error"
      hint="请检查网络后重试"
      @retry="load"
    />
    <view v-else>
      <view
        v-for="d in visibleDevices"
        :key="d.deviceId"
        class="card device-card"
        hover-class="device-card-hover"
        role="button"
        @click="goDetail(d.deviceId)"
      >
        <view class="device-main">
          <view class="device-left">
            <view class="thumb-wrap">
              <image
                class="device-thumb"
                src="/static/device-default.png"
                mode="aspectFill"
                aria-hidden="true"
              />
              <view class="online-dot" :class="d.online ? 'on' : 'off'" />
            </view>
            <view class="device-info">
              <view class="name-row">
                <text class="name">{{ d.deviceName || d.deviceId }}</text>
                <text
                  class="star"
                  role="button"
                  :aria-label="preferredId === d.deviceId ? '取消常驻柜' : '设为常驻柜'"
                  :class="{ on: preferredId === d.deviceId }"
                  @click.stop="togglePreferred(d.deviceId)"
                  >★</text
                >
                <!-- 2026-11-10 重排：状态标签从右侧独立竖列并进名称行。原来那条竖列白占 96rpx，
                     正文和「导航」都被挤在卡片中间；并进来后正文可用整卡宽度，导航才能真贴右边。 -->
                <view class="device-tags">
                  <text v-if="d.salesLocked" class="tag tag-lock">{{ UI_COPY.salesLocked }}</text>
                  <text v-else-if="d.replenishmentInProgress" class="tag tag-warn">{{
                    UI_COPY.replenishing
                  }}</text>
                  <!-- 在线/离线常驻：2026-10-05 改卡片时把它做成三选一的末档，补货中/停售会把运行状态整个吞掉 -->
                  <text class="tag" :class="d.online ? 'tag-on' : 'tag-off'">{{
                    onlineLabel(!!d.online)
                  }}</text>
                </view>
              </view>
              <!-- 2026-11-10：编号（deviceId）不在卡片展示——点进详情即可见，列表卡片不重复。
                   2026-10-28：今日收入上移到名称下方醒目处（原在 chips 行之后的最底行） -->
              <text v-if="canSeeRevenue" class="meta revenue"
                >今日收入 {{ fmtMoney(revenueCentsByDevice[d.deviceId ?? ''] || 0) }}</text
              >
              <text v-if="d.address" class="meta addr">{{ d.address }}</text>
              <!-- 状态/经营指标合并为可换行 chips 行：此前三条竖排 meta 让卡片又高又散（2026-10-25 排版优化）。
                   2026-11-10：停售原因长句与固件版本不再展示——右上已有「停售」标签，原因属详情页信息，
                   卡片只留商户决策要看的内容；导航与这一行同排，底部不再另起一行，卡片不留空白。 -->
              <view class="stat-line">
                <view
                  v-if="
                    d.routeCode ||
                    lifecycleText(d.lifecycleStatus) ||
                    d.currentTempC != null ||
                    stockSummary(d) ||
                    cardMetricList(d).length
                  "
                  class="stat-row"
                >
                  <text v-if="d.routeCode" class="stat-chip">线路 {{ d.routeCode }}</text>
                  <text v-if="lifecycleText(d.lifecycleStatus)" class="stat-chip">{{
                    lifecycleText(d.lifecycleStatus)
                  }}</text>
                  <text v-if="d.currentTempC != null" class="stat-chip"
                    >{{ d.currentTempC }}°C</text
                  >
                  <text v-if="stockSummary(d)" class="stat-chip stat-chip-warn">{{
                    stockSummary(d)
                  }}</text>
                  <!-- CB-050 扩展：柜机卡片指标（填充率/最近核对/最近补货），逐条一个 chip -->
                  <text v-for="m in cardMetricList(d)" :key="m" class="stat-chip">{{ m }}</text>
                </view>
                <view
                  v-if="d.latitude != null && d.longitude != null"
                  class="nav-link app-link-chevron"
                  role="button"
                  aria-label="导航"
                  hover-class="nav-link-hover"
                  @click.stop="openNav(d)"
                  >导航</view
                >
              </view>
            </view>
          </view>
        </view>
      </view>
      <empty-state
        v-if="!visibleDevices.length"
        kind="devices"
        icon="/static/menu/cabinet.png"
        :title="emptyHint"
        hint="可切换筛选或扫码绑定常驻柜"
      />
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { showError, showSuccess } from '@/utils/notify';
import { computed, ref } from 'vue';
import EmptyState from '@aicabinet/shared-uni/components/empty-state.vue';
import { hasPerm, merchantApi, isMerchantLoggedIn } from '@/utils/merchant-api';
import { softFallback } from '@/utils/soft-fallback';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import { getOrgFilter } from '@/utils/org-filter';
import {
  clearPreferredDeviceId,
  getPreferredDeviceId,
  setPreferredDeviceId
} from '@/utils/preferred-device';
import { fmtMoney, formatDateTimeShort } from '@aicabinet/shared-uni/format';
import { dictLabel } from '@aicabinet/shared-dict';
import { confirmOpenDeviceNavigation } from '@/utils/open-device-navigation';
import type { MerchantDeviceInfo } from '@aicabinet/shared-types';
import { UI_COPY, onlineLabel } from '@aicabinet/shared-uni/ui-copy';
import { getCustomNavPlaceStyle } from '@aicabinet/shared-uni/status-bar';

const navPlaceStyle = getCustomNavPlaceStyle();
const { me, meFromServer, refresh: refreshMe } = useMerchantMe();
/** 今日营业额仅报表可见角色展示（店长/财务；店员/补货员不展示，权限口径 2026-09-30 与运营确认） */
const canSeeRevenue = computed(() =>
  (me.value?.permissions || []).includes('merchant:reports:view')
);
const revenueCentsByDevice = ref<Record<string, number>>({});
const canListDevices = computed(() => hasPerm(me.value, 'merchant:devices:list'));
const canReplenishment = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));

const loading = ref(true);
const scanning = ref(false);
const error = ref('');
let loadSeq = 0;
// /api/v2/merchant/devices 实际返回 MerchantDeviceDto（含 oosSkuCount/lifecycleStatus 等），
// 此前错标为 DeviceInfo(=AdminDeviceDto)，导致 stockSummary() 等读取被 vue-tsc 判为「无公共属性」
const devices = ref<(MerchantDeviceInfo & { online?: boolean })[]>([]);
const keyword = ref('');
const filter = ref<'all' | 'online' | 'offline' | 'locked'>('all');
const preferredId = ref('');
const filters = [
  { label: '全部', value: 'all' as const },
  { label: '在线', value: 'online' as const },
  { label: '离线', value: 'offline' as const },
  { label: '停售', value: 'locked' as const }
];

const preferredLabel = computed(() => {
  const hit = devices.value.find((d) => d.deviceId === preferredId.value);
  return hit?.deviceName || preferredId.value || '未设置';
});

// CB-050 扩展：组织切换上下文——选定组织后只看该组织的柜机（后端本就全树放行）
// 列表、chip 计数、空态必须都基于这一层，否则同屏出现「全部 4」与 1 张卡
const scopedDevices = computed(() => {
  const org = getOrgFilter();
  return org ? devices.value.filter((d) => d.merchantId === org) : devices.value;
});

const visibleDevices = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  const list = scopedDevices.value.filter((d) => {
    let statusMatch = true;
    if (filter.value === 'online') statusMatch = !!d.online;
    else if (filter.value === 'offline') statusMatch = !d.online;
    else if (filter.value === 'locked') statusMatch = !!d.salesLocked;
    const keywordMatch = !q || `${d.deviceName || ''} ${d.deviceId}`.toLowerCase().includes(q);
    return statusMatch && keywordMatch;
  });
  if (!preferredId.value) return list;
  return [...list].sort((a, b) => {
    if (a.deviceId === preferredId.value) return -1;
    if (b.deviceId === preferredId.value) return 1;
    return 0;
  });
});

const emptyHint = computed(() => (scopedDevices.value.length ? '没有符合条件的柜机' : '暂无柜机'));

function countFor(value: 'all' | 'online' | 'offline' | 'locked') {
  if (value === 'all') return scopedDevices.value.length;
  if (value === 'locked') return scopedDevices.value.filter((d) => !!d.salesLocked).length;
  return scopedDevices.value.filter((d) => (value === 'online' ? d.online : !d.online)).length;
}

const deviceStatusTabs = computed(() =>
  filters.map((f) => ({
    key: f.value,
    label: f.label,
    badge: countFor(f.value) || undefined
  }))
);

function setDeviceFilter(key: string) {
  filter.value = key as typeof filter.value;
}

function togglePreferred(id?: string) {
  if (!id) return;
  if (preferredId.value === id) {
    clearPreferredDeviceId();
    preferredId.value = '';
    showSuccess('已取消常驻');
    return;
  }
  setPreferredDeviceId(id);
  preferredId.value = id;
  showSuccess('已设为常驻柜');
}

function clearPreferred() {
  clearPreferredDeviceId();
  preferredId.value = '';
}

async function load() {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const seq = ++loadSeq;
  preferredId.value = getPreferredDeviceId();
  // R2-B9：refreshMe 失败会走去权缓存（permissions 清空）——那是「权限不知道」，
  // 不是「没有权限」。原来直接落到 !canListDevices 分支，把弱网报成「无柜机权限」
  // 并切回首页。只有压根没有内存态（me 为 null）时才算不可知。
  let permUnknown = false;
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return;
    permUnknown = !meFromServer.value;
    seedMerchantMeDisplayCache(me);
  }
  if (seq !== loadSeq) return;
  if (!me.value) {
    seedMerchantMeDisplayCache(me);
  }
  if (permUnknown) {
    loading.value = false;
    error.value = '网络异常，暂时无法确认柜机权限，请重试';
    return;
  }
  if (!canListDevices.value) {
    // 无权限分支此前 loading 永不复位：直连/深链进入时页面卡在「加载中」白屏（G2-12 ④ run2 实测）
    loading.value = false;
    showError('无柜机权限');
    uni.switchTab({ url: '/pages/home/home' });
    return;
  }
  // 已有柜机列表时静默刷新，避免 Tab 切换时列表先消失再撑开
  if (!devices.value.length) loading.value = true;
  error.value = '';
  try {
    const list = await merchantApi.devices();
    if (seq !== loadSeq) return;
    devices.value = list.map((d) => ({
      ...d,
      online: (d.onlineStatus || '').toUpperCase() === 'ONLINE'
    }));
    if (canSeeRevenue.value) {
      softFallback(merchantApi.deviceReports(), [], '报表')
        .then((rows) => {
          if (seq !== loadSeq) return;
          const map: Record<string, number> = {};
          for (const r of rows as { deviceId?: string; revenueTodayCents?: number }[]) {
            if (r.deviceId) map[r.deviceId] = Number(r.revenueTodayCents || 0);
          }
          revenueCentsByDevice.value = map;
        })
        .catch(() => {});
    }
  } catch (e) {
    if (seq !== loadSeq) return;
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function goDetail(id?: string) {
  // 生成类型里 deviceId 可选；缺 id 时原先会跳到 ?id=undefined
  if (!id) return;
  uni.navigateTo({ url: `/pages/device-detail/device-detail?id=${encodeURIComponent(id)}` });
}

function openNav(d: MerchantDeviceInfo) {
  void confirmOpenDeviceNavigation({
    latitude: d.latitude,
    longitude: d.longitude,
    name: d.deviceName,
    address: d.address,
    deviceId: d.deviceId
  });
}

function goReplenishment() {
  if (!canReplenishment.value) {
    showError('无补货权限');
    return;
  }
  uni.navigateTo({ url: '/pages/replenishment/replenishment' });
}

async function onScan() {
  if (scanning.value) return;
  scanning.value = true;
  try {
    const id = await scanCabinetDeviceId();
    if (!id) return;
    const key = id.trim().toUpperCase();
    const hit = devices.value.find(
      (d) =>
        String(d.deviceId || '')
          .trim()
          .toUpperCase() === key
    );
    if (!hit?.deviceId) {
      showError('未找到该柜机或无权限');
      return;
    }
    setPreferredDeviceId(hit.deviceId);
    preferredId.value = hit.deviceId;
    goDetail(hit.deviceId);
  } finally {
    scanning.value = false;
  }
}

onShow(load);
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));

function lifecycleText(status?: string) {
  if (!status) return '';
  return dictLabel('device_lifecycle', status) || '';
}

/**
 * CB-050 扩展：柜机卡片指标行（填充率/最近核对库存/最近补货，字段来自 MerchantDeviceDto）。
 * 2026-11-10：原来把它们用「·」拼成一个长胶囊，导航并进同一行后该胶囊被挤成省略号
 * （真机实测「最近补货 10/30 15:52」→「最近补货 10/30 1…」）。改为逐条独立 chip，
 * 各自可换行，信息不再被截断。
 */
function cardMetricList(d: {
  fillRatePct?: number | null;
  lastCheckAt?: string | null;
  lastRestockAt?: string | null;
}): string[] {
  const parts: string[] = [];
  if (d.fillRatePct != null) parts.push(`填充率 ${d.fillRatePct}%`);
  if (d.lastCheckAt) parts.push(`最近核对 ${formatDateTimeShort(d.lastCheckAt)}`);
  if (d.lastRestockAt) parts.push(`最近补货 ${formatDateTimeShort(d.lastRestockAt)}`);
  return parts;
}

function stockSummary(d: { oosSkuCount?: number | null; lowStockSkuCount?: number | null }) {
  const oos = Number(d.oosSkuCount || 0);
  const low = Number(d.lowStockSkuCount || 0);
  if (oos > 0 && low > 0) return `缺货 ${oos} · 低库存 ${low}`;
  if (oos > 0) return `缺货 ${oos}`;
  if (low > 0) return `低库存 ${low}`;
  return '';
}
</script>

<style scoped>
.meta.addr {
  /* 2026-11-10：编号行删掉后正文恢复整卡宽度，原先 380rpx 的窄上限已无理由，
     实测会把「深圳市罗湖区演示点位 B（CAB-OTHER）」截成「…（CAB-O…」。 */
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.devices-page {
  padding: 0;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
  min-height: 100%;
  background: var(--page-tint, #f0fdfa);
  box-sizing: border-box;
}
.nav-place {
  width: 100%;
}
/* 昨晚终态：白底文字操作条（主操作品牌色+底线，次操作灰字），不是渐变胶囊 */
.toolbar {
  display: flex;
  align-items: stretch;
  background: var(--card-bg, #ffffff);
  padding: 0 8rpx;
  border-bottom: 1rpx solid var(--color-border-subtle, #e2e8f0);
}
.tool-item {
  flex: 1 1 0;
  /* MA-162：真机 191×38，只差高度。横向相邻是同排其它 tool-item ⇒ 只纵向扩。 */
  min-width: 88rpx;
  min-height: 72rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 22rpx 12rpx 18rpx;
  position: relative;
}
/* R4-B-新7：热区扩量挂 ::before（同 .nav-link 配方）—— 挂 ::after 会与
   is-primary 的下划线规则合并，把下划线甩到条目上方且热区失效。 */
.tool-item::before {
  content: '';
  position: absolute;
  inset: -12rpx 0;
}
.tool-label {
  font-size: 30rpx;
  font-weight: 500;
  color: var(--text-muted, #64748b);
  line-height: 1.2;
}
.tool-item.is-primary .tool-label {
  color: var(--brand, #0d9488);
  font-weight: 700;
}
.tool-item.is-primary::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: 0;
  width: 64rpx;
  height: 6rpx;
  margin-left: -32rpx;
  border-radius: 6rpx;
  background: var(--brand, #0d9488);
}
.tool-item.is-busy {
  opacity: 0.55;
  pointer-events: none;
}
.tool-item-hover {
  opacity: 0.72;
}
/* 无 page-body：卡片水平 gutter 由页面承担（对齐 M02） */
.devices-page > .card,
.devices-page .device-card {
  margin-left: var(--page-gutter, 24rpx);
  margin-right: var(--page-gutter, 24rpx);
  width: auto;
  max-width: none;
  box-sizing: border-box;
}
.device-card {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 0;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.device-card-hover {
  background: var(--page-bg, #ededed) !important;
  opacity: 0.96;
}
.device-main {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16rpx;
}
/* 2026-11-10：状态标签并进名称行后不再独占右侧竖列，横向并排、整体靠右 */
.device-tags {
  display: flex;
  align-items: center;
  gap: 8rpx;
  flex: 0 0 auto;
}
.tag {
  /* 2026-11-10 重排：标签从「右侧竖列、一列一个」改成名称行内并排，
     原 96rpx 最小宽 + 26rpx 字号会把名称挤没，收成内容宽度的小徽章。 */
  display: inline-block;
  min-width: 0;
  padding: 4rpx 14rpx;
  border-radius: 8rpx;
  font-size: 22rpx;
  font-weight: 700;
  line-height: 1.4;
  text-align: center;
  white-space: nowrap;
  box-sizing: border-box;
}
.tag-lock {
  /* 只改配色，尺寸跟基础 .tag：同排的 在线/离线/补货中 都是基础档，
     单独把停售放大到 112/30rpx 没有任何依据（4c231022 顺带带进来的） */
  color: var(--warning, #b45309);
  background: var(--warning-soft, #fff7ed);
}
.tag-warn {
  color: var(--warning, #b45309);
  background: var(--warning-soft, #fff7ed);
}
.tag-on {
  color: var(--brand, #0d9488);
  background: var(--brand-soft, #f0fdfa);
}
.tag-off {
  color: var(--text-muted, #64748b);
  background: var(--page-tint, #f0fdfa);
}
.star {
  /* MA-162：真机 13×16。给星星一个真实占位的 96rpx 槽位（字形靠左 ⇒ 视觉不位移），
     纵向再用 ::after 补到 96rpx。它在整卡可点的 device-card 内，上下约 15px 环形带归开关——
     嵌套小控件的固有取舍，按「开关优先」处理。 */
  display: inline-flex;
  align-items: center;
  justify-content: flex-start;
  min-width: 96rpx;
  min-height: 40rpx;
  color: var(--text-subtle, #cbd5e1);
  /* 2026-10-28：星星放名称旁，字号收敛 + 垂直居中（name-row 已 align-items:center） */
  font-size: 32rpx;
  line-height: 1;
  padding: 0;
  position: relative;
  z-index: 1;
  flex-shrink: 0;
}
.star::after {
  content: '';
  position: absolute;
  inset: -28rpx 0;
}
.star.on {
  color: var(--warning, #f59e0b);
}
.nav-link {
  /* MA-162：真机 38×16。同 aux-link 配方：min-width/min-height 让判据可证 + 纵向 inset。 */
  /* 2026-11-10：导航改为与 chips 行同排（.stat-line 的末项，贴该行右下角），
     不再绝对定位——底栏/占位都不需要，卡片高度不再为导航多留一行。
     relative 是给热区伪元素当定位基准，别删（删了 ::before 会相对外层容器扩，吞掉相邻点击）。 */
  position: relative;
  align-self: flex-end;
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 96rpx;
  min-height: 48rpx;
  flex-shrink: 0;
  padding: 0;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--brand, #0d9488);
  line-height: 1.2;
}
/* 🔴 热区扩量挂在 before 伪元素，不能挂 after：G08 箭头的 class app-link-chevron
   把 chevron 画在 after 上，同一元素只有一个 after，两条规则合并会把箭头甩到文字上方。
   （MA-162 热区；注释里不要写双冒号原文，热区门禁按选择器首个双冒号切分会被顶掉。） */
.nav-link::before {
  content: '';
  position: absolute;
  inset: -22rpx 0;
}
.nav-link-hover {
  opacity: 0.65;
}
.name,
.meta,
.online-dot {
  pointer-events: none;
}
.filters {
  position: sticky;
  top: 0;
  z-index: 5;
  isolation: isolate;
  background: var(--page-bg, #ededed);
  padding: 16rpx var(--page-gutter, 24rpx) 12rpx;
}
.search {
  /* 对齐 ui-search：高 88rpx、20rpx 圆角、浅灰底（原来 72rpx/8rpx/白底，显得单薄） */
  height: 88rpx;
  box-sizing: border-box;
  background: #f1f5f9;
  border: none;
  border-radius: 20rpx;
  padding: 0 28rpx;
  font-size: var(--font-size-body);
}
.chips {
  display: none;
}
.pref-hint {
  margin-top: 12rpx;
  display: flex;
  justify-content: space-between;
  font-size: var(--font-size-sm);
  color: var(--brand, #0d9488);
}
.pref-clear {
  color: var(--text-muted);
}
.empty {
  text-align: center;
  color: var(--text-muted);
}
.device-left {
  display: flex;
  align-items: center;
  gap: 16rpx;
  flex: 1;
  min-width: 0;
}
.device-info {
  flex: 1;
  min-width: 0;
}
.device-thumb {
  width: 88rpx;
  height: 88rpx;
  border-radius: var(--radius-control);
  background: var(--brand-soft);
  flex-shrink: 0;
}
.thumb-wrap {
  position: relative;
  width: 88rpx;
  height: 88rpx;
  flex-shrink: 0;
}
.online-dot {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
  border: 2rpx solid var(--card-bg, #ffffff);
}
.online-dot.on {
  background: var(--success, #16a34a);
  box-shadow: 0 0 8rpx rgba(22, 163, 74, 0.5);
}
.online-dot.off {
  background: var(--text-subtle, #cbd5e1);
}
/* 每个 meta 独占一行：小程序 <text> 默认 inline，缺 display 会与相邻 text 连排
   （真机实测「…入库 · 8°C固件 1.0.0」连成一句；.meta.addr 的 max-width 对 inline 亦无效） */
.meta {
  display: block;
}
.name {
  font-weight: 600;
  display: block;
  font-size: var(--font-size-md);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 2026-10-28：星星移到柜机名称旁并垂直居中（原在卡片右上角独立列，未居中） */
.name-row {
  display: flex;
  align-items: center;
  gap: 8rpx;
  max-width: 100%;
}
.name-row .name {
  /* 2026-11-10：名称不再吃满整行（flex:1 会把星标顶到最右、和状态标签挤在一起），
     改成按内容宽度、超长才收缩省略，星标紧跟名称，状态标签靠 margin-left:auto 贴右。 */
  flex: 0 1 auto;
  min-width: 0;
}
.name-row .device-tags {
  margin-left: auto;
}
.meta.revenue {
  /* 2026-10-28：今日收入上移到名称下方醒目处，品牌色 + 加粗强调 */
  color: var(--brand, #0d9488);
  font-weight: 700;
  margin-top: 6rpx;
}
.meta.stock-warn {
  color: var(--warning, #b45309);
}
/* 卡片状态/经营指标 chips 行（2026-10-25 排版优化）：flex wrap 逐项成胶囊，
   规避历史「inline 拼接连句」的换行坑（lessons：.meta.addr 注释） */
/* 2026-11-10：chips 行与「导航」同排的最后一行——chips 占左侧自动换行，导航贴该行右下角。
   导航不进底栏、不绝对定位，卡片高度不再为它单独留一行（用户否定的正是底部大片空白）。 */
.stat-line {
  display: flex;
  align-items: flex-end;
  gap: 12rpx;
  margin-top: 8rpx;
}
.stat-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8rpx 12rpx;
  /* 与 .nav-link 抢宽度：可压缩，否则长 chips 会把导航顶出卡片 */
  flex: 1;
  min-width: 0;
  margin-top: 0;
  max-width: 100%;
}
/* 停售原因长句 2026-11-10 起不在卡片展示（理由见模板注释），样式随之删除 */
.stat-chip {
  display: inline-block;
  padding: 4rpx 14rpx;
  border-radius: 999rpx;
  font-size: var(--font-size-xs, 20rpx);
  line-height: 1.5;
  color: var(--text-muted, #64748b);
  background: var(--page-tint, #f0fdfa);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  box-sizing: border-box;
}
.stat-chip-warn {
  color: var(--warning, #b45309);
  background: var(--warning-soft, #fff7ed);
}
</style>
