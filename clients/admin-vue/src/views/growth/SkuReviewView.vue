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
          <el-button
            type="warning"
            plain
            :disabled="!hasSelection"
            :loading="batchLoading === 'delist'"
            @click="batchDecide('DELIST')"
            >批量下架</el-button
          >
          <el-button
            type="success"
            plain
            :disabled="!hasSelection"
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
          <el-button @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
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
        <el-table
          ref="tableRef"
          v-loading="loading"
          :data="displayList"
          stripe
          border
          row-key="skuId"
          empty-text=" "
          class="report-table"
          @selection-change="onSelectionChange"
        >
          <template #empty>
            <el-empty v-if="!loading" description="暂无诊断数据，点击「运行诊断」生成" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="skuId"
            label="SKU"
            width="110"
            align="center"
            class-name="col-text"
          />
          <el-table-column prop="skuName" label="商品" min-width="140" align="center" />
          <el-table-column prop="category" label="分类" width="100" align="center" />
          <el-table-column label="动销表现" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="perfTag(row.performanceLevel)">{{
                perfLabel(row.performanceLevel)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="salesQty" label="销量" width="80" align="center" />
          <el-table-column label="营收(元)" width="110" align="center">
            <template #default="{ row }">{{ (row.revenueCents / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="stockDays" label="库存天数" width="100" align="center">
            <template #default="{ row }">{{ row.stockDays ?? '暂无' }}</template>
          </el-table-column>
          <el-table-column label="评审状态" width="130" align="center">
            <template #default="{ row }">
              <el-tag :type="reviewTag(row.reviewStatus)">{{
                reviewLabel(row.reviewStatus)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="220"
            align="center"
            class-name="col-action"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button link type="warning" @click="decide(row, 'RECOMMEND_DELIST')"
                >建议下架</el-button
              >
              <el-button link type="success" @click="decide(row, 'KEEP')">保留</el-button>
              <el-button link type="danger" @click="confirmDelist(row)">确认下架</el-button>
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
      layout="total, sizes, prev, pager, next, jumper"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import { useAdminListTable } from '@/composables/useAdminListTable';
import { useListCsv } from '@/composables/useListCsv';
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

const loading = ref(false);
const running = ref(false);
const listHydrated = ref(false);
const batchLoading = ref<'delist' | 'keep' | ''>('');
const days = ref(30);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const list = ref<ReviewRow[]>([]);

const {
  tableRef,
  keyword,
  hasSelection,
  onSelectionChange,
  pickSelected,
  exportButtonLabel,
  clearSelection,
  filterByKeyword,
  resetKeyword
} = useAdminListTable<ReviewRow>((r) => r.skuId);

const displayList = computed(() =>
  filterByKeyword(list.value, (row, kw) => {
    return (
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
  })
);

const { onExport } = useListCsv({
  filePrefix: '选品诊断',
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
  toRows: () =>
    pickSelected(displayList.value).map((r) => [
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
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    const data = await api.request<{ items: ReviewRow[]; total: number }>(
      `/api/v2/ops/admin/growth/sku-review?${q}`
    );
    list.value = data.items || [];
    total.value = Number(data.total) || 0;
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  load();
}

function resetFilters() {
  resetKeyword();
  page.value = 1;
  load();
}

function onSizeChange() {
  page.value = 1;
  load();
}

async function run() {
  running.value = true;
  try {
    await api.request<ReviewRow[]>(
      `/api/v2/ops/admin/growth/sku-review/run?days=${days.value}`,
      'POST'
    );
    page.value = 1;
    await load();
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

async function batchDecide(action: 'DELIST' | 'KEEP') {
  const targets = pickSelected(displayList.value);
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
        `确认批量下架 ${targets.length} 个 SKU？商品将停止销售。可填写统一替换 SKU（选填）。`,
        '批量下架',
        {
          confirmButtonText: '确认下架',
          cancelButtonText: '取消',
          inputPlaceholder: '替换 SKU（选填）'
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
        api.request<ReviewRow>(
          `/api/v2/ops/admin/growth/sku-review/${encodeURIComponent(row.skuId)}/decide`,
          'POST',
          {
            action,
            reason,
            replaceSkuId: action === 'DELIST' ? replaceSkuId : undefined
          }
        )
      )
    );
    const ok = results.filter((r) => r.status === 'fulfilled').length;
    const fail = results.length - ok;
    if (fail === 0) ElMessage.success(`已批量${label} ${ok} 个`);
    else ElMessage.warning(`批量${label}完成：成功 ${ok}，失败 ${fail}`);
    clearSelection();
    await load();
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
