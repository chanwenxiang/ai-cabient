<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">补货</span>
        <div class="actions">
          <el-tag size="small" type="info">待执行 {{ plannedCount }}</el-tag>
          <el-tag size="small" type="warning">待处理设备 {{ pendingTaskCount }}</el-tag>
          <el-tag size="small">要货 {{ requests.length }}</el-tag>
          <el-tag v-if="focusDeviceId" size="small" type="success" closable @close="clearDeviceFocus">
            设备 {{ focusDeviceId }}
          </el-tag>
          <el-button v-if="canEdit" type="primary" @click="openPlan">规划补货路线</el-button>
          <el-button @click="onExport">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="tab">
      <el-tab-pane label="补货路线" name="routes">
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 900px">
            <el-table v-loading="loading" :data="filteredRoutes" stripe border :empty-text="routesEmptyText">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="route-detail">
                <div class="route-meta">
                  <span>计划日期：{{ row.plannedDate || '-' }}</span>
                  <span>负责人：{{ row.assigneeUserId || '未分配' }}</span>
                  <span>预计里程：{{ row.totalDistanceM ? `${row.totalDistanceM} 米` : '未计算' }}</span>
                </div>
                <el-table :data="row.tasks || []" size="small" class="line-table">
                  <el-table-column label="设备" min-width="210">
                    <template #default="scope">
                      <div class="master-data-cell">
                        <strong>{{ deviceName(scope.row.deviceId) }}</strong>
                        <small>{{ scope.row.deviceId }}</small>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="任务状态" width="120">
                    <template #default="scope">
                      <el-tag :type="dictTagType(scope.row.status)" size="small">
                        {{ dictLabel('replenishment_task_status', scope.row.status) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="notes" label="说明" min-width="220" show-overflow-tooltip />
                  <template #empty><el-empty description="该路线暂无设备任务" :image-size="48" /></template>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="routeId" label="路线编号" width="110" />
          <el-table-column prop="routeName" label="路线名称" min-width="200" />
          <el-table-column label="设备数" width="100">
            <template #default="{ row }">{{ row.tasks?.length || 0 }}</template>
          </el-table-column>
          <el-table-column prop="plannedDate" label="计划日期" width="130" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">
                {{ dictLabel('replenishment_route_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无补货路线" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="商户要货" name="requests">
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 900px">
            <el-table v-loading="loading" :data="requests" stripe border>
          <el-table-column prop="requestId" label="要货单" width="110" />
          <el-table-column prop="merchantName" label="商户" min-width="160" />
          <el-table-column label="目标设备" min-width="200">
            <template #default="{ row }">
              <div class="master-data-cell">
                <strong>{{ deviceName(row.deviceId) }}</strong>
                <small>{{ row.deviceId }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">
                {{ dictLabel('replenishment_request_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="100" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                v-if="row.status === 'SUBMITTED'"
                :actions="requestActions"
                :max-primary="1"
                @action="(k) => onRequestAction(row, String(k))"
              />
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无待处理要货申请" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="planDialog" title="规划补货路线" width="620px" destroy-on-close>
      <el-form label-width="96px" class="plan-form">
        <el-form-item label="路线名称" required>
          <el-input v-model="planForm.routeName" maxlength="80" placeholder="例如：浦东早班补货路线" />
        </el-form-item>
        <el-form-item label="计划日期">
          <input v-model="planForm.plannedDate" class="native-date" type="date" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input-number v-model="planForm.assigneeUserId" :min="1" :precision="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="目标设备" required>
          <el-select
            v-model="planForm.deviceIds"
            multiple
            filterable
            collapse-tags
            :max-collapse-tags="3"
            placeholder="选择需要补货的柜机"
            style="width: 100%"
          >
            <el-option
              v-for="device in devices"
              :key="device.deviceId"
              :label="`${device.deviceName || device.deviceId}（${device.deviceId}）`"
              :value="device.deviceId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="createPlan">创建路线</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Check, Close, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
const route = useRoute();
const auth = useAuthStore();
const canEdit = computed(() => auth.hasPerm('ops:replenishment:edit'));

const loading = ref(false);
const saving = ref(false);
const tab = ref('routes');
const focusDeviceId = ref('');
const routes = ref<Row[]>([]);
const requests = ref<Row[]>([]);
const devices = ref<Row[]>([]);
const planDialog = ref(false);
const planForm = reactive({
  routeName: '',
  plannedDate: '',
  assigneeUserId: 1,
  deviceIds: [] as string[]
});

const plannedCount = computed(() =>
  routes.value.filter((item) => ['PLANNED', 'IN_PROGRESS'].includes(item.status)).length
);
const pendingTaskCount = computed(() =>
  routes.value
    .flatMap((item) => item.tasks || [])
    .filter((item) => ['PENDING', 'IN_PROGRESS'].includes(item.status)).length
);
const filteredRoutes = computed(() => {
  const id = focusDeviceId.value.trim();
  if (!id) return routes.value;
  return routes.value.filter((row) => (row.tasks || []).some((task: Row) => task.deviceId === id));
});
const routesEmptyText = computed(() =>
  focusDeviceId.value.trim()
    ? `设备 ${focusDeviceId.value} 暂无关联补货路线`
    : '暂无补货路线'
);

const requestActions: TableAction[] = [
  { key: 'accept', label: '接单', icon: Check, type: 'primary' },
  { key: 'reject', label: '驳回', icon: Close, type: 'danger', overflow: true }
];

const { onExport: exportRoutes } = useListCsv({
  filePrefix: '补货路线',
  headers: ['路线编号', '路线名称', '设备数', '计划日期', '状态'],
  toRows: () =>
    routes.value.map((row) => [
      row.routeId,
      row.routeName || '',
      row.tasks?.length || 0,
      row.plannedDate || '',
      dictLabel('replenishment_route_status', row.status)
    ])
});

const { onExport: exportRequests } = useListCsv({
  filePrefix: '商户要货',
  headers: ['要货单', '商户', '目标设备', '状态', '提交时间'],
  toRows: () =>
    requests.value.map((row) => [
      row.requestId,
      row.merchantName || '',
      deviceName(row.deviceId),
      dictLabel('replenishment_request_status', row.status),
      formatDateTime(row.createdAt)
    ])
});

function onExport() {
  if (tab.value === 'requests') exportRequests();
  else exportRoutes();
}

function currentAssigneeId() {
  const id = Number(auth.userId || localStorage.getItem('admin_userId') || 0);
  return Number.isFinite(id) && id > 0 ? id : 1;
}
function deviceName(deviceId: string) {
  return devices.value.find((item) => item.deviceId === deviceId)?.deviceName || deviceId || '-';
}
function localDate() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

function openPlan() {
  Object.assign(planForm, {
    routeName: `${new Date().toLocaleDateString('zh-CN')} 补货路线`,
    plannedDate: localDate(),
    assigneeUserId: currentAssigneeId(),
    deviceIds: focusDeviceId.value.trim() ? [focusDeviceId.value.trim()] : []
  });
  planDialog.value = true;
}

function clearDeviceFocus() {
  focusDeviceId.value = '';
}

function applyRouteQuery() {
  if (route.query.tab === 'routes' || route.query.tab === 'requests') {
    tab.value = String(route.query.tab);
  }
  focusDeviceId.value = typeof route.query.deviceId === 'string' ? route.query.deviceId : '';
}

async function createPlan() {
  if (!planForm.routeName.trim()) return ElMessage.warning('请填写路线名称');
  if (!planForm.deviceIds.length) return ElMessage.warning('请至少选择一台柜机');
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/replenishment/plan', 'POST', {
      ...planForm,
      startLatitude: null,
      startLongitude: null
    });
    planDialog.value = false;
    tab.value = 'routes';
    ElMessage.success('补货路线已创建');
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '路线创建失败');
  } finally {
    saving.value = false;
  }
}

async function onRequestAction(row: Row, key: string) {
  try {
    if (key === 'accept') {
      await ElMessageBox.confirm(`确认接单要货 ${row.requestId}？`, '接单', { type: 'warning' });
      await api.request(`/api/v2/ops/admin/replenishment/requests/${row.requestId}/accept`, 'POST');
      ElMessage.success('已接单');
    } else if (key === 'reject') {
      const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回要货', {
        inputValidator: (v) => !!String(v || '').trim() || '必须填写原因',
        confirmButtonText: '确认驳回',
        type: 'warning'
      });
      await api.request(`/api/v2/ops/admin/replenishment/requests/${row.requestId}/reject`, 'POST', {
        reason: value
      });
      ElMessage.success('已驳回');
    }
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '操作失败');
    }
  }
}

async function load() {
  loading.value = true;
  try {
    const [r, req, deviceRows] = await Promise.all([
      api.request<Row[]>('/api/v2/ops/admin/replenishment/routes', 'GET'),
      api.request<Row[]>('/api/v2/ops/admin/replenishment/requests?status=SUBMITTED', 'GET'),
      api.request<Row[]>('/api/v2/ops/admin/devices', 'GET')
    ]);
    routes.value = r || [];
    requests.value = req || [];
    devices.value = deviceRows || [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '补货数据加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  applyRouteQuery();
  load();
});
onActivated(() => {
  applyRouteQuery();
});
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.title { font-weight: 600; }
.actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.route-detail { padding: 8px 44px 12px; }
.route-meta { display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 12px; color: var(--layout-muted); font-size: 13px; }
.line-table { width: 100%; }
.master-data-cell { display: grid; gap: 2px; line-height: 1.35; }
.master-data-cell strong { color: var(--layout-text); font-weight: 650; }
.master-data-cell small { color: var(--layout-muted); font-size: 11px; }
.muted { color: var(--layout-muted); font-size: 13px; }
.plan-form { margin-top: 4px; }
.native-date {
  width: 100%; height: 32px; padding: 0 10px; border: 1px solid var(--layout-border);
  border-radius: 4px; color: var(--layout-text); background: var(--layout-card); box-sizing: border-box;
}
@media (max-width: 760px) {
  .route-detail { padding: 8px 12px; }
}
</style>
