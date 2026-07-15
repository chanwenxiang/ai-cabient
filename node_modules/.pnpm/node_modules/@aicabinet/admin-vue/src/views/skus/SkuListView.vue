<template>
  <el-card class="page-card">
    <template #header>
      <div class="page-header">
        <span>商品与识别</span>
        <div class="header-actions">
          <el-button type="primary" @click="openEnroll()">新建商品</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar" @submit.prevent>
      <el-form-item label="关键词">
        <el-input v-model="keyword" clearable placeholder="SKU / 名称 / 类名" style="width:220px" />
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="paged" stripe>
      <el-table-column prop="skuId" label="SKU" min-width="120">
        <template #default="{ row }"><code>{{ row.skuId }}</code></template>
      </el-table-column>
      <el-table-column prop="skuName" label="名称" min-width="120" />
      <el-table-column label="基准价" width="96">
        <template #default="{ row }">¥{{ ((row.priceCents || 0) / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="成本" width="96">
        <template #default="{ row }">
          {{ row.purchaseCostCents != null ? `¥${(row.purchaseCostCents / 100).toFixed(2)}` : '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="category" label="类目" width="100" show-overflow-tooltip>
        <template #default="{ row }">{{ row.category || '—' }}</template>
      </el-table-column>
      <el-table-column prop="yoloClassName" label="YOLO 类名" min-width="120">
        <template #default="{ row }"><code>{{ row.yoloClassName || '—' }}</code></template>
      </el-table-column>
      <el-table-column label="识别状态" width="110">
        <template #default="{ row }">
          <el-tag :type="enrollmentTagType(row.visionEnrollmentStatus)">
            {{ enrollmentLabel(row.visionEnrollmentStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="商品状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status || '—' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="扣款阈值" width="100">
        <template #default="{ row }">{{ formatConfidence(row.minChargeConfidence) }}</template>
      </el-table-column>
      <el-table-column label="检测阈值" width="100">
        <template #default="{ row }">{{ formatConfidence(row.detectionMinConfidence ?? 0.5) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="132" fixed="right" align="center">
        <template #default="{ row }">
          <TableActions
            :actions="skuActions(row)"
            :max-primary="2"
            @action="(key) => onSkuAction(key, row)"
          />
        </template>
      </el-table-column>
    </el-table>
    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="filtered.length"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
      />
    </div>

    <el-dialog v-model="enrollDialog" :title="enrollForm.skuId ? '编辑商品与识别' : '新建商品与识别'" width="640px">
      <el-form label-width="108px">
        <el-form-item label="SKU ID" required>
          <el-input v-model="enrollForm.skuId" :disabled="!!enrollForm.existing" placeholder="SKU-XXX-001" />
        </el-form-item>
        <el-form-item label="商品名称" required>
          <el-input v-model="enrollForm.skuName" @blur="suggestClassNameIfEmpty" />
        </el-form-item>
        <el-form-item label="基准价(分)" required>
          <el-input-number v-model="enrollForm.priceCents" :min="1" :step="10" />
        </el-form-item>
        <el-form-item label="YOLO 类名" required>
          <div class="inline-field">
            <el-input v-model="enrollForm.yoloClassName" placeholder="cola_330ml" />
            <el-button :loading="suggestingClass" @click="suggestClassNameIfEmpty">规则建议</el-button>
          </div>
        </el-form-item>
        <el-form-item label="主图 URL">
          <el-input v-model="enrollForm.imageUrl" placeholder="https://..." />
        </el-form-item>
        <el-form-item label="DeepSeek 建议">
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
        <el-button type="primary" :loading="saving" @click="saveEnroll">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="testDialog" :title="`识别测试 · ${testForm.skuName}`" width="560px">
      <el-form label-width="96px">
        <el-form-item label="设备 ID">
          <el-input v-model="testForm.deviceId" placeholder="CAB-DEMO-001" />
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
import { computed, onMounted, reactive, ref } from 'vue';
import { EditPen, Refresh, Upload, CircleCheck } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import type {
  DevRecognitionPreviewDto,
  SkuCatalog,
  UpsertSkuVisionEnrollmentRequest
} from '@aicabinet/shared-types';

const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const suggestingClass = ref(false);
const suggestingImage = ref(false);
const items = ref<SkuCatalog[]>([]);
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q) return items.value;
  return items.value.filter((row) =>
    [row.skuId, row.skuName, row.yoloClassName, row.category, row.status]
      .some((x) => String(x || '').toLowerCase().includes(q))
  );
});
const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
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

function enrollmentTagType(status?: string) {
  if (status === 'PRODUCTION') return 'success';
  if (status === 'TESTED') return 'warning';
  return 'info';
}

function skuActions(row: SkuCatalog): TableAction[] {
  const acts: TableAction[] = [
    { key: 'edit', label: '编辑', icon: EditPen, type: 'primary' },
    { key: 'test', label: '识别测试', icon: Upload, type: 'warning' }
  ];
  if (row.visionEnrollmentStatus !== 'PRODUCTION') {
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
    ElMessage.error(e instanceof Error ? e.message : 'DeepSeek 建议失败');
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
    ElMessage.warning('请填写 SKU 与名称');
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

async function load() {
  loading.value = true;
  try {
    items.value = await api.request<SkuCatalog[]>('/api/v2/ops/admin/sku-vision', 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.inline-field {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  width: 100%;
}

.hidden-input {
  display: none;
}

.field-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.test-table {
  margin-top: 12px;
}
</style>
