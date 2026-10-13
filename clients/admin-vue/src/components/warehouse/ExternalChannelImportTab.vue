<script setup lang="ts">
/**
 * 渠道对账导入（CB-036，V347）：向导（上传解析→映射治理→确认提交）+ 批次留痕 + 渠道流水。
 * 外部商品不进本系统 SKU 目录（拍板 2026-10-13）；提交后聚合成 external_channel_flow，
 * 月结（账期制）按 账期+仓 取「仓库外调 / 客情用水」列。
 */
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, authFetch } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface TemplateDto {
  templateId: number;
  channelCode: string;
  channelName: string;
  fileType: string;
  sheetStrategy: string;
  qtySemantics: string;
  keyField: string;
  personSource: string;
  opType: string;
  columnMap: Record<string, string>;
  enabled: boolean;
  remark: string | null;
}
interface ParsedRow {
  person: string | null;
  extKey: string;
  extName: string;
  qty: number;
  unitPriceCents: number | null;
  happenedAt: string | null;
  srcLine: number;
}
interface UnmappedProduct {
  externalProductId: number;
  externalKey: string;
  externalName: string;
  timesSeen: number;
  suggestedSkuId: string | null;
}
interface BatchDto {
  batchId: number;
  channelCode: string;
  channelName: string;
  periodLabel: string;
  fileName: string;
  personOverride: string | null;
  status: string;
  rowsTotal: number;
  rowsMapped: number;
  rowsUnmapped: number;
  unmappedProducts: UnmappedProduct[] | null;
  unresolvedPersons: string[] | null;
  rows: ParsedRow[] | null;
  commitSummary: Record<string, unknown> | null;
  uploadedByName: string | null;
  uploadedAt: string;
  committedAt: string | null;
}
interface ProductDto {
  externalProductId: number;
  channelCode: string;
  externalKey: string;
  externalName: string;
  brand: string | null;
  spec: string | null;
  unitPriceCents: number | null;
  timesSeen: number;
  lastSeenAt: string;
  mappedSkuId: string | null;
  mappedSkuName: string | null;
}
interface FlowDto {
  flowId: number;
  channelCode: string;
  periodLabel: string;
  warehouseId: string;
  personName: string | null;
  skuId: string;
  opType: string;
  qty: number;
  unitPriceCents: number | null;
  rowCount: number;
  remark: string | null;
}
interface PageResult<T> {
  items: T[];
  total: number;
}

const OP_LABELS: Record<string, string> = {
  WAREHOUSE_OUT: '仓库调出',
  WAREHOUSE_STOCKTAKE: '仓库盘点',
  GUEST_WATER: '客情用水'
};
const QTY_LABELS: Record<string, string> = {
  DIRECT: '直接数量列',
  BY_DIFF: '补货后−补货前',
  SALES: '销量=调出口径'
};
const PERSON_LABELS: Record<string, string> = {
  SHEET_NAME: 'sheet 名即线长',
  COLUMN: '人员列',
  FILE_NAME: '文件名',
  MANUAL: '导入时人工指定'
};

const innerTab = ref('wizard');
const templates = ref<TemplateDto[]>([]);
const channels = computed(() => templates.value.filter((t) => t.enabled));
const channelByCode = (code: string) => templates.value.find((t) => t.channelCode === code);

// ── 向导 ───────────────────────────────────────────────────────────────────────────────────
const wizard = reactive({
  channelCode: '',
  periodLabel: defaultPeriod(),
  personOverride: '',
  file: null as File | null,
  uploading: false
});
const previewBatch = ref<BatchDto | null>(null);
const previewErrors = ref<string[]>([]);
const personOverrides = reactive<Record<string, string>>({});

function defaultPeriod(): string {
  // 默认上一自然月（账期标签=结束月；26~25 账期由后端解释）
  const now = new Date();
  const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  return `${prev.getFullYear()}${String(prev.getMonth() + 1).padStart(2, '0')}`;
}

function currentTemplate(): TemplateDto | undefined {
  return channelByCode(wizard.channelCode);
}
const needsManualPerson = computed(() => currentTemplate()?.personSource === 'MANUAL');

function onFileChange(file: File | undefined) {
  wizard.file = file ?? null;
}

async function loadTemplates() {
  templates.value = await api.request<TemplateDto[]>(AdminEndpoints.externalImportTemplates, 'GET');
}

async function uploadPreview() {
  if (!wizard.channelCode) return ElMessage.warning('请选择渠道');
  if (!/^\d{6}$/.test(wizard.periodLabel))
    return ElMessage.warning('账期格式应为 yyyyMM，如 202609');
  if (needsManualPerson.value && !wizard.personOverride.trim())
    return ElMessage.warning('该渠道需人工指定线长');
  if (!wizard.file) return ElMessage.warning('请选择对账文件');
  wizard.uploading = true;
  try {
    const base =
      (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
    const form = new FormData();
    form.append('file', wizard.file);
    const params = new URLSearchParams({
      channelCode: wizard.channelCode,
      periodLabel: wizard.periodLabel
    });
    if (needsManualPerson.value) params.set('personOverride', wizard.personOverride.trim());
    const res = await authFetch(
      `${base}${AdminEndpoints.externalImportPreview}?${params.toString()}`,
      {
        method: 'POST',
        body: form
      }
    );
    const json = (await res.json().catch(() => ({}))) as {
      code?: number;
      message?: string;
      data?: BatchDto;
    };
    if (!res.ok || json.code !== 0 || !json.data) {
      throw new Error(json.message || `解析失败 (${res.status})`);
    }
    previewBatch.value = json.data;
    previewErrors.value = [];
    Object.keys(personOverrides).forEach((k) => delete personOverrides[k]);
    ElMessage.success(`解析完成：${json.data.rowsTotal} 行，可提交 ${json.data.rowsMapped} 行`);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '解析失败');
  } finally {
    wizard.uploading = false;
  }
}

async function commitPreview() {
  const batch = previewBatch.value;
  if (!batch) return;
  const unresolved = batch.unresolvedPersons ?? [];
  const missing = unresolved.filter((p) => !personOverrides[p]);
  if (missing.length) {
    return ElMessage.warning(`请为以下人员指定归属仓：${missing.join('、')}`);
  }
  await ElMessageBox.confirm(
    `确认把 ${batch.channelName} ${batch.periodLabel} 的 ${batch.rowsMapped} 行聚合入渠道流水？提交后批次锁死不可改。`,
    '确认提交',
    { type: 'warning' }
  );
  try {
    const data = await api.request<BatchDto>(
      AdminEndpoints.externalImportBatchCommit(batch.batchId),
      'POST',
      {
        personWarehouseOverrides: { ...personOverrides }
      }
    );
    ElMessage.success(
      `已提交：聚合 ${data.commitSummary?.flows ?? '?'} 条流水，共 ${data.commitSummary?.qtyTotal ?? '?'} 件`
    );
    previewBatch.value = null;
    wizard.file = null;
    await loadBatches();
    await loadFlows();
    innerTab.value = 'batches';
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交失败');
  }
}

async function discardPreview() {
  const batch = previewBatch.value;
  if (!batch) return;
  await ElMessageBox.confirm('放弃该解析批次？（留痕删除，可重新上传）', '放弃', {
    type: 'warning'
  });
  try {
    await api.request(AdminEndpoints.externalImportBatch(batch.batchId), 'DELETE');
    previewBatch.value = null;
    wizard.file = null;
    ElMessage.success('已放弃');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

// ── 批次历史 ───────────────────────────────────────────────────────────────────────────────
const batchFilterChannel = ref('');
const batches = ref<BatchDto[]>([]);
const batchesTotal = ref(0);
const batchesLoading = ref(false);
const batchDetail = ref<BatchDto | null>(null);

async function loadBatches() {
  batchesLoading.value = true;
  try {
    const params = new URLSearchParams({ page: '0', size: '50' });
    if (batchFilterChannel.value) params.set('channelCode', batchFilterChannel.value);
    const data = await api.request<PageResult<BatchDto>>(
      `${AdminEndpoints.externalImportBatches}?${params.toString()}`,
      'GET'
    );
    batches.value = data.items ?? [];
    batchesTotal.value = data.total ?? batches.value.length;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '批次加载失败');
  } finally {
    batchesLoading.value = false;
  }
}

const batchDetailVisible = ref(false);

async function openBatchDetail(batch: BatchDto) {
  try {
    batchDetail.value = await api.request<BatchDto>(
      AdminEndpoints.externalImportBatch(batch.batchId),
      'GET'
    );
    batchDetailVisible.value = true;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '批次详情加载失败');
  }
}

// ── 外部商品映射治理 ───────────────────────────────────────────────────────────────────────
const productFilter = reactive({ channelCode: '', mapped: false, keyword: '' });
const products = ref<ProductDto[]>([]);
const productsLoading = ref(false);
const bindDialog = ref(false);
const bindTarget = ref<ProductDto | null>(null);
const bindSkuId = ref('');
const bindNote = ref('');
const skuOptions = ref<{ skuId: string; skuName: string }[]>([]);
const skuSearchLoading = ref(false);

async function loadProducts() {
  productsLoading.value = true;
  try {
    const params = new URLSearchParams({ page: '0', size: '100' });
    if (productFilter.channelCode) params.set('channelCode', productFilter.channelCode);
    if (productFilter.mapped) params.set('mapped', 'false');
    if (productFilter.keyword.trim()) params.set('keyword', productFilter.keyword.trim());
    const data = await api.request<PageResult<ProductDto>>(
      `${AdminEndpoints.externalProducts}?${params.toString()}`,
      'GET'
    );
    products.value = data.items ?? [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '外部商品加载失败');
  } finally {
    productsLoading.value = false;
  }
}

function openBindDialog(product: ProductDto) {
  bindTarget.value = product;
  bindSkuId.value = product.mappedSkuId ?? '';
  bindNote.value = '';
  skuOptions.value = [];
  bindDialog.value = true;
  void searchSkus('');
}

async function searchSkus(keyword: string) {
  skuSearchLoading.value = true;
  try {
    const params = new URLSearchParams({ page: '0', size: '30' });
    if (keyword.trim()) params.set('keyword', keyword.trim());
    const data = await api.request<{ items?: { skuId: string; skuName: string }[] }>(
      AdminEndpoints.skusList(params),
      'GET'
    );
    skuOptions.value = data.items ?? [];
  } catch {
    skuOptions.value = [];
  } finally {
    skuSearchLoading.value = false;
  }
}

async function saveBind() {
  if (!bindTarget.value) return;
  if (!bindSkuId.value) return ElMessage.warning('请选择本系统 SKU');
  try {
    await api.request(
      AdminEndpoints.externalProductMapping(bindTarget.value.externalProductId),
      'POST',
      { skuId: bindSkuId.value, note: bindNote.value || null }
    );
    ElMessage.success('已绑定');
    bindDialog.value = false;
    await loadProducts();
    if (previewBatch.value) await refreshPreviewState();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '绑定失败');
  }
}

async function removeBind(product: ProductDto) {
  await ElMessageBox.confirm(
    `解绑「${product.externalName}」→ ${product.mappedSkuName ?? ''}？`,
    '解绑',
    {
      type: 'warning'
    }
  );
  try {
    await api.request(AdminEndpoints.externalProductMapping(product.externalProductId), 'DELETE');
    ElMessage.success('已解绑');
    await loadProducts();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '解绑失败');
  }
}

/** 绑定/解绑后刷新预览批次的映射状态（重新拉详情）。 */
async function refreshPreviewState() {
  if (!previewBatch.value) return;
  try {
    previewBatch.value = await api.request<BatchDto>(
      AdminEndpoints.externalImportBatch(previewBatch.value.batchId),
      'GET'
    );
  } catch {
    // 预览刷新失败不阻塞治理
  }
}

// ── 渠道流水 ───────────────────────────────────────────────────────────────────────────────
const flowFilter = reactive({ periodLabel: defaultPeriod(), warehouseId: '', opType: '' });
const flows = ref<FlowDto[]>([]);
const flowsLoading = ref(false);

async function loadFlows() {
  flowsLoading.value = true;
  try {
    const params = new URLSearchParams();
    if (flowFilter.periodLabel.trim()) params.set('periodLabel', flowFilter.periodLabel.trim());
    if (flowFilter.warehouseId) params.set('warehouseId', flowFilter.warehouseId);
    if (flowFilter.opType) params.set('opType', flowFilter.opType);
    const data = await api.request<FlowDto[]>(
      `${AdminEndpoints.externalImportFlows}?${params.toString()}`,
      'GET'
    );
    flows.value = data ?? [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '流水加载失败');
  } finally {
    flowsLoading.value = false;
  }
}

onMounted(async () => {
  await loadTemplates();
  await Promise.all([loadBatches(), loadProducts(), loadFlows()]);
});
</script>

<template>
  <div class="external-import-tab">
    <el-tabs v-model="innerTab">
      <el-tab-pane label="导入向导" name="wizard">
        <div class="wizard-form">
          <el-form inline @submit.prevent>
            <el-form-item label="渠道">
              <el-select
                v-model="wizard.channelCode"
                placeholder="选择外部渠道"
                style="width: 200px"
              >
                <el-option
                  v-for="t in channels"
                  :key="t.channelCode"
                  :value="t.channelCode"
                  :label="t.channelName"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="账期(所属月)">
              <el-input v-model="wizard.periodLabel" placeholder="202609" style="width: 120px" />
            </el-form-item>
            <el-form-item v-if="needsManualPerson" label="线长">
              <el-input
                v-model="wizard.personOverride"
                placeholder="该渠道人工指定"
                style="width: 140px"
              />
            </el-form-item>
            <el-form-item label="对账文件">
              <input
                type="file"
                :accept="currentTemplate()?.fileType === 'XLS' ? '.xls' : '.xlsx,.xls,.csv'"
                @change="(e) => onFileChange((e.target as HTMLInputElement).files?.[0])"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="wizard.uploading" @click="uploadPreview">
                上传解析
              </el-button>
            </el-form-item>
          </el-form>
          <p v-if="currentTemplate()" class="muted template-hint">
            {{ currentTemplate()?.channelName }}：文件 {{ currentTemplate()?.fileType }} · 数量口径
            {{
              QTY_LABELS[currentTemplate()?.qtySemantics ?? ''] ?? currentTemplate()?.qtySemantics
            }}
            · 人员归属
            {{
              PERSON_LABELS[currentTemplate()?.personSource ?? ''] ??
              currentTemplate()?.personSource
            }}
            <span v-if="currentTemplate()?.remark"> · {{ currentTemplate()?.remark }}</span>
          </p>
        </div>

        <div v-if="previewBatch" class="preview-card">
          <el-alert
            :type="previewBatch.rowsUnmapped === 0 ? 'success' : 'warning'"
            :closable="false"
            show-icon
          >
            <template #title>
              {{ previewBatch.channelName }} {{ previewBatch.periodLabel }} · 共
              {{ previewBatch.rowsTotal }} 行，可提交 {{ previewBatch.rowsMapped }} 行，待处理
              {{ previewBatch.rowsUnmapped }} 行
            </template>
          </el-alert>

          <div v-if="(previewBatch.unmappedProducts ?? []).length" class="preview-section">
            <h4>未映射外部商品（先到「外部商品映射」页绑定，再回来提交）</h4>
            <el-table :data="previewBatch.unmappedProducts" size="small" border>
              <el-table-column prop="externalKey" label="商品ID" min-width="100" />
              <el-table-column prop="externalName" label="商品名称" min-width="160" />
              <el-table-column
                prop="timesSeen"
                label="出现次数"
                width="90"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              />
            </el-table>
          </div>

          <div v-if="(previewBatch.unresolvedPersons ?? []).length" class="preview-section">
            <h4>无法归仓人员（指定其分仓）</h4>
            <el-form inline>
              <el-form-item v-for="p in previewBatch.unresolvedPersons" :key="p" :label="p">
                <el-input v-model="personOverrides[p]" placeholder="仓库ID" style="width: 140px" />
              </el-form-item>
            </el-form>
          </div>

          <div class="preview-section">
            <h4>解析行预览（{{ (previewBatch.rows ?? []).length }} 行）</h4>
            <el-table
              :data="(previewBatch.rows ?? []).slice(0, 200)"
              size="small"
              border
              max-height="320"
            >
              <el-table-column
                prop="srcLine"
                label="源行"
                width="70"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              />
              <el-table-column prop="person" label="线长" width="110" />
              <el-table-column prop="extKey" label="商品ID" min-width="100" />
              <el-table-column prop="extName" label="商品名称" min-width="160" />
              <el-table-column
                prop="qty"
                label="数量"
                width="80"
                align="right"
                class-name="col-num"
                label-class-name="col-num"
              />
              <el-table-column prop="happenedAt" label="业务日期" width="110" />
            </el-table>
          </div>

          <div class="preview-actions">
            <el-button
              type="primary"
              :disabled="previewBatch.status !== 'PARSED' || previewBatch.rowsMapped === 0"
              @click="commitPreview"
              >确认提交入流水</el-button
            >
            <el-button v-if="previewBatch.status === 'PARSED'" @click="discardPreview"
              >放弃</el-button
            >
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="外部商品映射" name="products">
        <el-form inline @submit.prevent>
          <el-form-item label="渠道">
            <el-select
              v-model="productFilter.channelCode"
              clearable
              placeholder="全部"
              style="width: 170px"
              @change="loadProducts"
            >
              <el-option
                v-for="t in templates"
                :key="t.channelCode"
                :value="t.channelCode"
                :label="t.channelName"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-checkbox v-model="productFilter.mapped" @change="loadProducts"
              >只看未映射</el-checkbox
            >
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="productFilter.keyword"
              placeholder="名称/ID 关键字"
              clearable
              style="width: 200px"
              @change="loadProducts"
            />
          </el-form-item>
          <el-form-item>
            <el-button @click="loadProducts">查询</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="productsLoading" :data="products" size="small" border>
          <el-table-column prop="channelCode" label="渠道" width="110">
            <template #default="{ row }">{{
              channelByCode(row.channelCode)?.channelName ?? row.channelCode
            }}</template>
          </el-table-column>
          <el-table-column prop="externalKey" label="商品ID" min-width="100" />
          <el-table-column prop="externalName" label="商品名称" min-width="180" />
          <el-table-column prop="brand" label="品牌" width="100" />
          <el-table-column prop="spec" label="规格" width="110" />
          <el-table-column
            prop="timesSeen"
            label="出现次数"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column label="映射" min-width="180">
            <template #default="{ row }">
              <span v-if="row.mappedSkuId">
                {{ row.mappedSkuName || row.mappedSkuId }}
                <el-button link type="danger" size="small" @click="removeBind(row)">解绑</el-button>
              </span>
              <span v-else class="muted">未映射</span>
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="90"
            align="center"
            class-name="col-action"
            label-class-name="col-action"
          >
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openBindDialog(row)">
                {{ row.mappedSkuId ? '改绑' : '绑定' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="批次历史" name="batches">
        <el-form inline @submit.prevent>
          <el-form-item label="渠道">
            <el-select
              v-model="batchFilterChannel"
              clearable
              placeholder="全部"
              style="width: 170px"
              @change="loadBatches"
            >
              <el-option
                v-for="t in templates"
                :key="t.channelCode"
                :value="t.channelCode"
                :label="t.channelName"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button @click="loadBatches">刷新</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="batchesLoading" :data="batches" size="small" border>
          <el-table-column prop="batchId" label="批次" width="80" />
          <el-table-column label="渠道" width="110">
            <template #default="{ row }">{{ row.channelName || row.channelCode }}</template>
          </el-table-column>
          <el-table-column
            prop="periodLabel"
            label="账期"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column prop="fileName" label="文件" min-width="180" />
          <el-table-column
            label="状态"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="row.status === 'COMMITTED' ? 'success' : 'warning'" size="small">
                {{ row.status === 'COMMITTED' ? '已提交' : '待确认' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="行数"
            width="130"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.rowsMapped }}/{{ row.rowsTotal }}</template>
          </el-table-column>
          <el-table-column prop="uploadedByName" label="上传人" width="100" />
          <el-table-column label="上传时间" width="160">
            <template #default="{ row }">{{ formatDateTime(row.uploadedAt) }}</template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="90"
            align="center"
            class-name="col-action"
            label-class-name="col-action"
          >
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openBatchDetail(row)"
                >明细</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="渠道流水" name="flows">
        <el-form inline @submit.prevent>
          <el-form-item label="账期">
            <el-input v-model="flowFilter.periodLabel" placeholder="202609" style="width: 120px" />
          </el-form-item>
          <el-form-item label="仓库">
            <el-input v-model="flowFilter.warehouseId" placeholder="仓库ID" style="width: 140px" />
          </el-form-item>
          <el-form-item label="操作类型">
            <el-select
              v-model="flowFilter.opType"
              clearable
              placeholder="全部"
              style="width: 140px"
            >
              <el-option v-for="(label, key) in OP_LABELS" :key="key" :value="key" :label="label" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadFlows">查询</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="flowsLoading" :data="flows" size="small" border>
          <el-table-column
            prop="periodLabel"
            label="账期"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column prop="warehouseId" label="仓库" min-width="110" />
          <el-table-column prop="personName" label="线长" width="110" />
          <el-table-column prop="skuId" label="SKU" min-width="110" />
          <el-table-column
            label="操作类型"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ OP_LABELS[row.opType] ?? row.opType }}</template>
          </el-table-column>
          <el-table-column
            prop="qty"
            label="数量"
            width="80"
            align="right"
            class-name="col-num"
            label-class-name="col-num"
          />
          <el-table-column
            prop="rowCount"
            label="原始行"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column prop="remark" label="备注" min-width="220" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="bindDialog" title="绑定本系统 SKU" width="480px">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="外部商品">
          <span>{{ bindTarget?.externalName }}（{{ bindTarget?.externalKey }}）</span>
        </el-form-item>
        <el-form-item label="本系统 SKU">
          <el-select
            v-model="bindSkuId"
            filterable
            remote
            :remote-method="searchSkus"
            :loading="skuSearchLoading"
            placeholder="输入名称/ID 搜索"
            style="width: 100%"
          >
            <el-option
              v-for="s in skuOptions"
              :key="s.skuId"
              :value="s.skuId"
              :label="`${s.skuName}（${s.skuId}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="bindNote" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bindDialog = false">取消</el-button>
        <el-button type="primary" @click="saveBind">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchDetailVisible" title="批次明细" width="860px" top="6vh">
      <template v-if="batchDetail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="批次">{{ batchDetail.batchId }}</el-descriptions-item>
          <el-descriptions-item label="渠道">{{
            batchDetail.channelName || batchDetail.channelCode
          }}</el-descriptions-item>
          <el-descriptions-item label="账期">{{ batchDetail.periodLabel }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ batchDetail.status }}</el-descriptions-item>
          <el-descriptions-item label="文件">{{ batchDetail.fileName }}</el-descriptions-item>
          <el-descriptions-item label="行数"
            >{{ batchDetail.rowsMapped }}/{{ batchDetail.rowsTotal }}</el-descriptions-item
          >
        </el-descriptions>
        <el-table
          :data="(batchDetail.rows ?? []).slice(0, 300)"
          size="small"
          border
          max-height="420"
        >
          <el-table-column
            prop="srcLine"
            label="源行"
            width="70"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column prop="person" label="线长" width="110" />
          <el-table-column prop="extKey" label="商品ID" min-width="100" />
          <el-table-column prop="extName" label="商品名称" min-width="160" />
          <el-table-column
            prop="qty"
            label="数量"
            width="80"
            align="right"
            class-name="col-num"
            label-class-name="col-num"
          />
          <el-table-column prop="happenedAt" label="业务日期" width="110" />
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.external-import-tab {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.template-hint {
  margin: 0 0 8px;
}
.preview-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}
.preview-section h4 {
  margin: 0 0 6px;
  font-size: var(--admin-font-size-sm, 13px);
}
.preview-actions {
  display: flex;
  gap: 10px;
}
</style>
