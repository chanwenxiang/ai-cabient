<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">补货调度</span>
            <span class="hint">路线 / 要货 / 缺货；任务可「补货开门」（绑定任务，非运维远程开门）</span>
          </div>
          <div class="kpi-tags">
            <el-tag size="small" type="info">待执行 {{ plannedCount }}</el-tag>
            <el-tag size="small" type="warning">待处理设备 {{ pendingTaskCount }}</el-tag>
            <el-tag size="small">要货 {{ requests.length }}</el-tag>
            <el-tag v-if="focusDeviceId" size="small" type="success" closable @close="clearDeviceFocus">
              设备 {{ focusDeviceId }}
            </el-tag>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-if="canEdit" type="primary" @click="openPlan">规划补货路线</el-button>
          <el-button v-hasPermi="['ops:replenishment:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="补货路线" name="routes">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="loading"
              :data="pagedRoutes"
              stripe
              border
              :empty-text="routesEmptyText"
              row-key="routeId"
              @selection-change="onRoutesSelectionChange"
            >
            <el-table-column type="selection" width="48" />
<el-table-column type="expand">
            <template #default="{ row }">
              <div class="route-detail">
                <div class="route-meta">
                  <span>计划日期：{{ row.plannedDate || '-' }}</span>
                  <span>负责人：{{ row.assigneeUserId || '未分配' }}</span>
                  <span>预计里程：{{ row.totalDistanceM ? `${row.totalDistanceM} 米` : '未计算' }}</span>
                </div>
                <el-table :data="row.tasks || []" size="small" class="line-table">
                  <el-table-column label="设备" min-width="200">
                    <template #default="scope">
                      <div class="master-data-cell">
                        <strong>
                          {{ deviceName(scope.row.deviceId) }}
                          <el-tag
                            size="small"
                            :type="deviceOnline(scope.row.deviceId) ? 'success' : 'info'"
                            class="online-tag"
                          >{{ deviceOnline(scope.row.deviceId) ? '在线' : '离线' }}</el-tag>
                        </strong>
                        <small>{{ scope.row.deviceId }}</small>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="任务状态" width="110">
                    <template #default="scope">
                      <el-tag :type="dictTagType(scope.row.status)" size="small">
                        {{ dictLabel('replenishment_task_status', scope.row.status) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="签到" width="88" align="center">
                    <template #default="scope">
                      <el-tag :type="scope.row.checkInAt ? 'success' : 'info'" size="small">
                        {{ scope.row.checkInAt ? '已签到' : '未签到' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="出库单" width="100" align="center">
                    <template #default="scope">
                      <el-tag v-if="scope.row.outboundId" size="small" type="warning">#{{ scope.row.outboundId }}</el-tag>
                      <span v-else class="muted">现场</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="notes" label="说明" min-width="140" show-overflow-tooltip />
                  <el-table-column v-if="canEdit" label="操作" width="120" align="center">
                    <template #default="scope">
                      <el-button
                        v-if="canOpenRestock(scope.row)"
                        link
                        type="primary"
                        :loading="openDoorLoading === scope.row.taskId"
                        @click="openRestockDoor(scope.row)"
                      >补货开门</el-button>
                      <span v-else class="muted">{{ openDoorHint(scope.row) }}</span>
                    </template>
                  </el-table-column>
                  <template #empty><el-empty description="该路线暂无设备任务" :image-size="48" /></template>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="路线" min-width="220" class-name="col-text">
            <template #default="{ row }">
              <div class="master-data-cell">
                <strong>{{ row.routeName || row.routeId }}</strong>
                <small>{{ row.routeId }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="设备数" width="88" align="center">
            <template #default="{ row }">{{ row.tasks?.length || 0 }}</template>
          </el-table-column>
          <el-table-column prop="plannedDate" label="计划日期" width="120" class-name="col-text" />
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">
                {{ dictLabel('replenishment_route_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="100" align="center" class-name="col-action">
            <template #default="{ row }">
              <el-button
                v-if="canCancelEmptyRoute(row)"
                link
                type="danger"
                :loading="cancelRouteLoading === row.routeId"
                data-testid="cancel-empty-route"
                @click="cancelEmptyRoute(row)"
              >{{ row.status === 'CANCELLED' ? '收口脏出库' : '取消空路线' }}</el-button>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无补货路线" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="商户要货" name="requests">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="loading"
              :data="pagedRequests"
              stripe
              border
              row-key="requestId"
              @selection-change="onRequestsSelectionChange"
            >
          <el-table-column type="selection" width="48" />
          <el-table-column label="要货单" min-width="120" class-name="col-text">
            <template #default="{ row }"><span class="cell-id">{{ row.requestId }}</span></template>
          </el-table-column>
          <el-table-column prop="merchantName" label="商户" min-width="160" class-name="col-text" show-overflow-tooltip />
          <el-table-column label="目标设备" min-width="200" class-name="col-text">
            <template #default="{ row }">
              <button type="button" class="device-link-cell" @click="goDevice(row.deviceId)">
                <strong>{{ deviceName(row.deviceId) }}</strong>
                <small>{{ row.deviceId }}</small>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">
                {{ dictLabel('replenishment_request_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="140" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                v-if="row.status === 'SUBMITTED'"
                :actions="requestActions"
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

      <el-tab-pane label="缺货建议" name="shortage">
        <div class="shortage-toolbar">
          <el-button v-if="canEdit && shortageDevices.length" type="primary" @click="planFromShortage">
            一键规划补货（{{ shortageDevices.length }} 台）
          </el-button>
          <el-button :icon="Refresh" :loading="shortageLoading" @click="loadShortage">刷新缺货</el-button>
        </div>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="shortageLoading"
              :data="pagedShortages"
              stripe
              border
              empty-text="当前无缺货/低库存货道"
              row-key="slotKey"
              @selection-change="onShortageSelectionChange"
            >
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="设备" min-width="180" class-name="col-text">
                <template #default="{ row }">
                  <button type="button" class="device-link-cell" @click="goDevice(row.deviceId)">
                    <strong>{{ deviceName(row.deviceId) }}</strong>
                    <small>{{ row.deviceId }}</small>
                  </button>
                </template>
              </el-table-column>
              <el-table-column prop="slotCode" label="货道" width="90" align="center" />
              <el-table-column prop="assignedSkuName" label="商品" min-width="140" class-name="col-text" show-overflow-tooltip />
              <el-table-column prop="bookQty" label="账面" width="80" align="center" />
              <el-table-column prop="minLevel" label="最低" width="80" align="center" />
              <el-table-column prop="parLevel" label="目标" width="80" align="center" />
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="stockTagType(row)" size="small">{{ stockLabel(row) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="canEdit" label="操作" width="100" align="center" class-name="col-action">
                <template #default="{ row }">
                  <el-button link type="primary" @click="planSingleDevice(row.deviceId)">补货</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="tabTotal"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
      />
    </div>

    <el-dialog
      v-model="planDialog"
      title="规划补货路线"
      width="620px"
      append-to-body
      destroy-on-close
      data-testid="plan-route-dialog"
    >
      <el-form label-width="96px" class="plan-form">
        <el-form-item label="路线名称" required>
          <el-input
            v-model="planForm.routeName"
            maxlength="80"
            placeholder="例如：浦东早班补货路线"
            data-testid="plan-route-name"
          />
        </el-form-item>
        <el-form-item label="计划日期">
          <input v-model="planForm.plannedDate" class="native-date" type="date" data-testid="plan-route-date" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input-number v-model="planForm.assigneeUserId" :min="1" :precision="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="目标设备" required>
          <!-- 勾选列表替代下拉：热区更大，Browser 不易难点选 -->
          <div class="plan-device-list" data-testid="plan-device-select">
            <el-checkbox-group v-model="planForm.deviceIds" class="plan-device-group">
              <!-- div 而非 label：避免外层 label 与 el-checkbox 内部 label 双绑导致偶发点选无效 -->
              <div
                v-for="device in devices"
                :key="device.deviceId"
                class="plan-device-option"
                :data-testid="`plan-device-option-${device.deviceId}`"
              >
                <el-checkbox :label="device.deviceId">
                  {{ device.deviceName || device.deviceId }}（{{ device.deviceId }}）
                </el-checkbox>
              </div>
            </el-checkbox-group>
          </div>
          <div v-if="!shortageDevices.length" class="plan-hint">
            当前无缺货建议：满柜时无法规划。请先盘点/消费产生缺口，或到「缺货建议」查看。
          </div>
          <div v-else-if="selectedDevicesWithoutShortage.length" class="plan-hint">
            所选设备中 {{ selectedDevicesWithoutShortage.join('、') }} 不在缺货建议内，满柜可能无法生成出库单。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="plan-dialog-footer">
          <el-button native-type="button" @click="planDialog = false">取消</el-button>
          <el-button
            type="primary"
            native-type="button"
            class="plan-create-btn"
            :loading="saving"
            :disabled="!planForm.deviceIds.length || saving"
            data-testid="plan-create-route"
            @click.stop="createPlan"
          >
            创建路线
          </el-button>
        </div>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Check, Close, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, downloadAuthFile } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { csvFileName } from '@/utils/csv';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
const route = useRoute();
const router = useRouter();
const { goPath } = useNavAccess();
const auth = useAuthStore();
const canEdit = computed(() => auth.hasPerm('ops:replenishment:edit'));

const loading = ref(false);
const saving = ref(false);
const shortageLoading = ref(false);
const openDoorLoading = ref<number | null>(null);
const cancelRouteLoading = ref<number | null>(null);
const tab = ref('routes');
const page = ref(1);
const size = ref(20);
const focusDeviceId = ref('');
const routes = ref<Row[]>([]);
const requests = ref<Row[]>([]);
const devices = ref<Row[]>([]);
const shortages = ref<Row[]>([]);

const shortageDevices = computed(() =>
  [...new Set(shortages.value.map((s) => s.deviceId).filter(Boolean))]
);
const planDialog = ref(false);
const planForm = reactive({
  routeName: '',
  plannedDate: '',
  assigneeUserId: currentAssigneeId(),
  deviceIds: [] as string[]
});
const selectedDevicesWithoutShortage = computed(() =>
  planForm.deviceIds.filter((id) => !shortageDevices.value.includes(id))
);

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

function slicePage<T>(rows: T[]) {
  const start = (page.value - 1) * size.value;
  return rows.slice(start, start + size.value);
}

const tabTotal = computed(() => {
  if (tab.value === 'requests') return requests.value.length;
  if (tab.value === 'shortage') return shortages.value.length;
  return filteredRoutes.value.length;
});
const pagedRoutes = computed(() => slicePage(filteredRoutes.value));
const pagedRequests = computed(() => slicePage(requests.value));
const pagedShortages = computed(() => slicePage(shortages.value));

watch([tab, focusDeviceId], () => {
  page.value = 1;
});

const requestActions: TableAction[] = [
  { key: 'accept', label: '接单', icon: Check, type: 'primary' },
  { key: 'reject', label: '驳回', icon: Close, type: 'danger' }
];

const {
  onSelectionChange: onRoutesSelectionChange,
  pickSelected: pickRoutes,
  exportButtonLabel: routesExportLabel,
  clearSelection: clearRoutesSelection
} = useTableSelection<Row>((r) => r.routeId);

const {
  onSelectionChange: onRequestsSelectionChange,
  pickSelected: pickRequests,
  exportButtonLabel: requestsExportLabel,
  clearSelection: clearRequestsSelection
} = useTableSelection<Row>((r) => r.requestId);

const {
  onSelectionChange: onShortageSelectionChange,
  pickSelected: pickShortages,
  exportButtonLabel: shortageExportLabel,
  clearSelection: clearShortageSelection
} = useTableSelection<Row>((r) => r.slotKey || `${r.deviceId}-${r.slotCode}`);

const exportButtonLabel = computed(() => {
  if (tab.value === 'requests') return requestsExportLabel.value;
  if (tab.value === 'shortage') return shortageExportLabel.value;
  return routesExportLabel.value;
});

const { onExport: exportRoutes } = useListCsv({
  filePrefix: '补货路线',
  headers: ['路线编号', '路线名称', '设备数', '计划日期', '状态'],
  toRows: () =>
    pickRoutes(routes.value).map((row) => [
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
    pickRequests(requests.value).map((row) => [
      row.requestId,
      row.merchantName || '',
      deviceName(row.deviceId),
      dictLabel('replenishment_request_status', row.status),
      formatDateTime(row.createdAt)
    ])
});

const { onExport: exportShortages } = useListCsv({
  filePrefix: '缺货建议',
  headers: ['设备', '货道', '商品', '账面', '最低', '目标', '状态'],
  toRows: () =>
    pickShortages(shortages.value).map((row) => [
      row.deviceId,
      row.slotCode,
      row.assignedSkuName || '',
      row.bookQty,
      row.minLevel,
      row.parLevel,
      row.stockStatus || (row.bookQty <= 0 ? '缺货' : '低库存')
    ])
});

async function onExport() {
  if (tab.value === 'shortage') {
    exportShortages();
    return;
  }
  if (tab.value === 'requests') {
    const selected = pickRequests(requests.value);
    if (selected.length && selected.length < requests.value.length) {
      exportRequests();
      return;
    }
    try {
      await downloadAuthFile('/api/v2/ops/admin/replenishment/requests/export', csvFileName('商户要货'));
      ElMessage.success('已导出');
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '导出失败');
    }
    return;
  }
  const selected = pickRoutes(routes.value);
  if (selected.length && selected.length < routes.value.length) {
    exportRoutes();
    return;
  }
  try {
    await downloadAuthFile('/api/v2/ops/admin/replenishment/routes/export', csvFileName('补货路线'));
    ElMessage.success('已导出');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
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

function stockLabel(row: Row) {
  const code = String(row.stockStatus || '').toUpperCase();
  if (code === 'OOS' || (row.bookQty ?? 0) <= 0) return '缺货';
  if (code === 'LOW') return '低库存';
  if (code === 'OK' || code === 'NORMAL') return '正常';
  return row.stockStatus || ((row.bookQty ?? 0) <= (row.minLevel ?? 0) ? '低库存' : '缺货');
}
function stockTagType(row: Row) {
  const label = stockLabel(row);
  if (label === '缺货') return 'danger';
  if (label === '低库存') return 'warning';
  return 'info';
}

function goDevice(deviceId?: string) {
  if (!deviceId) return;
  goPath(`/devices/${encodeURIComponent(deviceId)}`);
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

function syncRouteQuery() {
  const query: Record<string, string> = { tab: tab.value };
  if (focusDeviceId.value.trim()) query.deviceId = focusDeviceId.value.trim();
  router.replace({ query });
}

function onTabChange() {
  page.value = 1;
  syncRouteQuery();
}

function clearDeviceFocus() {
  focusDeviceId.value = '';
  syncRouteQuery();
}

function applyRouteQuery() {
  let changed = false;
  if (route.query.tab === 'routes' || route.query.tab === 'requests' || route.query.tab === 'shortage') {
    if (tab.value !== String(route.query.tab)) {
      tab.value = String(route.query.tab);
      changed = true;
    }
  }
  const nextFocus = typeof route.query.deviceId === 'string' ? route.query.deviceId : '';
  if (focusDeviceId.value !== nextFocus) {
    focusDeviceId.value = nextFocus;
    changed = true;
  }
  return changed;
}

async function planFromShortage() {
  const ids = shortageDevices.value;
  if (!ids.length) return ElMessage.warning('当前无缺货设备');
  Object.assign(planForm, {
    routeName: `${new Date().toLocaleDateString('zh-CN')} 缺货补货`,
    plannedDate: localDate(),
    assigneeUserId: currentAssigneeId(),
    deviceIds: focusDeviceId.value && ids.includes(focusDeviceId.value)
      ? [focusDeviceId.value]
      : ids
  });
  planDialog.value = true;
}

function planSingleDevice(deviceId: string) {
  if (!deviceId) return;
  Object.assign(planForm, {
    routeName: `${new Date().toLocaleDateString('zh-CN')} ${deviceId} 补货`,
    plannedDate: localDate(),
    assigneeUserId: currentAssigneeId(),
    deviceIds: [deviceId]
  });
  planDialog.value = true;
}

async function loadShortage() {
  shortageLoading.value = true;
  try {
    await load();
  } finally {
    shortageLoading.value = false;
  }
}

function deviceOnline(deviceId?: string) {
  if (!deviceId) return false;
  const d = devices.value.find((item) => item.deviceId === deviceId);
  return String(d?.onlineStatus || '').toUpperCase() === 'ONLINE';
}

function canOpenRestock(task: Row) {
  if (!task?.taskId || !task?.deviceId) return false;
  if (['COMPLETED', 'CANCELLED'].includes(String(task.status || ''))) return false;
  return !!task.checkInAt && deviceOnline(task.deviceId);
}

function openDoorHint(task: Row) {
  if (['COMPLETED', 'CANCELLED'].includes(String(task.status || ''))) return '-';
  if (!task.checkInAt) return '需先签到';
  if (!deviceOnline(task.deviceId)) return '设备离线';
  return '-';
}

function canCancelEmptyRoute(row: Row) {
  if (!row?.routeId) return false;
  if (String(row.status || '') === 'COMPLETED') return false;
  // CANCELLED：仍可幂等收口历史脏出库/在途
  if (String(row.status || '') === 'CANCELLED') return true;
  const tasks: Row[] = row.tasks || [];
  if (!tasks.length) return true;
  return tasks.every((t) => {
    if (['COMPLETED', 'CANCELLED'].includes(String(t.status || ''))) return true;
    return !t.checkInAt;
  });
}

async function cancelEmptyRoute(row: Row) {
  if (!row?.routeId) return;
  const orphanCleanup = String(row.status || '') === 'CANCELLED';
  try {
    await ElMessageBox.confirm(
      orphanCleanup
        ? `确认收口路线 #${row.routeId} 的脏出库/在途？\n已发运未签收将回仓并取消在途。`
        : `确认取消空路线 #${row.routeId}（${row.routeName || ''}）？\n仅未签到且未交接的任务可取消；已发运未签收会回仓。`,
      orphanCleanup ? '收口脏出库' : '取消空路线',
      { type: 'warning', confirmButtonText: orphanCleanup ? '确认收口' : '确认取消' }
    );
  } catch {
    return;
  }
  cancelRouteLoading.value = row.routeId;
  try {
    await api.request(`/api/v2/ops/admin/replenishment/routes/${row.routeId}/cancel-empty`, 'POST');
    ElMessage.success(orphanCleanup ? `路线 #${row.routeId} 脏出库已收口` : `路线 #${row.routeId} 已取消`);
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '取消失败');
  } finally {
    cancelRouteLoading.value = null;
  }
}

async function openRestockDoor(task: Row) {
  if (!task?.checkInAt) {
    ElMessage.warning('请先到店签到后再补货开门');
    return;
  }
  if (!deviceOnline(task.deviceId)) {
    ElMessage.warning(`${deviceName(task.deviceId)} 当前离线，无法下发补货开门`);
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认对 ${deviceName(task.deviceId)}（${task.deviceId}）下发补货开门？\n将绑定任务 #${task.taskId}，不产生消费者账单。\n（与设备详情「远程开门」不同）`,
      '补货开门',
      { type: 'warning', confirmButtonText: '开门' }
    );
  } catch {
    return;
  }
  openDoorLoading.value = task.taskId;
  try {
    const session = await api.request<{ sessionId?: string }>(
      '/api/v2/ops/restock/open-door',
      'POST',
      { deviceId: task.deviceId, taskId: task.taskId }
    );
    ElMessage.success({
      message: session?.sessionId ? `开门已下发（${session.sessionId}）` : '开门指令已下发',
      duration: 4000
    });
    await load();
  } catch (error) {
    ElMessage.error({
      message: error instanceof Error ? error.message : '开门失败',
      duration: 5000
    });
  } finally {
    openDoorLoading.value = null;
  }
}

async function createPlan() {
  if (!planForm.routeName.trim()) return ElMessage.warning('请填写路线名称');
  if (!planForm.deviceIds.length) return ElMessage.warning('请至少选择一台设备');
  saving.value = true;
  try {
    const route = await api.request<Row>('/api/v2/ops/admin/replenishment/plan', 'POST', {
      ...planForm,
      startLatitude: null,
      startLongitude: null
    });
    planDialog.value = false;
    tab.value = 'routes';
    syncRouteQuery();
    await load();
    const outbounds = await api.request<Row[]>('/api/v2/ops/admin/warehouse/outbounds').catch(() => []);
    const linked = (outbounds || []).filter((o) => o.routeId === route?.routeId);
    if (linked.length) {
      ElMessage.success({
        message: `路线已创建，出库单 #${linked[0].outboundId} 待拣货发运（仓库页）`,
        duration: 5000
      });
    } else {
      ElMessage.warning({
        message: '路线已创建，但未生成出库明细（仓库可用库存不足），可现场补录上架',
        duration: 5000
      });
    }
  } catch (error) {
    ElMessage.error({
      message: error instanceof Error ? error.message : '路线创建失败',
      duration: 5000
    });
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
    const [r, req, devicePage, disc] = await Promise.all([
      api.request<Row[]>('/api/v2/ops/admin/replenishment/routes', 'GET'),
      api.request<Row[]>('/api/v2/ops/admin/replenishment/requests?status=SUBMITTED', 'GET'),
      api.request<PageResult<Row>>('/api/v2/ops/admin/devices?page=0&size=200', 'GET'),
      api.request<Row[]>('/api/v2/ops/admin/slots/discrepancies', 'GET').catch(() => [])
    ]);
    routes.value = r || [];
    requests.value = req || [];
    devices.value = devicePage?.items || [];
    clearRoutesSelection();
    clearRequestsSelection();
    const lowStock: Row[] = [];
    for (const d of devices.value) {
      try {
        const suggest = await api.request<Row[]>(
          `/api/v2/ops/admin/replenishment/suggest/slots?deviceId=${encodeURIComponent(d.deviceId)}`,
          'GET'
        );
        for (const s of suggest || []) {
          if ((s.bookQty ?? 0) <= (s.minLevel ?? 0) || s.stockStatus === 'OOS' || s.stockStatus === 'LOW') {
            lowStock.push({ ...s, deviceId: d.deviceId });
          }
        }
      } catch {
        /* ignore per-device */
      }
    }
    const byKey = new Map<string, Row>();
    for (const row of [...(disc || []), ...lowStock]) {
      const key = `${row.deviceId}:${row.slotCode || row.skuId || ''}`;
      if (!byKey.has(key)) byKey.set(key, { ...row, slotKey: key });
    }
    let list = [...byKey.values()];
    if (focusDeviceId.value.trim()) {
      list = list.filter((x) => x.deviceId === focusDeviceId.value.trim());
    }
    shortages.value = list;
    clearShortageSelection();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '补货数据加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  applyRouteQuery();
  syncRouteQuery();
  load();
});
onActivated(() => {
  if (applyRouteQuery()) {
    page.value = 1;
    load();
  }
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
.page-card-head__meta { min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.kpi-tags { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.page-card-head__actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.route-detail { padding: 8px 44px 12px; }
.route-meta { display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 12px; color: var(--layout-muted); font-size: 13px; }
.line-table { width: 100%; }
.master-data-cell { display: grid; gap: 2px; line-height: 1.35; }
.master-data-cell strong { color: var(--layout-text); font-weight: 650; }
.master-data-cell small { color: var(--layout-muted); font-size: 11px; font-family: var(--app-font-mono); }
.device-link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  display: grid;
  gap: 2px;
  text-align: left;
  cursor: pointer;
  color: inherit;
  font: inherit;
  line-height: 1.35;
}
.device-link-cell strong { color: var(--el-color-primary); font-weight: 650; }
.device-link-cell small { color: var(--layout-muted); font-size: 11px; font-family: var(--app-font-mono); }
.device-link-cell:hover strong { text-decoration: underline; }
.muted { color: var(--layout-muted); font-size: 13px; }
.online-tag { margin-left: 6px; vertical-align: middle; }
.shortage-toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.plan-hint { margin-top: 6px; font-size: 12px; color: var(--el-color-warning); line-height: 1.4; }
.plan-form { margin-top: 4px; }
.plan-device-list {
  width: 100%;
  max-height: 220px;
  overflow: auto;
  border: 1px solid var(--layout-border);
  border-radius: 6px;
  padding: 4px 0;
  background: var(--layout-card);
}
.plan-device-group {
  display: flex;
  flex-direction: column;
  width: 100%;
}
.plan-device-option {
  display: flex;
  align-items: center;
  min-height: 40px;
  padding: 6px 12px;
  cursor: pointer;
  box-sizing: border-box;
}
.plan-device-option:hover {
  background: var(--el-fill-color-light);
}
.plan-device-option :deep(.el-checkbox) {
  width: 100%;
  height: auto;
  margin-right: 0;
}
.plan-device-option :deep(.el-checkbox__label) {
  white-space: normal;
  line-height: 1.35;
}
.plan-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  position: relative;
  z-index: 2;
}
.plan-create-btn {
  position: relative;
  z-index: 3;
  min-width: 96px;
  min-height: 36px;
  pointer-events: auto;
}
.native-date {
  width: 100%; height: 32px; padding: 0 10px; border: 1px solid var(--layout-border);
  border-radius: 4px; color: var(--layout-text); background: var(--layout-card); box-sizing: border-box;
}
@media (max-width: 760px) {
  .route-detail { padding: 8px 12px; }
}
</style>
