<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">对账</span>
            <span class="hint">按渠道 / 状态筛选；金额差额与未匹配笔数标红，可查看明细</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <!-- 导出 / 刷新由 CrudTable 内建工具条与操作行提供 -->
          <el-button v-if="canRun" type="primary" @click="openRunDialog">执行对账</el-button>
        </div>
      </div>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="t1-alert"
      title="T+1 结算说明"
      description="对账按 T+1 核对渠道账单与本账流水。状态「存在差异」可能因金额轧差或单据未匹配：未匹配笔数=账单对不上单号的行数；差额=渠道合计−本账合计（可为负）。二者可独立出现。"
    />

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="对账ID / 日期"
          style="width: 200px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item label="渠道">
        <el-select
          v-model="channel"
          clearable
          placeholder="全部"
          style="width: 140px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('pay_channel')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="statusFilter"
          clearable
          placeholder="全部"
          style="width: 140px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('reconciliation_status')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="kpi-tags">
      <el-tag size="small" type="info">批次总数 {{ crud.hydrated ? crud.total : '…' }}</el-tag>
      <el-tag size="small" type="danger"
        >本页差异 {{ crud.hydrated ? mismatchBatchCount : '…' }}</el-tag
      >
      <el-tag size="small" type="success"
        >本页匹配 {{ crud.hydrated ? matchedBatchCount : '…' }}</el-tag
      >
    </div>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="reconId"
          selectable
          :actions="rowActions"
          :action-width="88"
          empty-text="暂无对账记录"
          :csv="csvOptions"
          :row-class-name="rowClassName"
          @action="onAction"
        >
          <el-table-column label="对账ID" width="88" class-name="col-text">
            <template #default="{ row }">
              <span class="mono">{{ row.reconId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="账期" min-width="120" class-name="col-text">
            <template #default="{ row }">
              <button type="button" class="recon-cell" @click="openDetail(row)">
                <strong>{{ row.reconDate || '—' }}</strong>
              </button>
            </template>
          </el-table-column>
          <el-table-column
            label="渠道"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{
                dictLabel('pay_channel', row.channel)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">
                {{ dictLabel('reconciliation_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="差额" width="110" class-name="col-text">
            <template #default="{ row }">
              <span :class="{ 'is-mismatch': Number(row.diffCents ?? 0) !== 0 }">
                {{ formatCents(row.diffCents) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            label="未匹配笔数"
            width="110"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">
              <span :class="{ 'is-mismatch': (row.unmatchedCount ?? 0) > 0 }">
                {{ row.unmatchedCount ?? 0 }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="创建时间"
            width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog v-model="runDialog" title="执行对账" destroy-on-close>
      <p class="dialog-hint">按 T+1 节奏核对渠道流水与平台订单；请选择账期日期与渠道后执行。</p>
      <el-form label-position="top">
        <el-form-item label="日期" required>
          <el-date-picker
            v-model="runForm.date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="runForm.channel" style="width: 100%">
            <el-option
              v-for="item in dictOptions('pay_channel')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="runDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="runRecon">执行</el-button>
      </template>
    </el-dialog>

    <ResizableDrawer
      v-model="detailOpen"
      title="对账详情"
      storage-key="admin.drawer.recon.detail"
      :default-width="520"
      :min-width="420"
      destroy-on-close
    >
      <div v-loading="!detailHydrated" class="recon-detail-pane">
        <template v-if="detail">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="对账ID">
              <span class="cell-id">{{ detail.summary?.reconId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="日期">{{
              detail.summary?.reconDate
            }}</el-descriptions-item>
            <el-descriptions-item label="渠道">
              {{ dictLabel('pay_channel', detail.summary?.channel) }}
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="dictTagType(detail.summary?.status)" size="small">
                {{ dictLabel('reconciliation_status', detail.summary?.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="渠道合计">
              {{ formatCents(detail.summary?.platformTotal) }}
            </el-descriptions-item>
            <el-descriptions-item label="本账合计">
              {{ formatCents(detail.summary?.ledgerTotal) }}
            </el-descriptions-item>
            <el-descriptions-item label="差额">
              <span :class="{ 'is-mismatch': Number(detail.summary?.diffCents ?? 0) !== 0 }">
                {{ formatCents(detail.summary?.diffCents) }}
              </span>
              <span v-if="amountOnlyMismatch" class="recon-diff-hint">
                （单据均已匹配，差额来自金额轧差）
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="未匹配笔数">
              <span :class="{ 'is-mismatch': (detail.summary?.unmatchedCount ?? 0) > 0 }">
                {{ detail.summary?.unmatchedCount ?? 0 }}
              </span>
              <span class="recon-diff-hint">（渠道账单对不上商户单号的行数）</span>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ formatDateTime(detail.summary?.createdAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="完成时间">
              {{ formatDateTime(detail.summary?.completedAt) }}
            </el-descriptions-item>
          </el-descriptions>
          <el-table
            :data="detail.lines || []"
            stripe
            border
            style="margin-top: 16px"
            max-height="360"
            size="small"
            empty-text=" "
          >
            <el-table-column
              prop="platformTradeNo"
              label="平台流水"
              min-width="140"
              class-name="col-text"
            />
            <el-table-column
              prop="merchantOrderNo"
              label="商户单号"
              min-width="120"
              class-name="col-text"
            />
            <el-table-column
              label="金额"
              width="100"
              align="center"
              class-name="col-money"
              label-class-name="col-money"
            >
              <template #default="{ row }"
                >¥{{ ((row.amountCents || 0) / 100).toFixed(2) }}</template
              >
            </el-table-column>
            <el-table-column
              label="交易类型"
              width="100"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">{{
                row.tradeType ? dictLabel('platform_bill_trade_type', row.tradeType) : '暂无'
              }}</template>
            </el-table-column>
            <el-table-column
              align="center"
              label="交易时间"
              width="160"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">
                <span class="cell-datetime">{{
                  row.tradeTime ? formatDateTime(row.tradeTime) : '暂无'
                }}</span>
              </template>
            </el-table-column>
            <el-table-column
              label="匹配"
              width="80"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">
                <el-tag size="small" :type="row.matched ? 'success' : 'danger'">
                  {{ row.matched ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty v-if="detailHydrated" description="无明细行" :image-size="48" />
            </template>
          </el-table>
        </template>
        <el-empty v-else-if="detailHydrated" description="详情加载失败" :image-size="64" />
      </div>
    </ResizableDrawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { View } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import ResizableDrawer from '@/components/ResizableDrawer.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const canRun = computed(() => auth.hasPerm('ops:reconciliation:run'));

const saving = ref(false);
const channel = ref('');
const statusFilter = ref('');
const keyword = ref('');
const runDialog = ref(false);
const detailOpen = ref(false);
const detailHydrated = ref(false);
const detail = ref<Row | null>(null);
const runForm = reactive({ date: '', channel: 'WECHAT' });

// 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态 / 空态 / 刷新 / CSV 全部内建。
// 首查依赖路由筛选参数初始化（onMounted 内 applyRouteQuery 之后），故 autoLoad:false 显式首查。
const crud = useCrudTable<Row>({
  rowKey: (r) => r.reconId,
  autoLoad: false,
  fetchPage: (params) => {
    const q = new URLSearchParams({
      page: String(params.page),
      size: String(params.size)
    });
    if (channel.value) q.set('channel', channel.value);
    if (statusFilter.value) q.set('status', statusFilter.value);
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim());
    return api.request<Row[] | { items: Row[]; total: number }>(
      AdminEndpoints.reconciliationList(q),
      'GET'
    );
  }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '对账',
  exportPerm: 'ops:reconciliation:export',
  headers: ['对账ID', '日期', '渠道', '状态', '差额(分)', '未匹配笔数', '创建时间'],
  toRows: (rows) =>
    rows.map((row) => [
      row.reconId,
      row.reconDate || '',
      dictLabel('pay_channel', row.channel),
      dictLabel('reconciliation_status', row.status),
      row.diffCents ?? 0,
      row.unmatchedCount ?? 0,
      formatDateTime(row.createdAt)
    ])
};

function rowActions(_row: Row): CrudRowAction[] {
  return [{ key: 'detail', label: '详情', icon: View, type: 'primary' }];
}

function onAction({ key, row }: { key: string; row: Row }) {
  if (key === 'detail') void openDetail(row);
}

const mismatchBatchCount = computed(
  () =>
    crud.items.filter((row) => (row.unmatchedCount ?? 0) > 0 || row.status === 'MISMATCH').length
);
const matchedBatchCount = computed(() => crud.items.length - mismatchBatchCount.value);

const amountOnlyMismatch = computed(() => {
  const s = detail.value?.summary;
  if (!s || s.status !== 'MISMATCH') return false;
  return Number(s.diffCents ?? 0) !== 0 && Number(s.unmatchedCount ?? 0) === 0;
});

function formatCents(cents: unknown) {
  const n = Number(cents ?? 0);
  if (!Number.isFinite(n)) return '¥0.00';
  const sign = n < 0 ? '-' : '';
  return `${sign}¥${(Math.abs(n) / 100).toFixed(2)}`;
}

function localDate() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

function rowClassName({ row }: { row: Row }) {
  return (row.unmatchedCount ?? 0) > 0 || row.status === 'MISMATCH' ? 'is-mismatch-row' : '';
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (channel.value) query.channel = channel.value;
  if (statusFilter.value) query.status = statusFilter.value;
  router.replace({ query });
}

function search() {
  syncRouteQuery();
  void crud.search();
}

function reset() {
  channel.value = '';
  statusFilter.value = '';
  keyword.value = '';
  syncRouteQuery();
  void crud.search();
}

function openRunDialog() {
  if (!runForm.date) runForm.date = localDate();
  runDialog.value = true;
}

async function runRecon() {
  if (!runForm.date) {
    runForm.date = localDate();
  }
  saving.value = true;
  try {
    const q = new URLSearchParams({
      date: runForm.date,
      channel: runForm.channel || 'WECHAT'
    });
    await api.request(AdminEndpoints.reconciliationRun(q), 'POST');
    runDialog.value = false;
    ElMessage.success('对账已执行');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '执行失败');
  } finally {
    saving.value = false;
  }
}

async function openDetail(row: Row) {
  if (detail.value?.summary?.reconId !== row.reconId) {
    detail.value = null;
    detailHydrated.value = false;
  }
  detailOpen.value = true;
  try {
    detail.value = await api.request<Row>(AdminEndpoints.reconciliation(row.reconId), 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '详情加载失败');
    if (!detailHydrated.value) detail.value = null;
  } finally {
    detailHydrated.value = true;
  }
}

function applyRouteQuery() {
  let changed = false;
  const qStatus = typeof route.query.status === 'string' ? route.query.status : '';
  if (qStatus !== statusFilter.value) {
    statusFilter.value = qStatus;
    changed = true;
  }
  const qChannel = typeof route.query.channel === 'string' ? route.query.channel : '';
  if (qChannel !== channel.value) {
    channel.value = qChannel;
    changed = true;
  }
  return changed;
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  await crud.search();
}

watch([keyword, statusFilter], () => {
  crud.page = 1;
});

watch(
  () => [route.query.status, route.query.channel] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  runForm.date = localDate();
  applyRouteQuery();
  syncRouteQuery();
  // 首查依赖路由筛选初始化（crud 已 autoLoad:false），此处显式首查
  void crud.load();
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
  flex-wrap: wrap;
}
.t1-alert {
  margin: 0 0 12px;
}
.kpi-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
  margin: 0 0 12px;
}
.recon-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  display: grid;
  width: 100%;
  justify-items: center;
  gap: 2px;
  text-align: center;
  cursor: pointer;
  color: inherit;
  font: inherit;
  line-height: 1.35;
}
.recon-cell strong {
  color: var(--el-color-primary);
  font-weight: 650;
}
.recon-detail-pane {
  min-height: 160px;
}
.recon-diff-hint {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
}
.recon-cell small {
  color: var(--el-text-color-secondary);
  font-family: inherit;
}
.recon-cell:hover strong {
  text-decoration: underline;
}
.is-mismatch {
  color: var(--el-color-danger);
  font-weight: 650;
}
:deep(.el-table .is-mismatch-row > td.el-table__cell) {
  background: color-mix(
    in srgb,
    var(--el-color-danger) 6%,
    var(--el-table-bg-color, #fff)
  ) !important;
}
.dialog-hint {
  margin: 0 0 12px;
  color: var(--layout-muted);
  line-height: 1.5;
}
</style>
