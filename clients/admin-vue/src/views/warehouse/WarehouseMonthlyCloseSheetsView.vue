<template>
  <!-- 🔴 标准页壳 page-card：统一外层。本页此前用自带样式的 div.page（非全站规范壳，
       标题/表格样式都接不上 `.page-card` 体系），与其余 50+ 页拉齐。 -->
  <el-card class="page-card report-page" shadow="never" data-testid="monthly-close-sheets-page">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">月结单台账（审批锁账 + 差异处置）</span>
            <span class="hint">
              两步法：生成未审批（差异金额化）→ 审批锁单 → 逐行处置（进索赔台账 /
              标记正常损耗）。盘亏走索赔或损耗，<b>不生成应付</b>。金额 = 差异件数 ×
              目录采购成本（未配置成本的 SKU 行金额留空）。
            </span>
          </div>
        </div>
      </div>
    </template>

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
          <el-descriptions-item label="期初来源">
            <el-tag v-if="detail.openingSource === 'PERIOD_CLOSE'" type="success" size="small"
              >上期实盘结转</el-tag
            >
            <el-tag v-else type="warning" size="small">账面结转</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="审批人">{{
            detail.approvedByName || '—'
          }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="detail && detail.openingSource !== 'PERIOD_CLOSE'"
          type="warning"
          :closable="false"
          class="block"
          title="期初为账面值：上一账期无实盘可结转，期初列按账面流水结转（请核对）"
        />

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
            >导出 XLSX（财务 18 列）</el-button
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
            prop="rockLossQty"
            label="摇机"
            width="56"
            align="center"
          />
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            prop="expiredLossQty"
            label="过期"
            width="56"
            align="center"
          />
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            prop="guestWaterQty"
            label="客情"
            width="56"
            align="center"
          />
          <el-table-column
            class-name="col-text"
            label-class-name="col-text"
            prop="feeDeductQty"
            label="费用"
            width="56"
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
            <template #default="{ row }">
              <span :class="{ 'edited-flag': row.editedFlag }">{{ row.countedQty ?? '—' }}</span>
            </template>
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
          <el-table-column label="操作" width="130">
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
              <el-button
                v-if="detail?.status === 'DRAFT'"
                size="small"
                data-testid="mcs-edit-line-btn"
                @click="openEditLine(row)"
              >
                编辑
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <!-- 草稿行编辑弹窗（V348）：实盘修正 + 摇机损耗/费用抵扣手工补录 + 备注 -->
    <el-dialog
      v-model="editLineOpen"
      title="草稿行编辑（审批后不可改）"
      width="460px"
      data-testid="mcs-edit-line-dialog"
    >
      <el-form label-width="90px">
        <el-form-item label="SKU">
          <span>{{ editLine?.skuId }} {{ editLine?.skuName }}</span>
        </el-form-item>
        <el-form-item label="实盘数">
          <el-input-number
            v-model="editCounted"
            :min="0"
            :step="1"
            step-strictly
            data-testid="mcs-edit-counted"
          />
        </el-form-item>
        <el-form-item label="摇机损耗">
          <el-input-number
            v-model="editRock"
            :min="0"
            :step="1"
            step-strictly
            data-testid="mcs-edit-rock"
          />
        </el-form-item>
        <el-form-item label="费用抵扣">
          <el-input-number
            v-model="editFee"
            :min="0"
            :step="1"
            step-strictly
            data-testid="mcs-edit-fee"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="editRemark"
            placeholder="补录说明（如：X日摇机测试耗 2 件）"
            data-testid="mcs-edit-remark"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editLineOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="editLineSaving"
          data-testid="mcs-edit-line-confirm"
          @click="confirmEditLine"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

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
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, downloadAuthFile } from '@/api/client';
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
  /** V348 损耗四列：摇机 / 过期 / 客情 / 费用抵扣（原 lossQty 单列拆分）。 */
  rockLossQty: number;
  expiredLossQty: number;
  guestWaterQty: number;
  feeDeductQty: number;
  lossQty: number;
  expectedQty: number;
  countedQty: number | null;
  gapQty: number | null;
  gapAmountCents: number | null;
  /** 单价快照（分）：行编辑重算金额时优先用它。 */
  unitPriceCents: number | null;
  lineRemark: string | null;
  /** 人工编辑过（漂移核对跳过该行）。 */
  editedFlag: boolean;
  gapDisposition: string;
  claimWriteOffId: number | null;
}

interface CloseSheet {
  closeId: number;
  warehouseId: string;
  warehouseName: string;
  yearMonth: string;
  status: string;
  /** 期初来源：PERIOD_CLOSE=上期实盘结转（可信）/ BOOK=账面结转（亮黄提示）。 */
  openingSource: string;
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

// ── 草稿行编辑（V348）：实盘修正 + 摇机/费用补录 + 备注（审批后不可改） ──
const editLineOpen = ref(false);
const editLine = ref<CloseSheetLine | null>(null);
const editCounted = ref(0);
const editRock = ref(0);
const editFee = ref(0);
const editRemark = ref('');
const editLineSaving = ref(false);

function openEditLine(row: CloseSheetLine) {
  editLine.value = row;
  editCounted.value = row.countedQty ?? row.expectedQty;
  editRock.value = row.rockLossQty ?? 0;
  editFee.value = row.feeDeductQty ?? 0;
  editRemark.value = row.lineRemark ?? '';
  editLineOpen.value = true;
}

async function confirmEditLine() {
  if (!detail.value || !editLine.value) return;
  editLineSaving.value = true;
  try {
    const updated = await api.request<CloseSheet>(
      AdminEndpoints.warehouseCloseSheetLineUpdate(detail.value.closeId, editLine.value.lineId),
      'PATCH',
      {
        countedQty: editCounted.value,
        rockLossQty: editRock.value,
        feeDeductQty: editFee.value,
        lineRemark: editRemark.value.trim() || null
      }
    );
    detail.value = updated;
    editLineOpen.value = false;
    ElMessage.success('行已更新（已标记人工编辑，漂移核对跳过该行）');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '行更新失败');
  } finally {
    editLineSaving.value = false;
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

/** 财务 18 列导出（V348）：xlsx 由后端生成（含损耗四列/单价/金额），走鉴权下载。 */
async function exportCsv() {
  if (!detail.value) return;
  try {
    await downloadAuthFile(
      AdminEndpoints.warehouseCloseSheetExport(detail.value.closeId),
      `monthly-close-${detail.value.warehouseId}-${detail.value.yearMonth}.xlsx`
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}
</script>

<style scoped>
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
.edited-flag {
  color: var(--el-color-warning);
  font-weight: 600;
}
</style>
