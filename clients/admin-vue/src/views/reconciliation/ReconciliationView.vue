<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">对账</span>
            <span class="hint">按渠道 / 状态筛选；差异笔数标红，可查看明细行</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-if="canRun" type="primary" @click="openRunDialog">执行对账</el-button>
          <el-button v-hasPermi="['ops:reconciliation:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="t1-alert"
      title="T+1 结算说明"
      description="对账按 T+1 结算节奏核对渠道流水与平台订单；当日交易通常次日可完整对账。差异笔数标红请优先处理。"
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
      <el-tag size="small" type="info">批次 {{ listHydrated ? totalCount : '…' }}</el-tag>
      <el-tag size="small" type="danger">差异 {{ listHydrated ? mismatchBatchCount : '…' }}</el-tag>
      <el-tag size="small" type="success">匹配 {{ listHydrated ? matchedBatchCount : '…' }}</el-tag>
    </div>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="items"
          stripe
          border
          class="report-table"
          :row-class-name="rowClassName"
          row-key="reconId"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty
            ><el-empty v-if="listHydrated && !loading" description="暂无对账记录"
          /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="对账" min-width="160" align="center" class-name="col-text">
            <template #default="{ row }">
              <button type="button" class="recon-cell" @click="openDetail(row)">
                <strong>{{ row.reconDate || row.reconId }}</strong>
                <small>{{ row.reconId }}</small>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="渠道" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{
                dictLabel('pay_channel', row.channel)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">
                {{ dictLabel('reconciliation_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="差异笔数" width="100" align="center">
            <template #default="{ row }">
              <span :class="{ 'is-mismatch': (row.unmatchedCount ?? 0) > 0 }">
                {{ row.unmatchedCount ?? 0 }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="88" class-name="col-action" align="center" fixed="right">
            <template #default="{ row }">
              <TableActions
                :actions="[{ key: 'detail', label: '详情', icon: View, type: 'primary' }]"
                @action="() => openDetail(row)"
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
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />

    <el-dialog v-model="runDialog" title="执行对账" width="480px" destroy-on-close>
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
            <el-descriptions-item label="差异笔数">
              <span :class="{ 'is-mismatch': (detail.summary?.unmatchedCount ?? 0) > 0 }">
                {{ detail.summary?.unmatchedCount ?? 0 }}
              </span>
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
              align="center"
              class-name="col-text"
              show-overflow-tooltip
            />
            <el-table-column
              prop="merchantOrderNo"
              label="商户单号"
              min-width="120"
              align="center"
              class-name="col-text"
              show-overflow-tooltip
            />
            <el-table-column label="金额" width="100" align="center">
              <template #default="{ row }"
                >¥{{ ((row.amountCents || 0) / 100).toFixed(2) }}</template
              >
            </el-table-column>
            <el-table-column label="交易类型" width="100" align="center">
              <template #default="{ row }">{{
                row.tradeType ? dictLabel('platform_bill_trade_type', row.tradeType) : '暂无'
              }}</template>
            </el-table-column>
            <el-table-column label="交易时间" width="160" align="center" class-name="col-text">
              <template #default="{ row }">
                <span class="cell-datetime">{{
                  row.tradeTime ? formatDateTime(row.tradeTime) : '暂无'
                }}</span>
              </template>
            </el-table-column>
            <el-table-column label="匹配" width="80" align="center">
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
import { Refresh, View } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import ResizableDrawer from '@/components/ResizableDrawer.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { normalizeListPage } from '@/utils/normalize-list-page';

type Row = Record<string, any>;
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const canRun = computed(() => auth.hasPerm('ops:reconciliation:run'));

const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const channel = ref('');
const statusFilter = ref('');
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<Row[]>([]);
const runDialog = ref(false);
const detailOpen = ref(false);
const detailHydrated = ref(false);
const detail = ref<Row | null>(null);
const runForm = reactive({ date: '', channel: 'WECHAT' });

const filtered = computed(() => items.value);

const paged = computed(() => items.value);

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<Row>((r) => r.reconId);

const totalCount = computed(() => total.value);
const mismatchBatchCount = computed(
  () =>
    items.value.filter((row) => (row.unmatchedCount ?? 0) > 0 || row.status === 'MISMATCH').length
);
const matchedBatchCount = computed(() => items.value.length - mismatchBatchCount.value);

const { onExport } = useListCsv({
  filePrefix: '对账',
  headers: ['对账ID', '日期', '渠道', '状态', '差异笔数', '创建时间'],
  toRows: () =>
    pickSelected(filtered.value).map((row) => [
      row.reconId,
      row.reconDate || '',
      dictLabel('pay_channel', row.channel),
      dictLabel('reconciliation_status', row.status),
      row.unmatchedCount ?? 0,
      formatDateTime(row.createdAt)
    ])
});

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

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    if (channel.value) q.set('channel', channel.value);
    if (statusFilter.value) q.set('status', statusFilter.value);
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim());
    const data = await api.request<Row[] | { items: Row[]; total: number }>(
      `/api/v2/ops/admin/reconciliation?${q}`,
      'GET'
    );
    const pageData = normalizeListPage(data);
    items.value = pageData.items;
    total.value = pageData.total;
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function onSizeChange() {
  page.value = 1;
  load();
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load();
}

function reset() {
  channel.value = '';
  statusFilter.value = '';
  keyword.value = '';
  page.value = 1;
  syncRouteQuery();
  load();
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
    await api.request(`/api/v2/ops/admin/reconciliation/run?${q}`, 'POST');
    runDialog.value = false;
    ElMessage.success('对账已执行');
    await load();
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
    detail.value = await api.request<Row>(`/api/v2/ops/admin/reconciliation/${row.reconId}`, 'GET');
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
  page.value = 1;
  await load();
}

watch([keyword, statusFilter], () => {
  page.value = 1;
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
