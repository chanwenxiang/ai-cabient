<template>
  <div class="fee-ledger-page">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title">商户费率台账</span>
              <span class="hint">周期费用日扣流水 · 未结清计入欠费催缴与锁柜口径</span>
            </div>
          </div>
        </div>
      </template>
      <CrudTable
        :table="crud"
        row-key="ledgerId"
        :actions="rowActions"
        actions-test-id="fee-ledger"
        @action="onRowAction"
      >
        <template #toolbar>
          <el-input
            v-model="filterMerchantId"
            clearable
            placeholder="商户编号（空=全部）"
            style="width: 190px"
            @keyup.enter="applyFilters"
          />
          <el-select
            v-model="filterStatus"
            placeholder="状态（空=全部）"
            clearable
            style="width: 150px"
          >
            <el-option label="未结清 PENDING" value="PENDING" />
            <el-option label="已结清 SETTLED" value="SETTLED" />
          </el-select>
          <el-select
            v-model="filterFeeType"
            placeholder="费用类型（空=全部）"
            clearable
            style="width: 150px"
          >
            <el-option label="租金 REN" value="REN" />
            <el-option label="流量费 DATA" value="DATA" />
          </el-select>
          <el-button type="primary" @click="applyFilters">查询</el-button>
          <el-button
            v-hasPermi="['ops:merchant-withdraw:adjust']"
            type="warning"
            @click="openRerun"
          >
            按日期重跑日扣
          </el-button>
        </template>
        <el-table-column prop="ledgerId" label="ID" width="80" />
        <el-table-column prop="merchantId" label="商户" width="150" />
        <el-table-column prop="feeDate" label="费用日期" width="120" />
        <el-table-column label="费用类型" width="110">
          <template #default="{ row }">{{ feeTypeText(row.feeType) }}</template>
        </el-table-column>
        <el-table-column label="金额（元）" width="110">
          <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SETTLED' ? 'success' : 'warning'" size="small">
              {{ row.status === 'SETTLED' ? '已结清' : '未结清' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结清时间" width="160">
          <template #default="{ row }">{{
            row.settledAt ? formatDateTime(row.settledAt) : '—'
          }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" />
      </CrudTable>

      <el-alert
        class="tip"
        type="info"
        :closable="false"
        show-icon
        title="口径（2026-10-16 拍板）：每日 00:20 从商户钱包真扣周期费用（租金=月额 ÷ 当月天数、流量费=分/天且不分租赁/买断），扣款成功后才写这一行并直接记「已结清」。费用为 0 或未配置的柜机不扣钱也不写行；只有「未结清 PENDING」才计入欠费催缴，未结清里最老一行满 7 天（系统参数 merchant.overdue.lock_days）锁柜，结清自动复机。"
      />
    </el-card>

    <el-dialog v-model="rerunVisible" title="按日期重跑周期费用日扣" width="480px">
      <el-form label-width="110px">
        <el-form-item label="费用日期" required>
          <el-date-picker
            v-model="rerunDate"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="幂等只补「那天没扣成」的商户：已扣过的不会重扣，也不会追平当天改过的费率差额（要纠历史费率请走钱包调整 + 台账备注）。日期只能选昨天及更早、回溯 92 天内。"
      />
      <template #footer>
        <el-button @click="rerunVisible = false">取消</el-button>
        <el-button type="primary" :loading="rerunLoading" @click="submitRerun">重跑日扣</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { yuanTextZero as yuan } from '@/utils/display';
import { ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Check } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { errorMessage, isUserDismiss } from '@/utils/error-message';

interface MerchantFeeLedgerRow {
  ledgerId: number;
  merchantId: string;
  feeDate: string;
  feeType: string;
  amountCents: number;
  status: string;
  settledAt: string | null;
  remark: string | null;
}

const filterMerchantId = ref('');
const filterStatus = ref('');
const filterFeeType = ref('');

function feeTypeText(t: string): string {
  return t === 'REN' ? '租金' : t === 'DATA' ? '流量费' : t;
}

// 后端 list 无分页 ⇒ 拉全量后本页切片（与机器账单页同法，防回潮门禁要求走 CrudTable）。
const crud = useCrudTable<MerchantFeeLedgerRow>({
  rowKey: (r) => r.ledgerId,
  sort: { prop: 'ledgerId', mode: 'local', defaultDir: 'desc' },
  fetchPage: async (params) => {
    const q = new URLSearchParams();
    if (filterMerchantId.value.trim()) q.set('merchantId', filterMerchantId.value.trim());
    if (filterStatus.value) q.set('status', filterStatus.value);
    if (filterFeeType.value) q.set('feeType', filterFeeType.value);
    const all = await api.request<MerchantFeeLedgerRow[]>(
      `${AdminEndpoints.merchantFeeLedgers}?${q.toString()}`,
      'GET'
    );
    const start = params.page * params.size;
    return { items: all.slice(start, start + params.size), total: all.length };
  }
});

function applyFilters(): void {
  void crud.load({ resetPage: true });
}

function rowActions(row: MerchantFeeLedgerRow): CrudRowAction[] {
  if (row.status === 'SETTLED') return [];
  return [{ key: 'settle', label: '标记结清', icon: Check, perm: 'ops:merchant-withdraw:adjust' }];
}

async function onRowAction(payload: { key: string; row: MerchantFeeLedgerRow }): Promise<void> {
  if (payload.key !== 'settle') return;
  let remark = '线下收款结清';
  try {
    const input = await ElMessageBox.prompt(
      `结清 ${payload.row.merchantId} 的${feeTypeText(payload.row.feeType)}（${payload.row.feeDate}，${yuan(payload.row.amountCents)}）。请填写依据（谁收的款/转账流水号）。`,
      '标记结清',
      { inputValue: remark, inputValidator: (v: string) => (v && v.trim() ? true : '必须填写依据') }
    );
    remark = (input.value || remark).trim();
  } catch (e) {
    if (isUserDismiss(e)) return;
    ElMessage.error(errorMessage(e, '弹窗不可用，已取消'));
    return;
  }
  await api.request(AdminEndpoints.merchantFeeLedgerSettle(payload.row.ledgerId, remark), 'POST');
  ElMessage.success('已标记结清；若该商户欠费清零，费用欠缴锁柜机会自动解除');
  await crud.load();
}

const rerunVisible = ref(false);
const rerunLoading = ref(false);
const rerunDate = ref(yesterday());

function yesterday(): string {
  const d = new Date();
  d.setDate(d.getDate() - 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function openRerun(): void {
  rerunVisible.value = true;
}

async function submitRerun(): Promise<void> {
  if (!rerunDate.value) {
    ElMessage.warning('请选择费用日期');
    return;
  }
  rerunLoading.value = true;
  try {
    const res = await api.request<{ chargedCents?: number; note?: string }>(
      AdminEndpoints.merchantFeeLedgerRerun(rerunDate.value),
      'POST'
    );
    ElMessage.success(
      `${rerunDate.value} 重跑完成，本轮实扣 ${yuan(res?.chargedCents ?? 0)}（幂等，已扣过的不重扣）`
    );
    rerunVisible.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '重跑失败'));
  } finally {
    rerunLoading.value = false;
  }
}
</script>

<style scoped>
.tip {
  margin-top: 12px;
}
</style>
