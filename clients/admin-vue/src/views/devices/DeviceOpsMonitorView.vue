<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备运维</span>
            <span class="hint"
              >与交易异常分流：离线 / 禁售 / 锁机等设备侧事件；事件 ID
              默认升序，可在列表上方切换升降序</span
            >
          </div>
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
        <el-input
          v-model="keyword"
          clearable
          placeholder="事件 ID / 详情"
          style="width: 180px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="eventId"
          selectable
          empty-text="暂无运维事件"
          sort-field-label="事件ID"
          :csv="csvOptions"
        >
          <el-table-column
            prop="eventId"
            label="事件ID"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.eventId }}</span>
            </template>
          </el-table-column>
          <el-table-column
            prop="eventType"
            label="类型"
            width="120"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ eventTypeLabel(row.eventType) }}</template>
          </el-table-column>
          <el-table-column
            label="级别"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag
                :type="
                  row.severity === 'CRITICAL'
                    ? 'danger'
                    : row.severity === 'WARN'
                      ? 'warning'
                      : 'info'
                "
                size="small"
              >
                {{ severityLabel(row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="设备名称"
            min-width="140"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">{{ row.deviceName || '无' }}</template>
          </el-table-column>
          <el-table-column
            prop="deviceId"
            label="设备编号"
            min-width="120"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            prop="title"
            label="标题"
            min-width="140"
            class-name="col-text"
            label-class-name="col-text"
          />
          <el-table-column
            prop="detail"
            label="详情"
            min-width="200"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">{{ formatEventDetail(row.detail) }}</template>
          </el-table-column>
          <el-table-column
            label="账龄"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ eventAge(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column
            label="时间"
            width="170"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useDeviceOptions } from '@/composables/useDeviceOptions';
import { useDictOptions } from '@/composables/useDictOptions';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { displayLabel } from '@aicabinet/shared-dict';

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

const eventType = ref('');
const severity = ref('');
const deviceFilter = ref('');
const keyword = ref('');

const eventTypeOptions = useDictOptions('device_ops_event');
const riskSeverityDict = useDictOptions('risk_severity');
const severityOptions = computed(() =>
  riskSeverityDict.value.filter((o) => ['INFO', 'WARN', 'CRITICAL', 'HIGH'].includes(o.value))
);

/** 关键词为纯前端过滤（后端无该参数）：原 displayItems 计算属性前移到取数处，total 仍取服务端值 */
function filterByKeyword(rows: OpsEvent[]): OpsEvent[] {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return rows;
  return rows.filter((row) => {
    const idMatch = String(row.eventId).includes(kw);
    const rawDetail = (row.detail || '').toLowerCase();
    const detailMatch =
      rawDetail.includes(kw) || formatEventDetail(row.detail).toLowerCase().includes(kw);
    return idMatch || detailMatch;
  });
}

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 全部内建。
// 首查依赖 loadDeviceOptions（设备筛选项）先就绪，故 autoLoad:false，onMounted 显式首查。
const crud = useCrudTable<OpsEvent>({
  rowKey: (r) => r.eventId,
  pageSize: 50,
  autoLoad: false,
  errorMessage: '加载失败',
  fetchPage: async (params) => {
    const q = new URLSearchParams({
      page: String(params.page), // 0 起（useCrudTable 已换算）
      size: String(params.size),
      sortDir: params.sortDir ?? 'asc'
    });
    if (eventType.value) q.set('eventType', eventType.value);
    if (severity.value) q.set('severity', severity.value);
    if (deviceFilter.value) q.set('deviceId', deviceFilter.value);
    const data = await api.request<{ items: OpsEvent[]; total?: number }>(
      AdminEndpoints.deviceOpsEventsList(q),
      'GET'
    );
    const items = data.items || [];
    return { items: filterByKeyword(items), total: Number(data.total ?? items.length) };
  },
  // 后端固定按 eventId 排序、仅接收方向（默认升序）；方向由壳内「升/降序」按钮驱动重查
  sort: { prop: 'eventId', mode: 'server', defaultDir: 'asc' }
});

// 导出移入 CrudTable 内建工具条；勾选行时仅导出选中（原 pickSelected 语义）
const csvOptions: CrudCsvOptions = {
  filePrefix: '设备运维事件',
  exportPerm: 'ops:device-ops:export',
  headers: ['事件ID', '类型', '级别', '设备名称', '设备编号', '标题', '详情', '时间'],
  toRows: (rows) =>
    rows.map((row) => [
      row.eventId,
      eventTypeLabel(row.eventType),
      severityLabel(row.severity),
      row.deviceName || '无',
      row.deviceId || '',
      row.title || '',
      formatEventDetail(row.detail),
      formatDateTime(row.createdAt)
    ])
};

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

function search() {
  void crud.search();
}

onMounted(async () => {
  await loadDeviceOptions();
  await crud.load();
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
.cell-id {
  font-variant-numeric: tabular-nums;
}
</style>
