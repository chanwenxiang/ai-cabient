<script setup lang="ts">
// V311 配套：**待索赔台账**（仓储补货域缺口 #4 方案 C）。
//
//   🔴 这个页面**不是**「冲减供应商应付」的入口 —— 它只回答「谁该赔、赔多少」。
//   为什么不自动冲减：盘亏 ≠ 供应商赔（过期报损 / 搬运破损 / 用户拿走，责任方各不相同），
//   且同 SKU 可能来自多个供应商 ⇒ 一个批次横跨多张采购单，无法确定冲减谁。
//   ⇒ 先把归因与金额记清楚，**等追偿实际发生再走支付流程**。
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { useAuthStore } from '@/stores/auth';
import CrudTable from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { yuanText } from '@/utils/display';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface ClaimRow {
  writeOffId: number;
  deviceId?: string | null;
  warehouseId?: string | null;
  skuId: string;
  batchNo?: string | null;
  quantity: number;
  reason?: string | null;
  reasonCategory?: string | null;
  responsibleParty?: string | null;
  claimNo?: string | null;
  claimAmountCents?: number | null;
  costCents?: number | null;
  createdAt?: string;
}

interface SummaryRow {
  responsible_party: string;
  claim_cents: number;
  claim_count: number;
}

const auth = useAuthStore();
const canList = computed(() => auth.hasPerm('ops:replenishment:list'));

const partyFilter = ref<string>('');
const summary = ref<SummaryRow[]>([]);
const summaryLoading = ref(false);

const PARTY_LABELS: Record<string, string> = {
  SUPPLIER: '供应商',
  LOGISTICS: '物流',
  MERCHANT: '商户',
  NONE: '无责任方（自然损耗）',
  UNDETERMINED: '待判定'
};

/** 与后端 `WriteOffReasonCategory` 同语义（枚举定义在 common-core）。 */
const CATEGORY_LABELS: Record<string, string> = {
  EXPIRED: '过期/临期',
  DAMAGED: '外力损坏',
  LOST: '运输丢失',
  SHRINKAGE: '盘亏（待查）',
  SAMPLING: '样品/试吃',
  OTHER: '其他'
};

const crud = useCrudTable<ClaimRow>({
  rowKey: (r) => r.writeOffId,
  fetchPage: async (params) => {
    const q = new URLSearchParams({
      page: String(params.page), // 0 起（useCrudTable 已换算）
      size: String(params.size)
    });
    if (partyFilter.value) q.set('party', partyFilter.value);
    const data = await api.request<{ items: ClaimRow[]; total: number }>(
      AdminEndpoints.inventoryWriteOffClaims(q)
    );
    return { items: data.items || [], total: Number(data.total) || 0 };
  }
});

/**
 * 拉「按责任方汇总」（谁该赔多少钱）。
 *
 * 🔴 失败时**只提示、不清空** `summary`：清空会让「接口挂了」看起来像
 * 「没人欠钱了」——这两者的处置完全不同（前者要查，后者可以放一放）。
 */
async function loadSummary() {
  summaryLoading.value = true;
  try {
    summary.value = await api.request<SummaryRow[]>(
      AdminEndpoints.inventoryWriteOffClaimsSummary(new URLSearchParams())
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载汇总失败');
  } finally {
    summaryLoading.value = false;
  }
}
void loadSummary();

function onPartyFilter() {
  void crud.load();
  void loadSummary();
}

function partyLabel(p?: string | null) {
  if (!p) return '—';
  return PARTY_LABELS[p] || p;
}
function categoryLabel(c?: string | null) {
  if (!c) return '未分类';
  return CATEGORY_LABELS[c] || c;
}

/** 位置：设备侧 or 仓库侧（V313 起两者皆可报损）。 */
function locationLabel(r: ClaimRow) {
  return r.warehouseId ? `仓库 ${r.warehouseId}` : `柜机 ${r.deviceId || '—'}`;
}
</script>

<template>
  <div class="claim-ledger">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="待索赔台账：只列「责任方已认定 且 索赔额 > 0」的记录。它不是「自动冲减供应商应付」—— 追偿实际发生再走支付流程。"
      style="margin-bottom: 12px"
    />

    <el-card shadow="never" style="margin-bottom: 12px">
      <template #header>
        <div class="card-header">
          <span>按责任方汇总（谁该赔多少钱）</span>
          <el-button
            v-if="canList"
            :loading="summaryLoading"
            data-testid="claim-summary-refresh"
            @click="loadSummary"
            >刷新</el-button
          >
        </div>
      </template>
      <!-- 🔴 本汇总表**刻意不用 CrudTable**：它是只读聚合（按责任方，来自独立聚合接口、无分页），
           而 CrudTable 是「服务端分页表格」的收敛形态 —— 套过来会引入假的分页/排序，是错误抽象。
           责任方枚举仅 5 种（见 PARTY_LABELS），用项目既有的 el-descriptions 只读范式更贴切。
           （直接写原生表格标签会触犯 check-admin-table-gate「裸表只减不增」，故此处不用表格形态。）-->
      <el-descriptions
        v-loading="summaryLoading"
        :column="1"
        border
        data-testid="claim-summary-table"
      >
        <el-descriptions-item
          v-for="row in summary"
          :key="row.responsible_party"
          :label="partyLabel(row.responsible_party)"
        >
          {{ row.claim_count }} 笔 ·
          <b>{{ yuanText(row.claim_cents) }}</b>
        </el-descriptions-item>
        <el-descriptions-item v-if="!summary.length" label="待索赔">
          <span class="muted">暂无待索赔记录</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-form :inline="true" :model="{}" style="margin-bottom: 8px">
      <el-form-item label="责任方">
        <el-select
          v-model="partyFilter"
          clearable
          placeholder="全部"
          style="width: 200px"
          data-testid="claim-party-filter"
          @change="onPartyFilter"
        >
          <el-option
            v-for="(label, value) in PARTY_LABELS"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
      </el-form-item>
    </el-form>

    <!--🔴 本页**只读** —— 归因与索赔金额的修改走「报损」入口。
         改索赔额 = 改一笔「要向人追的钱」，属资金相关动作必须留痕（走���端审计），
         表格内联编辑做不到。 -->
    <CrudTable
      v-if="canList"
      :table="crud"
      row-key="writeOffId"
      :empty-text="'暂无待索赔记录（责任方未认定 或 索赔额为 0 的记录不列入）'"
      data-testid="claim-ledger-table"
    >
      <!--🔴 中心对齐必须配 `class-name`（门禁 check:admin-table-align 的「裸 center」规则）：
           居中是**全表约定**而非逐列随手写，class-name 才是它与全局样式表的接点。
           ID 列是文本类⇒ `col-text`。 -->
      <el-table-column
        prop="writeOffId"
        label="ID"
        width="90"
        align="center"
        class-name="col-text"
        label-class-name="col-text"
      />
      <el-table-column label="商品 / 位置" min-width="180">
        <template #default="{ row }">
          <div>{{ row.skuId }}</div>
          <div class="hint">{{ locationLabel(row) }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="batchNo" label="批次" min-width="120">
        <template #default="{ row }">{{ row.batchNo || '—' }}</template>
      </el-table-column>
      <el-table-column prop="quantity" label="数量" width="80" align="right" />
      <el-table-column label="原因分类" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="row.reasonCategory ? 'warning' : 'info'">
            {{ categoryLabel(row.reasonCategory) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="责任方" width="160">
        <template #default="{ row }">{{ partyLabel(row.responsibleParty) }}</template>
      </el-table-column>
      <el-table-column label="理赔单号" min-width="140">
        <template #default="{ row }">{{ row.claimNo || '—' }}</template>
      </el-table-column>
      <el-table-column label="索赔额" width="130" align="right">
        <template #default="{ row }">
          <b>{{ yuanText(row.claimAmountCents) }}</b>
          <el-tooltip
            v-if="row.costCents != null"
            :content="`账面损失 ${yuanText(row.costCents)}（与索赔额不同：协商折价时会不等）`"
            placement="top"
          >
            <span class="hint">?</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="账面损失" width="120" align="right">
        <template #default="{ row }">{{ yuanText(row.costCents) }}</template>
      </el-table-column>
      <el-table-column label="报损时间" min-width="160">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
    </CrudTable>
    <el-empty v-else description="无「仓储补货查看」权限" />
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.hint {
  margin-left: 4px;
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
</style>
