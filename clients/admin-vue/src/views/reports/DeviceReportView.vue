<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备经营报表</span>
            <span class="hint">按柜机汇总累计 / 今日订单、营收与会话</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button @click="onExport">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="kpi-grid">
      <div v-for="tile in kpiTiles" :key="tile.label" class="kpi-tile" :class="tile.accent">
        <div class="kpi-label">{{ tile.label }}</div>
        <div class="kpi-value">{{ tile.value }}</div>
      </div>
    </div>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="设备编号 / 名称"
          style="width: 220px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="onlineFilter" clearable placeholder="全部" style="width: 120px" @change="search">
          <el-option label="在线" value="ONLINE" />
          <el-option label="离线" value="OFFLINE" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 1072px">
        <el-table
          v-loading="loading"
          :data="paged"
          stripe
          border
          class="report-table"
          row-key="deviceId"
        >
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="设备编号" width="128">
            <template #default="{ row }"><span class="cell-id">{{ row.deviceId }}</span></template>
          </el-table-column>
          <el-table-column prop="deviceName" label="设备名称" width="120" show-overflow-tooltip />
          <el-table-column label="状态" width="88">
            <template #default="{ row }">
              <el-tag :type="row.onlineStatus === 'ONLINE' ? 'success' : 'info'" size="small">
                {{ dictLabel('online_status', row.onlineStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="orderTotal" label="累计订单" width="96" />
          <el-table-column label="累计营收" width="108">
            <template #default="{ row }">¥{{ (row.revenueTotalCents / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="orderToday" label="今日订单" width="96" />
          <el-table-column label="今日营收" width="108">
            <template #default="{ row }">¥{{ (row.revenueTodayCents / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="sessionTotal" label="累计会话" width="96" />
          <el-table-column prop="sessionActive" label="进行中" width="88" />
          <el-table-column label="操作" width="96" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                :actions="[{ key: 'detail', label: '详情', icon: View, type: 'primary' }]"
                @action="() => router.push(`/devices/${encodeURIComponent(row.deviceId)}`)"
              />
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无设备报表数据" /></template>
        </el-table>
      </div>
    </div>

    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="filtered.length"
        :page-sizes="[10, 20, 50]"
        :layout="paginationLayout"
        :pager-count="pagerCount"
        background
        @current-change="() => {}"
        @size-change="onSizeChange"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh, View } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';

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

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const rows = ref<DeviceReportRow[]>([]);
const keyword = ref('');
const onlineFilter = ref('');
const page = ref(1);
const size = ref(20);
const viewportWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1280);

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  return rows.value.filter((r) => {
    if (onlineFilter.value && r.onlineStatus !== onlineFilter.value) return false;
    if (!kw) return true;
    return [r.deviceId, r.deviceName].some((v) => String(v || '').toLowerCase().includes(kw));
  });
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

const sum = computed(() =>
  filtered.value.reduce(
    (acc, r) => ({
      orderTotal: acc.orderTotal + (r.orderTotal || 0),
      revenueTotal: acc.revenueTotal + (r.revenueTotalCents || 0),
      revenueToday: acc.revenueToday + (r.revenueTodayCents || 0)
    }),
    { orderTotal: 0, revenueTotal: 0, revenueToday: 0 }
  )
);

const kpiTiles = computed(() => [
  { label: '设备数', value: String(filtered.value.length), accent: 'accent-teal' },
  { label: '累计订单', value: String(sum.value.orderTotal), accent: 'accent-blue' },
  { label: '累计营收', value: `¥${(sum.value.revenueTotal / 100).toFixed(2)}`, accent: 'accent-violet' },
  { label: '今日营收', value: `¥${(sum.value.revenueToday / 100).toFixed(2)}`, accent: 'accent-amber' }
]);

const paginationLayout = computed(() => {
  if (viewportWidth.value < 560) return 'prev, pager, next';
  if (viewportWidth.value < 900) return 'total, prev, pager, next';
  return 'total, sizes, prev, pager, next';
});

const pagerCount = computed(() => (viewportWidth.value < 560 ? 5 : 7));

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
    paged.value.map((row) => [
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
  viewportWidth.value = window.innerWidth;
}

watch(keyword, () => {
  page.value = 1;
});

async function load() {
  loading.value = true;
  try {
    rows.value = await api.request<DeviceReportRow[]>('/api/v2/ops/admin/reports/devices', 'GET');
    page.value = 1;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
}

function reset() {
  keyword.value = '';
  onlineFilter.value = '';
  page.value = 1;
}

function onSizeChange() {
  page.value = 1;
}

onMounted(() => {
  applyRouteQuery();
  window.addEventListener('resize', onResize, { passive: true });
  load();
});
onActivated(() => {
  if (applyRouteQuery()) {
    page.value = 1;
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
  window.removeEventListener('resize', onResize);
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
  border-radius: 10px;
  padding: 12px 14px;
  border: 1px solid var(--layout-border);
  background: var(--layout-bg, #f8fafc);
  position: relative;
  overflow: hidden;
}

.kpi-tile::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
}

.kpi-tile.accent-teal::before { background: #2dd4bf; }
.kpi-tile.accent-blue::before { background: #60a5fa; }
.kpi-tile.accent-violet::before { background: #a78bfa; }
.kpi-tile.accent-amber::before { background: #fbbf24; }

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
}

@media (max-width: 640px) {
  .hint {
    width: 100%;
  }
}
</style>
