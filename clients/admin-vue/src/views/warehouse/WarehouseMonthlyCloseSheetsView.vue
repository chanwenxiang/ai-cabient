<template>
  <div class="page" data-testid="monthly-close-sheets-page">
    <h2 class="page-title">月结单台账（审批锁账 + 差异处置）</h2>
    <p class="page-hint">
      两步法：生成未审批（差异金额化）→ 审批锁单 → 逐行处置（进索赔台账 / 标记正常损耗）。
      盘亏走索赔或损耗，<b>不生成应付</b>。金额 = 差异件数 × 目录采购成本（未配置成本的 SKU
      行金额留空）。
    </p>

    <!-- 生成区 -->
    <el-card class="block" shadow="never">
      <div class="gen-row">
        <el-select
          v-model="genWarehouseId"
          placeholder="选择仓库"
          filterable
          style="width: 220px"
          :loading="warehousesLoading"
          data-testid="mcs-warehouse-select"
        >
          <el-option
            v-for="w in warehouses"
            :key="w.warehouseId"
            :label="w.warehouseName"
            :value="w.warehouseId"
          />
        </el-select>
        <el-date-picker
          v-model="genMonthDate"
          type="month"
          placeholder="月结月份"
          value-format="YYYY-MM"
          style="width: 150px"
          data-testid="mcs-month-picker"
        />
        <el-button
          type="primary"
          :loading="generating"
          data-testid="mcs-generate-btn"
          @click="generate"
        >
          生成 / 重新生成未审批
        </el-button>
      </div>
    </el-card>

    <!-- 台账列表 -->
    <el-table
      class="block"
      :data="sheets"
      v-loading="listLoading"
      size="small"
      data-testid="mcs-sheet-table"
    >
      <el-table-column prop="closeId" label="单号" width="80" />
      <el-table-column prop="warehouseName" label="仓库" min-width="120" />
      <el-table-column prop="yearMonth" label="月份" width="90" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.status === 'APPROVED'" type="success" size="small">已审批</el-tag>
          <el-tag v-else type="info" size="small">未审批</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        class-name="col-money"
        label-class-name="col-money"
        label="盘亏（件 / 元）"
        width="150"
      >
        <template #default="{ row }">
          {{ row.lossQty }} / {{ fmtCents(row.lossAmountCents) }}
        </template>
      </el-table-column>
      <el-table-column
        class-name="col-money"
        label-class-name="col-money"
        label="盘盈（件 / 元）"
        width="150"
      >
        <template #default="{ row }">
          {{ row.surplusQty }} / {{ fmtCents(row.surplusAmountCents) }}
        </template>
      </el-table-column>
      <el-table-column prop="approvedByName" label="审批人" width="100" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" data-testid="mcs-detail-btn" @click="openDetail(row)"
            >查看</el-button
          >
          <el-button
            v-if="row.status === 'DRAFT'"
            size="small"
            type="success"
            data-testid="mcs-approve-btn"
            @click="approve(row)"
          >
            审批
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 详情 -->
    <el-drawer v-model="detailOpen" size="72%" :title="detailTitle">
      <template #default>
        <el-descriptions
          v-if="detail"
          :column="3"
          border
          size="small"
          data-testid="mcs-detail-head"
        >
          <el-descriptions-item label="仓库">{{ detail.warehouseName }}</el-descriptions-item>
          <el-descriptions-item label="月份">{{ detail.yearMonth }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ detail.status === 'APPROVED' ? '已审批（锁单）' : '未审批' }}
          </el-descriptions-item>
          <el-descriptions-item label="盘亏合计">
            {{ detail.lossQty }} 件 / {{ fmtCents(detail.lossAmountCents) }}
          </el-descriptions-item>
          <el-descriptions-item label="盘盈合计">
            {{ detail.surplusQty }} 件 / {{ fmtCents(detail.surplusAmountCents) }}
          </el-descriptions-item>
          <el-descriptions-item label="审批人">{{
            detail.approvedByName || '—'
          }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="driftInfo && !driftInfo.consistent"
          type="error"
          :closable="false"
          class="block"
          data-testid="mcs-drift-alert"
          :title="`重算漂移：${driftInfo.driftedSkuCount} 个 SKU 的账面/实盘与审批版不一致（生成后底层又发生了业务）`"
        />
        <el-alert
          v-if="detail && detail.status !== 'APPROVED'"
          type="warning"
          :closable="false"
          class="block"
          title="未审批状态：随时可重新生成覆盖；差异处置须先审批锁单"
        />

        <div class="block" style="display: flex; gap: 8px">
          <el-button
            size="small"
            :loading="driftLoading"
            data-testid="mcs-drift-btn"
            @click="checkDrift"
          >
            重算漂移核对
          </el-button>
          <el-button size="small" data-testid="mcs-export-csv" @click="exportCsv"
            >导出 CSV</el-button
          >
        </div>

        <el-table
          :data="detail?.lines || []"
          size="small"
          max-height="520"
          data-testid="mcs-line-table"
        >
          <el-table-column prop="skuId" label="SKU" min-width="110" />
          <el-table-column prop="skuName" label="名称" min-width="110" />
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            prop="openingQty"
            label="上期"
            width="60"
            align="center"
          />
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            prop="purchaseInQty"
            label="采购入"
            width="70"
            align="center"
          />
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            prop="transferInQty"
            label="调入"
            width="60"
            align="center"
          />
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            prop="transferOutQty"
            label="调出"
            width="60"
            align="center"
          />
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            prop="restockQty"
            label="上柜"
            width="60"
            align="center"
          />
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            prop="returnQty"
            label="退货"
            width="60"
            align="center"
          />
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            prop="expectedQty"
            label="应有"
            width="60"
            align="center"
          />
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            label="实盘"
            width="60"
            align="center"
          >
            <template #default="{ row }">{{ row.countedQty ?? '—' }}</template>
          </el-table-column>
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            label="差异"
            width="70"
            align="center"
          >
            <template #default="{ row }">
              <span v-if="row.gapQty == null">—</span>
              <span v-else-if="row.gapQty < 0" class="gap-loss" data-testid="mcs-gap-loss">{{
                row.gapQty
              }}</span>
              <span v-else-if="row.gapQty > 0" class="gap-surplus">+{{ row.gapQty }}</span>
              <span v-else>0</span>
            </template>
          </el-table-column>
          <el-table-column
            class-name="col-money"
            label-class-name="col-money"
            label="差异金额"
            width="90"
            align="right"
          >
            <template #default="{ row }">
              <span v-if="row.gapAmountCents == null" class="muted">未配成本</span>
              <span v-else>{{ fmtCents(row.gapAmountCents) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="处置" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="dispositionTag(row.gapDisposition)">
                {{ dispositionLabel(row.gapDisposition) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button
                v-if="
                  detail?.status === 'APPROVED' &&
                  row.gapDisposition === 'PENDING' &&
                  (row.gapQty ?? 0) !== 0
                "
                size="small"
                data-testid="mcs-dispose-btn"
                @click="openDispose(row)"
              >
                处置
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <!-- 处置弹窗 -->
    <el-dialog
      v-model="disposeOpen"
      title="差异处置（不可逆）"
      width="420px"
      data-testid="mcs-dispose-dialog"
    >
      <el-form label-width="90px">
        <el-form-item label="处置方式">
          <el-radio-group v-model="disposeMode" data-testid="mcs-dispose-mode">
            <el-radio value="CLAIM">进索赔台账</el-radio>
            <el-radio value="NORMAL_LOSS">正常损耗</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="disposeMode === 'CLAIM'" label="责任方">
          <el-input
            v-model="disposeParty"
            placeholder="如：供应商 / 搬运工姓名"
            data-testid="mcs-dispose-party"
          />
        </el-form-item>
        <el-form-item v-if="disposeMode === 'CLAIM'" label="索赔单号">
          <el-input v-model="disposeClaimNo" placeholder="选填" data-testid="mcs-dispose-claimno" />
        </el-form-item>
        <el-form-item v-if="disposeLine" label="差异">
          <span>
            {{ disposeLine.skuId }}：{{ disposeLine.gapQty }} 件，
            {{
              disposeLine.gapAmountCents != null
                ? fmtCents(disposeLine.gapAmountCents)
                : '金额未配（成本未配置）'
            }}
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="disposeOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="disposing"
          data-testid="mcs-dispose-confirm"
          @click="confirmDispose"
        >
          确认处置
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';

interface CloseSheetLine {
  lineId: number;
  skuId: string;
  skuName: string | null;
  openingQty: number;
  purchaseInQty: number;
  transferInQty: number;
  transferOutQty: number;
  restockQty: number;
  returnQty: number;
  lossQty: number;
  expectedQty: number;
  countedQty: number | null;
  gapQty: number | null;
  gapAmountCents: number | null;
  gapDisposition: string;
  claimWriteOffId: number | null;
}

interface CloseSheet {
  closeId: number;
  warehouseId: string;
  warehouseName: string;
  yearMonth: string;
  status: string;
  lossQty: number;
  surplusQty: number;
  lossAmountCents: number;
  surplusAmountCents: number;
  lineCount: number;
  approvedByName: string | null;
  approvedAt: string | null;
  remark: string | null;
  createdAt: string | null;
  lines?: CloseSheetLine[];
}

interface WarehouseOption {
  warehouseId: string;
  warehouseName: string;
}

// ── 仓库下拉 ──
const warehouses = ref<WarehouseOption[]>([]);
const warehousesLoading = ref(false);
async function loadWarehouses() {
  warehousesLoading.value = true;
  try {
    const data = await api.request<{ items: WarehouseOption[] }>(
      AdminEndpoints.warehouseListAll,
      'GET'
    );
    warehouses.value = data.items || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? `仓库列表加载失败：${e.message}` : '仓库列表加载失败');
  } finally {
    warehousesLoading.value = false;
  }
}
void loadWarehouses();

// ── 生成 ──
const genWarehouseId = ref('');
const genMonthDate = ref<string>(defaultLastMonth());
const generating = ref(false);
const genMonth = computed(() => genMonthDate.value || '');

/** 默认上月（月结是回顾动作）。 */
function defaultLastMonth(): string {
  const now = new Date();
  const d = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

async function generate() {
  if (!genWarehouseId.value || !genMonth.value) {
    ElMessage.warning('请先选择仓库和月结月份');
    return;
  }
  generating.value = true;
  try {
    const sheet = await api.request<CloseSheet>(
      AdminEndpoints.warehouseCloseSheetsGenerate,
      'POST',
      {
        warehouseId: genWarehouseId.value,
        yearMonth: genMonth.value
      }
    );
    ElMessage.success(
      `已生成未审批 #${sheet.closeId}（盘亏 ${sheet.lossQty} 件 / 盘盈 ${sheet.surplusQty} 件）`
    );
    await loadSheets();
    detail.value = sheet;
    detailOpen.value = true;
    driftInfo.value = null;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成月结单失败');
  } finally {
    generating.value = false;
  }
}

// ── 台账列表 ──
const sheets = ref<CloseSheet[]>([]);
const listLoading = ref(false);
async function loadSheets() {
  listLoading.value = true;
  try {
    const data = await api.request<{ items: CloseSheet[]; total: number }>(
      AdminEndpoints.warehouseCloseSheets(new URLSearchParams({ page: '0', size: '50' })),
      'GET'
    );
    // 🔴 失败不清空旧数据（claim-ledger 先例）：这里请求成功才赋值
    sheets.value = data.items || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '月结单加载失败');
  } finally {
    listLoading.value = false;
  }
}
void loadSheets();

// ── 详情 ──
const detail = ref<CloseSheet | null>(null);
const detailOpen = ref(false);
const detailTitle = computed(() =>
  detail.value
    ? `月结单 #${detail.value.closeId}（${detail.value.warehouseName} ${detail.value.yearMonth}）`
    : ''
);
const driftInfo = ref<{ consistent: boolean; driftedSkuCount: number } | null>(null);
const driftLoading = ref(false);

async function openDetail(row: CloseSheet) {
  try {
    detail.value = await api.request<CloseSheet>(
      AdminEndpoints.warehouseCloseSheet(row.closeId),
      'GET'
    );
    detailOpen.value = true;
    driftInfo.value = null;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载月结单失败');
  }
}

async function checkDrift() {
  if (!detail.value) return;
  driftLoading.value = true;
  try {
    driftInfo.value = await api.request<{ consistent: boolean; driftedSkuCount: number }>(
      AdminEndpoints.warehouseCloseSheetDrift(detail.value.closeId),
      'GET'
    );
    if (driftInfo.value?.consistent) {
      ElMessage.success('重算一致：账面/实盘与审批版相同');
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '漂移核对失败');
  } finally {
    driftLoading.value = false;
  }
}

// ── 审批 ──
async function approve(row: CloseSheet) {
  try {
    await ElMessageBox.confirm(
      '审批后月结单锁单（不可改删、不可重新生成），差异处置在审批后进行。确认审批？',
      '审批确认',
      { confirmButtonText: '确认审批', cancelButtonText: '取消', type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    const updated = await api.request<CloseSheet>(
      AdminEndpoints.warehouseCloseSheetApprove(row.closeId),
      'POST'
    );
    ElMessage.success('已审批锁单');
    detail.value = updated;
    await loadSheets();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '审批失败');
  }
}

// ── 逐行处置 ──
const disposeOpen = ref(false);
const disposeLine = ref<CloseSheetLine | null>(null);
const disposeMode = ref<'CLAIM' | 'NORMAL_LOSS'>('CLAIM');
const disposeParty = ref('');
const disposeClaimNo = ref('');
const disposing = ref(false);

function openDispose(row: CloseSheetLine) {
  disposeLine.value = row;
  disposeMode.value = row.gapQty != null && row.gapQty > 0 ? 'NORMAL_LOSS' : 'CLAIM';
  disposeParty.value = '';
  disposeClaimNo.value = '';
  disposeOpen.value = true;
}

async function confirmDispose() {
  if (!detail.value || !disposeLine.value) return;
  if (disposeMode.value === 'CLAIM' && !disposeParty.value.trim()) {
    ElMessage.warning('进索赔台账必须填责任方');
    return;
  }
  disposing.value = true;
  try {
    const updated = await api.request<CloseSheet>(
      AdminEndpoints.warehouseCloseSheetDispose(detail.value.closeId, disposeLine.value.lineId),
      'POST',
      {
        disposition: disposeMode.value,
        responsibleParty: disposeMode.value === 'CLAIM' ? disposeParty.value.trim() : null,
        claimNo: disposeClaimNo.value.trim() || null
      }
    );
    detail.value = updated;
    disposeOpen.value = false;
    ElMessage.success('已处置');
    await loadSheets();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '处置失败');
  } finally {
    disposing.value = false;
  }
}

// ── 展示 helpers ──
function fmtCents(v: number | null): string {
  if (v == null) return '—';
  return `¥${(v / 100).toFixed(2)}`;
}

function dispositionLabel(d: string): string {
  switch (d) {
    case 'PENDING':
      return '待认定';
    case 'CLAIM':
      return '已索赔';
    case 'NORMAL_LOSS':
      return '正常损耗';
    case 'SURPLUS':
      return '盘盈待查';
    default:
      return d;
  }
}

function dispositionTag(d: string): 'warning' | 'danger' | 'info' | 'success' {
  switch (d) {
    case 'PENDING':
      return 'warning';
    case 'CLAIM':
      return 'danger';
    case 'NORMAL_LOSS':
      return 'info';
    default:
      return 'success';
  }
}

/** CSV 导出（BOM + 转义）：供财务线下对账（一期不接总账）。 */
function exportCsv() {
  if (!detail.value) return;
  const esc = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`;
  const header = [
    'SKU',
    '名称',
    '上期',
    '采购入',
    '调入',
    '调出',
    '上柜',
    '退货',
    '应有',
    '实盘',
    '差异',
    '差异金额(元)',
    '处置'
  ];
  const dispMap: Record<string, string> = {
    PENDING: '待认定',
    CLAIM: '已索赔',
    NORMAL_LOSS: '正常损耗',
    SURPLUS: '盘盈待查'
  };
  const rows = (detail.value.lines || []).map((r) => [
    r.skuId,
    r.skuName ?? '',
    r.openingQty,
    r.purchaseInQty,
    r.transferInQty,
    r.transferOutQty,
    r.restockQty,
    r.returnQty,
    r.expectedQty,
    r.countedQty ?? '',
    r.gapQty ?? '',
    r.gapAmountCents != null ? (r.gapAmountCents / 100).toFixed(2) : '',
    dispMap[r.gapDisposition] ?? r.gapDisposition
  ]);
  const head = [
    `月结单 #${detail.value.closeId}`,
    `${detail.value.warehouseName} ${detail.value.yearMonth}`,
    `状态:${detail.value.status === 'APPROVED' ? '已审批' : '未审批'}`
  ];
  const csv =
    '\uFEFF' +
    [
      head.map(esc).join(','),
      header.map(esc).join(','),
      ...rows.map((r) => r.map(esc).join(','))
    ].join('\r\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `monthly-close-${detail.value.warehouseId}-${detail.value.yearMonth}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
}
</script>

<style scoped>
.page {
  padding: 0 4px;
}
.page-title {
  margin: 0 0 4px;
  font-size: 18px;
}
.page-hint {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.block {
  margin-bottom: 12px;
}
.gen-row {
  display: flex;
  gap: 10px;
  align-items: center;
}
.gap-loss {
  color: var(--el-color-danger);
  font-weight: 600;
}
.gap-surplus {
  color: var(--el-color-success);
  font-weight: 600;
}
.muted {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
</style>
