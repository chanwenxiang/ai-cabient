<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备经营报表</span>
            <span class="hint">按设备汇总累计 / 今日订单、营收与会话</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:report:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="kpi-grid">
      <button
        type="button"
        v-for="tile in kpiTiles"
        :key="tile.label"
        class="kpi-tile"
        :class="[tile.accent, { 'is-clickable': !!tile.action }]"
        :aria-label="`${tile.label} ${tile.value}${tile.hint ? ` ${tile.hint}` : ''}`"
        @click="tile.action?.()"
      >
        <div class="kpi-label">{{ tile.label }}</div>
        <div class="kpi-value">{{ tile.value }}</div>
        <div v-if="tile.hint" class="kpi-hint">{{ tile.hint }}</div>
      </button>
    </div>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="柜机">
        <el-select
          v-model="deviceId"
          clearable
          filterable
          placeholder="全部柜机"
          style="width: 200px"
          @change="search"
        >
          <el-option
            v-for="d in deviceOptions"
            :key="d.deviceId"
            :label="`${d.deviceName || d.deviceId}（${d.deviceId}）`"
            :value="d.deviceId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="设备编号 / 名称"
          style="width: 180px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="onlineFilter"
          clearable
          placeholder="全部"
          style="width: 120px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('online_status').filter((o) => o.value !== 'UNKNOWN')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="rows"
          stripe
          border
          class="report-table"
          row-key="deviceId"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="deviceId"
            label="设备编号"
            min-width="140"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.deviceId }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="设备"
            min-width="140"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <button
                v-if="canAccessPath('/devices')"
                type="button"
                class="link-cell"
                @click="goDevice(row.deviceId)"
              >
                {{ row.deviceName || '无' }}
              </button>
              <span v-else>{{ row.deviceName || '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="row.onlineStatus === 'ONLINE' ? 'success' : 'info'" size="small">
                {{ dictLabel('online_status', row.onlineStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="orderTotal" label="累计订单" min-width="96" align="center" />
          <el-table-column label="累计营收" min-width="108" align="center">
            <template #default="{ row }">¥{{ (row.revenueTotalCents / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="orderToday" label="今日订单" min-width="96" align="center" />
          <el-table-column label="今日营收" min-width="108" align="center">
            <template #default="{ row }">¥{{ (row.revenueTodayCents / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="sessionTotal" label="累计会话" min-width="96" align="center" />
          <el-table-column prop="sessionActive" label="进行中" min-width="88" align="center" />
          <el-table-column
            v-if="canAccessPath('/devices')"
            label="操作"
            width="96"
            class-name="col-action"
            align="center"
          >
            <template #default="{ row }">
              <TableActions
                :actions="[{ key: 'detail', label: '详情', icon: View, type: 'primary' }]"
                @action="() => goDevice(row.deviceId)"
              />
            </template>
          </el-table-column>
          <template #empty
            ><el-empty v-if="listHydrated && !loading" description="暂无设备报表数据"
          /></template>
        </el-table>
      </div>
    </div>

    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50]"
      :layout="paginationLayout"
      :pager-count="pagerCount"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh, View } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

interface DeviceReportRow {
  deviceId: string;
  deviceName?: string;
  onlineStatus?: string;
  orderTotal: number;
  revenueTotalCents: number;
  orderToday: number;
  revenueTodayCents: number;
  sessionTotal: number;
  sessionActive: number;
}

const route = useRoute();
const { router, canAccessPath, goPath } = useNavAccess();
const loading = ref(false);
/** 首屏未拉完前勿展示 0 / ¥0.00 */
const listHydrated = ref(false);
const rows = ref<DeviceReportRow[]>([]);
const total = ref(0);
const offlineTotal = ref(0);
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('deviceId');
const keyword = ref('');
const deviceId = ref('');
const onlineFilter = ref('');
const page = ref(1);
const size = ref(20);
const viewportWidth = ref(typeof globalThis === 'undefined' ? 1280 : globalThis.innerWidth);

const deviceOptions = ref<{ deviceId: string; deviceName?: string }[]>([]);

const sum = computed(() =>
  rows.value.reduce(
    (acc, r) => ({
      orderTotal: acc.orderTotal + (r.orderTotal || 0),
      revenueTotal: acc.revenueTotal + (r.revenueTotalCents || 0),
      revenueToday: acc.revenueToday + (r.revenueTodayCents || 0),
      offline: acc.offline + (r.onlineStatus === 'OFFLINE' ? 1 : 0)
    }),
    { orderTotal: 0, revenueTotal: 0, revenueToday: 0, offline: 0 }
  )
);

const pagePartial = computed(() => total.value > rows.value.length);

function deviceReportDeviceCountHint(ready: boolean) {
  if (!ready) return '加载中…';
  if (onlineFilter.value) return `已筛选 · 共 ${total.value} 台`;
  return undefined;
}

function deviceReportOfflineHint(ready: boolean) {
  if (!ready) return '加载中…';
  if (offlineTotal.value) return '点击筛选离线';
  return '全部在线';
}

function deviceReportClearOnlineFilter() {
  if (!onlineFilter.value) return;
  onlineFilter.value = '';
  search();
}

function deviceReportToggleOfflineFilter() {
  onlineFilter.value = onlineFilter.value === 'OFFLINE' ? '' : 'OFFLINE';
  search();
}

const kpiTiles = computed(() => {
  const ready = listHydrated.value;
  const pageHint = pagePartial.value ? '本页合计' : undefined;
  const deviceCountHint = deviceReportDeviceCountHint(ready);
  const offlineHint = deviceReportOfflineHint(ready);
  return [
    {
      label: '设备数',
      value: ready ? String(total.value) : '…',
      accent: 'accent-teal',
      hint: deviceCountHint,
      action: ready ? deviceReportClearOnlineFilter : undefined
    },
    {
      label: '离线设备',
      value: ready ? String(offlineTotal.value) : '…',
      accent: 'accent-amber',
      hint: offlineHint,
      action: ready ? deviceReportToggleOfflineFilter : undefined
    },
    {
      label: '累计营收',
      value: ready ? `¥${(sum.value.revenueTotal / 100).toFixed(2)}` : '…',
      accent: 'accent-violet',
      hint: ready ? pageHint || `订单 ${sum.value.orderTotal}` : '加载中…'
    },
    {
      label: '今日营收',
      value: ready ? `¥${(sum.value.revenueToday / 100).toFixed(2)}` : '…',
      accent: 'accent-blue',
      hint: ready ? pageHint : '加载中…'
    }
  ];
});

const paginationLayout = computed(() => {
  if (viewportWidth.value < 560) return 'prev, pager, next';
  if (viewportWidth.value < 900) return 'total, prev, pager, next';
  return 'total, sizes, prev, pager, next';
});

const pagerCount = computed(() => (viewportWidth.value < 560 ? 5 : 7));

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<DeviceReportRow>((r) => r.deviceId);

const { onExport } = useListCsv({
  filePrefix: '设备经营报表',
  headers: [
    '设备编号',
    '设备名称',
    '状态',
    '累计订单',
    '累计营收',
    '今日订单',
    '今日营收',
    '累计会话',
    '进行中'
  ],
  toRows: () =>
    pickSelected(rows.value).map((row) => [
      row.deviceId,
      row.deviceName || '',
      dictLabel('online_status', row.onlineStatus),
      row.orderTotal,
      `¥${(row.revenueTotalCents / 100).toFixed(2)}`,
      row.orderToday,
      `¥${(row.revenueTodayCents / 100).toFixed(2)}`,
      row.sessionTotal,
      row.sessionActive
    ])
});

function onResize() {
  viewportWidth.value = globalThis.innerWidth;
}

watch(keyword, () => {
  page.value = 1;
});

function queryParams() {
  const q = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (keyword.value.trim()) q.set('keyword', keyword.value.trim());
  if (onlineFilter.value) q.set('online', onlineFilter.value);
  if (deviceId.value) q.set('deviceId', deviceId.value);
  return q;
}

async function loadDeviceOptions() {
  try {
    deviceOptions.value =
      (await api.request<{ deviceId: string; deviceName?: string }[]>(
        '/api/v2/ops/admin/devices/ref',
        'GET'
      )) || [];
  } catch {
    deviceOptions.value = [];
  }
}

async function loadOfflineTotal() {
  try {
    const q = new URLSearchParams({ online: 'OFFLINE', page: '0', size: '1' });
    const data = await api.request<{ total?: number }>(
      `/api/v2/ops/admin/reports/devices?${q}`,
      'GET'
    );
    offlineTotal.value = Number(data.total) || 0;
  } catch {
    offlineTotal.value = 0;
  }
}

async function load() {
  loading.value = true;
  try {
    const data = await api.request<{ items: DeviceReportRow[]; total: number }>(
      `/api/v2/ops/admin/reports/devices?${queryParams()}`,
      'GET'
    );
    rows.value = sortById(data.items || [], 'deviceId');
    total.value = Number(data.total) || 0;
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (onlineFilter.value) query.online = onlineFilter.value;
  router.replace({ query });
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load();
}

function reset() {
  keyword.value = '';
  deviceId.value = '';
  onlineFilter.value = '';
  page.value = 1;
  syncRouteQuery();
  load();
}

function onSizeChange() {
  page.value = 1;
  load();
}

function goDevice(deviceId: string) {
  goPath(`/devices/${encodeURIComponent(deviceId)}`);
}

onMounted(() => {
  applyRouteQuery();
  globalThis.addEventListener('resize', onResize, { passive: true });
  void loadDeviceOptions();
  void loadOfflineTotal();
  load();
});
onActivated(() => {
  if (applyRouteQuery()) {
    page.value = 1;
    load();
  }
});

function applyRouteQuery() {
  let changed = false;
  if (typeof route.query.online === 'string' && route.query.online !== onlineFilter.value) {
    onlineFilter.value = route.query.online;
    changed = true;
  }
  if (typeof route.query.keyword === 'string' && route.query.keyword !== keyword.value) {
    keyword.value = route.query.keyword;
    changed = true;
  }
  return changed;
}

onUnmounted(() => {
  globalThis.removeEventListener('resize', onResize);
});
</script>

<style scoped>
.report-page :deep(.el-card__body) {
  min-width: 0;
}

.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}

.page-card-head__meta {
  min-width: 0;
  flex: 1 1 240px;
}

.page-card-head__title {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}

.title {
  font-weight: 600;
  font-size: 15px;
}

.hint {
  color: var(--layout-muted);
  font-size: 12px;
  font-weight: 400;
}

.page-card-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.kpi-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-bottom: 12px;
}

@media (min-width: 900px) {
  .kpi-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

.kpi-tile {
  min-width: 0;
  width: 100%;
  border-radius: 10px;
  padding: 12px 14px;
  border: 1px solid var(--layout-border);
  background: var(--el-fill-color-light);
  position: relative;
  overflow: hidden;
  text-align: center;
  color: inherit;
  font: inherit;
}
.kpi-tile.is-clickable {
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    transform 0.15s ease;
}
.kpi-tile.is-clickable:hover,
.kpi-tile.is-clickable:focus-visible {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--app-primary, #0f766e) 40%, var(--layout-border));
  outline: none;
}

.kpi-tile::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
}

.kpi-tile.accent-teal::before {
  background: #2dd4bf;
}
.kpi-tile.accent-blue::before {
  background: #60a5fa;
}
.kpi-tile.accent-violet::before {
  background: #a78bfa;
}
.kpi-tile.accent-amber::before {
  background: #fbbf24;
}

.kpi-label {
  font-size: 13px;
  color: var(--layout-muted);
}

.kpi-value {
  font-size: clamp(18px, 2.4vw, 22px);
  font-weight: 700;
  margin-top: 4px;
  line-height: 1.2;
  word-break: break-word;
  color: var(--layout-text);
}
.kpi-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--layout-muted);
}
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  font: inherit;
  font-weight: 650;
}
.link-cell:hover {
  text-decoration: underline;
}
.report-table :deep(th.col-text > .cell),
.report-table :deep(td.col-text > .cell) {
  text-align: center;
}

@media (max-width: 640px) {
  .hint {
    width: 100%;
  }
}
</style>
