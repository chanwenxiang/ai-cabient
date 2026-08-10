<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">选品诊断</span>
            <span class="hint">SKU 动销 / 营收 / 库存天数 → 建议下架 / 保留 / 替换</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-select v-model="days" style="width: 110px">
            <el-option label="近 7 天" :value="7" />
            <el-option label="近 30 天" :value="30" />
            <el-option label="近 90 天" :value="90" />
          </el-select>
          <el-button type="primary" :loading="running" @click="run">运行诊断</el-button>
          <el-button @click="onExport">导出 CSV</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table
      v-loading="loading"
      :data="list"
      stripe
      border
      row-key="skuId"
      empty-text=" "
      class="report-table"
    >
      <template #empty>
        <el-empty v-if="!loading" description="暂无诊断数据，点击「运行诊断」生成" />
      </template>
      <el-table-column prop="skuId" label="SKU" width="110" align="center" class-name="col-text" />
      <el-table-column prop="skuName" label="商品" min-width="140" align="center" />
      <el-table-column prop="category" label="分类" width="100" align="center" />
      <el-table-column label="动销表现" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="perfTag(row.performanceLevel)">{{ perfLabel(row.performanceLevel) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="salesQty" label="销量" width="80" align="center" />
      <el-table-column label="营收(元)" width="110" align="center">
        <template #default="{ row }">{{ (row.revenueCents / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="stockDays" label="库存天数" width="100" align="center">
        <template #default="{ row }">{{ row.stockDays ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="评审状态" width="130" align="center">
        <template #default="{ row }">
          <el-tag :type="reviewTag(row.reviewStatus)">{{ reviewLabel(row.reviewStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" align="center">
        <template #default="{ row }">
          <el-button link type="warning" @click="decide(row, 'RECOMMEND_DELIST')"
            >建议下架</el-button
          >
          <el-button link type="success" @click="decide(row, 'KEEP')">保留</el-button>
          <el-button link type="danger" @click="confirmDelist(row)">确认下架</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';
import { dictLabel } from '@aicabinet/shared-dict';

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

const loading = ref(false);
const running = ref(false);
const days = ref(30);
const list = ref<ReviewRow[]>([]);

const { onExport } = useListCsv({
  filePrefix: '选品诊断',
  headers: ['SKU', '商品', '分类', '动销表现', '销量', '营收(元)', '库存天数', '评审状态', '建议', '原因', '替换SKU'],
  toRows: () =>
    list.value.map((r) => [
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
});

onMounted(load);

async function load() {
  loading.value = true;
  try {
    list.value = await api.request<ReviewRow[]>('/api/v2/ops/admin/growth/sku-review');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function run() {
  running.value = true;
  try {
    list.value = await api.request<ReviewRow[]>(
      `/api/v2/ops/admin/growth/sku-review/run?days=${days.value}`,
      'POST'
    );
    ElMessage.success('诊断完成');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '诊断失败');
  } finally {
    running.value = false;
  }
}

async function decide(row: ReviewRow, action: string) {
  try {
    await api.request<ReviewRow>(
      `/api/v2/ops/admin/growth/sku-review/${encodeURIComponent(row.skuId)}/decide`,
      'POST',
      { action }
    );
    ElMessage.success(action === 'KEEP' ? '已保留' : '已建议下架');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

async function confirmDelist(row: ReviewRow) {
  const { value } = await ElMessageBox.prompt(
    `确认下架「${row.skuName}」？商品将停止销售。可填写替换商品 SKU（选填）。`,
    '确认下架',
    {
      confirmButtonText: '确认下架',
      cancelButtonText: '取消',
      inputPlaceholder: '替换 SKU（选填）'
    }
  ).catch(() => ({ value: undefined as string | undefined }));
  if (value === undefined) return;
  try {
    await api.request<ReviewRow>(
      `/api/v2/ops/admin/growth/sku-review/${encodeURIComponent(row.skuId)}/decide`,
      'POST',
      { action: 'DELIST', reason: '选品诊断确认下架', replaceSkuId: value.trim() || undefined }
    );
    ElMessage.success('已下架');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

function perfLabel(level?: string) {
  return dictLabel('sku_perf_level', level) || '—';
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
  return dictLabel('sku_review_status', status) || status;
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
