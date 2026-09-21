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
        <CrudTable
          :table="crud"
          row-key="deviceId"
          selectable
          :actions="canAccessPath('/devices') ? rowActions : undefined"
          :action-width="96"
          empty-text="暂无设备报表数据"
          sort-field-label="设备编号"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column prop="deviceId" label="设备编号" min-width="140" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.deviceId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="设备" min-width="140" class-name="col-text">
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
          <el-table-column
            label="状态"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="row.onlineStatus === 'ONLINE' ? 'success' : 'info'" size="small">
                {{ dictLabel('online_status', row.onlineStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="商户"
            min-width="120"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">{{ row.merchantName || '—' }}</template>
          </el-table-column>
          <el-table-column
            label="线路"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.routeCode || '—' }}</template>
          </el-table-column>
          <el-table-column label="地址" min-width="140" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.address || ''">{{ row.address || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="停售"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag v-if="row.salesLocked" type="danger" size="small" effect="plain">停售</el-tag>
              <span v-else class="muted">否</span>
            </template>
          </el-table-column>
          <el-table-column
            label="温度"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span v-if="row.currentTempC != null">{{ row.currentTempC }}°C</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column
            label="固件"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.firmwareVersion || '—' }}</template>
          </el-table-column>
          <el-table-column
            prop="orderTotal"
            label="累计订单"
            min-width="96"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            label="累计营收"
            min-width="108"
            align="center"
            class-name="col-money"
            label-class-name="col-money"
          >
            <template #default="{ row }">¥{{ (row.revenueTotalCents / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column
            label="累计客单"
            min-width="96"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }"
              >¥{{ ((row.avgOrderValueTotalCents || 0) / 100).toFixed(2) }}</template
            >
          </el-table-column>
          <el-table-column
            prop="orderToday"
            label="今日订单"
            min-width="96"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            label="今日营收"
            min-width="108"
            align="center"
            class-name="col-money"
            label-class-name="col-money"
          >
            <template #default="{ row }">¥{{ (row.revenueTodayCents / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column
            label="今日客单"
            min-width="96"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }"
              >¥{{ ((row.avgOrderValueTodayCents || 0) / 100).toFixed(2) }}</template
            >
          </el-table-column>
          <el-table-column
            prop="sessionTotal"
            label="累计会话"
            min-width="96"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            prop="sessionActive"
            label="进行中"
            min-width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
        </CrudTable>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { View } from '@element-plus/icons-vue';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable, type CrudPageParams } from '@/composables/useCrudTable';
import { useNavAccess } from '@/composables/useNavAccess';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

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
  merchantId?: string;
  merchantName?: string;
  routeCode?: string;
  address?: string;
  salesLocked?: boolean;
  salesLockReason?: string;
  currentTempC?: number | null;
  firmwareVersion?: string | null;
  avgOrderValueTodayCents?: number;
  avgOrderValueTotalCents?: number;
}

const route = useRoute();
const { router, canAccessPath, goPath } = useNavAccess();
const offlineTotal = ref(0);
const keyword = ref('');
const deviceId = ref('');
const onlineFilter = ref('');

const deviceOptions = ref<{ deviceId: string; deviceName?: string }[]>([]);

// 首屏先把路由 query 同步进筛选状态，再交给 useCrudTable 挂载后自动加载（原 onMounted 前置逻辑）
applyRouteQuery();

/** 拉取一页设备报表；查询拼装保持原样（page 已是 0 起） */
async function fetchPage(params: CrudPageParams) {
  const q = new URLSearchParams({
    page: String(params.page),
    size: String(params.size)
  });
  if (keyword.value.trim()) q.set('keyword', keyword.value.trim());
  if (onlineFilter.value) q.set('online', onlineFilter.value);
  if (deviceId.value) q.set('deviceId', deviceId.value);
  return api.request<{ items: DeviceReportRow[]; total: number }>(
    AdminEndpoints.reportsDevicesList(q),
    'GET'
  );
}

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 / 刷新 全部内建
const crud = useCrudTable<DeviceReportRow>({
  rowKey: (r) => r.deviceId,
  fetchPage,
  sort: { prop: 'deviceId', mode: 'local' },
  errorMessage: '加载失败'
});

const sum = computed(() =>
  crud.items.reduce(
    (acc, r) => ({
      orderTotal: acc.orderTotal + (r.orderTotal || 0),
      revenueTotal: acc.revenueTotal + (r.revenueTotalCents || 0),
      revenueToday: acc.revenueToday + (r.revenueTodayCents || 0),
      offline: acc.offline + (r.onlineStatus === 'OFFLINE' ? 1 : 0)
    }),
    { orderTotal: 0, revenueTotal: 0, revenueToday: 0, offline: 0 }
  )
);

const pagePartial = computed(() => crud.total > crud.items.length);

function deviceReportDeviceCountHint(ready: boolean) {
  if (!ready) return UI_COPY.loading;
  if (onlineFilter.value) return `已筛选 · 共 ${crud.total} 台`;
  return undefined;
}

function deviceReportOfflineHint(ready: boolean) {
  if (!ready) return UI_COPY.loading;
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
  const ready = crud.hydrated;
  const pageHint = pagePartial.value ? '本页合计' : undefined;
  const deviceCountHint = deviceReportDeviceCountHint(ready);
  const offlineHint = deviceReportOfflineHint(ready);
  return [
    {
      label: '设备数',
      value: ready ? String(crud.total) : '…',
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
      hint: ready ? pageHint || `订单 ${sum.value.orderTotal}` : UI_COPY.loading
    },
    {
      label: '今日营收',
      value: ready ? `¥${(sum.value.revenueToday / 100).toFixed(2)}` : '…',
      accent: 'accent-blue',
      hint: ready ? pageHint : UI_COPY.loading
    }
  ];
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '设备经营报表',
  exportPerm: 'ops:report:export',
  headers: [
    '设备编号',
    '设备名称',
    '状态',
    '商户',
    '线路',
    '地址',
    '停售',
    '停售原因',
    '温度',
    '固件',
    '累计订单',
    '累计营收',
    '累计客单',
    '今日订单',
    '今日营收',
    '今日客单',
    '累计会话',
    '进行中'
  ],
  // 选中优先由 CrudTable 内部处理（勾选了就只导选中行）
  toRows: (rows) =>
    rows.map((row) => [
      row.deviceId,
      row.deviceName || '',
      dictLabel('online_status', row.onlineStatus),
      row.merchantName || '',
      row.routeCode || '',
      row.address || '',
      row.salesLocked ? '是' : '否',
      row.salesLocked ? row.salesLockReason || '' : '',
      row.currentTempC == null ? '' : `${row.currentTempC}°C`,
      row.firmwareVersion || '',
      row.orderTotal,
      `¥${(row.revenueTotalCents / 100).toFixed(2)}`,
      `¥${((row.avgOrderValueTotalCents || 0) / 100).toFixed(2)}`,
      row.orderToday,
      `¥${(row.revenueTodayCents / 100).toFixed(2)}`,
      `¥${((row.avgOrderValueTodayCents || 0) / 100).toFixed(2)}`,
      row.sessionTotal,
      row.sessionActive
    ])
};

function rowActions(_row: DeviceReportRow): CrudRowAction[] {
  return [{ key: 'detail', label: '详情', icon: View, type: 'primary' }];
}

function onAction({ key, row }: { key: string; row: DeviceReportRow }) {
  if (key === 'detail') goDevice(row.deviceId);
}

async function loadDeviceOptions() {
  try {
    deviceOptions.value =
      (await api.request<{ deviceId: string; deviceName?: string }[]>(
        AdminEndpoints.devicesRef,
        'GET'
      )) || [];
  } catch {
    deviceOptions.value = [];
  }
}

async function loadOfflineTotal() {
  try {
    const q = new URLSearchParams({ online: 'OFFLINE', page: '0', size: '1' });
    const data = await api.request<{ total?: number }>(AdminEndpoints.reportsDevicesList(q), 'GET');
    offlineTotal.value = Number(data.total) || 0;
  } catch {
    offlineTotal.value = 0;
  }
}

function goDevice(deviceId: string) {
  goPath(`/devices/${encodeURIComponent(deviceId)}`);
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (onlineFilter.value) query.online = onlineFilter.value;
  router.replace({ query });
}

function search() {
  syncRouteQuery();
  void crud.search();
}

function reset() {
  keyword.value = '';
  deviceId.value = '';
  onlineFilter.value = '';
  syncRouteQuery();
  void crud.search();
}

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

// 列表首查由 useCrudTable 挂载时自动触发；这里只补充 KPI 离线数与柜机下拉选项
onMounted(() => {
  void loadDeviceOptions();
  void loadOfflineTotal();
});
onActivated(() => {
  if (applyRouteQuery()) {
    void crud.search();
  }
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
  font-size: var(--admin-font-size-title);
}

.hint {
  color: var(--layout-muted);
  font-size: var(--admin-font-size-sm);
  font-weight: 400;
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
  /* 覆盖全局 button{inline-flex}，否则标签/数值/提示挤成一行 */
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
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
  appearance: none;
  cursor: default;
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
  font-size: var(--admin-font-size-table);
  color: var(--layout-muted);
  line-height: 1.3;
}

.kpi-value {
  font-size: clamp(18px, 2.4vw, 22px);
  font-weight: 700;
  margin-top: 0;
  line-height: 1.2;
  word-break: break-word;
  color: var(--layout-text);
  font-variant-numeric: tabular-nums;
}
.kpi-hint {
  margin-top: 2px;
  font-size: var(--admin-font-size-sm);
  line-height: 1.3;
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

@media (max-width: 640px) {
  .hint {
    width: 100%;
  }
}
</style>
