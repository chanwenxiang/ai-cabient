<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">选品诊断</span>
            <span class="hint"
              >动销诊断；下架会停售 SKU。替换 SKU 仅备注建议，不会改柜内货道商品</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            type="warning"
            plain
            :disabled="!crud.hasSelection"
            :loading="batchLoading === 'delist'"
            @click="batchDecide('DELIST')"
            >批量下架</el-button
          >
          <el-button
            type="success"
            plain
            :disabled="!crud.hasSelection"
            :loading="batchLoading === 'keep'"
            @click="batchDecide('KEEP')"
            >批量保留</el-button
          >
          <el-select v-model="days" style="width: 110px">
            <el-option label="近 7 天" :value="7" />
            <el-option label="近 30 天" :value="30" />
            <el-option label="近 90 天" :value="90" />
          </el-select>
          <el-button type="primary" :loading="running" @click="run">运行诊断</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="SKU / 商品名 / 分类"
          style="width: 200px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="skuId"
          selectable
          :actions="rowActions"
          :action-width="220"
          actions-testid="sku-review"
          empty-text="暂无诊断数据，点击「运行诊断」生成"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column prop="skuId" label="SKU" width="110" class-name="col-text" />
          <el-table-column
            prop="skuName"
            label="商品"
            min-width="140"
            class-name="col-text"
            label-class-name="col-text"
          />
          <el-table-column
            prop="category"
            label="分类"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            label="动销表现"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="perfTag(row.performanceLevel)">{{
                perfLabel(row.performanceLevel)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            prop="salesQty"
            label="销量"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            label="营收(元)"
            width="110"
            align="center"
            class-name="col-money"
            label-class-name="col-money"
          >
            <template #default="{ row }">{{ (row.revenueCents / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column
            prop="stockDays"
            label="库存天数"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.stockDays ?? '暂无' }}</template>
          </el-table-column>
          <el-table-column
            label="评审状态"
            width="130"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="reviewTag(row.reviewStatus)">{{
                reviewLabel(row.reviewStatus)
              }}</el-tag>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CircleCheck, CircleClose, Warning } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { displayLabel } from '@aicabinet/shared-dict';

type ReviewRow = {
  id: number;
  skuId: string;
  skuName: string;
  category?: string;
  reviewStatus: string;
  performanceLevel?: string;
  salesQty: number;
  revenueCents: number;
  stockDays?: number | null;
  actionType?: string;
  reason?: string;
  replaceSkuId?: string;
};

const running = ref(false);
const batchLoading = ref<'delist' | 'keep' | ''>('');
const days = ref(30);
const keyword = ref('');

/** 关键词为纯前端过滤（接口无该参数）：过滤前移到取数处，与原 displayList 行为一致 */
function filterByKeyword(rows: ReviewRow[]): ReviewRow[] {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return rows;
  return rows.filter(
    (row) =>
      String(row.skuId || '')
        .toLowerCase()
        .includes(kw) ||
      String(row.skuName || '')
        .toLowerCase()
        .includes(kw) ||
      String(row.category || '')
        .toLowerCase()
        .includes(kw)
  );
}

// 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态 / 空态 / 刷新 / 导出 全部内建
const crud = useCrudTable<ReviewRow>({
  rowKey: (r) => r.skuId,
  fetchPage: async (params) => {
    const q = new URLSearchParams({ page: String(params.page), size: String(params.size) });
    const data = await api.request<{ items: ReviewRow[]; total: number }>(
      AdminEndpoints.growthSkuReviewList(q)
    );
    const raw = data.items || [];
    const items = filterByKeyword(raw);
    // 关键词仅前端本页过滤：有剔除时 total=过滤后长度，避免空表仍「共 N 条」（同 lessons #208）
    const total = items.length === raw.length ? Number(data.total) || 0 : items.length;
    return { items, total };
  }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '选品诊断',
  exportPerm: 'ops:sku:export',
  headers: [
    'SKU',
    '商品',
    '分类',
    '动销表现',
    '销量',
    '营收(元)',
    '库存天数',
    '评审状态',
    '建议',
    '原因',
    '替换SKU'
  ],
  toRows: (rows) =>
    rows.map((r) => [
      r.skuId,
      r.skuName,
      r.category || '',
      perfLabel(r.performanceLevel),
      r.salesQty,
      (r.revenueCents / 100).toFixed(2),
      r.stockDays ?? '',
      reviewLabel(r.reviewStatus),
      r.actionType || '',
      r.reason || '',
      r.replaceSkuId || ''
    ])
};

function rowActions(_row: ReviewRow): CrudRowAction[] {
  return [
    { key: 'recommend', label: '建议下架', icon: Warning, type: 'warning' },
    { key: 'keep', label: '保留', icon: CircleCheck, type: 'success' },
    { key: 'delist', label: '确认下架', icon: CircleClose, type: 'danger' }
  ];
}

function onAction({ key, row }: { key: string; row: ReviewRow }) {
  if (key === 'recommend') void decide(row, 'RECOMMEND_DELIST');
  else if (key === 'keep') void decide(row, 'KEEP');
  else if (key === 'delist') void confirmDelist(row);
}

function search() {
  void crud.search();
}

function resetFilters() {
  keyword.value = '';
  void crud.search();
}

async function run() {
  running.value = true;
  try {
    await api.request<ReviewRow[]>(AdminEndpoints.growthSkuReviewRun(days.value), 'POST');
    await crud.search();
    ElMessage.success('诊断完成');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '诊断失败');
  } finally {
    running.value = false;
  }
}

async function decide(row: ReviewRow, action: string) {
  try {
    await api.request<ReviewRow>(AdminEndpoints.growthSkuReviewDecide(row.skuId), 'POST', {
      action
    });
    ElMessage.success(action === 'KEEP' ? '已保留' : '已建议下架');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

async function confirmDelist(row: ReviewRow) {
  const { value } = await ElMessageBox.prompt(
    `确认下架「${row.skuName}」？商品将停止销售。可填建议替换 SKU（仅备注，不改货道）。`,
    '确认下架',
    {
      confirmButtonText: '确认下架',
      cancelButtonText: '取消',
      inputPlaceholder: '建议替换 SKU（仅备注，不改货道）'
    }
  ).catch(() => ({ value: undefined as string | undefined }));
  if (value === undefined) return;
  try {
    await api.request<ReviewRow>(AdminEndpoints.growthSkuReviewDecide(row.skuId), 'POST', {
      action: 'DELIST',
      reason: '选品诊断确认下架',
      replaceSkuId: value.trim() || undefined
    });
    ElMessage.success('已下架');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

async function batchDecide(action: 'DELIST' | 'KEEP') {
  const targets = crud.pickSelected(crud.items);
  if (!targets.length) {
    ElMessage.warning('请先勾选 SKU');
    return;
  }
  const label = action === 'KEEP' ? '保留' : '下架';
  let replaceSkuId: string | undefined;
  let reason = action === 'KEEP' ? '选品诊断批量保留' : '选品诊断批量下架';
  try {
    if (action === 'DELIST') {
      const { value } = await ElMessageBox.prompt(
        `确认批量下架 ${targets.length} 个 SKU？商品将停止销售。可填统一「建议替换」SKU（仅备注，不改货道）。`,
        '批量下架',
        {
          confirmButtonText: '确认下架',
          cancelButtonText: '取消',
          inputPlaceholder: '建议替换 SKU（仅备注，不改货道）'
        }
      );
      replaceSkuId = value?.trim() || undefined;
    } else {
      await ElMessageBox.confirm(`确认批量保留 ${targets.length} 个 SKU？`, '批量保留', {
        type: 'warning'
      });
    }
  } catch {
    return;
  }
  batchLoading.value = action === 'KEEP' ? 'keep' : 'delist';
  try {
    const results = await Promise.allSettled(
      targets.map((row) =>
        api.request<ReviewRow>(AdminEndpoints.growthSkuReviewDecide(row.skuId), 'POST', {
          action,
          reason,
          replaceSkuId: action === 'DELIST' ? replaceSkuId : undefined
        })
      )
    );
    const ok = results.filter((r) => r.status === 'fulfilled').length;
    const fail = results.length - ok;
    if (fail === 0) ElMessage.success(`已批量${label} ${ok} 个`);
    else ElMessage.warning(`批量${label}完成：成功 ${ok}，失败 ${fail}`);
    crud.clearSelection();
    await crud.load();
  } finally {
    batchLoading.value = '';
  }
}

function perfLabel(level?: string) {
  return displayLabel('sku_perf_level', level, '暂无');
}
function perfTag(level?: string) {
  return (
    {
      BEST_SELLER: 'success',
      NORMAL: 'primary',
      SLOW_MOVER: 'warning',
      NO_SALES: 'danger'
    }[level || ''] || 'info'
  );
}
function reviewLabel(status: string) {
  return displayLabel('sku_review_status', status, '暂无');
}
function reviewTag(status: string) {
  return (
    {
      PENDING: 'info',
      RECOMMEND_DELIST: 'warning',
      DELISTED: 'danger',
      KEPT: 'success'
    }[status] || 'info'
  );
}
</script>
