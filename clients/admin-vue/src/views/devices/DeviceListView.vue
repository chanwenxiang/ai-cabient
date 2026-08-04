<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备管理</span>
            <span class="hint">运营状态看板：在线 / 离线 / 在售 / 停售一键筛选；可批量锁机停售或解锁营业</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="danger"
            plain
            :disabled="!selectedKeys.length"
            :loading="batchCmdLoading === 'LOCK'"
            @click="batchCommand('LOCK')"
          >批量停售</el-button>
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="success"
            plain
            :disabled="!selectedKeys.length"
            :loading="batchCmdLoading === 'UNLOCK'"
            @click="batchCommand('UNLOCK')"
          >批量恢复</el-button>
          <el-button
            v-hasPermi="['ops:device:edit']"
            plain
            :disabled="!selectedKeys.length"
            :loading="batchCmdLoading === 'DEPLOY'"
            @click="batchLifecycle('DEPLOY')"
          >批量投放</el-button>
          <el-button
            v-hasPermi="['ops:device:edit']"
            plain
            :disabled="!selectedKeys.length"
            :loading="batchCmdLoading === 'UNDEPLOY'"
            @click="batchLifecycle('UNDEPLOY')"
          >批量未投放</el-button>
          <el-button v-hasPermi="['ops:device:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button type="primary" :icon="Refresh" :loading="loading" @click="load(false)">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="ops-board" role="group" aria-label="设备运营状态看板">
      <button
        v-for="tile in boardTiles"
        :key="tile.key"
        type="button"
        class="ops-board__tile"
        :class="{
          active: boardTab === tile.key,
          warn: tile.warn && boardCounts[tile.key] > 0
        }"
        @click="selectBoard(tile.key)"
      >
        <span class="ops-board__label">{{ tile.label }}</span>
        <span class="ops-board__value">{{ boardCounts[tile.key] }}</span>
        <span v-if="tile.hint" class="ops-board__hint">{{ tile.hint }}</span>
      </button>
    </div>

    <el-alert
      v-if="attentionCount > 0 && boardTab === 'ALL'"
      type="warning"
      :closable="false"
      show-icon
      class="ops-banner"
      :title="`需关注 ${attentionCount} 台：离线 ${boardCounts.OFFLINE} · 停售 ${boardCounts.LOCKED}（点击看板筛选）`"
    />

    <el-tabs v-model="boardTab" class="status-tabs" @tab-change="onBoardTab">
      <el-tab-pane :label="`全部 (${boardCounts.ALL})`" name="ALL" />
      <el-tab-pane :label="`在线 (${boardCounts.ONLINE})`" name="ONLINE" />
      <el-tab-pane :label="`离线 (${boardCounts.OFFLINE})`" name="OFFLINE" />
      <el-tab-pane :label="`在售 (${boardCounts.ON_SALE})`" name="ON_SALE" />
      <el-tab-pane :label="`停售 (${boardCounts.LOCKED})`" name="LOCKED" />
    </el-tabs>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          placeholder="编号 / 名称 / 商户 / IMEI / 标签"
          clearable
          style="width: 220px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="生命周期">
        <el-select v-model="lifecycleFilter" clearable placeholder="全部" style="width: 130px" @change="search">
          <el-option
            v-for="item in dictOptions('device_lifecycle')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="合作方式">
        <el-select v-model="coopFilter" clearable placeholder="全部" style="width: 120px" @change="search">
          <el-option
            v-for="item in dictOptions('device_coop_mode')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="路线">
        <el-select
          v-model="routeFilter"
          clearable
          filterable
          placeholder="全部"
          style="width: 160px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('route_code')"
            :key="item.value"
            :label="`${item.label}（${item.value}）`"
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
          <el-table-column label="运营态" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="row.salesLocked ? 'danger' : 'success'" size="small">
                {{ row.salesLocked ? '停售' : '在售' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="生命周期" width="96" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ lifecycleLabel(row.lifecycleStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="IMEI" min-width="120" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ row.imei || '-' }}</template>
          </el-table-column>
          <el-table-column label="资产方" min-width="100" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ row.assetOwner || '-' }}</template>
          </el-table-column>
          <el-table-column label="路线" width="90" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ row.routeCode || '-' }}</template>
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
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import type { DeviceInfo, PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type BoardTab = 'ALL' | 'ONLINE' | 'OFFLINE' | 'ON_SALE' | 'LOCKED';

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const loading = ref(false);
const keyword = ref('');
const lifecycleFilter = ref('');
const coopFilter = ref('');
const routeFilter = ref('');
const boardTab = ref<BoardTab>('ALL');
const devices = ref<DeviceInfo[]>([]);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const batchCmdLoading = ref('');
const policyVisible = ref(false);
const policySaving = ref(false);
const boardCounts = reactive({
  ALL: 0,
  ONLINE: 0,
  OFFLINE: 0,
  ON_SALE: 0,
  LOCKED: 0
});
const boardTiles: { key: BoardTab; label: string; hint?: string; warn?: boolean }[] = [
  { key: 'ALL', label: '全部设备' },
  { key: 'ONLINE', label: '在线', hint: '心跳正常' },
  { key: 'OFFLINE', label: '离线', hint: '需巡检', warn: true },
  { key: 'ON_SALE', label: '在售', hint: '可营业' },
  { key: 'LOCKED', label: '停售', hint: '已锁机', warn: true }
];
const policyForm = reactive({
  deviceId: '',
  deviceLabel: '',
  refundPolicy: 'INHERIT'
});

const attentionCount = computed(() => boardCounts.OFFLINE + boardCounts.LOCKED);

function boardQuery(tab: BoardTab): { online?: string; salesLocked?: string } {
  switch (tab) {
    case 'ONLINE':
      return { online: 'ONLINE' };
    case 'OFFLINE':
      return { online: 'OFFLINE' };
    case 'ON_SALE':
      return { salesLocked: 'false' };
    case 'LOCKED':
      return { salesLocked: 'true' };
    default:
      return {};
  }
}

function tabFromRouteQuery(): BoardTab {
  if (typeof route.query.online === 'string') {
    const online = route.query.online.toUpperCase();
    if (online === 'ONLINE' || online === 'OFFLINE') return online;
  }
  if (typeof route.query.salesLocked === 'string') {
    if (route.query.salesLocked === 'true') return 'LOCKED';
    if (route.query.salesLocked === 'false') return 'ON_SALE';
  }
  if (typeof route.query.tab === 'string') {
    const tab = route.query.tab.toUpperCase();
    if (tab === 'ONLINE' || tab === 'OFFLINE' || tab === 'ON_SALE' || tab === 'LOCKED' || tab === 'ALL') {
      return tab as BoardTab;
    }
  }
  return 'ALL';
}

function effectivePolicy(row: DeviceInfo) {
  return row.effectiveRefundPolicy || row.refundPolicy || 'AUTO_REFUND';
}

function policyLabel(policy?: string | null) {
  if (policy === 'DISPUTE_ONLY') return '仅申诉审核';
  return '自助退款';
}

function lifecycleLabel(status?: string | null) {
  return dictLabel('device_lifecycle', status || 'DEPLOYED');
}

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection, selectedKeys } =
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
  headers: ['设备编号', '名称', '类型', '在线', '运营态', '生命周期', 'IMEI', '资产方', '路线', '商户编号', '商户', '退款方式', '最近会话', '会话状态', '更新时间'],
  toRows: () =>
    pickSelected(devices.value).map((row) => [
      row.deviceId,
      row.deviceName,
      dictLabel('device_type', row.deviceType),
      dictLabel('online_status', row.onlineStatus),
      row.salesLocked ? '停售' : '在售',
      lifecycleLabel(row.lifecycleStatus),
      row.imei,
      row.assetOwner,
      row.routeCode,
      row.merchantId,
      row.merchantName,
      `${policyLabel(effectivePolicy(row))}${row.refundPolicy ? '' : '(全局)'}`,
      row.activeSessionId,
      row.activeSessionState ? dictLabel('session_state', row.activeSessionState) : '-',
      formatDateTime(row.updatedAt)
    ])
});

async function batchLifecycle(action: 'DEPLOY' | 'UNDEPLOY') {
  const targets = devices.value.filter((d) => selectedKeys.value.map(String).includes(d.deviceId));
  if (!targets.length) {
    ElMessage.warning('请先勾选设备');
    return;
  }
  const label = action === 'DEPLOY' ? '投放' : '未投放';
  try {
    await ElMessageBox.confirm(`将对 ${targets.length} 台执行「${label}」，确认？`, label, {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  batchCmdLoading.value = action;
  let ok = 0;
  let fail = 0;
  try {
    for (const row of targets) {
      try {
        await api.request(
          `/api/v2/ops/admin/devices/${encodeURIComponent(row.deviceId)}/lifecycle`,
          'POST',
          { action, remark: `batch-${action.toLowerCase()}` }
        );
        ok += 1;
      } catch {
        fail += 1;
      }
    }
    if (fail === 0) ElMessage.success(`已${label} ${ok} 台`);
    else ElMessage.warning(`${label}完成：成功 ${ok}，失败 ${fail}`);
    clearSelection();
    await load(false);
  } finally {
    batchCmdLoading.value = '';
  }
}

async function batchCommand(command: 'LOCK' | 'UNLOCK') {
  const targets = devices.value.filter((d) => selectedKeys.value.map(String).includes(d.deviceId));
  if (!targets.length) {
    ElMessage.warning('请先勾选设备');
    return;
  }
  const label = command === 'LOCK' ? '锁机停售' : '解锁营业';
  try {
    await ElMessageBox.confirm(
      `将对 ${targets.length} 台设备执行「${label}」，确认继续？`,
      label,
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  batchCmdLoading.value = command;
  let ok = 0;
  let fail = 0;
  try {
    for (const row of targets) {
      try {
        const result = await api.request<{ salesLocked?: boolean }>(
          `/api/v2/ops/admin/devices/${encodeURIComponent(row.deviceId)}/commands`,
          'POST',
          { command, reason: `batch-${command.toLowerCase()}` }
        );
        const idx = devices.value.findIndex((d) => d.deviceId === row.deviceId);
        if (idx >= 0) {
          devices.value[idx] = {
            ...devices.value[idx],
            salesLocked: result.salesLocked ?? command === 'LOCK'
          };
        }
        ok += 1;
      } catch {
        fail += 1;
      }
    }
    if (fail === 0) ElMessage.success(`已${label} ${ok} 台`);
    else ElMessage.warning(`${label}完成：成功 ${ok}，失败 ${fail}`);
    clearSelection();
  } finally {
    batchCmdLoading.value = '';
  }
}

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
  if (lifecycleFilter.value) query.lifecycleStatus = lifecycleFilter.value;
  if (coopFilter.value) query.coopMode = coopFilter.value;
  if (routeFilter.value.trim()) query.routeCode = routeFilter.value.trim();
  const filters = boardQuery(boardTab.value);
  if (filters.online) query.online = filters.online;
  if (filters.salesLocked) query.salesLocked = filters.salesLocked;
  router.replace({ query });
}

async function refreshBoardCounts() {
  const specs: { key: BoardTab; online?: string; salesLocked?: string }[] = [
    { key: 'ALL' },
    { key: 'ONLINE', online: 'ONLINE' },
    { key: 'OFFLINE', online: 'OFFLINE' },
    { key: 'ON_SALE', salesLocked: 'false' },
    { key: 'LOCKED', salesLocked: 'true' }
  ];
  await Promise.all(
    specs.map(async (spec) => {
      try {
        const q = new URLSearchParams({ page: '0', size: '1' });
        if (keyword.value.trim()) q.set('q', keyword.value.trim());
        if (spec.online) q.set('online', spec.online);
        if (spec.salesLocked) q.set('salesLocked', spec.salesLocked);
        const data = await api.request<PageResult<DeviceInfo>>(`/api/v2/ops/admin/devices?${q}`, 'GET');
        boardCounts[spec.key] = data.total || 0;
      } catch {
        /* keep previous */
      }
    })
  );
}

function selectBoard(tab: BoardTab) {
  if (boardTab.value === tab) return;
  boardTab.value = tab;
  page.value = 1;
  syncRouteQuery();
  load(false);
}

function onBoardTab(name: string | number) {
  boardTab.value = String(name) as BoardTab;
  page.value = 1;
  syncRouteQuery();
  load(false);
}

async function load(showToast = false) {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    if (keyword.value.trim()) q.set('q', keyword.value.trim());
    if (lifecycleFilter.value) q.set('lifecycleStatus', lifecycleFilter.value);
    if (coopFilter.value) q.set('coopMode', coopFilter.value);
    if (routeFilter.value.trim()) q.set('routeCode', routeFilter.value.trim());
    const filters = boardQuery(boardTab.value);
    if (filters.online) q.set('online', filters.online);
    if (filters.salesLocked) q.set('salesLocked', filters.salesLocked);
    const data = await api.request<PageResult<DeviceInfo>>(`/api/v2/ops/admin/devices?${q}`, 'GET');
    devices.value = data.items || [];
    total.value = data.total || 0;
    if (boardTab.value in boardCounts) {
      boardCounts[boardTab.value] = data.total || 0;
    }
    clearSelection();
    void refreshBoardCounts();
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
  lifecycleFilter.value = '';
  coopFilter.value = '';
  routeFilter.value = '';
  boardTab.value = 'ALL';
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
  const nextTab = tabFromRouteQuery();
  if (nextTab !== boardTab.value) {
    boardTab.value = nextTab;
    changed = true;
  }
  const nextKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (nextKeyword !== keyword.value) {
    keyword.value = nextKeyword;
    changed = true;
  }
  const nextLifecycle = typeof route.query.lifecycleStatus === 'string' ? route.query.lifecycleStatus : '';
  if (nextLifecycle !== lifecycleFilter.value) {
    lifecycleFilter.value = nextLifecycle;
    changed = true;
  }
  const nextCoop = typeof route.query.coopMode === 'string' ? route.query.coopMode : '';
  if (nextCoop !== coopFilter.value) {
    coopFilter.value = nextCoop;
    changed = true;
  }
  const nextRoute = typeof route.query.routeCode === 'string' ? route.query.routeCode : '';
  if (nextRoute !== routeFilter.value) {
    routeFilter.value = nextRoute;
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
.ops-board {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.ops-board__tile {
  appearance: none;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
  display: grid;
  gap: 2px;
  transition: border-color 0.15s ease, background 0.15s ease, box-shadow 0.15s ease;
  color: inherit;
  font: inherit;
  min-width: 0;
}
.ops-board__tile:hover,
.ops-board__tile:focus-visible {
  border-color: var(--el-color-primary-light-5);
  outline: none;
}
.ops-board__tile.active {
  border-color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 8%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--el-color-primary) 35%, transparent);
}
.ops-board__tile.warn:not(.active) {
  border-color: color-mix(in srgb, var(--el-color-warning) 45%, var(--el-border-color-lighter));
  background: color-mix(in srgb, var(--el-color-warning) 8%, transparent);
}
.ops-board__tile.warn.active {
  border-color: var(--el-color-warning);
  background: color-mix(in srgb, var(--el-color-warning) 12%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--el-color-warning) 40%, transparent);
}
.ops-board__label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ops-board__value {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.ops-board__hint {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.ops-banner {
  margin-bottom: 10px;
}
.status-tabs {
  margin: 0 0 10px;
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
@media (max-width: 900px) {
  .ops-board {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 560px) {
  .ops-board {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
