<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">商品与识别</span>
            <span class="hint">YOLO 类名映射与识别阈值；支持导入与新建</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:sku:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button v-hasPermi="['ops:sku:import']" @click="onDownloadTemplate(['SKU-DEMO-001', '示例商品', '3.50', '', '饮料', 'demo_sku', '映射中', '上架', '92%', '50%'])">导入模板</el-button>
          <el-button v-hasPermi="['ops:sku:import']" :loading="importing" @click="triggerImport">导入</el-button>
          <input ref="importInput" type="file" accept=".csv,text/csv" class="hidden-input" @change="onImportFile" />
          <el-button v-hasPermi="['ops:sku:edit']" type="primary" @click="openEnroll()">新建商品</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="编号 / 名称 / 类名"
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item label="识别状态">
        <el-select v-model="enrollmentFilter" clearable placeholder="全部" style="width: 130px" @change="search">
          <el-option v-for="s in enrollmentStatuses" :key="s" :label="enrollmentLabel(s)" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 1180px">
        <el-table
          v-loading="loading"
          :data="paged"
          stripe
          border
          table-layout="auto"
          row-key="skuId"
          class="report-table sku-table"
          @selection-change="onSelectionChange"
        >
          <template #empty><el-empty description="暂无商品" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="商品" min-width="180" class-name="col-text">
            <template #default="{ row }">
              <button type="button" class="sku-cell" @click="openEnroll(row)">
                <strong>{{ row.skuName || row.skuId }}</strong>
                <small>{{ row.skuId }}</small>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="基准价" width="96" align="right" class-name="col-money">
            <template #default="{ row }">¥{{ ((row.priceCents || 0) / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="成本" width="96" align="right" class-name="col-money">
            <template #default="{ row }">
              {{ row.purchaseCostCents != null ? `¥${(row.purchaseCostCents / 100).toFixed(2)}` : '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="category" label="类目" min-width="100" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ row.category || '-' }}</template>
          </el-table-column>
          <el-table-column label="识别类名" min-width="130" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.yoloClassName" class="cell-id">{{ row.yoloClassName }}</span>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="识别状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="enrollmentTagType(row.visionEnrollmentStatus)">
                {{ enrollmentLabel(row.visionEnrollmentStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="商品状态" width="96" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                {{ skuStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="扣款阈值" width="96" align="center">
            <template #default="{ row }">{{ formatConfidence(row.minChargeConfidence) }}</template>
          </el-table-column>
          <el-table-column label="检测阈值" width="96" align="center">
            <template #default="{ row }">{{ formatConfidence(row.detectionMinConfidence ?? 0.5) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="132" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                :actions="skuActions(row)"
                :max-primary="2"
                @action="(key) => onSkuAction(key, row)"
              />
            </template>
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

    <el-dialog v-model="enrollDialog" :title="enrollForm.existing ? '编辑商品与识别' : '新建商品与识别'" width="640px">
      <el-form label-width="108px">
        <el-form-item label="商品编号" required>
          <el-input v-model="enrollForm.skuId" :disabled="!!enrollForm.existing" placeholder="例如 SKU-COLA-001" />
        </el-form-item>
        <el-form-item label="商品名称" required>
          <el-input v-model="enrollForm.skuName" @blur="suggestClassNameIfEmpty" />
        </el-form-item>
        <el-form-item label="基准价(分)" required>
          <el-input-number v-model="enrollForm.priceCents" :min="1" :step="10" />
        </el-form-item>
        <el-form-item label="识别类名" required>
          <div class="inline-field">
            <el-input v-model="enrollForm.yoloClassName" placeholder="例如 cola_330ml（英文类名）" />
            <el-button :loading="suggestingClass" @click="suggestClassNameIfEmpty">规则建议</el-button>
          </div>
        </el-form-item>
        <el-form-item label="主图地址">
          <el-input v-model="enrollForm.imageUrl" placeholder="商品图片地址（可选）" />
        </el-form-item>
        <el-form-item label="智能建议">
          <div class="inline-field">
            <input ref="classImageInput" type="file" accept="image/*" class="hidden-input" @change="onClassImagePick" />
            <el-button :loading="suggestingImage" @click="triggerClassImage">上传主图建议类名</el-button>
            <span v-if="classSuggestHint" class="field-hint">{{ classSuggestHint }}</span>
          </div>
        </el-form-item>
        <el-form-item label="识别状态">
          <el-select v-model="enrollForm.visionEnrollmentStatus" style="width: 100%">
            <el-option v-for="s in enrollmentStatuses" :key="s" :label="enrollmentLabel(s)" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="检测阈值">
          <el-slider v-model="enrollForm.detectionPercent" :min="10" :max="100" show-input />
        </el-form-item>
        <el-form-item label="扣款阈值">
          <el-slider v-model="enrollForm.chargePercent" :min="50" :max="100" show-input />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="enrollDialog = false">取消</el-button>
        <el-button v-hasPermi="['ops:sku:edit']" type="primary" :loading="saving" @click="saveEnroll">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="testDialog" :title="`识别测试 · ${testForm.skuName}`" width="560px">
      <el-form label-width="96px">
        <el-form-item label="设备 ID">
          <el-input v-model="testForm.deviceId" placeholder="例如 CAB-001" />
        </el-form-item>
        <el-form-item label="测试图片">
          <input ref="testImageInput" type="file" accept="image/*" @change="onTestImagePick" />
        </el-form-item>
      </el-form>
      <el-alert v-if="testPreview?.hint" :title="testPreview.hint" :type="testPreview.needReview ? 'warning' : 'success'" show-icon />
      <el-table v-if="testPreview?.items?.length" :data="testPreview.items" size="small" stripe class="test-table">
        <el-table-column prop="skuName" label="商品" />
        <el-table-column prop="quantity" label="数量" width="72" />
        <el-table-column label="置信度" width="88">
          <template #default="{ row }">{{ Math.round((row.confidence || 0) * 100) }}%</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="testDialog = false">关闭</el-button>
        <el-button type="primary" :loading="testing" :disabled="!testImageFile" @click="runTest">预览识别</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, Refresh, Upload, CircleCheck } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { ENABLE_TEST_TOOLS } from '@/config/feature-flags';
import { useAuthStore } from '@/stores/auth';
import type {
  DevRecognitionPreviewDto,
  SkuCatalog,
  UpsertSkuVisionEnrollmentRequest
} from '@aicabinet/shared-types';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const suggestingClass = ref(false);
const suggestingImage = ref(false);
const items = ref<SkuCatalog[]>([]);
const keyword = ref('');
const enrollmentFilter = ref('');
const page = ref(1);
const size = ref(20);
const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  return items.value.filter((row) => {
    if (enrollmentFilter.value && row.visionEnrollmentStatus !== enrollmentFilter.value) return false;
    if (!q) return true;
    return [row.skuId, row.skuName, row.yoloClassName, row.category, row.status]
      .some((x) => String(x || '').toLowerCase().includes(q));
  });
});
const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});
watch([keyword, enrollmentFilter], () => {
  page.value = 1;
});

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<SkuCatalog>((r) => r.skuId);

const enrollmentStatusByLabel: Record<string, string> = {
  草稿: 'DRAFT',
  映射中: 'MAPPING',
  已测试: 'TESTED',
  生产: 'PRODUCTION',
  DRAFT: 'DRAFT',
  MAPPING: 'MAPPING',
  TESTED: 'TESTED',
  PRODUCTION: 'PRODUCTION'
};
const skuStatusByLabel: Record<string, string> = {
  上架: 'ACTIVE',
  下架: 'INACTIVE',
  停用: 'DISABLED',
  ACTIVE: 'ACTIVE',
  INACTIVE: 'INACTIVE',
  DISABLED: 'DISABLED'
};

function parseConfidence(raw: string | undefined, fallback: number) {
  if (raw == null || !String(raw).trim()) return fallback;
  const n = Number(String(raw).replace(/%/g, '').trim());
  if (Number.isNaN(n)) return fallback;
  return n > 1 ? n / 100 : n;
}

const { importing, importInput, onExport, onDownloadTemplate, triggerImport, onImportFile } = useListCsv({
  filePrefix: '商品',
  headers: ['商品编号', '名称', '基准价', '成本', '类目', '识别类名', '识别状态', '商品状态', '扣款阈值', '检测阈值'],
  toRows: () =>
    pickSelected(filtered.value).map((row) => [
      row.skuId,
      row.skuName,
      ((row.priceCents || 0) / 100).toFixed(2),
      row.purchaseCostCents != null ? (row.purchaseCostCents / 100).toFixed(2) : '',
      row.category || '',
      row.yoloClassName || '',
      enrollmentLabel(row.visionEnrollmentStatus),
      skuStatusLabel(row.status),
      formatConfidence(row.minChargeConfidence),
      formatConfidence(row.detectionMinConfidence ?? 0.5)
    ]),
  onImportRows: async (rows) => {
    let ok = 0;
    for (const row of rows) {
      const skuId = (row['商品编号'] || row.skuId || '').trim();
      const skuName = (row['名称'] || row.skuName || '').trim();
      if (!skuId || !skuName) continue;
      const yoloClassName = (row['识别类名'] || row.yoloClassName || '').trim() || skuId.toLowerCase().replace(/[^a-z0-9_]+/g, '_');
      const priceCents = Math.round((Number(row['基准价'] || row.priceYuan) || 0) * 100);
      const visionEnrollmentStatus =
        enrollmentStatusByLabel[row['识别状态'] || row.visionEnrollmentStatus] || 'MAPPING';
      const status = skuStatusByLabel[row['商品状态'] || row.status] || 'ACTIVE';
      const body: UpsertSkuVisionEnrollmentRequest = {
        sku: {
          skuId,
          skuName,
          priceCents: priceCents || 350,
          visionEnabled: true,
          status,
          category: (row['类目'] || row.category || '').trim() || undefined,
          minChargeConfidence: parseConfidence(row['扣款阈值'] || row.minChargeConfidence, 0.92),
          yoloClassName,
          visionEnrollmentStatus,
          detectionMinConfidence: parseConfidence(row['检测阈值'] || row.detectionMinConfidence, 0.5)
        },
        yoloClassName,
        visionEnrollmentStatus,
        detectionMinConfidence: parseConfidence(row['检测阈值'] || row.detectionMinConfidence, 0.5),
        mappingSource: 'YOLO_SKU'
      };
      await api.request<SkuCatalog>('/api/v2/ops/admin/sku-vision/enroll', 'POST', body);
      ok++;
    }
    clearSelection();
    await load();
    return ok;
  }
});
const enrollDialog = ref(false);
const testDialog = ref(false);
const testPreview = ref<DevRecognitionPreviewDto | null>(null);
const testImageFile = ref<File | null>(null);
const classImageInput = ref<HTMLInputElement | null>(null);
const testImageInput = ref<HTMLInputElement | null>(null);
const classSuggestHint = ref('');

const enrollmentStatuses = ['DRAFT', 'MAPPING', 'TESTED', 'PRODUCTION'];

const enrollForm = reactive({
  existing: false,
  skuId: '',
  skuName: '',
  priceCents: 350,
  yoloClassName: '',
  imageUrl: '',
  visionEnrollmentStatus: 'MAPPING',
  detectionPercent: 50,
  chargePercent: 92
});

const testForm = reactive({
  skuId: '',
  skuName: '',
  deviceId: 'CAB-DEMO-001'
});

function formatConfidence(value?: number) {
  if (value == null || Number.isNaN(value)) return '92%';
  return `${Math.round(value * 100)}%`;
}

function enrollmentLabel(status?: string) {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    MAPPING: '映射中',
    TESTED: '已测试',
    PRODUCTION: '生产'
  };
  return map[status || 'DRAFT'] || status || '草稿';
}

function skuStatusLabel(status?: string) {
  const map: Record<string, string> = {
    ACTIVE: '上架',
    INACTIVE: '下架',
    DISABLED: '停用'
  };
  return map[status || ''] || status || '—';
}

function enrollmentTagType(status?: string) {
  if (status === 'PRODUCTION') return 'success';
  if (status === 'TESTED') return 'warning';
  return 'info';
}

function skuActions(row: SkuCatalog): TableAction[] {
  const acts: TableAction[] = [];
  if (auth.hasPerm('ops:sku:edit')) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (ENABLE_TEST_TOOLS) {
    acts.push({ key: 'test', label: '识别测试', icon: Upload, type: 'warning' });
  }
  if (auth.hasPerm('ops:sku:edit') && row.visionEnrollmentStatus !== 'PRODUCTION') {
    acts.push({ key: 'production', label: '转生产', icon: CircleCheck, type: 'success', overflow: true });
  }
  return acts;
}

function onSkuAction(key: string, row: SkuCatalog) {
  if (key === 'edit') openEnroll(row);
  else if (key === 'test') openTest(row);
  else if (key === 'production') markProduction(row);
}

function openEnroll(row?: SkuCatalog) {
  classSuggestHint.value = '';
  if (row) {
    enrollForm.existing = true;
    enrollForm.skuId = row.skuId;
    enrollForm.skuName = row.skuName;
    enrollForm.priceCents = row.priceCents;
    enrollForm.yoloClassName = row.yoloClassName || '';
    enrollForm.imageUrl = row.imageUrl || '';
    enrollForm.visionEnrollmentStatus = row.visionEnrollmentStatus || 'MAPPING';
    enrollForm.detectionPercent = Math.round((row.detectionMinConfidence ?? 0.5) * 100);
    enrollForm.chargePercent = Math.round((row.minChargeConfidence ?? 0.92) * 100);
  } else {
    enrollForm.existing = false;
    enrollForm.skuId = '';
    enrollForm.skuName = '';
    enrollForm.priceCents = 350;
    enrollForm.yoloClassName = '';
    enrollForm.imageUrl = '';
    enrollForm.visionEnrollmentStatus = 'MAPPING';
    enrollForm.detectionPercent = 50;
    enrollForm.chargePercent = 92;
  }
  enrollDialog.value = true;
}

function openTest(row: SkuCatalog) {
  testForm.skuId = row.skuId;
  testForm.skuName = row.skuName;
  testPreview.value = null;
  testImageFile.value = null;
  if (testImageInput.value) testImageInput.value.value = '';
  testDialog.value = true;
}

async function suggestClassNameIfEmpty() {
  if (!enrollForm.skuName.trim()) return;
  suggestingClass.value = true;
  try {
    const data = await api.request<{ yoloClassName: string }>(
      `/api/v2/ops/admin/sku-vision/suggest-class-name?skuName=${encodeURIComponent(enrollForm.skuName)}`,
      'GET'
    );
    if (!enrollForm.yoloClassName.trim()) {
      enrollForm.yoloClassName = data.yoloClassName;
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '建议失败');
  } finally {
    suggestingClass.value = false;
  }
}

function triggerClassImage() {
  classImageInput.value?.click();
}

async function onClassImagePick(ev: Event) {
  const input = ev.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  suggestingImage.value = true;
  classSuggestHint.value = '';
  try {
    const result = await uploadMultipart<{ yoloClassName?: string; reason?: string }>(
      '/api/v2/ops/admin/sku-vision/suggest-class',
      { skuName: enrollForm.skuName, image: file }
    );
    if (result.yoloClassName) {
      enrollForm.yoloClassName = result.yoloClassName;
      classSuggestHint.value = result.reason ? `建议：${result.yoloClassName}（${result.reason}）` : `建议：${result.yoloClassName}`;
    } else {
      classSuggestHint.value = result.reason || '未能生成建议';
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '智能建议失败');
  } finally {
    suggestingImage.value = false;
    input.value = '';
  }
}

function onTestImagePick(ev: Event) {
  const input = ev.target as HTMLInputElement;
  testImageFile.value = input.files?.[0] || null;
  testPreview.value = null;
}

async function saveEnroll() {
  if (!enrollForm.skuId.trim() || !enrollForm.skuName.trim()) {
    ElMessage.warning('请填写商品编号与名称');
    return;
  }
  if (!enrollForm.yoloClassName.trim()) {
    await suggestClassNameIfEmpty();
  }
  saving.value = true;
  try {
    const body: UpsertSkuVisionEnrollmentRequest = {
      sku: {
        skuId: enrollForm.skuId.trim(),
        skuName: enrollForm.skuName.trim(),
        priceCents: enrollForm.priceCents,
        visionEnabled: true,
        imageUrl: enrollForm.imageUrl || undefined,
        status: 'ACTIVE',
        minChargeConfidence: enrollForm.chargePercent / 100,
        yoloClassName: enrollForm.yoloClassName.trim(),
        visionEnrollmentStatus: enrollForm.visionEnrollmentStatus,
        detectionMinConfidence: enrollForm.detectionPercent / 100
      },
      yoloClassName: enrollForm.yoloClassName.trim(),
      visionEnrollmentStatus: enrollForm.visionEnrollmentStatus,
      detectionMinConfidence: enrollForm.detectionPercent / 100,
      mappingSource: 'YOLO_SKU'
    };
    const updated = await api.request<SkuCatalog>('/api/v2/ops/admin/sku-vision/enroll', 'POST', body);
    const idx = items.value.findIndex((i) => i.skuId === updated.skuId);
    if (idx >= 0) items.value[idx] = updated;
    else items.value.push(updated);
    items.value.sort((a, b) => a.skuId.localeCompare(b.skuId));
    enrollDialog.value = false;
    ElMessage.success('已保存商品与识别配置');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function markProduction(row: SkuCatalog) {
  try {
    const updated = await api.request<SkuCatalog>(
      `/api/v2/ops/admin/sku-vision/${encodeURIComponent(row.skuId)}/status?status=PRODUCTION`,
      'PATCH'
    );
    const idx = items.value.findIndex((i) => i.skuId === row.skuId);
    if (idx >= 0) items.value[idx] = updated;
    ElMessage.success(`${row.skuName} 已标记为生产识别`);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败');
  }
}

async function runTest() {
  if (!testImageFile.value) {
    ElMessage.warning('请选择测试图片');
    return;
  }
  testing.value = true;
  try {
    testPreview.value = await uploadMultipart<DevRecognitionPreviewDto>(
      '/api/v2/ops/recognition-preview',
      { image: testImageFile.value }
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '识别失败');
  } finally {
    testing.value = false;
  }
}

async function uploadMultipart<T>(path: string, fields: Record<string, string | File>): Promise<T> {
  const token = localStorage.getItem('admin_token');
  const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || window.location.origin;
  const form = new FormData();
  for (const [key, val] of Object.entries(fields)) {
    form.append(key === 'image' ? 'image' : key, val);
  }
  const res = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok || json.code !== 0) {
    throw new Error(json.message || `请求失败 (${res.status})`);
  }
  return json.data as T;
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (enrollmentFilter.value) query.enrollment = enrollmentFilter.value;
  router.replace({ query });
}

function search() {
  page.value = 1;
  syncRouteQuery();
}

function resetFilters() {
  keyword.value = '';
  enrollmentFilter.value = '';
  page.value = 1;
  syncRouteQuery();
}

function applyRouteQuery() {
  let changed = false;
  if (typeof route.query.keyword === 'string' && route.query.keyword !== keyword.value) {
    keyword.value = route.query.keyword;
    changed = true;
  }
  if (typeof route.query.enrollment === 'string' && route.query.enrollment !== enrollmentFilter.value) {
    enrollmentFilter.value = route.query.enrollment;
    changed = true;
  }
  return changed;
}

async function load() {
  loading.value = true;
  try {
    items.value = await api.request<SkuCatalog[]>('/api/v2/ops/admin/sku-vision', 'GET');
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
.sku-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  display: grid;
  gap: 2px;
  text-align: left;
  cursor: pointer;
  color: inherit;
  font: inherit;
  line-height: 1.35;
}
.sku-cell strong { color: var(--el-color-primary); font-weight: 650; }
.sku-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
.sku-cell:hover strong { text-decoration: underline; }
.muted { color: var(--el-text-color-secondary); }
.sku-table { font-size: 14px; }
.inline-field {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  width: 100%;
}
.hidden-input { display: none; }
.field-hint { color: var(--layout-muted); font-size: 13px; }
.test-table { margin-top: 12px; font-size: 14px; }
</style>
