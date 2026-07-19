<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">商户分账</span>
            <span class="hint">商户权限与抽成配置；分账明细可提交微信分账</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:merchant:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading || loadingMerchants || loadingStatus" @click="refresh">
            刷新
          </el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="商户列表" name="merchants">
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 920px">
            <el-table
              v-loading="loadingMerchants"
              :data="merchants"
              stripe
              border
              class="report-table"
              row-key="merchantId"
              @selection-change="onMerchantsSelectionChange"
            >
              <template #empty><el-empty description="暂无商户" /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="商户" min-width="200" class-name="col-text">
                <template #default="{ row }">
                  <div class="master-data-cell">
                    <strong>{{ row.merchantName || row.merchantId }}</strong>
                    <small>{{ row.merchantId }}</small>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="抽成" width="96" align="right">
                <template #default="{ row }">{{ (row.platformRateBps / 100).toFixed(1) }}%</template>
              </el-table-column>
              <el-table-column label="商户改货道" width="120" align="center">
                <template #default="{ row }">
                  <el-switch
                    :model-value="row.allowMerchantPlanogramEdit"
                    :disabled="!canEdit"
                    :aria-label="`${row.merchantName}允许修改货道`"
                    :title="`${row.merchantName}：允许修改货道`"
                    @change="(v: boolean) => toggleFlag(row, 'planogram', v)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="商户改价" width="120" align="center">
                <template #default="{ row }">
                  <el-switch
                    :model-value="row.allowMerchantPricingEdit"
                    :disabled="!canEdit"
                    :aria-label="`${row.merchantName}允许修改价格`"
                    :title="`${row.merchantName}：允许修改价格`"
                    @change="(v: boolean) => toggleFlag(row, 'pricing', v)"
                  />
                </template>
              </el-table-column>
              <el-table-column prop="deviceCount" label="设备数" width="90" align="center" />
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="分账明细" name="splits">
        <el-alert
          v-if="psStatus"
          class="status-banner"
          :type="psStatus.apiReady ? 'success' : 'warning'"
          :closable="false"
          show-icon
        >
          <template #title>
            {{ psStatus.note }}
            <span class="status-meta">
              启用={{ psStatus.enabled ? '是' : '否' }} · API={{ psStatus.apiReady ? '就绪' : '未就绪' }} ·
              微信={{ psStatus.wechatPayConfigured }}
            </span>
          </template>
        </el-alert>

        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="onSplitFilterChange">
          <el-form-item label="状态">
            <el-select v-model="status" clearable placeholder="全部" style="width: 150px" @change="onSplitFilterChange">
              <el-option
                v-for="item in dictOptions('split_status')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="onSplitFilterChange">查询</el-button>
            <el-button @click="resetSplitFilter">重置</el-button>
          </el-form-item>
        </el-form>

        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 1000px">
            <el-table
              v-loading="loading"
              :data="splits"
              stripe
              border
              class="report-table"
              row-key="splitId"
              @selection-change="onSplitsSelectionChange"
            >
              <template #empty><el-empty description="暂无分账明细" /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="分账编号" min-width="150" class-name="col-text">
                <template #default="{ row }"><span class="cell-id">{{ row.splitId }}</span></template>
              </el-table-column>
              <el-table-column label="订单" min-width="130" class-name="col-text" show-overflow-tooltip>
                <template #default="{ row }">
                  <button
                    v-if="row.orderId"
                    type="button"
                    class="link-cell mono"
                    @click="goOrders()"
                  >{{ row.orderId }}</button>
                  <span v-else class="muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="商户" min-width="150" class-name="col-text" show-overflow-tooltip>
                <template #default="{ row }">
                  <div class="master-data-cell">
                    <strong>{{ row.merchantName || row.merchantId || '-' }}</strong>
                    <small v-if="row.merchantId">{{ row.merchantId }}</small>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="商户收入" width="110" align="right" class-name="col-money">
                <template #default="{ row }">¥{{ money(row.merchantCents) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="120" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="splitTagType(row.status)">
                    {{ dictLabel('split_status', row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="失败原因" min-width="160" class-name="col-text" show-overflow-tooltip>
                <template #default="{ row }">{{ row.failureReason || '-' }}</template>
              </el-table-column>
              <el-table-column
                v-if="canSplit"
                label="操作"
                width="110"
                class-name="col-action"
                align="center"
              >
                <template #default="{ row }">
                  <TableActions
                    v-if="splitActions(row).length"
                    :actions="splitActions(row)"
                    @action="(key) => onSplitAction(key, row)"
                  />
                  <span v-else class="muted">-</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <div class="page-pager">
          <el-pagination
            v-model:current-page="splitPage"
            v-model:page-size="splitSize"
            :total="splitTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @current-change="loadSplits"
            @size-change="onSplitSizeChange"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="submitDialog" title="提交微信分账" width="480px" destroy-on-close>
      <p class="dialog-hint">
        分账 <code>{{ current?.splitId }}</code> · 订单 {{ current?.orderId }}
      </p>
      <el-form label-position="top">
        <el-form-item label="微信交易号 wxTransactionId">
          <el-input
            v-model="wxTransactionId"
            clearable
            placeholder="余额支付订单必填；微信支付订单可留空"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitDialog = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="confirmSubmit">确认提交</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh, RefreshRight, Upload } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import type { MerchantDto, PageResult, ProfitSharingStatus, RevenueSplit } from '@aicabinet/shared-types';

const route = useRoute();
const { router, goPath } = useNavAccess();
const auth = useAuthStore();
const canEdit = computed(() => auth.hasPerm('ops:merchant:edit'));
const canSplit = computed(() => auth.hasPerm('ops:merchant:split'));

const tab = ref('merchants');
const loading = ref(false);
const loadingMerchants = ref(false);
const loadingStatus = ref(false);
const acting = ref(false);
const status = ref('');
const splits = ref<RevenueSplit[]>([]);
const merchants = ref<MerchantDto[]>([]);
const splitsLoaded = ref(false);
const splitPage = ref(1);
const splitSize = ref(20);
const splitTotal = ref(0);
const psStatus = ref<ProfitSharingStatus | null>(null);

const submitDialog = ref(false);
const wxTransactionId = ref('');
const current = ref<RevenueSplit | null>(null);

const {
  onSelectionChange: onMerchantsSelectionChange,
  pickSelected: pickMerchants,
  exportButtonLabel: merchantsExportLabel,
  clearSelection: clearMerchantsSelection
} = useTableSelection<MerchantDto>((r) => r.merchantId);

const {
  onSelectionChange: onSplitsSelectionChange,
  pickSelected: pickSplits,
  exportButtonLabel: splitsExportLabel,
  clearSelection: clearSplitsSelection
} = useTableSelection<RevenueSplit>((r) => r.splitId);

const exportButtonLabel = computed(() =>
  tab.value === 'splits' ? splitsExportLabel.value : merchantsExportLabel.value
);

const { onExport: exportMerchants } = useListCsv({
  filePrefix: '商户',
  headers: ['商户编号', '名称', '抽成', '商户改货道', '商户改价', '设备数'],
  toRows: () =>
    pickMerchants(merchants.value).map((row) => [
      row.merchantId,
      row.merchantName,
      `${(row.platformRateBps / 100).toFixed(1)}%`,
      row.allowMerchantPlanogramEdit ? '是' : '否',
      row.allowMerchantPricingEdit ? '是' : '否',
      row.deviceCount ?? 0
    ])
});

const { onExport: exportSplits } = useListCsv({
  filePrefix: '分账明细',
  headers: ['分账编号', '订单', '商户', '商户收入', '状态', '失败原因'],
  toRows: () =>
    pickSplits(splits.value).map((row) => [
      row.splitId,
      row.orderId,
      row.merchantName || '',
      `¥${money(row.merchantCents)}`,
      dictLabel('split_status', row.status),
      row.failureReason || '-'
    ])
});

function money(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function onExport() {
  if (tab.value === 'splits') exportSplits();
  else exportMerchants();
}

function goOrders() {
  goPath('/orders');
}

function splitTagType(s: string) {
  if (s === 'SETTLED' || s === 'WECHAT_FINISHED') return 'success';
  if (s === 'WECHAT_FAILED' || s === 'FAILED') return 'danger';
  if (s === 'WECHAT_SUBMITTED') return 'warning';
  return 'info';
}

function splitActions(row: RevenueSplit): TableAction[] {
  const actions: TableAction[] = [];
  if (['ACCRUED', 'LEDGER_ONLY', 'WECHAT_FAILED'].includes(row.status)) {
    actions.push({ key: 'submit', label: '提交', icon: Upload, type: 'primary' });
  }
  if (row.status === 'WECHAT_SUBMITTED' || row.status === 'WECHAT_FAILED') {
    actions.push({ key: 'refresh', label: '刷新', icon: RefreshRight, type: 'success' });
  }
  return actions;
}

function syncRouteQuery() {
  const query: Record<string, string> = { tab: tab.value };
  if (tab.value === 'splits' && status.value) query.status = status.value;
  router.replace({ query });
}

async function loadMerchants() {
  loadingMerchants.value = true;
  try {
    merchants.value = await api.request<MerchantDto[]>('/api/v2/ops/admin/merchants', 'GET');
    clearMerchantsSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '商户加载失败');
  } finally {
    loadingMerchants.value = false;
  }
}

async function loadStatus() {
  if (!canSplit.value) return;
  loadingStatus.value = true;
  try {
    psStatus.value = await api.request<ProfitSharingStatus>(
      '/api/v2/ops/admin/merchants/profit-sharing/status',
      'GET'
    );
  } catch {
    psStatus.value = null;
  } finally {
    loadingStatus.value = false;
  }
}

async function loadSplits() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(Math.max(0, splitPage.value - 1)),
      size: String(splitSize.value)
    });
    if (status.value) q.set('status', status.value);
    const data = await api.request<PageResult<RevenueSplit>>(
      `/api/v2/ops/admin/merchants/revenue-splits?${q}`,
      'GET'
    );
    splits.value = data.items || [];
    splitTotal.value = data.total ?? splits.value.length;
    splitsLoaded.value = true;
    clearSplitsSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '分账明细加载失败');
  } finally {
    loading.value = false;
  }
}

function onSplitSizeChange() {
  splitPage.value = 1;
  loadSplits();
}

function onSplitFilterChange() {
  splitPage.value = 1;
  syncRouteQuery();
  loadSplits();
}

function resetSplitFilter() {
  status.value = '';
  splitPage.value = 1;
  syncRouteQuery();
  loadSplits();
}

function onTabChange(name: string | number) {
  const next = String(name);
  tab.value = next;
  syncRouteQuery();
  if (next === 'splits') {
    if (!splitsLoaded.value) loadSplits();
    if (!psStatus.value) loadStatus();
  }
}

function refresh() {
  loadMerchants();
  if (tab.value === 'splits') {
    loadSplits();
    loadStatus();
  }
}

function onSplitAction(key: string, row: RevenueSplit) {
  if (key === 'submit') openSubmit(row);
  else if (key === 'refresh') doRefresh(row);
}

function openSubmit(row: RevenueSplit) {
  current.value = row;
  wxTransactionId.value = row.wechatTransactionId || '';
  submitDialog.value = true;
}

async function confirmSubmit() {
  if (!current.value) return;
  acting.value = true;
  try {
    const body = wxTransactionId.value.trim()
      ? { wxTransactionId: wxTransactionId.value.trim() }
      : {};
    await api.request(
      `/api/v2/ops/admin/merchants/revenue-splits/${encodeURIComponent(current.value.splitId)}/wechat-submit`,
      'POST',
      body
    );
    submitDialog.value = false;
    ElMessage.success('已提交分账');
    await loadSplits();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交失败');
  } finally {
    acting.value = false;
  }
}

async function doRefresh(row: RevenueSplit) {
  acting.value = true;
  try {
    await api.request(
      `/api/v2/ops/admin/merchants/revenue-splits/${encodeURIComponent(row.splitId)}/wechat-refresh`,
      'POST'
    );
    ElMessage.success('已刷新状态');
    await loadSplits();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '刷新失败');
  } finally {
    acting.value = false;
  }
}

async function toggleFlag(row: MerchantDto, kind: 'planogram' | 'pricing', value: boolean) {
  if (!canEdit.value) return;
  try {
    await api.request('/api/v2/ops/admin/merchants', 'POST', {
      merchantId: row.merchantId,
      merchantName: row.merchantName,
      contactPhone: row.contactPhone,
      platformRateBps: row.platformRateBps,
      wechatReceiverId: row.wechatReceiverId,
      status: row.status,
      remark: row.remark,
      allowMerchantPlanogramEdit: kind === 'planogram' ? value : row.allowMerchantPlanogramEdit,
      allowMerchantPricingEdit: kind === 'pricing' ? value : row.allowMerchantPricingEdit
    });
    if (kind === 'planogram') row.allowMerchantPlanogramEdit = value;
    else row.allowMerchantPricingEdit = value;
    ElMessage.success('已更新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败');
  }
}

function applyRouteQuery() {
  let changed = false;
  if (route.query.tab === 'merchants' || route.query.tab === 'splits') {
    if (tab.value !== String(route.query.tab)) {
      tab.value = String(route.query.tab);
      changed = true;
    }
  }
  if (typeof route.query.status === 'string' && route.query.status !== status.value) {
    status.value = route.query.status;
    changed = true;
  }
  return changed;
}

onMounted(() => {
  applyRouteQuery();
  syncRouteQuery();
  loadMerchants();
  if (tab.value === 'splits') {
    loadSplits();
    loadStatus();
  }
});
onActivated(() => {
  if (applyRouteQuery()) {
    if (tab.value === 'splits') {
      loadSplits();
      loadStatus();
    }
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
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; }
.master-data-cell { display: grid; gap: 2px; line-height: 1.35; }
.master-data-cell strong { font-weight: 650; }
.master-data-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  text-align: left;
  font: inherit;
}
.link-cell:hover { text-decoration: underline; }
.link-cell.mono { font-family: var(--app-font-mono); font-size: 12px; }
.status-banner { margin-bottom: 12px; }
.status-meta { margin-left: 8px; font-weight: 400; opacity: 0.85; font-size: 12px; }
.muted { color: var(--layout-muted); font-size: 13px; }
.dialog-hint { margin: 0 0 12px; color: var(--layout-muted); line-height: 1.5; }
.dialog-hint code { font-size: 12px; }
</style>
