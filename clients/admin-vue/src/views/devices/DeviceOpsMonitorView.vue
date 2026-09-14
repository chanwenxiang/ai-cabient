<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备运维</span>
            <span class="hint"
              >与交易异常分流：离线 / 禁售 / 锁机等设备侧事件；事件 ID
              默认升序，可在筛选栏切换；列表使用虚拟滚动</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact">
      <el-form-item label="类型">
        <el-select
          v-model="eventType"
          clearable
          filterable
          placeholder="全部"
          style="width: 160px"
          @change="search"
        >
          <el-option
            v-for="item in eventTypeOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="级别">
        <el-select
          v-model="severity"
          clearable
          placeholder="全部"
          style="width: 120px"
          @change="search"
        >
          <el-option
            v-for="item in severityOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="设备">
        <el-select
          v-model="deviceFilter"
          clearable
          filterable
          placeholder="筛选设备"
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
        <el-input v-model="keyword" clearable placeholder="事件 ID / 详情" style="width: 180px" />
      </el-form-item>
      <el-form-item label="事件ID排序">
        <el-select v-model="sortDir" style="width: 120px" @change="search">
          <el-option label="升序" value="asc" />
          <el-option label="降序" value="desc" />
        </el-select>
      </el-form-item>
    </el-form>

    <div v-loading="loading" class="table-scroll">
      <AdminVirtualTable
        :columns="virtualColumns"
        :data="displayItems"
        row-key="eventId"
        height="min(560px, calc(100svh - 320px))"
      />
      <el-empty
        v-if="listHydrated && !loading && !displayItems.length"
        class="virtual-empty"
        description="暂无运维事件"
      />
    </div>
    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="ADMIN_LIST_PAGE_SIZES"
      layout="total, sizes, prev, pager, next"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import PagePager from '@/components/PagePager.vue';
import AdminVirtualTable from '@/components/AdminVirtualTable.vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElTag, type Column } from 'element-plus';
import { api } from '@/api/client';
import { useAdminListTable } from '@/composables/useAdminListTable';
import { createLoadSeq } from '@/composables/createLoadSeq';
import { useDeviceOptions } from '@/composables/useDeviceOptions';
import { useListCsv } from '@/composables/useListCsv';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { displayLabel } from '@aicabinet/shared-dict';
import { useDictOptions } from '@/composables/useDictOptions';
import {
  ADMIN_LIST_PAGE_SIZES,
  clampAdminPageSize
} from '@/utils/admin-list-pager';

const { deviceOptions, loadDeviceOptions } = useDeviceOptions();

interface OpsEvent {
  eventId: number;
  deviceId: string;
  deviceName?: string;
  eventType: string;
  severity?: string;
  title?: string;
  detail?: string;
  createdAt?: string;
}

const loading = ref(false);
const listHydrated = ref(false);
const loadSeq = createLoadSeq();
const eventType = ref('');
const severity = ref('');
const deviceFilter = ref('');
const items = ref<OpsEvent[]>([]);
const page = ref(1);
const size = ref(clampAdminPageSize(50));
const total = ref(0);
/** 默认升序；筛选栏可切换 */
const sortDir = ref<'asc' | 'desc'>('asc');

const eventTypeOptions = useDictOptions('device_ops_event');
const riskSeverityDict = useDictOptions('risk_severity');
const severityOptions = computed(() =>
  riskSeverityDict.value.filter((o) => ['INFO', 'WARN', 'CRITICAL', 'HIGH'].includes(o.value))
);

const { keyword, pickSelected, exportButtonLabel, clearSelection, filterByKeyword } =
  useAdminListTable<OpsEvent>((r) => r.eventId);

const displayItems = computed(() =>
  filterByKeyword(items.value, (row, kw) => {
    const idMatch = String(row.eventId).includes(kw);
    const rawDetail = (row.detail || '').toLowerCase();
    const detailMatch =
      rawDetail.includes(kw) || formatEventDetail(row.detail).toLowerCase().includes(kw);
    return idMatch || detailMatch;
  })
);

const virtualColumns = computed((): Column<OpsEvent>[] => [
  {
    key: 'eventId',
    dataKey: 'eventId',
    title: '事件ID',
    width: 110,
    align: 'center',
    cellRenderer: ({ rowData }) => h('span', { class: 'cell-id' }, String(rowData.eventId))
  },
  {
    key: 'eventType',
    dataKey: 'eventType',
    title: '类型',
    width: 120,
    align: 'center',
    cellRenderer: ({ rowData }) => eventTypeLabel(rowData.eventType)
  },
  {
    key: 'severity',
    dataKey: 'severity',
    title: '级别',
    width: 100,
    align: 'center',
    cellRenderer: ({ rowData }) =>
      h(
        ElTag,
        {
          type:
            rowData.severity === 'CRITICAL'
              ? 'danger'
              : rowData.severity === 'WARN'
                ? 'warning'
                : 'info',
          size: 'small'
        },
        () => severityLabel(rowData.severity)
      )
  },
  {
    key: 'deviceName',
    dataKey: 'deviceName',
    title: '设备名称',
    width: 160,
    cellRenderer: ({ rowData }) => rowData.deviceName || '无'
  },
  {
    key: 'deviceId',
    dataKey: 'deviceId',
    title: '设备编号',
    width: 140,
    align: 'center'
  },
  {
    key: 'title',
    dataKey: 'title',
    title: '标题',
    width: 160
  },
  {
    key: 'detail',
    dataKey: 'detail',
    title: '详情',
    width: 240,
    cellRenderer: ({ rowData }) => formatEventDetail(rowData.detail)
  },
  {
    key: 'age',
    dataKey: 'createdAt',
    title: '账龄',
    width: 100,
    align: 'center',
    cellRenderer: ({ rowData }) => eventAge(rowData.createdAt)
  },
  {
    key: 'createdAt',
    dataKey: 'createdAt',
    title: '时间',
    width: 170,
    align: 'center',
    cellRenderer: ({ rowData }) => formatDateTime(rowData.createdAt)
  }
] as Column<OpsEvent>[]);

const { onExport } = useListCsv({
  filePrefix: '设备运维事件',
  headers: ['事件ID', '类型', '级别', '设备名称', '设备编号', '标题', '详情', '时间'],
  toRows: () =>
    pickSelected(displayItems.value).map((row) => [
      row.eventId,
      eventTypeLabel(row.eventType),
      severityLabel(row.severity),
      row.deviceName || '无',
      row.deviceId || '',
      row.title || '',
      formatEventDetail(row.detail),
      formatDateTime(row.createdAt)
    ])
});

function eventTypeLabel(t?: string) {
  return displayLabel('device_ops_event', t, '未知');
}
function severityLabel(s?: string) {
  return displayLabel('risk_severity', s, '未知');
}

/** OBS-023：详情里的 onlineStatus=OFFLINE / lifecycle=DEPLOYED 等键值中文化 */
function formatEventDetail(detail?: string) {
  if (!detail) return '暂无';
  if (!detail.includes('=')) return detail;
  const keyLabels: Record<string, string> = {
    onlineStatus: '在线状态',
    lifecycle: '生命周期',
    lifecycleStatus: '生命周期',
    salesLocked: '锁机'
  };
  return detail
    .split(/[,;]+/)
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => {
      const i = part.indexOf('=');
      if (i <= 0) return part;
      const key = part.slice(0, i).trim();
      const val = part.slice(i + 1).trim();
      const keyLabel = keyLabels[key] || key;
      let valLabel = val;
      if (key === 'onlineStatus') valLabel = displayLabel('online_status', val, '未知');
      else if (key === 'lifecycle' || key === 'lifecycleStatus')
        valLabel = displayLabel('device_lifecycle', val, '未知');
      else if (key === 'salesLocked') {
        if (val === 'true' || val === 't' || val === '1') valLabel = '是';
        else if (val === 'false' || val === 'f' || val === '0') valLabel = '否';
        else valLabel = val;
      }
      return `${keyLabel}：${valLabel}`;
    })
    .join('；');
}

function eventAge(createdAt?: string) {
  if (!createdAt) return '暂无';
  const ms = Date.now() - new Date(createdAt).getTime();
  if (!Number.isFinite(ms) || ms < 0) return '暂无';
  const m = Math.floor(ms / 60000);
  if (m < 60) return `${Math.max(0, m)} 分前`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${h} 小时前`;
  return `${Math.floor(h / 24)} 天前`;
}

async function load() {
  const seq = loadSeq.begin();
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(clampAdminPageSize(size.value)),
      sortDir: sortDir.value
    });
    if (eventType.value) q.set('eventType', eventType.value);
    if (severity.value) q.set('severity', severity.value);
    if (deviceFilter.value) q.set('deviceId', deviceFilter.value);
    const data = await api.request<{ items: OpsEvent[]; total?: number }>(
      `/api/v2/ops/admin/device-ops/events?${q}`,
      'GET'
    );
    items.value = data.items || [];
    total.value = Number(data.total ?? items.value.length);
    clearSelection();
  } catch (e) {
    if (!loadSeq.isCurrent(seq)) return;
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    if (!loadSeq.isCurrent(seq)) return;
    listHydrated.value = true;
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  load();
}

function onSizeChange() {
  size.value = clampAdminPageSize(size.value);
  page.value = 1;
  void load();
}

onMounted(async () => {
  await loadDeviceOptions();
  await load();
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: var(--admin-font-size-title);
}
.hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
.table-scroll {
  position: relative;
  min-height: 240px;
}
.virtual-empty {
  position: absolute;
  inset: 48px 0 0;
  pointer-events: none;
}
.cell-id {
  font-variant-numeric: tabular-nums;
}
</style>
