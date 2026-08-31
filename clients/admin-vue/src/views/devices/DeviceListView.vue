<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备管理</span>
            <span class="hint"
              >运营状态看板：在线 / 离线 / 在售 / 停售一键筛选；可批量锁机停售或解锁营业</span
            >
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
            >批量停售</el-button
          >
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="success"
            plain
            :disabled="!selectedKeys.length"
            :loading="batchCmdLoading === 'UNLOCK'"
            @click="batchCommand('UNLOCK')"
            >批量恢复</el-button
          >
          <el-button
            v-hasPermi="['ops:device:edit']"
            plain
            :disabled="!selectedKeys.length"
            :loading="batchCmdLoading === 'DEPLOY'"
            @click="batchLifecycle('DEPLOY')"
            >批量投放</el-button
          >
          <el-button
            v-hasPermi="['ops:device:edit']"
            plain
            :disabled="!selectedKeys.length"
            :loading="batchCmdLoading === 'UNDEPLOY'"
            @click="batchLifecycle('UNDEPLOY')"
            >批量未投放</el-button
          >
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="warning"
            plain
            :disabled="!selectedKeys.length"
            :loading="batchCmdLoading === 'RETIRE'"
            @click="batchRetire"
            >批量退役</el-button
          >
          <el-button v-hasPermi="['ops:device:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button v-hasPermi="['ops:device:create']" type="primary" @click="openCreate"
            >新建设备</el-button
          >
          <el-button :icon="Refresh" :loading="loading" @click="load(false)">刷新</el-button>
        </div>
      </div>
    </template>

    <fieldset class="ops-board" aria-label="设备运营状态看板">
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
        <span class="ops-board__value">{{ formatBoardCount(tile.key) }}</span>
        <span v-if="tile.hint" class="ops-board__hint">{{
          boardHydrated ? tile.hint : '加载中…'
        }}</span>
      </button>
    </fieldset>

    <el-alert
      v-if="boardHydrated && attentionCount > 0 && boardTab === 'ALL'"
      type="warning"
      :closable="false"
      show-icon
      class="ops-banner"
      :title="`需关注 ${attentionCount} 台：离线 ${boardCounts.OFFLINE} · 停售 ${boardCounts.LOCKED}（点击看板筛选）`"
    />

    <el-tabs v-model="boardTab" class="status-tabs" @tab-change="onBoardTab">
      <el-tab-pane :label="boardTabLabel('ALL', '全部')" name="ALL" />
      <el-tab-pane :label="boardTabLabel('ONLINE', '在线')" name="ONLINE" />
      <el-tab-pane :label="boardTabLabel('OFFLINE', '离线')" name="OFFLINE" />
      <el-tab-pane :label="boardTabLabel('ON_SALE', '在售')" name="ON_SALE" />
      <el-tab-pane :label="boardTabLabel('LOCKED', '停售')" name="LOCKED" />
    </el-tabs>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          placeholder="编号 / 名称 / 商户 / IMEI / 标签…"
          clearable
          style="width: 220px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="生命周期">
        <el-select
          v-model="lifecycleFilter"
          clearable
          placeholder="全部"
          style="width: 130px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('device_lifecycle')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="合作方式">
        <el-select
          v-model="coopFilter"
          clearable
          placeholder="全部"
          style="width: 120px"
          @change="search"
        >
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
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="devices"
          stripe
          border
          class="report-table"
          row-key="deviceId"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty>
            <el-empty v-if="listHydrated && !loading" description="暂无设备" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="deviceId"
            label="设备编号"
            min-width="140"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.deviceId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="设备" min-width="140" align="center" class-name="col-text">
            <template #default="{ row }">
              <button type="button" class="link-cell" @click="goDetail(row)">
                <img
                  class="device-thumb"
                  :src="'/admin/device-default.png'"
                  alt=""
                  aria-hidden="true"
                />
                <span>{{ row.deviceName || '无' }}</span>
              </button>
            </template>
          </el-table-column>
          <el-table-column
            label="类型"
            min-width="100"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
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
          <el-table-column label="柜内温度" width="90" align="center">
            <template #default="{ row }">
              <span
                :class="
                  row.currentTempC != null &&
                  row.targetTempC != null &&
                  Math.abs(row.currentTempC - row.targetTempC) > 2
                    ? 'temp-warn'
                    : ''
                "
                >{{ row.currentTempC != null ? `${row.currentTempC}°C` : '暂无' }}</span
              >
            </template>
          </el-table-column>
          <el-table-column label="停售原因" min-width="140" align="center" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.salesLocked && row.salesLockReason">{{ row.salesLockReason }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="固件" width="88" align="center" show-overflow-tooltip>
            <template #default="{ row }">{{ row.firmwareVersion || '暂无' }}</template>
          </el-table-column>
          <el-table-column
            label="地址"
            min-width="160"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.address || '暂无' }}</template>
          </el-table-column>
          <el-table-column
            label="IMEI"
            min-width="120"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.imei || '无' }}</template>
          </el-table-column>
          <el-table-column
            label="资产方"
            min-width="100"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.assetOwner || '无' }}</template>
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
            label="商户"
            min-width="120"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.merchantName || row.merchantId || '无' }}</template>
          </el-table-column>
          <el-table-column
            label="退款方式"
            min-width="168"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <el-tag
                :type="effectivePolicy(row) === 'DISPUTE_ONLY' ? 'warning' : 'success'"
                size="small"
              >
                {{ policyLabel(effectivePolicy(row)) }}
              </el-tag>
              <span v-if="!row.refundPolicy" class="inherit-hint">全局默认</span>
            </template>
          </el-table-column>
          <el-table-column
            label="最近会话"
            min-width="140"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span v-if="row.activeSessionId" class="mono">{{
                displayBizNo(row.activeSessionId)
              }}</span>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="会话状态" min-width="100" align="center">
            <template #default="{ row }">
              {{
                row.activeSessionState ? dictLabel('session_state', row.activeSessionState) : '无'
              }}
            </template>
          </el-table-column>
          <el-table-column
            label="更新时间"
            min-width="168"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" class-name="col-action" align="center" fixed="right">
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

    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      background
      @current-change="() => load(false)"
      @size-change="onSizeChange"
    />

    <el-dialog
      v-model="createVisible"
      title="新建设备"
      width="480px"
      destroy-on-close
      append-to-body
      align-center
    >
      <el-form label-width="88px">
        <el-form-item label="设备编号">
          <el-input
            v-model="createForm.deviceId"
            placeholder="留空则系统自动分配（6–10 位数字）"
            maxlength="10"
            inputmode="numeric"
          />
          <p v-if="suggestedDeviceId" class="form-hint muted">
            建议编号 {{ suggestedDeviceId }}（与机身贴码一致）；柜机首次联网时将自动绑定 IMEI / 主板 SN
          </p>
        </el-form-item>
        <el-form-item label="设备名称">
          <el-input v-model="createForm.deviceName" placeholder="可选…" />
        </el-form-item>
        <el-form-item label="设备类型">
          <el-select
            v-model="createForm.deviceType"
            clearable
            placeholder="可选"
            style="width: 100%"
          >
            <el-option
              v-for="item in dictOptions('device_type')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="商户">
          <el-select
            v-model="createForm.merchantId"
            filterable
            clearable
            placeholder="可选"
            style="width: 100%"
          >
            <el-option
              v-for="m in merchantOptions"
              :key="m.merchantId"
              :label="`${m.merchantName || m.merchantId}（${m.merchantId}）`"
              :value="m.merchantId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSaving" @click="saveCreate">创建</el-button>
      </template>
    </el-dialog>

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
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="primary"
          :loading="policySaving"
          @click="savePolicy"
          >保存</el-button
        >
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
import PagePager from '@/components/PagePager.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import type { DeviceInfo, PageResult } from '@aicabinet/shared-types';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

type BoardTab = 'ALL' | 'ONLINE' | 'OFFLINE' | 'ON_SALE' | 'LOCKED';

interface MerchantOption {
  merchantId: string;
  merchantName?: string;
}

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const loading = ref(false);
const listHydrated = ref(false);
const keyword = ref('');
const lifecycleFilter = ref('');
const coopFilter = ref('');
const routeFilter = ref('');
const boardTab = ref<BoardTab>('ALL');
const devices = ref<DeviceInfo[]>([]);
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('deviceId', {
  onChange: () => {
    devices.value = sortById([...devices.value], 'deviceId');
  }
});
const page = ref(1);
const size = ref(20);
const total = ref(0);
const batchCmdLoading = ref('');
const policyVisible = ref(false);
const policySaving = ref(false);
const createVisible = ref(false);
const createSaving = ref(false);
const suggestedDeviceId = ref('');
const merchantOptions = ref<MerchantOption[]>([]);
const createForm = reactive({
  deviceId: '',
  deviceName: '',
  deviceType: '',
  merchantId: ''
});
const boardCounts = reactive({
  ALL: 0,
  ONLINE: 0,
  OFFLINE: 0,
  ON_SALE: 0,
  LOCKED: 0
});
/** 避免首屏看板/Tab 在请求完成前误显「0」 */
const boardHydrated = ref(false);
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

function formatBoardCount(key: BoardTab) {
  return boardHydrated.value ? String(boardCounts[key]) : '暂无';
}

function boardTabLabel(key: BoardTab, label: string) {
  return boardHydrated.value ? `${label} (${boardCounts[key]})` : `${label} (…)`;
}

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
    if (
      tab === 'ONLINE' ||
      tab === 'OFFLINE' ||
      tab === 'ON_SALE' ||
      tab === 'LOCKED' ||
      tab === 'ALL'
    ) {
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
  headers: [
    '设备编号',
    '名称',
    '类型',
    '在线',
    '运营态',
    '停售原因',
    '柜内温度',
    '固件',
    '生命周期',
    'IMEI',
    '资产方',
    '路线',
    '商户编号',
    '商户',
    '退款方式',
    '最近会话',
    '会话状态',
    '更新时间'
  ],
  toRows: () =>
    pickSelected(devices.value).map((row) => [
      row.deviceId,
      row.deviceName,
      dictLabel('device_type', row.deviceType),
      dictLabel('online_status', row.onlineStatus),
      row.salesLocked ? '停售' : '在售',
      row.salesLocked ? row.salesLockReason || '' : '',
      row.currentTempC != null ? `${row.currentTempC}` : '',
      row.firmwareVersion || '',
      lifecycleLabel(row.lifecycleStatus),
      row.imei,
      row.assetOwner,
      row.routeCode,
      row.merchantId,
      row.merchantName,
      `${policyLabel(effectivePolicy(row))}${row.refundPolicy ? '' : '(全局)'}`,
      row.activeSessionId,
      row.activeSessionState ? dictLabel('session_state', row.activeSessionState) : '无',
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

async function batchRetire() {
  const targets = devices.value.filter((d) => selectedKeys.value.map(String).includes(d.deviceId));
  if (!targets.length) {
    ElMessage.warning('请先勾选设备');
    return;
  }
  let remark = '';
  try {
    const res = await ElMessageBox.prompt(
      `将对 ${targets.length} 台执行退役（需填写备注），确认？`,
      '批量退役',
      {
        type: 'warning',
        inputPlaceholder: '退役原因（必填）',
        confirmButtonText: '确认退役',
        inputValidator: (v) => (!!v && v.trim().length >= 2) || '请填写至少2字备注'
      }
    );
    remark = String(res.value || '').trim();
  } catch {
    return;
  }
  batchCmdLoading.value = 'RETIRE';
  let ok = 0;
  let fail = 0;
  try {
    for (const row of targets) {
      try {
        await api.request(
          `/api/v2/ops/admin/devices/${encodeURIComponent(row.deviceId)}/lifecycle`,
          'POST',
          { action: 'RETIRE', remark }
        );
        ok += 1;
      } catch {
        fail += 1;
      }
    }
    if (fail === 0) ElMessage.success(`已退役 ${ok} 台`);
    else ElMessage.warning(`退役完成：成功 ${ok}，失败 ${fail}`);
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
    await ElMessageBox.confirm(`将对 ${targets.length} 台设备执行「${label}」，确认继续？`, label, {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消'
    });
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
  router
    .push({
      name: 'device-detail',
      params: { id: row.deviceId }
    })
    .catch(() => {
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
  policyForm.deviceLabel = row.deviceName ? `${row.deviceName}（${row.deviceId}）` : row.deviceId;
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
    ElMessage.success(
      `已保存：${policyLabel(updated.effectiveRefundPolicy || updated.refundPolicy)}`
    );
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
  try {
    await Promise.all(
      specs.map(async (spec) => {
        try {
          const q = new URLSearchParams({ page: '0', size: '1' });
          if (keyword.value.trim()) q.set('q', keyword.value.trim());
          if (spec.online) q.set('online', spec.online);
          if (spec.salesLocked) q.set('salesLocked', spec.salesLocked);
          const data = await api.request<PageResult<DeviceInfo>>(
            `/api/v2/ops/admin/devices?${q}`,
            'GET'
          );
          boardCounts[spec.key] = data.total || 0;
        } catch {
          /* keep previous */
        }
      })
    );
  } finally {
    boardHydrated.value = true;
  }
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
    devices.value = sortById(data.items || [], 'deviceId');
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
    listHydrated.value = true;
    loading.value = false;
  }
}

async function loadMerchants() {
  try {
    const data = await api.request<{ items?: MerchantOption[] }>(
      '/api/v2/ops/admin/merchants?page=0&size=500',
      'GET'
    );
    merchantOptions.value = data.items || [];
  } catch {
    merchantOptions.value = [];
  }
}

function openCreate() {
  createForm.deviceId = '';
  createForm.deviceName = '';
  createForm.deviceType = '';
  createForm.merchantId = '';
  suggestedDeviceId.value = '';
  createVisible.value = true;
  void loadMerchants();
  void loadSuggestedDeviceId();
}

async function loadSuggestedDeviceId() {
  try {
    const data = await api.request<{ deviceId: string }>('/api/v2/ops/admin/devices/next-id', 'GET');
    suggestedDeviceId.value = data.deviceId;
    createForm.deviceId = data.deviceId;
  } catch {
    suggestedDeviceId.value = '';
  }
}

async function saveCreate() {
  const deviceId = createForm.deviceId.trim();
  createSaving.value = true;
  try {
    await api.request('/api/v2/ops/admin/devices', 'POST', {
      ...(deviceId ? { deviceId } : {}),
      deviceName: createForm.deviceName.trim() || undefined,
      deviceType: createForm.deviceType || undefined,
      merchantId: createForm.merchantId || undefined
    });
    ElMessage.success('设备已创建');
    createVisible.value = false;
    await load(false);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  } finally {
    createSaving.value = false;
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
  const nextLifecycle =
    typeof route.query.lifecycleStatus === 'string' ? route.query.lifecycleStatus : '';
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
.temp-warn {
  color: var(--el-color-danger);
  font-weight: 600;
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
  margin: 0 0 12px;
  padding: 0;
  min-width: 0;
  border: none;
}
.ops-board__tile {
  appearance: none;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
  padding: 10px 12px;
  text-align: center;
  cursor: pointer;
  display: grid;
  gap: 2px;
  transition:
    border-color 0.15s ease,
    background 0.15s ease,
    box-shadow 0.15s ease;
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
  background: color-mix(in srgb, var(--el-color-primary) 8%, var(--layout-card, #fff));
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--el-color-primary) 35%, var(--layout-border));
}
.ops-board__tile.warn:not(.active) {
  border-color: color-mix(in srgb, var(--el-color-warning) 45%, var(--el-border-color-lighter));
  background: color-mix(in srgb, var(--el-color-warning) 8%, var(--layout-card, #fff));
}
.ops-board__tile.warn.active {
  border-color: var(--el-color-warning);
  background: color-mix(in srgb, var(--el-color-warning) 12%, var(--layout-card, #fff));
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--el-color-warning) 40%, var(--layout-border));
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
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.device-thumb {
  width: 30px;
  height: 30px;
  border-radius: 6px;
  object-fit: cover;
  background: #ecfdf5;
  flex: 0 0 auto;
}
.link-cell:hover {
  text-decoration: underline;
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
.form-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
}
.mono {
  font-family: inherit;
  font-size: inherit;
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
