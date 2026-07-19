<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备管理</span>
            <span class="hint">在离线筛选、退款策略与运维详情入口</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:device:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button type="primary" :icon="Refresh" :loading="loading" @click="load(false)">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          placeholder="编号 / 名称 / 商户"
          clearable
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
      <div class="table-scroll-inner" style="min-width: 1080px">
        <el-table
          v-loading="loading"
          :data="devices"
          stripe
          border
          class="report-table"
          row-key="deviceId"
          @selection-change="onSelectionChange"
        >
          <template #empty><el-empty description="暂无设备" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="设备" min-width="180" class-name="col-text">
            <template #default="{ row }">
              <button type="button" class="device-cell" @click="goDetail(row)">
                <strong>{{ row.deviceName || row.deviceId }}</strong>
                <small>{{ row.deviceId }}</small>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="类型" min-width="100" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ dictLabel('device_type', row.deviceType) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="row.onlineStatus === 'ONLINE' ? 'success' : 'info'" size="small">
                {{ dictLabel('online_status', row.onlineStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="商户" min-width="160" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="master-data-cell">
                <strong>{{ row.merchantName || row.merchantId || '-' }}</strong>
                <small v-if="row.merchantId">{{ row.merchantId }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="退款方式" min-width="168" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tag :type="effectivePolicy(row) === 'DISPUTE_ONLY' ? 'warning' : 'success'" size="small">
                {{ policyLabel(effectivePolicy(row)) }}
              </el-tag>
              <span v-if="!row.refundPolicy" class="inherit-hint">全局默认</span>
            </template>
          </el-table-column>
          <el-table-column label="最近会话" min-width="140" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.activeSessionId" class="mono">{{ row.activeSessionId }}</span>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="会话状态" min-width="100" align="center">
            <template #default="{ row }">
              {{ row.activeSessionState ? dictLabel('session_state', row.activeSessionState) : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="更新时间" min-width="168" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                :actions="deviceActions(row)"
                @action="(key: string) => onRowAction(key, row)"
              />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="() => load(false)"
        @size-change="onSizeChange"
      />
    </div>

    <el-dialog
      v-model="policyVisible"
      title="设置退款方式"
      width="440px"
      destroy-on-close
      append-to-body
      align-center
    >
      <el-form label-position="top">
        <el-form-item label="设备">
          <el-input :model-value="policyForm.deviceLabel" disabled />
        </el-form-item>
        <el-form-item label="退款方式">
          <el-select
            v-model="policyForm.refundPolicy"
            placeholder="请选择"
            style="width: 100%"
            teleported
          >
            <el-option label="跟随全局默认" value="INHERIT" />
            <el-option label="消费者可自助退款" value="AUTO_REFUND" />
            <el-option label="仅可申诉，运营审核后退款" value="DISPUTE_ONLY" />
          </el-select>
          <p class="policy-hint">{{ policyHint }}</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="policyVisible = false">取消</el-button>
        <el-button v-hasPermi="['ops:device:edit']" type="primary" :loading="policySaving" @click="savePolicy">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh, Setting, View } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import type { DeviceInfo, PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const loading = ref(false);
const keyword = ref('');
const onlineFilter = ref('');
const devices = ref<DeviceInfo[]>([]);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const policyVisible = ref(false);
const policySaving = ref(false);
const policyForm = reactive({
  deviceId: '',
  deviceLabel: '',
  refundPolicy: 'INHERIT'
});

function effectivePolicy(row: DeviceInfo) {
  return row.effectiveRefundPolicy || row.refundPolicy || 'AUTO_REFUND';
}

function policyLabel(policy?: string | null) {
  if (policy === 'DISPUTE_ONLY') return '仅申诉审核';
  return '自助退款';
}

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<DeviceInfo>((r) => r.deviceId);

const policyHint = computed(() => {
  switch (policyForm.refundPolicy) {
    case 'AUTO_REFUND':
      return '消费者可在订单页一键退款，资金即时原路退回。';
    case 'DISPUTE_ONLY':
      return '消费者只能提交申诉，需运营核对录像后再退款。';
    default:
      return '不单独设置本柜，沿用系统「参数配置」里的全局退款默认。';
  }
});

const { onExport } = useListCsv({
  filePrefix: '设备',
  headers: ['设备编号', '名称', '类型', '状态', '商户编号', '商户', '退款方式', '最近会话', '会话状态', '更新时间'],
  toRows: () =>
    pickSelected(devices.value).map((row) => [
      row.deviceId,
      row.deviceName,
      dictLabel('device_type', row.deviceType),
      dictLabel('online_status', row.onlineStatus),
      row.merchantId,
      row.merchantName,
      `${policyLabel(effectivePolicy(row))}${row.refundPolicy ? '' : '(全局)'}`,
      row.activeSessionId,
      row.activeSessionState ? dictLabel('session_state', row.activeSessionState) : '-',
      formatDateTime(row.updatedAt)
    ])
});

function goDetail(row: DeviceInfo) {
  if (!row?.deviceId) return;
  // 使用具名路由 + encode，避免偶发路径匹配失败被 catch-all 打回工作台
  router.push({
    name: 'device-detail',
    params: { id: row.deviceId },
  }).catch(() => {
    router.push(`/devices/${encodeURIComponent(row.deviceId)}`);
  });
}

function deviceActions(_row: DeviceInfo): TableAction[] {
  const actions: TableAction[] = [{ key: 'detail', label: '详情', icon: View, type: 'primary' }];
  if (auth.hasPerm('ops:device:edit')) {
    actions.push({ key: 'policy', label: '退款设置', icon: Setting, type: 'warning' });
  }
  return actions;
}

function onRowAction(key: string, row: DeviceInfo) {
  if (key === 'detail') {
    goDetail(row);
    return;
  }
  if (key === 'policy') {
    openPolicy(row);
  }
}

function openPolicy(row: DeviceInfo) {
  policyForm.deviceId = row.deviceId;
  policyForm.deviceLabel = row.deviceName
    ? `${row.deviceName}（${row.deviceId}）`
    : row.deviceId;
  policyForm.refundPolicy = row.refundPolicy || 'INHERIT';
  policyVisible.value = true;
}

async function savePolicy() {
  if (!policyForm.deviceId) {
    ElMessage.warning('缺少设备编号');
    return;
  }
  policySaving.value = true;
  try {
    const updated = await api.request<DeviceInfo>(
      `/api/v2/ops/admin/devices/${encodeURIComponent(policyForm.deviceId)}`,
      'PATCH',
      { refundPolicy: policyForm.refundPolicy }
    );
    const idx = devices.value.findIndex((d) => d.deviceId === updated.deviceId);
    if (idx >= 0) {
      devices.value[idx] = { ...devices.value[idx], ...updated };
    } else {
      await load(false);
    }
    ElMessage.success(`已保存：${policyLabel(updated.effectiveRefundPolicy || updated.refundPolicy)}`);
    policyVisible.value = false;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    policySaving.value = false;
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (onlineFilter.value) query.online = onlineFilter.value;
  router.replace({ query });
}

async function load(showToast = false) {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    if (keyword.value.trim()) q.set('q', keyword.value.trim());
    if (onlineFilter.value) q.set('online', onlineFilter.value);
    const data = await api.request<PageResult<DeviceInfo>>(`/api/v2/ops/admin/devices?${q}`, 'GET');
    devices.value = data.items || [];
    total.value = data.total || 0;
    clearSelection();
    if (showToast) ElMessage.success('已刷新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load(false);
}
function reset() {
  keyword.value = '';
  onlineFilter.value = '';
  page.value = 1;
  syncRouteQuery();
  load(false);
}
function onSizeChange() {
  page.value = 1;
  load(false);
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

onMounted(() => {
  applyRouteQuery();
  load(false);
});
onActivated(() => {
  if (applyRouteQuery()) {
    page.value = 1;
    load(false);
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
.page-card-head__meta {
  min-width: 0;
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
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
}
.device-cell {
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
.device-cell strong {
  color: var(--el-color-primary);
  font-weight: 650;
}
.device-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
.device-cell:hover strong {
  text-decoration: underline;
}
.master-data-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
}
.master-data-cell strong {
  font-weight: 650;
}
.master-data-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
.inherit-hint {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.policy-hint {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.mono {
  font-family: var(--app-font-mono);
  font-size: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
}
</style>
