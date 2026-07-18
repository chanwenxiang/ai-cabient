<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">识别映射</span>
            <span class="hint">YOLO 类名 → SKU；建档请在「商品与识别」维护</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button @click="router.push('/skus')">商品与识别</el-button>
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
          placeholder="类别 / SKU / 商品名"
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 720px">
        <el-table
          v-loading="loading"
          :data="paged"
          stripe
          border
          class="report-table"
          row-key="className"
          @selection-change="onSelectionChange"
        >
          <template #empty><el-empty description="暂无 YOLO 映射" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="类别" min-width="160" class-name="col-text">
            <template #default="{ row }">
              <div class="name-cell">
                <strong>{{ row.className || '-' }}</strong>
                <small v-if="row.minConfidence != null">最低置信度 {{ formatConfidence(row.minConfidence) }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="商品" min-width="200" class-name="col-text">
            <template #default="{ row }">
              <div class="name-cell">
                <strong>{{ row.skuName || row.skuId || '-' }}</strong>
                <small v-if="row.skuId && row.skuName && row.skuName !== row.skuId" class="cell-id">
                  {{ row.skuId }}
                </small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="最低置信度" width="120" align="center">
            <template #default="{ row }">{{ formatConfidence(row.minConfidence) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="filtered.length"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';

interface YoloMappingRow {
  className?: string;
  skuId?: string;
  skuName?: string;
  minConfidence?: number | string;
}

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const yoloMappings = ref<YoloMappingRow[]>([]);

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q) return yoloMappings.value;
  return yoloMappings.value.filter((row) =>
    [row.className, row.skuId, row.skuName].some((x) => String(x || '').toLowerCase().includes(q))
  );
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

watch(keyword, () => {
  page.value = 1;
});

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<YoloMappingRow>((r) => r.className || `${r.skuId}`);

const { onExport } = useListCsv({
  filePrefix: 'YOLO识别映射',
  headers: ['类别', 'SKU', '商品名', '最低置信度'],
  toRows: () =>
    pickSelected(yoloMappings.value).map((row) => [
      row.className ?? '',
      row.skuId ?? '',
      row.skuName ?? '',
      row.minConfidence ?? ''
    ])
});

function formatConfidence(v?: number | string) {
  if (v == null || v === '') return '-';
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return n <= 1 ? `${Math.round(n * 100)}%` : String(n);
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  router.replace({ query });
}

function applyRouteQuery() {
  if (typeof route.query.keyword === 'string' && route.query.keyword !== keyword.value) {
    keyword.value = route.query.keyword;
    return true;
  }
  return false;
}

function search() {
  page.value = 1;
  syncRouteQuery();
}

function reset() {
  keyword.value = '';
  page.value = 1;
  syncRouteQuery();
}

async function load() {
  loading.value = true;
  try {
    const data = await api.request<{ yolo?: YoloMappingRow[] } | YoloMappingRow[]>(
      '/api/v2/ops/admin/vision-mappings',
      'GET'
    );
    if (Array.isArray(data)) {
      yoloMappings.value = data;
    } else {
      yoloMappings.value = data.yolo || [];
    }
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  applyRouteQuery();
  load();
});
onActivated(() => {
  applyRouteQuery();
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
.page-card-head__actions { display: flex; gap: 8px; flex-wrap: wrap; }
.name-cell { display: grid; gap: 2px; line-height: 1.35; }
.name-cell strong { font-weight: 650; }
.name-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
</style>
