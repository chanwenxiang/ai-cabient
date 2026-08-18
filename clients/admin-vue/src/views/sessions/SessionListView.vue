<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">开门记录</span>
            <span class="hint"
              >按设备 / 会话状态筛选；活跃态超过
              {{ STALE_MINUTES }} 分钟高亮滞留，便于日间跟进</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:session:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="statusTab" class="status-tabs" @tab-change="onStatusTab">
      <el-tab-pane label="全部" name="ALL" />
      <el-tab-pane v-for="o in stateOptions" :key="o.value" :label="o.label" :name="o.value" />
    </el-tabs>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="会话 / 设备 / 用户 / 订单…"
          style="width: 260px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="类型">
        <el-select
          v-model="kindFilter"
          clearable
          placeholder="全部"
          style="width: 120px"
          @change="search"
        >
          <el-option label="消费" value="CONSUMER" />
          <el-option label="补货" value="RESTOCK" />
          <el-option label="运维" value="OPS" />
        </el-select>
      </el-form-item>
      <el-form-item label="时间">
        <el-date-picker
          v-model="createdRange"
          type="datetimerange"
          value-format="x"
          start-placeholder="起"
          end-placeholder="止"
          style="width: 340px"
          @change="search"
        />
      </el-form-item>
      <el-form-item>
        <el-checkbox v-model="stuckOnly" @change="onStuckToggle">仅滞留</el-checkbox>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="listHydrated && (stuckOnly ? total > 0 : pageStuckCount > 0)"
      type="warning"
      :closable="false"
      show-icon
      class="sla-banner"
      :title="
        stuckOnly
          ? `当前筛选共 ${total} 条滞留会话（活跃态超过 ${STALE_MINUTES} 分钟）`
          : `本页 ${pageStuckCount} 条可能滞留，可勾选「仅滞留」或从工作台「异常会话」进入`
      "
    />

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="displayItems"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          stripe
          border
          class="report-table"
          row-key="sessionId"
          :row-class-name="rowClassName"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty>
            <el-empty
              v-if="listHydrated && !loading"
              :description="
                stuckOnly ? `当前无超过 ${STALE_MINUTES} 分钟的滞留会话` : '暂无开门记录'
              "
            />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="sessionId"
            label="会话编号"
            min-width="160"
            align="center"
            class-name="col-text"
            sortable="custom"
          >
            <template #default="{ row }">
              <button type="button" class="link-cell mono" @click="openTimeline(row)">
                <span class="cell-id">{{ displayBizNo(row.sessionId) }}</span>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="88" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="sessionKindType(row.sessionKind)" effect="plain">
                {{ sessionKindLabel(row.sessionKind) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="88" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.userId ?? '无' }}</template>
          </el-table-column>
          <el-table-column label="设备" min-width="110" align="center" class-name="col-text">
            <template #default="{ row }">
              <button
                v-if="row.deviceId"
                type="button"
                class="link-cell"
                @click="goPath(`/devices/${encodeURIComponent(row.deviceId)}`)"
              >
                {{ row.deviceId }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column
            label="订单"
            min-width="130"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <button
                v-if="row.orderId"
                type="button"
                class="link-cell mono"
                @click="goOrders(row.deviceId)"
              >
                {{ displayBizNo(row.orderId) }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="sessionStateType(row.state)">
                {{ dictLabel('session_state', row.state) || row.state || '未知状态' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="等待原因"
            min-width="160"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span>{{ waitReason(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="滞留 / 时限" width="160" align="center" class-name="col-text">
            <template #default="{ row }">
              <div v-if="isActiveState(row.state)" class="sla-cell">
                <template v-if="isStuck(row)">
                  <el-tag type="danger" size="small">已滞留</el-tag>
                  <small class="sla-meta danger">超 {{ formatAge(overdueMs(row)) }}</small>
                </template>
                <template v-else-if="isDueSoon(row)">
                  <el-tag type="warning" size="small">临近时限</el-tag>
                  <small class="sla-meta">已等 {{ formatAge(ageMs(row)) }}</small>
                </template>
                <template v-else>
                  <span class="cell-datetime">已等 {{ formatAge(ageMs(row)) }}</span>
                  <small class="sla-meta">时限 {{ STALE_MINUTES }} 分</small>
                </template>
              </div>
              <div v-else-if="sessionDurationMs(row) > 0" class="sla-cell">
                <span class="cell-datetime">时长 {{ formatAge(sessionDurationMs(row)) }}</span>
                <small class="sla-meta">已结束</small>
              </div>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column
            label="失败原因"
            min-width="140"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ failReasonText(row) }}</template>
          </el-table-column>
          <el-table-column label="更新时间" width="160" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="时长" width="110" align="center">
            <template #default="{ row }">{{ formatDuration(row) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                :actions="sessionActions(row)"
                @action="(k) => onAction(String(k), row)"
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
      @current-change="load"
      @size-change="onSizeChange"
    />

    <el-drawer v-model="timelineOpen" title="会话时间线" size="420px" destroy-on-close>
      <template v-if="timelineRow">
        <el-descriptions :column="1" border size="small" class="mb12">
          <el-descriptions-item label="会话">
            <span class="cell-id">{{ displayBizNo(timelineRow.sessionId) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="设备">
            <button
              v-if="timelineRow.deviceId"
              type="button"
              class="link-cell"
              @click="goPath(`/devices/${encodeURIComponent(timelineRow.deviceId)}`)"
            >
              {{ timelineRow.deviceId }}
            </button>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="订单">
            <button
              v-if="timelineRow.orderId"
              type="button"
              class="link-cell mono"
              @click="goOrders(timelineRow.deviceId)"
            >
              {{ timelineRow.orderId }}
            </button>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ dictLabel('session_state', timelineRow.state) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-timeline>
          <el-timeline-item
            v-for="step in sessionTimeline(timelineRow)"
            :key="step.label"
            :timestamp="step.time"
            :type="step.type"
          >
            {{ step.label }}
            <div v-if="step.detail" class="tl-detail">{{ step.detail }}</div>
          </el-timeline-item>
        </el-timeline>
        <div class="tl-actions">
          <el-button
            v-if="
              timelineRow.sessionId &&
              (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))
            "
            type="warning"
            :loading="videoLoading"
            @click="playVideo(timelineRow.sessionId)"
            >播放录像</el-button
          >
          <el-button
            v-if="timelineRow.deviceId && canAccessPath('/upload-queue')"
            @click="goPath('/upload-queue', { deviceId: timelineRow.deviceId })"
            >录像上传队列</el-button
          >
          <el-button
            v-if="timelineRow.orderId && canAccessPath('/orders')"
            type="primary"
            @click="goOrders(timelineRow.deviceId)"
            >查看订单</el-button
          >
        </div>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import {
  CircleClose,
  Clock,
  CopyDocument,
  Refresh,
  View,
  VideoCamera
} from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api, downloadAuthFile } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useDictOptions } from '@/composables/useDictOptions';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useSessionVideo } from '@/composables/useSessionVideo';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import type { PageResult } from '@aicabinet/shared-types';
import {
  displayBizNo,
  formatDateTime
} from '@aicabinet/shared-uni/format';
import { csvFileName } from '@/utils/csv';
import { comparePrimaryKey } from '@/utils/sort-by-pk';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

interface SessionRow {
  sessionId: string;
  userId?: string;
  deviceId?: string;
  state?: string;
  orderId?: string;
  failureReason?: string;
  failReason?: string;
  openTime?: string;
  closeTime?: string;
  uploadStatus?: string;
  createdAt?: string;
  updatedAt?: string;
  /** CONSUMER / RESTOCK / OPS */
  sessionKind?: string;
  replenishmentTaskId?: number | null;
}

/** Align with AdminDashboardService STALE_SESSION_MINUTES. */
const STALE_MINUTES = 30;
const STALE_MS = STALE_MINUTES * 60 * 1000;
const DUE_SOON_MS = 10 * 60 * 1000;
const ACTIVE_STATES = new Set([
  'OPENING',
  'OPEN',
  'DOOR_OPEN',
  'SHOPPING',
  'WAITING_UPLOAD',
  'RECOGNIZING',
  'SETTLING'
]);

const route = useRoute();
const { router, canAccessPath, goPath } = useNavAccess();
const { playSessionVideo } = useSessionVideo();
const auth = useAuthStore();
const loading = ref(false);
const listHydrated = ref(false);
const videoLoading = ref(false);
const keyword = ref('');
const createdRange = ref<[string, string] | null>(null);
const kindFilter = ref('');
const statusTab = ref('ALL');
/** API 状态筛选项：与 statusTab 同源，ALL 时为空字符串 */
const stateFilter = computed(() => (statusTab.value === 'ALL' ? '' : statusTab.value));
const stuckOnly = ref(false);
const focusSessionId = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<SessionRow[]>([]);
const {
  defaultSort: idDefaultSort,
  onSortChange: onIdSortChange,
  sortById,
  idSortDir
} = useIdColumnSort<SessionRow>('sessionId');
const timelineOpen = ref(false);
const timelineRow = ref<SessionRow | null>(null);
const stateOptions = useDictOptions('session_state');

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<SessionRow>((r) => r.sessionId);

const displayItems = computed(() => {
  let list = [...items.value];
  if (kindFilter.value) {
    list = list.filter((r) => (r.sessionKind || 'CONSUMER') === kindFilter.value);
  }
  if (stuckOnly.value) return sortById(list);
  const dir = idSortDir.value === 'desc' ? -1 : 1;
  return list.sort((a, b) => {
    const as = isStuck(a) ? 1 : 0;
    const bs = isStuck(b) ? 1 : 0;
    if (as !== bs) return bs - as;
    return comparePrimaryKey(a.sessionId, b.sessionId) * dir;
  });
});

const pageStuckCount = computed(() => items.value.filter((r) => isStuck(r)).length);

const { onExport: exportSelectedCsv } = useListCsv({
  filePrefix: '开门记录',
  headers: [
    '会话编号',
    '类型',
    '用户',
    '设备',
    '订单',
    '状态',
    '等待原因',
    '滞留分钟',
    '是否滞留',
    '失败原因',
    '更新时间'
  ],
  toRows: () =>
    pickSelected(displayItems.value).map((row) => [
      row.sessionId,
      sessionKindLabel(row.sessionKind),
      row.userId,
      row.deviceId,
      row.orderId,
      dictLabel('session_state', row.state),
      waitReason(row),
      String(Math.floor(ageMs(row) / 60000)),
      isStuck(row) ? '是' : '否',
      failReasonText(row),
      formatDateTime(row.updatedAt)
    ])
});

function appendSessionFilters(q: URLSearchParams) {
  if (keyword.value.trim()) q.set('q', keyword.value.trim());
  if (stateFilter.value) q.set('state', stateFilter.value);
  if (createdRange.value?.length === 2) {
    const fromMs = Number(createdRange.value[0]);
    const toMs = Number(createdRange.value[1]);
    if (Number.isFinite(fromMs)) q.set('from', new Date(fromMs).toISOString());
    if (Number.isFinite(toMs)) q.set('to', new Date(toMs).toISOString());
  }
}

async function onExport() {
  const selected = pickSelected(displayItems.value);
  // 有勾选时导出勾选项；否则走服务端 F 码导出（当前筛选条件）
  if (selected.length && selected.length < displayItems.value.length) {
    exportSelectedCsv();
    return;
  }
  try {
    const q = new URLSearchParams();
    appendSessionFilters(q);
    const qs = q.toString();
    await downloadAuthFile(
      `/api/v2/ops/admin/sessions/export${qs ? `?${qs}` : ''}`,
      csvFileName('开门记录')
    );
    ElMessage.success('已导出');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

function isActiveState(s?: string) {
  return !!s && ACTIVE_STATES.has(s);
}

function parseTs(value?: string) {
  if (!value) return NaN;
  const t = Date.parse(value);
  return Number.isNaN(t) ? NaN : t;
}

function ageMs(row: SessionRow) {
  const t = parseTs(row.updatedAt);
  return Number.isNaN(t) ? 0 : Math.max(0, Date.now() - t);
}

function overdueMs(row: SessionRow) {
  return Math.max(0, ageMs(row) - STALE_MS);
}

function remainMs(row: SessionRow) {
  return Math.max(0, STALE_MS - ageMs(row));
}

function isStuck(row: SessionRow) {
  return isActiveState(row.state) && ageMs(row) >= STALE_MS;
}

function isDueSoon(row: SessionRow) {
  if (!isActiveState(row.state) || isStuck(row)) return false;
  const left = remainMs(row);
  return left > 0 && left <= DUE_SOON_MS;
}

function formatAge(ms: number) {
  const abs = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(abs / 3600);
  const m = Math.floor((abs % 3600) / 60);
  if (h >= 48) return `${Math.floor(h / 24)} 天`;
  if (h > 0) return `${h} 小时 ${m} 分`;
  if (m > 0) return `${m} 分钟`;
  return '不到 1 分钟';
}

function formatDuration(row: SessionRow) {
  const start = row.openTime || row.createdAt;
  const end = row.closeTime || row.updatedAt;
  if (!start || !end) return '—';
  const sec = Math.max(0, Math.floor((new Date(end).getTime() - new Date(start).getTime()) / 1000));
  if (sec < 60) return `${sec}s`;
  return `${Math.floor(sec / 60)}m${sec % 60}s`;
}

function failReasonText(row: SessionRow) {
  const text = String(row.failureReason || row.failReason || '').trim();
  return text || '无';
}

function sessionDurationMs(row: SessionRow) {
  const start = parseTs(row.openTime || row.createdAt);
  const end = parseTs(row.closeTime || row.updatedAt);
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return 0;
  return end - start;
}

function waitReason(row: SessionRow) {
  const s = String(row.state || '').toUpperCase();
  const upload = String(row.uploadStatus || '').toUpperCase();
  const stuck = isStuck(row);
  const fail = failReasonText(row);
  if (s === 'WAITING_UPLOAD') {
    if (upload === 'FAILED') return '录像上传失败，待设备侧重试';
    if (upload === 'UPLOADING') return stuck ? '上传中断或极慢' : '等待录像上传完成';
    if (upload === 'LOCAL_QUEUED')
      return stuck ? '本地排队超时，可能弱网/离线' : '设备本地排队待推送';
    return stuck ? '关门后长期待上传' : '关门后等待录像上报';
  }
  if (s === 'RECOGNIZING') return stuck ? '识别滞留，需人工跟进' : '视觉识别处理中';
  if (s === 'SETTLING') return stuck ? '结算滞留，需核对扣款' : '订单结算中';
  if (s === 'SHOPPING' || s === 'OPEN' || s === 'DOOR_OPEN') {
    return stuck ? '长时间未关门' : '购物中 / 柜门开启';
  }
  if (s === 'OPENING') return stuck ? '开门指令超时' : '开门指令下发中';
  if (s === 'FAILED') return fail !== '无' ? fail : '会话失败';
  if (s === 'CANCELLED') return fail !== '无' ? fail : '会话已取消';
  if (s === 'DISPUTED') return '待人工审核';
  if (fail !== '无') return fail;
  return isActiveState(row.state) ? '会话处理中' : '无';
}

function rowClassName({ row }: { row: SessionRow }) {
  const classes: string[] = [];
  if (isStuck(row)) classes.push('is-overdue');
  else if (isDueSoon(row)) classes.push('is-due-soon');
  if (focusSessionId.value && row.sessionId === focusSessionId.value) classes.push('is-focus');
  return classes.join(' ');
}

function canCancel(s?: string) {
  // 识别/结算中禁止直接取消，应走异常中心人工处理，避免库存与录像链路被截断
  return !!s && ['CREATED', 'OPENING', 'SHOPPING', 'OPEN', 'DOOR_OPEN'].includes(s);
}

function sessionKindLabel(kind?: string) {
  return dictLabel('session_kind', kind) || '消费';
}

function sessionKindType(kind?: string) {
  if (kind === 'RESTOCK') return 'warning';
  if (kind === 'OPS') return 'info';
  return 'success';
}

function sessionStateType(s?: string) {
  if (s === 'COMPLETED') return 'success';
  if (s === 'CANCELLED') return 'info';
  if (s === 'FAILED') return 'danger';
  if (s === 'SHOPPING' || s === 'OPEN' || s === 'DOOR_OPEN' || s === 'WAITING_UPLOAD')
    return 'warning';
  return '';
}

function sessionActions(row: SessionRow): TableAction[] {
  const acts: TableAction[] = [{ key: 'timeline', label: '时间线', icon: Clock, type: 'primary' }];
  if (row.deviceId && canAccessPath('/devices')) {
    acts.push({ key: 'device', label: '看设备', icon: View, type: 'info' });
  }
  if (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload')) {
    acts.push({ key: 'play', label: '播放录像', icon: VideoCamera, type: 'warning' });
  }
  acts.push({ key: 'copy', label: '复制会话ID', icon: CopyDocument, type: 'info', overflow: true });
  if (row.deviceId && canAccessPath('/upload-queue')) {
    acts.push({
      key: 'video',
      label: '录像队列',
      icon: VideoCamera,
      type: 'warning',
      overflow: true
    });
  }
  if (canCancel(row.state) && auth.hasPerm('ops:session:cancel')) {
    acts.push({
      key: 'cancel',
      label: '取消会话',
      icon: CircleClose,
      type: 'danger',
      overflow: true
    });
  }
  return acts;
}

function sessionTimeline(row: SessionRow) {
  const steps: {
    label: string;
    time: string;
    type?: 'primary' | 'success' | 'warning' | 'danger' | 'info';
    detail?: string;
  }[] = [];
  steps.push({ label: '创建会话', time: formatDateTime(row.createdAt), type: 'primary' });
  if (row.openTime)
    steps.push({ label: '开门', time: formatDateTime(row.openTime), type: 'success' });
  if (row.closeTime)
    steps.push({ label: '关门', time: formatDateTime(row.closeTime), type: 'success' });
  if (row.uploadStatus && row.uploadStatus !== 'NONE') {
    steps.push({
      label: '录像上传',
      time: formatDateTime(row.updatedAt),
      type: String(row.uploadStatus).includes('FAIL') ? 'danger' : 'warning',
      detail: dictLabel('upload_status', row.uploadStatus) || row.uploadStatus
    });
  }
  if (row.orderId) {
    steps.push({
      label: '生成订单',
      time: formatDateTime(row.updatedAt),
      type: 'success',
      detail: row.orderId
    });
  }
  const fail = row.failureReason || row.failReason;
  if (fail) {
    steps.push({
      label: '失败',
      time: formatDateTime(row.updatedAt),
      type: 'danger',
      detail: fail
    });
  }
  steps.push({
    label: `当前：${dictLabel('session_state', row.state)}`,
    time: formatDateTime(row.updatedAt),
    type: 'info'
  });
  return steps;
}

function openTimeline(row: SessionRow) {
  timelineRow.value = row;
  timelineOpen.value = true;
}

function goOrders(device?: string) {
  const query: Record<string, string> = {};
  if (device) query.deviceId = device;
  goPath('/orders', query);
}

async function onAction(key: string, row: SessionRow) {
  if (key === 'timeline') {
    openTimeline(row);
    return;
  }
  if (key === 'device' && row.deviceId) {
    goPath(`/devices/${encodeURIComponent(row.deviceId)}`);
    return;
  }
  if (key === 'play') {
    await playVideo(row.sessionId);
    return;
  }
  if (key === 'copy') {
    try {
      await navigator.clipboard.writeText(row.sessionId);
      ElMessage.success('已复制会话 ID');
    } catch {
      ElMessage.error('复制失败');
    }
    return;
  }
  if (key === 'video' && row.deviceId) {
    goPath('/upload-queue', { deviceId: row.deviceId });
    return;
  }
  if (key === 'cancel') {
    await cancelSession(row.sessionId);
  }
}

async function playVideo(sessionId?: string) {
  videoLoading.value = true;
  try {
    await playSessionVideo(sessionId);
  } finally {
    videoLoading.value = false;
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (stateFilter.value) query.state = stateFilter.value;
  if (stuckOnly.value) query.stuck = '1';
  if (focusSessionId.value) query.sessionId = focusSessionId.value;
  router.replace({ query });
}

async function maybeOpenFocusedSession() {
  if (!focusSessionId.value) return;
  const hit = items.value.find((r) => r.sessionId === focusSessionId.value);
  if (hit) {
    await nextTick();
    openTimeline(hit);
  }
}

async function load() {
  loading.value = true;
  try {
    if (stuckOnly.value) {
      // No server-side stuck filter yet: scan recent pages then paginate locally.
      const pageSize = 100;
      const maxScan = 500;
      const stuck: SessionRow[] = [];
      let apiPage = 0;
      let scanned = 0;
      let serverTotal = Number.POSITIVE_INFINITY;
      while (scanned < maxScan && scanned < serverTotal) {
        const q = new URLSearchParams({ page: String(apiPage), size: String(pageSize) });
        appendSessionFilters(q);
        const data = await api.request<PageResult<SessionRow>>(
          `/api/v2/ops/admin/sessions?${q}`,
          'GET'
        );
        const batch = data.items || [];
        serverTotal = data.total ?? batch.length;
        stuck.push(...batch.filter((r) => isStuck(r)));
        scanned += batch.length;
        if (!batch.length || batch.length < pageSize) break;
        apiPage += 1;
      }
      stuck.sort((a, b) => ageMs(b) - ageMs(a));
      total.value = stuck.length;
      const start = (page.value - 1) * size.value;
      items.value = stuck.slice(start, start + size.value);
    } else {
      const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
      appendSessionFilters(q);
      const data = await api.request<PageResult<SessionRow>>(
        `/api/v2/ops/admin/sessions?${q}`,
        'GET'
      );
      items.value = data.items;
      total.value = data.total;
    }
    clearSelection();
    await maybeOpenFocusedSession();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load();
}

function onStatusTab(name: string | number) {
  statusTab.value = String(name);
  page.value = 1;
  syncRouteQuery();
  load();
}

function onStuckToggle() {
  page.value = 1;
  syncRouteQuery();
  load();
}

function reset() {
  keyword.value = '';
  createdRange.value = null;
  kindFilter.value = '';
  statusTab.value = 'ALL';
  stuckOnly.value = false;
  focusSessionId.value = '';
  page.value = 1;
  syncRouteQuery();
  load();
}

function onSizeChange() {
  page.value = 1;
  load();
}

async function cancelSession(sessionId: string) {
  try {
    await ElMessageBox.confirm('确认取消该会话？', '取消会话');
    await api.request(`/api/v2/ops/admin/sessions/${encodeURIComponent(sessionId)}/cancel`, 'POST');
    ElMessage.success('已取消');
    load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '操作失败');
    }
  }
}

function applyRouteQuery() {
  let changed = false;
  const routeKeyword =
    typeof route.query.keyword === 'string'
      ? route.query.keyword
      : typeof route.query.q === 'string'
        ? route.query.q
        : typeof route.query.deviceId === 'string'
          ? route.query.deviceId
          : typeof route.query.qSessionId === 'string'
            ? route.query.qSessionId
            : typeof route.query.userId === 'string'
              ? route.query.userId
              : '';
  if (routeKeyword !== keyword.value) {
    keyword.value = routeKeyword;
    changed = true;
  }
  if (typeof route.query.state === 'string' && route.query.state !== stateFilter.value) {
    statusTab.value = route.query.state || 'ALL';
    changed = true;
  } else if (!route.query.state && stateFilter.value && route.query.stuck) {
    // 仅 stuck 深链时保留当前状态 Tab
  } else if (!route.query.state && stateFilter.value && !route.query.stuck) {
    statusTab.value = 'ALL';
    changed = true;
  } else if (!route.query.state && !stateFilter.value && statusTab.value !== 'ALL') {
    statusTab.value = 'ALL';
  }
  const qStuck = route.query.stuck === '1' || route.query.stuck === 'true';
  if (qStuck !== stuckOnly.value) {
    stuckOnly.value = qStuck;
    changed = true;
  }
  if (typeof route.query.sessionId === 'string') {
    if (route.query.sessionId !== focusSessionId.value) {
      focusSessionId.value = route.query.sessionId;
      changed = true;
    }
  } else if (focusSessionId.value) {
    focusSessionId.value = '';
    changed = true;
  }
  return changed;
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await load();
}

watch(
  () =>
    [
      route.query.keyword,
      route.query.q,
      route.query.deviceId,
      route.query.state,
      route.query.stuck,
      route.query.sessionId
    ] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  load();
});
onActivated(() => {
  void reloadFromRouteQuery();
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
  flex-wrap: wrap;
}
.status-tabs {
  margin: 0 0 10px;
}
.sla-banner {
  margin-bottom: 10px;
}
.sla-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
  text-align: center;
  justify-items: center;
}
.sla-meta {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
.sla-meta.danger {
  color: var(--el-color-danger);
}
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  text-align: center;
  font: inherit;
}
.link-cell:hover {
  text-decoration: underline;
}
.link-cell.mono {
  font-family: inherit;
  font-size: inherit;
}
.muted {
  color: var(--el-text-color-secondary);
}
.mb12 {
  margin-bottom: 12px;
}
.tl-detail {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tl-actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
:deep(.el-table .is-overdue > td.el-table__cell) {
  background: color-mix(
    in srgb,
    var(--el-color-danger) 6%,
    var(--el-table-bg-color, #fff)
  ) !important;
}
:deep(.el-table .is-due-soon > td.el-table__cell) {
  background: color-mix(
    in srgb,
    var(--el-color-warning) 7%,
    var(--el-table-bg-color, #fff)
  ) !important;
}
/* 固定操作列保持实心底，避免滞留/临近高亮行透视 */
:deep(.el-table .is-overdue > td.el-table-fixed-column--right),
:deep(.el-table .is-due-soon > td.el-table-fixed-column--right) {
  background: var(--el-table-bg-color, #fff) !important;
}
:deep(.el-table--striped .is-overdue.el-table__row--striped > td.el-table-fixed-column--right),
:deep(.el-table--striped .is-due-soon.el-table__row--striped > td.el-table-fixed-column--right) {
  background: var(--el-fill-color-lighter, #fafafa) !important;
}
:deep(.el-table .is-focus > td.el-table__cell) {
  outline: 1px solid color-mix(in srgb, var(--el-color-primary) 45%, transparent);
  outline-offset: -1px;
}
</style>
