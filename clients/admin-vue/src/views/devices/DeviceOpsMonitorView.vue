<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备运维</span>
            <span class="hint">与交易异常分流：离线 / 禁售 / 锁机等设备侧事件；事件 ID 默认升序，点击表头可切换</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact">
      <el-form-item label="类型">
        <el-select v-model="eventType" clearable filterable placeholder="全部" style="width: 160px" @change="search">
          <el-option
            v-for="item in eventTypeOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="级别">
        <el-select v-model="severity" clearable placeholder="全部" style="width: 120px" @change="search">
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
    </el-form>

    <div class="table-scroll">
      <el-table
        v-loading="loading"
        :data="displayItems"
        stripe
        border
        class="report-table"
        :default-sort="{ prop: 'eventId', order: 'ascending' }"
        @sort-change="onSortChange"
      >
        <template #empty><el-empty description="暂无运维事件" /></template>
        <el-table-column prop="eventId" label="事件ID" width="110" align="center" sortable="custom">
          <template #default="{ row }">
            <span class="cell-id">{{ row.eventId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="eventType" label="类型" width="120" align="center">
          <template #default="{ row }">{{ eventTypeLabel(row.eventType) }}</template>
        </el-table-column>
        <el-table-column label="级别" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.severity === 'CRITICAL' ? 'danger' : row.severity === 'WARN' ? 'warning' : 'info'"
              size="small"
            >
              {{ severityLabel(row.severity) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="设备名称" min-width="140" show-overflow-tooltip align="center">
          <template #default="{ row }">{{ row.deviceName || '无' }}</template>
        </el-table-column>
        <el-table-column prop="deviceId" label="设备编号" min-width="120" show-overflow-tooltip align="center" />
        <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip align="center" />
        <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip align="center" />
        <el-table-column label="时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </div>
    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="load"
        @size-change="search"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, type Sort } from 'element-plus';
import { api } from '@/api/client';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { dictLabel, displayLabel } from '@aicabinet/shared-dict';
import { useDictOptions } from '@/composables/useDictOptions';

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

interface DeviceOpt {
  deviceId: string;
  deviceName?: string;
}

const loading = ref(false);
const eventType = ref('');
const severity = ref('');
const deviceFilter = ref('');
const items = ref<OpsEvent[]>([]);
const deviceOptions = ref<DeviceOpt[]>([]);
const page = ref(1);
const size = ref(50);
const total = ref(0);
/** 默认升序；点击「事件ID」表头切换 */
const sortDir = ref<'asc' | 'desc'>('asc');

const eventTypeOptions = useDictOptions('device_ops_event');
const riskSeverityDict = useDictOptions('risk_severity');
const severityOptions = computed(() =>
  riskSeverityDict.value.filter((o) => ['INFO', 'WARN', 'CRITICAL', 'HIGH'].includes(o.value))
);

const displayItems = computed(() => {
  let list = items.value;
  if (severity.value) list = list.filter((e) => e.severity === severity.value);
  if (deviceFilter.value) list = list.filter((e) => e.deviceId === deviceFilter.value);
  return list;
});

function eventTypeLabel(t?: string) {
  return dictLabel('device_ops_event', t) || t || '未知';
}
function severityLabel(s?: string) {
  return displayLabel('risk_severity', s, '未知');
}

function onSortChange(payload: Sort) {
  if (payload.prop !== 'eventId') return;
  if (payload.order === 'descending') sortDir.value = 'desc';
  else sortDir.value = 'asc'; // ascending 或取消排序都回默认升序
  page.value = 1;
  load();
}

async function loadDevices() {
  try {
    const res = await api.request<{ items: DeviceOpt[] } | DeviceOpt[]>(
      '/api/v2/ops/admin/devices?page=0&size=200',
      'GET'
    );
    deviceOptions.value = Array.isArray(res) ? res : res.items || [];
  } catch {
    deviceOptions.value = [];
  }
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value),
      sortDir: sortDir.value
    });
    if (eventType.value) q.set('eventType', eventType.value);
    const data = await api.request<{ items: OpsEvent[]; total?: number }>(
      `/api/v2/ops/admin/device-ops/events?${q}`,
      'GET'
    );
    items.value = data.items || [];
    total.value = Number(data.total ?? items.value.length);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  load();
}

onMounted(async () => {
  await loadDevices();
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
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { font-size: 12px; color: var(--el-text-color-secondary); }
.page-pager { margin-top: 12px; display: flex; justify-content: flex-end; }
.cell-id {
  font-variant-numeric: tabular-nums;
}
</style>
