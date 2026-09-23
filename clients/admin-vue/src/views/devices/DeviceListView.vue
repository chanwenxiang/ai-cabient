<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备管理</span>
            <span class="hint"
              >「可购买」=
              在线且未锁机；「未锁机」只表示没锁营业，离线仍买不了。可批量锁机或解锁</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="danger"
            plain
            :disabled="!crud.hasSelection"
            :loading="batchCmdLoading === 'LOCK'"
            @click="batchCommand('LOCK')"
            >批量锁机</el-button
          >
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="success"
            plain
            :disabled="!crud.hasSelection"
            :loading="batchCmdLoading === 'UNLOCK'"
            @click="batchCommand('UNLOCK')"
            >批量解锁</el-button
          >
          <el-button
            v-hasPermi="['ops:device:edit']"
            plain
            :disabled="!crud.hasSelection"
            :loading="batchCmdLoading === 'DEPLOY'"
            @click="batchLifecycle('DEPLOY')"
            >批量投放</el-button
          >
          <el-button
            v-hasPermi="['ops:device:edit']"
            plain
            :disabled="!crud.hasSelection"
            :loading="batchCmdLoading === 'UNDEPLOY'"
            @click="batchLifecycle('UNDEPLOY')"
            >批量未投放</el-button
          >
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="warning"
            plain
            :disabled="!crud.hasSelection"
            :loading="batchCmdLoading === 'RETIRE'"
            @click="batchRetire"
            >批量退役</el-button
          >
          <el-button v-hasPermi="['ops:device:create']" type="primary" @click="openCreate"
            >新建设备</el-button
          >
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
          boardHydrated ? tile.hint : UI_COPY.loading
        }}</span>
      </button>
    </fieldset>

    <el-alert
      v-if="boardHydrated && attentionCount > 0 && boardTab === 'ALL'"
      type="warning"
      :closable="false"
      show-icon
      class="ops-banner"
      :title="`需关注 ${attentionCount} 台：离线 ${boardCounts.OFFLINE} · 已锁机 ${boardCounts.LOCKED}（点击看板筛选）`"
    />

    <el-tabs v-model="boardTab" class="status-tabs" @tab-change="onBoardTab">
      <el-tab-pane :label="boardTabLabel('ALL', '全部')" name="ALL" />
      <el-tab-pane
        :label="boardTabLabel('ONLINE', displayLabel('online_status', 'ONLINE'))"
        name="ONLINE"
      />
      <el-tab-pane
        :label="boardTabLabel('OFFLINE', displayLabel('online_status', 'OFFLINE'))"
        name="OFFLINE"
      />
      <el-tab-pane :label="boardTabLabel('CAN_BUY', '可购买')" name="CAN_BUY" />
      <el-tab-pane :label="boardTabLabel('ON_SALE', '未锁机')" name="ON_SALE" />
      <el-tab-pane :label="boardTabLabel('LOCKED', '已锁机')" name="LOCKED" />
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
        <CrudTable
          :table="crud"
          row-key="deviceId"
          manage-table="device_info"
          selectable
          :actions="rowActions"
          :action-width="140"
          actions-testid="device"
          empty-text="暂无设备"
          sort-field-label="设备编号"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column prop="deviceId" label="设备编号" min-width="140" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.deviceId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="设备" min-width="140" class-name="col-text">
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
          <el-table-column label="类型" min-width="100" class-name="col-text">
            <template #default="{ row }">{{ dictLabel('device_type', row.deviceType) }}</template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="row.onlineStatus === 'ONLINE' ? 'success' : 'info'" size="small">
                {{ dictLabel('online_status', row.onlineStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="锁机"
            width="100"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="row.salesLocked ? 'danger' : 'success'" size="small">
                {{ row.salesLocked ? '已锁机' : '未锁机' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="能否购买"
            width="100"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag
                :type="!row.salesLocked && row.onlineStatus === 'ONLINE' ? 'success' : 'info'"
                size="small"
              >
                {{
                  row.salesLocked
                    ? '不可买'
                    : row.onlineStatus === 'ONLINE'
                      ? '可购买'
                      : '离线不可买'
                }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="生命周期"
            width="96"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ lifecycleLabel(row.lifecycleStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="柜内温度"
            width="90"
            class-name="col-status"
            label-class-name="col-status"
          >
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
          <el-table-column label="锁机原因" min-width="140" class-name="col-text">
            <template #default="{ row }">
              <span
                v-if="row.salesLocked && row.salesLockReason"
                class="cell-ellipsis"
                :title="row.salesLockReason"
                >{{ row.salesLockReason }}</span
              >
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="固件"
            width="88"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.firmwareVersion || ''">{{
                row.firmwareVersion || '暂无'
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="地址" min-width="160" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.address || ''">{{
                row.address || '暂无'
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="IMEI" min-width="120" class-name="col-text">
            <template #default="{ row }">{{ row.imei || '无' }}</template>
          </el-table-column>
          <el-table-column label="资产方" min-width="100" class-name="col-text">
            <template #default="{ row }">{{ row.assetOwner || '无' }}</template>
          </el-table-column>
          <el-table-column label="路线" width="90" class-name="col-text">
            <template #default="{ row }">{{ row.routeCode || '无' }}</template>
          </el-table-column>
          <el-table-column label="商户" min-width="120" class-name="col-text">
            <template #default="{ row }">{{ row.merchantName || row.merchantId || '无' }}</template>
          </el-table-column>
          <el-table-column
            align="center"
            label="退款方式"
            min-width="168"
            class-name="col-status"
            label-class-name="col-status"
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
          <el-table-column label="最近会话" min-width="140" class-name="col-text">
            <template #default="{ row }">
              <span v-if="row.activeSessionId" class="mono">{{
                displayBizNo(row.activeSessionId)
              }}</span>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column
            label="会话状态"
            min-width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              {{
                row.activeSessionState ? dictLabel('session_state', row.activeSessionState) : '无'
              }}
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="更新时间"
            min-width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog
      v-model="createVisible"
      title="新建设备"
      destroy-on-close
      append-to-body
      align-center
    >
      <el-form label-width="auto">
        <el-form-item label="设备编号">
          <p class="form-hint muted">
            创建后由系统自动分配 12 位数字编号（无序、不可修改）；柜机首次联网时将自动绑定 IMEI /
            主板 SN
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
        <el-form-item label="点位地址">
          <!-- 地址在坐标之前：先选省/市/区，点「解析坐标」即把经纬度填到下面，不必再手敲 -->
          <AddressPicker
            v-model="createForm.address"
            :resolvable="geoConfigured"
            @update:latitude="(v) => (createForm.latitude = v ?? undefined)"
            @update:longitude="(v) => (createForm.longitude = v ?? undefined)"
          />
        </el-form-item>
        <el-form-item label="点位坐标">
          <div style="display: flex; gap: 8px; width: 100%">
            <el-input-number
              v-model="createForm.latitude"
              :controls="false"
              :precision="6"
              :step="0.0001"
              :min="-90"
              :max="90"
              style="width: 100%"
              placeholder="纬度，如 31.230400"
            />
            <el-input-number
              v-model="createForm.longitude"
              :controls="false"
              :precision="6"
              :step="0.0001"
              :min="-180"
              :max="180"
              style="width: 100%"
              placeholder="经度，如 121.473700"
            />
          </div>
          <p class="form-hint muted">
            选择商户即视为柜机已部署，此时经纬度必填：补货签到靠坐标做地理围栏，缺坐标的柜机无法校验签到位置。
          </p>
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
import { Setting, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import AddressPicker from '@/components/AddressPicker.vue';
import { useCrudTable, type CrudPageParams } from '@/composables/useCrudTable';
import { useAuthStore } from '@/stores/auth';
import type {
  OpenApiAdminDeviceDto,
  OpenApiPageResultAdminDeviceDto
} from '@aicabinet/shared-types';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

type BoardTab = 'ALL' | 'ONLINE' | 'OFFLINE' | 'CAN_BUY' | 'ON_SALE' | 'LOCKED';

interface MerchantOption {
  merchantId: string;
  merchantName?: string;
}

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const keyword = ref('');
const lifecycleFilter = ref('');
const coopFilter = ref('');
const routeFilter = ref('');
const boardTab = ref<BoardTab>('ALL');
const batchCmdLoading = ref('');
const policyVisible = ref(false);
const policySaving = ref(false);
const createVisible = ref(false);
const createSaving = ref(false);
/** 高德是否已配置：决定要不要显示「解析坐标」（未配置时仍可手填经纬度） */
const geoConfigured = ref(false);
const merchantOptions = ref<MerchantOption[]>([]);
const createForm = reactive({
  deviceName: '',
  deviceType: '',
  merchantId: '',
  /** 点位坐标：填了商户即视为部署，此时经纬度必填（后端 createDevice 亦 fail-closed） */
  latitude: undefined as number | undefined,
  longitude: undefined as number | undefined,
  address: ''
});
const boardCounts = reactive({
  ALL: 0,
  ONLINE: 0,
  OFFLINE: 0,
  CAN_BUY: 0,
  ON_SALE: 0,
  LOCKED: 0
});
/** 离线且已锁机的交集，用于「需关注」去重：|离线 ∪ 已锁机| = 离线 + 已锁机 − 交集 */
const attentionOverlap = ref(0);
/** 避免首屏看板/Tab 在请求完成前误显「0」 */
const boardHydrated = ref(false);
const boardTiles: { key: BoardTab; label: string; hint?: string; warn?: boolean }[] = [
  { key: 'ALL', label: '全部设备' },
  { key: 'ONLINE', label: displayLabel('online_status', 'ONLINE'), hint: '心跳正常' },
  { key: 'OFFLINE', label: displayLabel('online_status', 'OFFLINE'), hint: '需巡检', warn: true },
  { key: 'CAN_BUY', label: '可购买', hint: '在线且未锁' },
  { key: 'ON_SALE', label: '未锁机', hint: '含离线柜' },
  { key: 'LOCKED', label: '已锁机', hint: '禁止开门', warn: true }
];
const policyForm = reactive({
  deviceId: '',
  deviceLabel: '',
  refundPolicy: 'INHERIT'
});

const attentionCount = computed(() =>
  Math.max(boardCounts.OFFLINE + boardCounts.LOCKED - attentionOverlap.value, 0)
);

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
    case 'CAN_BUY':
      return { online: 'ONLINE', salesLocked: 'false' };
    case 'ON_SALE':
      return { salesLocked: 'false' };
    case 'LOCKED':
      return { salesLocked: 'true' };
    default:
      return {};
  }
}

function tabFromRouteQuery(): BoardTab {
  const online = typeof route.query.online === 'string' ? route.query.online.toUpperCase() : '';
  const salesLocked = typeof route.query.salesLocked === 'string' ? route.query.salesLocked : '';
  if (online === 'ONLINE' && salesLocked === 'false') return 'CAN_BUY';
  if (online === 'ONLINE' || online === 'OFFLINE') return online;
  if (salesLocked === 'true') return 'LOCKED';
  if (salesLocked === 'false') return 'ON_SALE';
  if (typeof route.query.tab === 'string') {
    const tab = route.query.tab.toUpperCase();
    if (
      tab === 'ONLINE' ||
      tab === 'OFFLINE' ||
      tab === 'CAN_BUY' ||
      tab === 'ON_SALE' ||
      tab === 'LOCKED' ||
      tab === 'ALL'
    ) {
      return tab as BoardTab;
    }
  }
  return 'ALL';
}

function effectivePolicy(row: OpenApiAdminDeviceDto) {
  return row.effectiveRefundPolicy || row.refundPolicy || 'AUTO_REFUND';
}

function policyLabel(policy?: string | null) {
  if (policy === 'DISPUTE_ONLY') return '仅申诉审核';
  return '自助退款';
}

function lifecycleLabel(status?: string | null) {
  return dictLabel('device_lifecycle', status || 'DEPLOYED');
}

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

// 首屏先把路由 query 同步进筛选状态，再交给 useCrudTable 挂载后自动加载（原 onMounted 前置逻辑）
applyRouteQuery();

// 高德是否配置：拿不到就当未配置（只影响「解析坐标」按钮是否出现，手填经纬度始终可用）
onMounted(async () => {
  try {
    const data = await api.request<{ configured: boolean }>(AdminEndpoints.geoStatus, 'GET');
    geoConfigured.value = !!data.configured;
  } catch {
    geoConfigured.value = false;
  }
});

/** 拉取一页设备；顺带同步当前 tab 的看板计数并刷新整个看板（原 load 的附带副作用） */
async function fetchPage(params: CrudPageParams) {
  const q = new URLSearchParams({
    page: String(params.page),
    size: String(params.size)
  });
  if (keyword.value.trim()) q.set('q', keyword.value.trim());
  if (lifecycleFilter.value) q.set('lifecycleStatus', lifecycleFilter.value);
  if (coopFilter.value) q.set('coopMode', coopFilter.value);
  if (routeFilter.value.trim()) q.set('routeCode', routeFilter.value.trim());
  const filters = boardQuery(boardTab.value);
  if (filters.online) q.set('online', filters.online);
  if (filters.salesLocked) q.set('salesLocked', filters.salesLocked);
  const tabAtRequest = boardTab.value;
  const data = await api.request<OpenApiPageResultAdminDeviceDto>(
    AdminEndpoints.devicesList(q),
    'GET'
  );
  if (tabAtRequest in boardCounts) {
    boardCounts[tabAtRequest] = data.total || 0;
  }
  void refreshBoardCounts();
  return data;
}

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 / 刷新 全部内建
const crud = useCrudTable<OpenApiAdminDeviceDto>({
  rowKey: (r) => r.deviceId ?? '',
  fetchPage,
  sort: { prop: 'deviceId', mode: 'local' }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '设备',
  exportPerm: 'ops:device:export',
  headers: [
    '设备编号',
    '名称',
    '类型',
    displayLabel('online_status', 'ONLINE'),
    '锁机',
    '能否购买',
    '锁机原因',
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
  // 选中优先由 CrudTable 内部处理（勾选了就只导选中行）
  toRows: (rows) =>
    rows.map((row) => [
      row.deviceId,
      row.deviceName,
      dictLabel('device_type', row.deviceType),
      dictLabel('online_status', row.onlineStatus),
      row.salesLocked ? '已锁机' : '未锁机',
      row.salesLocked ? '不可买' : row.onlineStatus === 'ONLINE' ? '可购买' : '离线不可买',
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
};

function rowActions(_row: OpenApiAdminDeviceDto): CrudRowAction[] {
  return [
    { key: 'detail', label: '详情', icon: View, type: 'primary' },
    { key: 'policy', label: '退款设置', icon: Setting, type: 'warning', perm: 'ops:device:edit' }
  ];
}

function onAction({ key, row }: { key: string; row: OpenApiAdminDeviceDto }) {
  if (key === 'detail') {
    goDetail(row);
    return;
  }
  if (key === 'policy') {
    openPolicy(row);
  }
}

async function batchLifecycle(action: 'DEPLOY' | 'UNDEPLOY') {
  if (!auth.hasPerm('ops:device:edit')) {
    ElMessage.warning('无设备编辑权限');
    return;
  }
  const targets = crud.items.filter((d) =>
    crud.selectedKeys.map(String).includes(d.deviceId ?? '')
  );
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
        await api.request(AdminEndpoints.deviceLifecycle(row.deviceId ?? ''), 'POST', {
          action,
          remark: `batch-${action.toLowerCase()}`
        });
        ok += 1;
      } catch {
        fail += 1;
      }
    }
    if (fail === 0) ElMessage.success(`已${label} ${ok} 台`);
    else ElMessage.warning(`${label}完成：成功 ${ok}，失败 ${fail}`);
    crud.clearSelection();
    await crud.load();
  } finally {
    batchCmdLoading.value = '';
  }
}

async function batchRetire() {
  if (!auth.hasPerm('ops:device:edit')) {
    ElMessage.warning('无设备编辑权限');
    return;
  }
  const targets = crud.items.filter((d) =>
    crud.selectedKeys.map(String).includes(d.deviceId ?? '')
  );
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
        await api.request(AdminEndpoints.deviceLifecycle(row.deviceId ?? ''), 'POST', {
          action: 'RETIRE',
          remark
        });
        ok += 1;
      } catch {
        fail += 1;
      }
    }
    if (fail === 0) ElMessage.success(`已退役 ${ok} 台`);
    else ElMessage.warning(`退役完成：成功 ${ok}，失败 ${fail}`);
    crud.clearSelection();
    await crud.load();
  } finally {
    batchCmdLoading.value = '';
  }
}

async function batchCommand(command: 'LOCK' | 'UNLOCK') {
  if (!auth.hasPerm('ops:device:edit')) {
    ElMessage.warning('无设备编辑权限');
    return;
  }
  const targets = crud.items.filter((d) =>
    crud.selectedKeys.map(String).includes(d.deviceId ?? '')
  );
  if (!targets.length) {
    ElMessage.warning('请先勾选设备');
    return;
  }
  const label = command === 'LOCK' ? '锁机' : '解锁';
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
          AdminEndpoints.deviceCommands(row.deviceId ?? ''),
          'POST',
          { command, reason: `batch-${command.toLowerCase()}` }
        );
        const idx = crud.items.findIndex((d) => d.deviceId === row.deviceId);
        if (idx >= 0) {
          crud.items[idx] = {
            ...crud.items[idx],
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
    crud.clearSelection();
  } finally {
    batchCmdLoading.value = '';
  }
}

function goDetail(row: OpenApiAdminDeviceDto) {
  const id = row.deviceId ?? '';
  if (!id) return;
  // 使用具名路由 + encode，避免偶发路径匹配失败被 catch-all 打回工作台
  router
    .push({
      name: 'device-detail',
      params: { id }
    })
    .catch(() => {
      router.push(`/devices/${encodeURIComponent(id)}`);
    });
}

function openPolicy(row: OpenApiAdminDeviceDto) {
  policyForm.deviceId = row.deviceId ?? '';
  policyForm.deviceLabel = row.deviceName
    ? `${row.deviceName}（${row.deviceId ?? ''}）`
    : (row.deviceId ?? '');
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
    const updated = await api.request<OpenApiAdminDeviceDto>(
      AdminEndpoints.device(policyForm.deviceId),
      'PATCH',
      { refundPolicy: policyForm.refundPolicy }
    );
    const idx = crud.items.findIndex((d) => d.deviceId === updated.deviceId);
    if (idx >= 0) {
      crud.items[idx] = { ...crud.items[idx], ...updated };
    } else {
      await crud.load();
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
    { key: 'CAN_BUY', online: 'ONLINE', salesLocked: 'false' },
    { key: 'ON_SALE', salesLocked: 'false' },
    { key: 'LOCKED', salesLocked: 'true' }
  ];
  try {
    await Promise.all([
      ...specs.map(async (spec) => {
        try {
          const q = new URLSearchParams({ page: '0', size: '1' });
          if (keyword.value.trim()) q.set('q', keyword.value.trim());
          if (spec.online) q.set('online', spec.online);
          if (spec.salesLocked) q.set('salesLocked', spec.salesLocked);
          const data = await api.request<OpenApiPageResultAdminDeviceDto>(
            AdminEndpoints.devicesList(q),
            'GET'
          );
          boardCounts[spec.key] = data.total || 0;
        } catch {
          /* keep previous */
        }
      }),
      (async () => {
        try {
          const q = new URLSearchParams({
            page: '0',
            size: '1',
            online: 'OFFLINE',
            salesLocked: 'true'
          });
          if (keyword.value.trim()) q.set('q', keyword.value.trim());
          const data = await api.request<OpenApiPageResultAdminDeviceDto>(
            AdminEndpoints.devicesList(q),
            'GET'
          );
          attentionOverlap.value = data.total || 0;
        } catch {
          /* keep previous */
        }
      })()
    ]);
  } finally {
    boardHydrated.value = true;
  }
}

function selectBoard(tab: BoardTab) {
  if (boardTab.value === tab) return;
  boardTab.value = tab;
  syncRouteQuery();
  void crud.search();
}

function onBoardTab(name: string | number) {
  boardTab.value = String(name) as BoardTab;
  syncRouteQuery();
  void crud.search();
}

async function loadMerchants() {
  try {
    const data = await api.request<{ items?: MerchantOption[] }>(
      AdminEndpoints.merchantsList('page=0&size=500'),
      'GET'
    );
    merchantOptions.value = data.items || [];
  } catch {
    merchantOptions.value = [];
  }
}

function openCreate() {
  if (!auth.hasPerm('ops:device:create')) {
    ElMessage.warning('无新建设备权限');
    return;
  }
  createForm.deviceName = '';
  createForm.deviceType = '';
  createForm.merchantId = '';
  createForm.latitude = undefined;
  createForm.longitude = undefined;
  createForm.address = '';
  createVisible.value = true;
  void loadMerchants();
}

async function saveCreate() {
  if (!auth.hasPerm('ops:device:create')) {
    ElMessage.warning('无新建设备权限');
    return;
  }
  // 设备名称允许留空（后端会回退为系统自动分配的 12 位编号），但设备名是运维识别柜机的
  // 主要依据，直接落库会产出以编号命名的设备。故空名称时先与用户确认，避免误点即建。
  if (!createForm.deviceName.trim()) {
    try {
      await ElMessageBox.confirm(
        '未填写设备名称，将使用系统自动分配的 12 位编号作为设备名称。是否继续创建？',
        '确认新建设备',
        {
          type: 'warning',
          confirmButtonText: '继续创建',
          cancelButtonText: '返回填写',
          appendTo: 'body'
        }
      );
    } catch {
      return;
    }
  }
  // 坐标必填：选了商户 = 直接部署，此时必须先完成点位建档。
  // 前端拦一道只是为了让用户少跑一次 400；权威判据在后端 createDevice。
  const hasLat = typeof createForm.latitude === 'number';
  const hasLng = typeof createForm.longitude === 'number';
  if (hasLat !== hasLng) {
    ElMessage.warning('纬度与经度需成对填写');
    return;
  }
  if (createForm.merchantId && (!hasLat || !hasLng)) {
    ElMessage.warning('已选择商户（柜机将直接部署），必须填写点位经纬度');
    return;
  }
  createSaving.value = true;
  try {
    const created = await api.request<{ deviceId: string }>(AdminEndpoints.devices, 'POST', {
      deviceName: createForm.deviceName.trim() || undefined,
      deviceType: createForm.deviceType || undefined,
      merchantId: createForm.merchantId || undefined,
      latitude: createForm.latitude,
      longitude: createForm.longitude,
      address: createForm.address.trim() || undefined
    });
    ElMessage.success(`设备已创建，编号 ${created.deviceId}`);
    createVisible.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  } finally {
    createSaving.value = false;
  }
}

function search() {
  syncRouteQuery();
  void crud.search();
}
function reset() {
  keyword.value = '';
  lifecycleFilter.value = '';
  coopFilter.value = '';
  routeFilter.value = '';
  boardTab.value = 'ALL';
  syncRouteQuery();
  void crud.search();
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

onActivated(() => {
  if (applyRouteQuery()) {
    void crud.search();
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
  font-size: var(--admin-font-size-title);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
}
.ops-board {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
  margin: 0 0 12px;
  padding: 0;
  min-width: 0;
  border: none;
}
.ops-board__tile {
  appearance: none;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--radius-tag);
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
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
.ops-board__value {
  font-size: var(--admin-font-size-display-md);
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.ops-board__hint {
  font-size: var(--admin-font-size-xs);
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
  border-radius: var(--radius-tag);
  object-fit: cover;
  background: color-mix(in srgb, var(--brand, var(--app-primary)) 10%, #fff);
  flex: 0 0 auto;
}
.link-cell:hover {
  text-decoration: underline;
}
.inherit-hint {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
}
.policy-hint {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.5;
}
.form-hint {
  margin: 6px 0 0;
  font-size: var(--admin-font-size-sm);
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
