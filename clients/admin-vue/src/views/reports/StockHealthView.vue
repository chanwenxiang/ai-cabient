<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">库存健康</span>
            <span class="hint">投放柜缺货 / 低库存 / 临期批次</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-if="canAccessPath('/replenishment') && listHydrated && planDeviceIds.length"
            v-hasPermi="['ops:replenishment:edit']"
            type="primary"
            @click="goPlanReplenishment()"
          >
            一键补货规划（{{ planDeviceIds.length }} 台）
          </el-button>
          <el-button v-hasPermi="['ops:stock-health:export']" @click="onExport">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="load">
      <el-form-item label="维度">
        <el-radio-group v-model="dimension" @change="load">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="STOCKOUT">断货</el-radio-button>
          <el-radio-button value="LOW">低库存</el-radio-button>
          <el-radio-button value="NEAR_EXPIRY">临期</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="柜机">
        <el-select
          v-model="deviceId"
          clearable
          filterable
          placeholder="全部柜机"
          style="width: 200px"
          @change="load"
        >
          <el-option
            v-for="d in deviceOptions"
            :key="d.deviceId"
            :label="`${d.deviceName || d.deviceId}（${d.deviceId}）`"
            :value="d.deviceId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="商户">
        <el-input
          v-model="merchantId"
          clearable
          placeholder="商户编号"
          style="width: 140px"
          @keyup.enter="load"
        />
      </el-form-item>
      <el-form-item label="路线">
        <el-input
          v-model="routeCode"
          clearable
          placeholder="路线编码"
          style="width: 120px"
          @keyup.enter="load"
        />
      </el-form-item>
      <el-form-item label="生命周期">
        <el-select
          v-model="lifecycleStatus"
          clearable
          placeholder="默认投放"
          style="width: 130px"
          @change="load"
        >
          <el-option
            v-for="item in dictOptions('device_lifecycle')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
          <el-option label="全部状态" value="ALL" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <div class="kpi-row">
      <button
        type="button"
        class="kpi-tile warn"
        :aria-label="listHydrated ? `断货行 ${countBy('STOCKOUT')}` : '断货行 — 加载中…'"
      >
        <div class="kpi-label">断货行</div>
        <div class="kpi-value">{{ listHydrated ? countBy('STOCKOUT') : '—' }}</div>
        <div v-if="!listHydrated" class="kpi-hint">加载中…</div>
      </button>
      <button
        type="button"
        class="kpi-tile"
        :aria-label="listHydrated ? `低库存行 ${countBy('LOW')}` : '低库存行 — 加载中…'"
      >
        <div class="kpi-label">低库存行</div>
        <div class="kpi-value">{{ listHydrated ? countBy('LOW') : '—' }}</div>
        <div v-if="!listHydrated" class="kpi-hint">加载中…</div>
      </button>
      <button
        type="button"
        class="kpi-tile warn"
        :aria-label="listHydrated ? `临期行 ${countBy('NEAR_EXPIRY')}` : '临期行 — 加载中…'"
      >
        <div class="kpi-label">临期行</div>
        <div class="kpi-value">{{ listHydrated ? countBy('NEAR_EXPIRY') : '—' }}</div>
        <div v-if="!listHydrated" class="kpi-hint">加载中…</div>
      </button>
      <button
        type="button"
        class="kpi-tile"
        :aria-label="listHydrated ? `涉及柜机 ${deviceCount}` : '涉及柜机 — 加载中…'"
      >
        <div class="kpi-label">涉及柜机</div>
        <div class="kpi-value">{{ listHydrated ? deviceCount : '—' }}</div>
        <div v-if="!listHydrated" class="kpi-hint">加载中…</div>
      </button>
    </div>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="rows"
          stripe
          border
          class="report-table"
          empty-text=" "
        >
          <template #empty
            ><el-empty v-if="listHydrated && !loading" description="暂无异常库存"
          /></template>
          <el-table-column label="维度" width="96" align="center">
            <template #default="{ row }">
              <el-tag :type="dimTag(row.dimension)" size="small">{{
                dimLabel(row.dimension)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="设备"
            min-width="120"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.deviceName || row.deviceId || '无' }}</template>
          </el-table-column>
          <el-table-column
            label="设备ID"
            min-width="120"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.deviceId || '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="商户"
            min-width="110"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.merchantId || '无' }}</template>
          </el-table-column>
          <el-table-column
            label="路线"
            width="90"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.routeCode || '无' }}</template>
          </el-table-column>
          <el-table-column
            label="SKU"
            min-width="120"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.skuName || row.skuId || '无' }}</template>
          </el-table-column>
          <el-table-column
            label="SKU ID"
            min-width="120"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.skuId || '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="库存" width="80" align="center">
            <template #default="{ row }">{{ row.quantity }}</template>
          </el-table-column>
          <el-table-column label="容量" width="80" align="center">
            <template #default="{ row }">{{ row.capacity }}</template>
          </el-table-column>
          <el-table-column label="阈值" width="72" align="center">
            <template #default="{ row }">{{ row.lowThreshold ?? '无' }}</template>
          </el-table-column>
          <el-table-column label="缺货率" width="88" align="center">
            <template #default="{ row }"
              >{{ Number(row.stockoutRatePct || 0).toFixed(1) }}%</template
            >
          </el-table-column>
          <el-table-column label="断货天" width="80" align="center">
            <template #default="{ row }">{{ row.daysOutOfStock ?? '无' }}</template>
          </el-table-column>
          <el-table-column label="到期日" width="120" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.expiryDate || '未填' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions :actions="rowActions(row)" @action="(key) => onRowAction(key, row)" />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Box, Delete, Refresh, Remove, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api, downloadAuthFile } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useNavAccess } from '@/composables/useNavAccess';
import { useAuthStore } from '@/stores/auth';
import { useDeviceOptions } from '@/composables/useDeviceOptions';

interface StockHealthRow {
  dimension: string;
  deviceId: string;
  deviceName?: string;
  merchantId?: string;
  routeCode?: string;
  lifecycleStatus?: string;
  skuId?: string;
  skuName?: string;
  quantity?: number;
  capacity?: number;
  lowThreshold?: number | null;
  stockoutRatePct?: number;
  daysOutOfStock?: number | null;
  expiryDate?: string | null;
  updatedAt?: string;
  lotId?: string;
  batchNo?: string;
}

const route = useRoute();
const auth = useAuthStore();
const { canAccessPath, goPath } = useNavAccess();
const loading = ref(false);
/** 首屏未拉完前勿展示 0，避免与真实库存异常数闪错 */
const listHydrated = ref(false);
const dimension = ref('ALL');
const deviceId = ref('');
const merchantId = ref('');
const routeCode = ref('');
const lifecycleStatus = ref('DEPLOYED');
const rows = ref<StockHealthRow[]>([]);
const { deviceOptions, loadDeviceOptions } = useDeviceOptions();

const deviceCount = computed(() => new Set(rows.value.map((r) => r.deviceId).filter(Boolean)).size);

/** 断货/低库存柜机（临期不进一键补货） */
const planDeviceIds = computed(
  () =>
    [
      ...new Set(
        rows.value
          .filter((r) => r.dimension === 'STOCKOUT' || r.dimension === 'LOW')
          .map((r) => r.deviceId)
          .filter(Boolean)
      )
    ] as string[]
);

function rowActions(row: StockHealthRow): TableAction[] {
  const actions: TableAction[] = [];
  if (canAccessPath('/devices')) {
    actions.push({ key: 'device', label: '设备', icon: View, type: 'primary' });
  }
  if (
    canAccessPath('/replenishment') &&
    (row.dimension === 'STOCKOUT' || row.dimension === 'LOW')
  ) {
    actions.push({ key: 'plan', label: '补货', icon: Box });
  }
  if (
    row.dimension === 'NEAR_EXPIRY' &&
    row.lotId &&
    (auth.hasPerm('ops:replenishment:edit') || canAccessPath('/replenishment'))
  ) {
    actions.push({ key: 'pull-off', label: '下架', icon: Remove, type: 'warning' });
    actions.push({ key: 'write-off', label: '报损', icon: Delete, type: 'danger' });
  }
  return actions;
}

function onRowAction(key: string, row: StockHealthRow) {
  if (key === 'device') {
    goPath(`/devices/${encodeURIComponent(row.deviceId)}`);
    return;
  }
  if (key === 'plan') {
    goPlanReplenishment([row.deviceId]);
    return;
  }
  if (key === 'pull-off') {
    void createPullOff(row);
    return;
  }
  if (key === 'write-off') {
    void writeOffLot(row);
  }
}

async function createPullOff(row: StockHealthRow) {
  if (!row.lotId) {
    ElMessage.warning('缺少批次信息');
    return;
  }
  try {
    const task = await api.request<{ taskId: number }>(
      '/api/v2/ops/admin/expiry/alerts/ensure',
      'POST',
      { lotId: row.lotId }
    );
    await api.request(
      `/api/v2/ops/admin/expiry/alerts/${task.taskId}/create-replenishment`,
      'POST',
      { lineType: 'PULL_OFF' }
    );
    ElMessage.success('已生成临期下架补货任务');
    goPath('/replenishment', { tab: 'expiry', deviceId: row.deviceId });
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建下架任务失败');
  }
}

async function writeOffLot(row: StockHealthRow) {
  try {
    await ElMessageBox.confirm(
      `确认报损 ${row.skuName || row.skuId} × ${row.quantity}？将直接扣减柜内批次库存。`,
      '临期报损',
      { type: 'warning' }
    );
    await api.request('/api/v2/ops/admin/inventory/write-off', 'POST', {
      deviceId: row.deviceId,
      skuId: row.skuId,
      batchNo: row.batchNo || undefined,
      quantity: Number(row.quantity) || 1,
      reason: 'EXPIRED'
    });
    ElMessage.success('已报损');
    await load();
  } catch (e) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '报损失败');
  }
}

function goPlanReplenishment(ids?: string[]) {
  const deviceIds = (ids?.length ? ids : planDeviceIds.value).filter(Boolean);
  if (!deviceIds.length) {
    ElMessage.warning('当前列表无断货/低库存柜机');
    return;
  }
  goPath('/replenishment', {
    tab: 'shortage',
    plan: '1',
    deviceIds: deviceIds.join(',')
  });
}

function countBy(dim: string) {
  return rows.value.filter((r) => r.dimension === dim).length;
}

function dimLabel(dim?: string) {
  return dictLabel('stock_health_dim', dim) || dim || '未知';
}

function dimTag(dim?: string): 'danger' | 'warning' | 'info' {
  if (dim === 'STOCKOUT') return 'danger';
  if (dim === 'NEAR_EXPIRY') return 'warning';
  return 'info';
}

function queryString() {
  const q = new URLSearchParams();
  q.set('dimension', dimension.value || 'ALL');
  if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
  if (merchantId.value.trim()) q.set('merchantId', merchantId.value.trim());
  if (routeCode.value.trim()) q.set('routeCode', routeCode.value.trim());
  if (lifecycleStatus.value && lifecycleStatus.value !== 'ALL') {
    q.set('lifecycleStatus', lifecycleStatus.value);
  } else if (lifecycleStatus.value === 'ALL') {
    q.set('lifecycleStatus', '');
  }
  return q.toString();
}

async function load() {
  loading.value = true;
  try {
    rows.value = await api.request<StockHealthRow[]>(
      `/api/v2/ops/admin/reports/stock-health?${queryString()}`,
      'GET'
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

async function onExport() {
  try {
    await downloadAuthFile(
      `/api/v2/ops/admin/reports/stock-health/export?${queryString()}`,
      'stock-health.csv'
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

const VALID_DIMS = new Set(['ALL', 'STOCKOUT', 'LOW', 'NEAR_EXPIRY']);

/** 看板快捷入口带 query 时覆盖本地筛选；无对应 query 则保留用户上次选择 */
function applyRouteQuery() {
  let changed = false;
  const dim =
    typeof route.query.dimension === 'string' ? route.query.dimension.trim().toUpperCase() : '';
  if (VALID_DIMS.has(dim) && dim !== dimension.value) {
    dimension.value = dim;
    changed = true;
  }
  if ('deviceId' in route.query) {
    const next = typeof route.query.deviceId === 'string' ? route.query.deviceId : '';
    if (next !== deviceId.value) {
      deviceId.value = next;
      changed = true;
    }
  }
  if ('merchantId' in route.query) {
    const next = typeof route.query.merchantId === 'string' ? route.query.merchantId : '';
    if (next !== merchantId.value) {
      merchantId.value = next;
      changed = true;
    }
  }
  if ('routeCode' in route.query) {
    const next = typeof route.query.routeCode === 'string' ? route.query.routeCode : '';
    if (next !== routeCode.value) {
      routeCode.value = next;
      changed = true;
    }
  }
  if ('lifecycleStatus' in route.query) {
    const next =
      typeof route.query.lifecycleStatus === 'string' ? route.query.lifecycleStatus : 'DEPLOYED';
    if (next !== lifecycleStatus.value) {
      lifecycleStatus.value = next || 'DEPLOYED';
      changed = true;
    }
  }
  return changed;
}

onMounted(async () => {
  applyRouteQuery();
  await loadDeviceOptions();
  await load();
});

onActivated(() => {
  // keep-alive 复用时 onMounted 不重跑；须按本次路由 query 同步维度
  applyRouteQuery();
  void load();
});

watch(
  () =>
    [
      route.query.dimension,
      route.query.deviceId,
      route.query.merchantId,
      route.query.routeCode,
      route.query.lifecycleStatus
    ] as const,
  () => {
    if (applyRouteQuery()) {
      void load();
    }
  }
);
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
  font-size: 15px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
@media (max-width: 900px) {
  .kpi-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.kpi-tile {
  display: block;
  width: 100%;
  text-align: left;
  font: inherit;
  color: inherit;
  border: none;
  cursor: default;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 10px 12px;
}
.kpi-tile.warn {
  background: color-mix(in srgb, var(--el-color-warning) 12%, var(--layout-card, #fff));
}
.kpi-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.kpi-value {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.kpi-hint {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
