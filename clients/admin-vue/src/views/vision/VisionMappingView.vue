<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">识别映射</span>
            <span class="hint"
              >端侧类名 → 商品；建档请在「商品管理 /
              识别入驻」维护。生产=进入结算白名单；端侧可换任意识别算法，mock/低置信仍进争议</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-if="canAccessPath('/skus')" @click="goPath('/skus')">商品管理</el-button>
          <el-button v-if="canAccessPath('/sku-vision')" @click="goPath('/sku-vision')"
            >识别入驻</el-button
          >
          <el-button v-hasPermi="['ops:vision:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
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
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="paged"
          stripe
          border
          class="report-table"
          row-key="className"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty
            ><el-empty v-if="listHydrated && !loading" description="暂无识别类名映射"
          /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="className"
            label="类名"
            min-width="140"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.className || '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="商品"
            min-width="160"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.skuName || row.skuId || '无' }}</template>
          </el-table-column>
          <el-table-column label="入驻状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="enrollmentTagType(row.visionEnrollmentStatus)">
                {{ enrollmentLabel(row.visionEnrollmentStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="映射/模型" min-width="140" align="center">
            <template #default="{ row }">
              <div class="pipe-cell">
                <el-tag size="small" :type="row.mappingEffective ? 'success' : 'info'">
                  {{ row.mappingEffective ? '结算白名单' : '未进白名单' }}
                </el-tag>
                <el-tag size="small" type="warning" effect="plain">端侧质量门禁</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="最低置信度" width="120" align="center">
            <template #default="{ row }">{{ formatConfidence(row.minConfidence) }}</template>
          </el-table-column>
          <el-table-column
            v-if="auth.hasPerm('ops:vision:edit')"
            label="操作"
            width="120"
            class-name="col-action"
            align="center"
          >
            <template #default="{ row }">
              <TableActions
                :actions="[
                  { key: 'edit', label: '编辑', icon: EditPen, type: 'primary' },
                  { key: 'delete', label: '删除', icon: Delete, type: 'danger' }
                ]"
                @action="(k) => onAction(String(k), row)"
              />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="filtered.length"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      background
    />

    <el-card class="aliyun-card" shadow="never">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title">阿里云类目映射</span>
              <span class="hint">阿里云商品类目 → SKU（categoryId 为类目 ID）</span>
            </div>
          </div>
          <div class="page-card-head__actions">
            <el-button v-hasPermi="['ops:vision:edit']" type="primary" @click="openAliyunCreate"
              >新增映射</el-button
            >
          </div>
        </div>
      </template>
      <el-table :data="aliyunMappings" stripe border size="small">
        <el-table-column prop="categoryId" label="类目ID" width="150" class-name="col-text" />
        <el-table-column prop="categoryName" label="类目名" min-width="150" show-overflow-tooltip />
        <el-table-column prop="skuId" label="SKU" width="130" class-name="col-text" />
        <el-table-column label="最低置信度" width="110" align="center">
          <template #default="{ row }">{{ row.minConfidence }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center">
          <template #default="{ row }">
            <el-button v-hasPermi="['ops:vision:edit']" size="small" @click="openAliyunEdit(row)"
              >编辑</el-button
            >
            <el-button
              v-hasPermi="['ops:vision:edit']"
              size="small"
              type="danger"
              @click="deleteAliyun(row)"
              >删除</el-button
            >
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无阿里云类目映射" /></template>
      </el-table>
    </el-card>

    <el-dialog
      v-model="aliyunVisible"
      :title="aliyunForm.categoryId ? '编辑阿里云映射' : '新增阿里云映射'"
      width="440px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="类目ID">
          <el-input v-model="aliyunForm.categoryId" placeholder="阿里云类目 ID" />
        </el-form-item>
        <el-form-item label="类目名">
          <el-input v-model="aliyunForm.categoryName" />
        </el-form-item>
        <el-form-item label="SKU">
          <el-select v-model="aliyunForm.skuId" filterable style="width: 100%">
            <el-option
              v-for="s in skuOptions"
              :key="s.skuId"
              :label="`${s.skuName}（${s.skuId}）`"
              :value="s.skuId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="最低置信度">
          <el-input-number v-model="aliyunForm.minConfidence" :min="0" :max="1" :step="0.05" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="aliyunVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAliyun">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="编辑识别映射" width="480px" destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="类别">
          <el-input :model-value="editForm.className" disabled />
        </el-form-item>
        <el-form-item label="商品" required>
          <el-select
            v-model="editForm.skuId"
            filterable
            clearable
            placeholder="选择 SKU"
            style="width: 100%"
          >
            <el-option
              v-for="s in skuOptions"
              :key="s.skuId"
              :label="`${s.skuName || s.skuId}（${s.skuId}）`"
              :value="s.skuId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="最低置信度" required>
          <el-input-number
            v-model="editForm.minConfidence"
            :min="0"
            :max="1"
            :step="0.01"
            :precision="2"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Delete, EditPen, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { displayLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

interface YoloMappingRow {
  className?: string;
  skuId?: string;
  skuName?: string;
  minConfidence?: number | string;
  visionEnrollmentStatus?: string;
  mappingEffective?: boolean;
  modelPipelineStatus?: string;
  mappingSource?: string;
}

interface AliyunMappingRow {
  categoryId: string;
  categoryName?: string;
  skuId: string;
  minConfidence: number;
}

interface SkuOption {
  skuId: string;
  skuName?: string;
}

const route = useRoute();
const auth = useAuthStore();
const { router, canAccessPath, goPath } = useNavAccess();
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('className');
const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const yoloMappings = ref<YoloMappingRow[]>([]);
const aliyunMappings = ref<AliyunMappingRow[]>([]);
const skuOptions = ref<SkuOption[]>([]);
const dialogVisible = ref(false);
const aliyunVisible = ref(false);
const aliyunForm = ref<AliyunMappingRow>({
  categoryId: '',
  categoryName: '',
  skuId: '',
  minConfidence: 0.8
});
const editForm = reactive({
  className: '',
  skuId: '',
  minConfidence: 0.72,
  mappingSource: '' as string | undefined
});

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  const rows = !q
    ? yoloMappings.value
    : yoloMappings.value.filter((row) =>
        [row.className, row.skuId, row.skuName].some((x) =>
          String(x || '')
            .toLowerCase()
            .includes(q)
        )
      );
  return sortById(rows);
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
  filePrefix: '识别类名映射',
  headers: ['类别', 'SKU', '商品名', '最低置信度'],
  toRows: () =>
    pickSelected(filtered.value).map((row) => [
      row.className ?? '',
      row.skuId ?? '',
      row.skuName ?? '',
      row.minConfidence ?? ''
    ])
});

function formatConfidence(v?: number | string) {
  if (v == null || v === '') return '无';
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return n <= 1 ? `${Math.round(n * 100)}%` : String(n);
}

function enrollmentLabel(status?: string) {
  return displayLabel('sku_enrollment_status', status, '未知状态');
}

function enrollmentTagType(status?: string) {
  if (status === 'PRODUCTION') return 'success';
  if (status === 'TESTED') return 'warning';
  return 'info';
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  router.replace({ query });
}

function applyRouteQuery() {
  let changed = false;
  const qKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (qKeyword !== keyword.value) {
    keyword.value = qKeyword;
    changed = true;
  }
  return changed;
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

async function loadSkus() {
  try {
    const data = await api.request<SkuOption[] | { items?: SkuOption[] }>(
      '/api/v2/ops/admin/skus',
      'GET'
    );
    skuOptions.value = Array.isArray(data) ? data : data.items || [];
  } catch {
    skuOptions.value = [];
  }
}

async function load() {
  loading.value = true;
  try {
    const data = await api.request<
      { yolo?: YoloMappingRow[]; aliyun?: AliyunMappingRow[] } | YoloMappingRow[]
    >(
      '/api/v2/ops/admin/vision-mappings',
      'GET'
    );
    if (Array.isArray(data)) {
      yoloMappings.value = data;
      aliyunMappings.value = [];
    } else {
      yoloMappings.value = data.yolo || [];
      aliyunMappings.value = data.aliyun || [];
    }
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function openEdit(row: YoloMappingRow) {
  editForm.className = row.className || '';
  editForm.skuId = row.skuId || '';
  const conf = Number(row.minConfidence);
  editForm.minConfidence = Number.isFinite(conf) ? (conf > 1 ? conf / 100 : conf) : 0.72;
  editForm.mappingSource = row.mappingSource;
  dialogVisible.value = true;
}

function openAliyunCreate() {
  aliyunForm.value = { categoryId: '', categoryName: '', skuId: '', minConfidence: 0.8 };
  aliyunVisible.value = true;
}

function openAliyunEdit(row: AliyunMappingRow) {
  aliyunForm.value = { ...row };
  aliyunVisible.value = true;
}

async function saveAliyun() {
  if (!aliyunForm.value.categoryId.trim() || !aliyunForm.value.skuId) {
    ElMessage.warning('请填写类目ID并选择SKU');
    return;
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/vision-mappings/aliyun', 'POST', {
      categoryId: aliyunForm.value.categoryId.trim(),
      categoryName: aliyunForm.value.categoryName || undefined,
      skuId: aliyunForm.value.skuId,
      minConfidence: aliyunForm.value.minConfidence
    });
    ElMessage.success('已保存');
    aliyunVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function deleteAliyun(row: AliyunMappingRow) {
  try {
    await ElMessageBox.confirm(`确认删除阿里云映射「${row.categoryId}」？`, '删除映射', {
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await api.request(
      `/api/v2/ops/admin/vision-mappings/aliyun/${encodeURIComponent(row.categoryId)}`,
      'DELETE'
    );
    ElMessage.success('已删除');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

async function saveEdit() {
  if (!editForm.className || !editForm.skuId) {
    ElMessage.warning('请选择商品');
    return;
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/vision-mappings/yolo', 'POST', {
      className: editForm.className,
      skuId: editForm.skuId,
      minConfidence: editForm.minConfidence,
      mappingSource: editForm.mappingSource || undefined
    });
    ElMessage.success('已保存');
    dialogVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function onDelete(row: YoloMappingRow) {
  const className = row.className;
  if (!className) return;
  try {
    await ElMessageBox.confirm(`确认删除映射「${className}」？`, '删除识别映射', {
      type: 'warning'
    });
    await api.request(
      `/api/v2/ops/admin/vision-mappings/yolo/${encodeURIComponent(className)}`,
      'DELETE'
    );
    ElMessage.success('已删除');
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败');
    }
  }
}

function onAction(key: string, row: YoloMappingRow) {
  if (key === 'edit') openEdit(row);
  else if (key === 'delete') void onDelete(row);
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
}

watch(
  () => route.query.keyword,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  void loadSkus();
  load();
});
onActivated(() => {
  void reloadFromRouteQuery();
});
</script>

<style scoped>
.aliyun-card {
  margin-top: 16px;
}

.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta {
  min-width: 0;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: 15px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.pipe-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
}
</style>
