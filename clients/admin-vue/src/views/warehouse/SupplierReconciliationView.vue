<script setup lang="ts">
// V326 配套：**供应商月度对账单**（E3，缺口 #8）。
//
//   行业公式（COMPETITOR_BENCHMARK CB-016，勤策 ERP 原文）：
//   期末余额 = 期初余额 + 本期新增应付 − 本期退货冲减 − 本期付款。
//
//   🔴 数据边界：对账基于事件流水（supplier_payable_entry，V326 起）+ 付款流水（supplier_payment）。
//   流水启用月份之前的月份**没有数据**——界面上如实提示「无流水数据」，
//   而不是把 0 当成「余额为 0」（这两种状态的处置完全不同）。
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { useAuthStore } from '@/stores/auth';
import { yuanText } from '@/utils/display';

interface SupplierOption {
  supplierId: string;
  supplierName: string;
}

interface ReconciliationResult {
  supplierId: string;
  supplierName: string;
  month: string;
  openingCents: number;
  receivedCents: number;
  returnedCents: number;
  paidCents: number;
  closingCents: number;
  ledgerBalanceCents: number;
  mainBalanceCents: number;
  ledgerConsistent: boolean;
}

const auth = useAuthStore();
const canList = computed(() => auth.hasPerm('ops:procurement:list'));

/** 默认查上个自然月：对账惯例是「对上个月的账」，当月账还在滚动。 */
function defaultMonth(): string {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() - 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

const supplierId = ref<string>('');
const suppliers = ref<SupplierOption[]>([]);
const month = ref<string>(defaultMonth());
const result = ref<ReconciliationResult | null>(null);
const loading = ref(false);

const suppliersLoading = ref(false);
async function loadSuppliers() {
  suppliersLoading.value = true;
  try {
    const data = await api.request<{ items: SupplierOption[] }>(
      AdminEndpoints.suppliersListAll,
      'GET'
    );
    suppliers.value = data.items || [];
  } catch (e) {
    // 供应商列表加载失败只提示：用户也可能手输已知供应商 ID
    ElMessage.error(e instanceof Error ? `供应商列表加载失败：${e.message}` : '供应商列表加载失败');
  } finally {
    suppliersLoading.value = false;
  }
}
void loadSuppliers();

/**
 * 生成对账单。
 *
 * 🔴 结果**成功也不清空旧结果之外的状态**——查询失败时保留旧结果并提示，
 * 「接口挂了」和「查无数据」是两回事（照 claim-ledger 的失败不致盲先例）。
 */
async function generate() {
  if (!supplierId.value || !month.value) {
    ElMessage.warning('请先选择供应商和对账月份');
    return;
  }
  loading.value = true;
  try {
    const q = new URLSearchParams({ supplierId: supplierId.value, month: month.value });
    result.value = await api.request<ReconciliationResult>(
      AdminEndpoints.supplierReconciliation(q),
      'GET'
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成对账单失败');
  } finally {
    loading.value = false;
  }
}

const hasAnyData = (r: ReconciliationResult) =>
  r.openingCents !== 0 || r.receivedCents !== 0 || r.returnedCents !== 0 || r.paidCents !== 0;

/** 月份选择器绑定值（Date）与字符串（yyyy-MM）互转。 */
function onMonthChange(v: Date | null) {
  if (v) month.value = `${v.getFullYear()}-${String(v.getMonth() + 1).padStart(2, '0')}`;
}
const monthDate = computed<Date | null>(() => {
  const [y, m] = month.value.split('-').map(Number);
  return Number.isFinite(y) && Number.isFinite(m) ? new Date(y, m - 1, 1) : null;
});

/** CSV 导出：一行对账单 + 交叉验证说明，供与供应商线下核对（一期不做系统内收票登记）。 */
function exportCsv() {
  const r = result.value;
  if (!r) return;
  const rows: string[][] = [
    ['供应商月度对账单'],
    ['供应商', r.supplierName, r.supplierId],
    ['对账月份', r.month],
    [],
    ['项目', '金额（元）'],
    ['期初余额', (r.openingCents / 100).toFixed(2)],
    ['本期新增应付（收货）', (r.receivedCents / 100).toFixed(2)],
    ['本期退货冲减', (r.returnedCents / 100).toFixed(2)],
    ['本期付款', (r.paidCents / 100).toFixed(2)],
    ['期末余额（应付供应商）', (r.closingCents / 100).toFixed(2)],
    [],
    ['流水推算当前余额', (r.ledgerBalanceCents / 100).toFixed(2)],
    ['系统应付主表当前余额', (r.mainBalanceCents / 100).toFixed(2)],
    ['两套口径一致', r.ledgerConsistent ? '是' : '否（对账单不可信，需先排查）'],
    [],
    ['生成时间', new Date().toISOString()],
    [
      '口径说明',
      '月边界为 UTC；金额为含税应付口径；与供应商开票数的差异请线下人工核对（系统暂无收票登记）'
    ]
  ];
  const csv =
    '\ufeff' +
    rows
      .map((row) => row.map((c) => `"${String(c ?? '').replace(/"/g, '""')}"`).join(','))
      .join('\r\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `对账单_${r.supplierId}_${r.month}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
}
</script>

<template>
  <div class="reconciliation">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="供应商月度对账单：期初 + 本期新增应付 − 本期退货冲减 − 本期付款 = 期末。只读聚合，不动任何资金。"
      style="margin-bottom: 12px"
    />

    <el-card shadow="never" style="margin-bottom: 12px">
      <el-form :inline="true" :model="{}" data-testid="recon-filter">
        <el-form-item label="供应商">
          <el-select
            v-model="supplierId"
            v-loading="suppliersLoading"
            filterable
            placeholder="选择供应商"
            style="width: 260px"
            data-testid="recon-supplier"
          >
            <el-option
              v-for="s in suppliers"
              :key="s.supplierId"
              :label="`${s.supplierName}（${s.supplierId}）`"
              :value="s.supplierId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="对账月份">
          <el-date-picker
            :model-value="monthDate"
            type="month"
            placeholder="选择月份"
            value-format="YYYY-MM"
            data-testid="recon-month"
            @update:model-value="onMonthChange"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            v-if="canList"
            type="primary"
            :loading="loading"
            data-testid="recon-generate"
            @click="generate"
            >生成对账单</el-button
          >
          <el-button v-if="canList && result" data-testid="recon-export" @click="exportCsv"
            >导出 CSV</el-button
          >
        </el-form-item>
      </el-form>
    </el-card>

    <template v-if="result">
      <!-- 🔴 流水与主表不一致 ⇒ 对账单整体不可信，必须先排查（有人绕过 Service 改库/有未登记事件）。
           这个红条比对账数字本身更重要——数字错了会传染到每一次与供应商的核对。 -->
      <el-alert
        v-if="!result.ledgerConsistent"
        type="error"
        :closable="false"
        show-icon
        data-testid="recon-inconsistent"
        title="🔴 流水推算余额与应付主表余额不一致 —— 对账单不可信。可能原因：绕过系统直改数据库、或有未走标准入口的账务变更。请先排查，不要用这张单对外核对。"
        style="margin-bottom: 12px"
      />

      <el-alert
        v-else-if="!hasAnyData(result)"
        type="warning"
        :closable="false"
        show-icon
        data-testid="recon-empty"
        title="该月份无流水数据。对账流水自 2026-10（V326）起记录，更早的月份无法生成对账单 —— 这是「没数据」，不代表当月余额为 0。"
        style="margin-bottom: 12px"
      />

      <!-- 🔴 对账单是**单行结果**，用 el-descriptions 只读范式（同 claim-summary 先例），
           不套 CrudTable（服务端分页表格的收敛形态，套过来是假分页）；也不写裸 table（门禁只减不增）。 -->
      <el-card shadow="never">
        <template #header>
          <span
            >{{ result.supplierName }}（{{ result.supplierId }}）·
            {{ result.month }} 月度对账单</span
          >
        </template>
        <el-descriptions :column="1" border data-testid="recon-result">
          <el-descriptions-item label="期初余额">
            <b>{{ yuanText(result.openingCents) }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="本期新增应付（收货累加）">
            <b>{{ yuanText(result.receivedCents) }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="本期退货冲减">
            <b>{{ yuanText(result.returnedCents) }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="本期付款">
            <b>{{ yuanText(result.paidCents) }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="期末余额（尚应付供应商）">
            <b style="font-size: 1.15em">{{ yuanText(result.closingCents) }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="交叉验证">
            <el-tag v-if="result.ledgerConsistent" type="success" size="small">
              流水与主表一致（{{ yuanText(result.ledgerBalanceCents) }}）
            </el-tag>
            <el-tag v-else type="danger" size="small">不一致，见顶部红条</el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <div class="hint" style="margin-top: 8px">
          与供应商开票数的差异请线下人工核对（CSV 可导出）；系统内收票登记为二期范围。
        </div>
      </el-card>
    </template>
    <el-empty v-else-if="canList" description="选择供应商与月份后生成对账单" />
    <el-empty v-else description="无「采购查看」权限" />
  </div>
</template>

<style scoped>
.hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
</style>
