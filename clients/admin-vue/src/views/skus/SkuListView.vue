<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">商品管理</span>
            <span class="hint">主数据：编号 / 条码 / 品牌规格；识别类名与入驻请到「识别入驻」</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:sku:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button
            v-hasPermi="['ops:sku:import']"
            @click="onDownloadTemplate(['', '6901234567890', '示例可乐', '可口可乐', '330ml', '瓶', '3.50', '1.20', '饮料', '上架'])"
          >导入模板</el-button>
          <el-button v-hasPermi="['ops:sku:import']" :loading="importing" @click="triggerImport">导入</el-button>
          <input ref="importInput" type="file" accept=".csv,text/csv" class="hidden-input" @change="onImportFile" />
          <el-button v-if="canAccessPath('/sku-vision')" @click="goVision">识别入驻</el-button>
          <el-button v-hasPermi="['ops:sku:edit']" type="primary" @click="openEdit()">新建商品</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="saleTab" class="status-tabs" @tab-change="onSaleTab">
      <el-tab-pane label="在售商品" name="ACTIVE" />
      <el-tab-pane label="所有商品" name="ALL" />
    </el-tabs>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="编号 / 名称 / 条码 / 品牌"
          style="width: 240px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item label="类目">
        <el-select
          v-model="categoryFilter"
          clearable
          placeholder="全部类目"
          style="width: 160px"
          @change="search"
        >
          <el-option
            v-for="item in categoryOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
        <el-button
          v-hasPermi="['ops:sku:edit']"
          type="danger"
          plain
          :disabled="!selectedKeys.length"
          :loading="batchDelisting"
          @click="batchDelist"
        >批量下架</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="paged"
          stripe
          border
          table-layout="auto"
          row-key="skuId"
          class="report-table sku-table"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange" empty-text=" ">
          <template #empty>
            <el-empty v-if="listHydrated && !loading" :description="skuEmptyText" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column prop="skuCode" label="编号" width="100" align="center" class-name="col-text" sortable="custom">
            <template #default="{ row }">
              <span class="cell-id">{{ row.skuCode ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="主图" width="72" align="center">
            <template #default="{ row }">
              <img
                v-if="row.imageUrl"
                :src="row.imageUrl"
                alt=""
                class="sku-thumb"
                loading="lazy"
                referrerpolicy="no-referrer"
                @error="onThumbError"
              />
              <div v-else class="sku-thumb sku-thumb--empty">无图</div>
            </template>
          </el-table-column>
          <el-table-column prop="barcode" label="条码" min-width="120" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ row.barcode || '无' }}</template>
          </el-table-column>
          <el-table-column label="名称" min-width="140" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <button
                type="button"
                class="link-cell"
                @click="canEdit ? openEdit(row) : ElMessage.info('当前账号无商品编辑权限')"
              >
                {{ row.skuName || '无' }}
              </button>
            </template>
          </el-table-column>
          <el-table-column prop="brand" label="品牌" min-width="90" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ row.brand || '无' }}</template>
          </el-table-column>
          <el-table-column prop="spec" label="规格" min-width="90" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ row.spec || '无' }}</template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="72" align="center">
            <template #default="{ row }">{{ row.unit || '件' }}</template>
          </el-table-column>
          <el-table-column label="售价" width="96" align="center" class-name="col-money">
            <template #default="{ row }">¥{{ ((row.priceCents || 0) / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="成本" width="96" align="center" class-name="col-money">
            <template #default="{ row }">
              {{ row.purchaseCostCents != null ? `¥${(row.purchaseCostCents / 100).toFixed(2)}` : '无' }}
            </template>
          </el-table-column>
          <el-table-column prop="category" label="类目" min-width="100" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="88" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                {{ skuStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="添加时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作人" min-width="110" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ row.updatedByName || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" class-name="col-action" align="center" fixed="right">
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
    <PagePager :hydrated="listHydrated"
        v-model:current-page="page"
        v-model:page-size="size"
        :total="filtered.length"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
      />

    <el-dialog v-model="editDialog" :title="form.existing ? '编辑商品' : '新建商品'" width="640px">
      <el-form label-width="108px">
        <el-form-item label="数字编号">
          <el-input :model-value="form.skuCode ? String(form.skuCode) : '保存后自动分配'" disabled />
        </el-form-item>
        <el-form-item label="商品名称" required>
          <el-input v-model="form.skuName" placeholder="如 可口可乐" />
        </el-form-item>
        <el-form-item label="条码">
          <el-input v-model="form.barcode" placeholder="EAN / UPC，可空" />
        </el-form-item>
        <el-form-item label="品牌">
          <el-input v-model="form.brand" />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="form.spec" placeholder="如 330ml" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="form.unit" placeholder="件" style="width: 120px" />
        </el-form-item>
        <el-form-item label="售价(元)" required>
          <el-input-number v-model="form.priceYuan" :min="0.01" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="成本(元)">
          <el-input-number v-model="form.costYuan" :min="0" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="类目">
          <el-select
            v-model="form.category"
            clearable
            placeholder="请选择类目"
            style="width: 100%"
          >
            <el-option
              v-for="item in categoryOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="克重(g)">
          <el-input-number v-model="form.weightGrams" :min="0" :step="1" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 160px">
            <el-option label="上架" value="ACTIVE" />
            <el-option label="下架" value="INACTIVE" />
          </el-select>
        </el-form-item>
        <el-form-item label="主图">
          <div class="sku-image-field">
            <el-upload
              v-hasPermi="['ops:sku:edit']"
              :show-file-list="false"
              accept="image/jpeg,image/png,image/webp,image/gif,.jpg,.jpeg,.png,.webp,.gif"
              :http-request="onImageUpload"
              :disabled="imageUploading"
            >
              <el-button :loading="imageUploading" type="primary" plain>上传图片</el-button>
            </el-upload>
            <el-button v-if="form.imageUrl" link type="danger" @click="form.imageUrl = ''">清除</el-button>
            <div class="field-hint">支持 jpg/png/webp/gif，单张不超过 5MB</div>
            <img
              v-if="form.imageUrl.trim()"
              :src="form.imageUrl.trim()"
              alt="主图预览"
              class="sku-preview"
              referrerpolicy="no-referrer"
            />
          </div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button v-hasPermi="['ops:sku:edit']" type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus';
import { dictLabel, dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useDictOptions } from '@/composables/useDictOptions';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { findNavByPath } from '@/config/menu';
import { dictRuntimeEpoch } from '@/stores/dict-runtime';
import type { FileAttachmentDto, SkuCatalog, UpsertSkuRequest } from '@aicabinet/shared-types';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('skuCode');
const canEdit = computed(() => auth.hasPerm('ops:sku:edit'));

const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const imageUploading = ref(false);
const batchDelisting = ref(false);
const items = ref<SkuCatalog[]>([]);
const keyword = ref('');
const categoryFilter = ref('');
const categoryOptions = useDictOptions('category_code');
const saleTab = ref('ACTIVE');

function categoryLabel(code?: string | null) {
  if (!code) return '无';
  void dictRuntimeEpoch.value;
  return dictLabel('category_code', code) || code;
}

/** 下拉值为字典键值（如 FRESH_PRODUCE）；历史数据可能存中文标签「生鲜」 */
function categoryMatches(stored: string | null | undefined, selected: string): boolean {
  if (!selected) return true;
  const raw = String(stored || '').trim();
  if (!raw) return false;
  if (raw === selected) return true;
  const label = dictLabel('category_code', selected);
  if (label && label !== selected && raw === label) return true;
  // 反向：库里是键值、筛选项误为标签时
  for (const o of dictOptions('category_code')) {
    if (o.label === selected && (raw === o.value || raw === o.label)) return true;
  }
  return false;
}

/** 写入/编辑时尽量落到字典键值，避免再次出现「标签 vs 键值」不一致 */
function normalizeCategoryToCode(raw?: string | null): string {
  const text = String(raw || '').trim();
  if (!text) return '';
  void dictRuntimeEpoch.value;
  for (const o of dictOptions('category_code')) {
    if (o.value === text || o.label === text) return o.value;
  }
  return text;
}
const page = ref(1);
const size = ref(20);
const editDialog = ref(false);

const form = reactive({
  existing: false,
  skuId: '',
  skuCode: undefined as number | undefined,
  skuName: '',
  barcode: '',
  brand: '',
  spec: '',
  unit: '件',
  priceYuan: 3.5,
  costYuan: undefined as number | undefined,
  category: '',
  weightGrams: undefined as number | undefined,
  status: 'ACTIVE',
  imageUrl: '',
  description: '',
  // preserve vision fields on update
  visionEnabled: true,
  minChargeConfidence: undefined as number | undefined,
  yoloClassName: undefined as string | undefined,
  visionEnrollmentStatus: undefined as string | undefined,
  detectionMinConfidence: undefined as number | undefined,
  referenceImageUrlsJson: undefined as string | undefined,
  shelfLifeDays: undefined as number | undefined,
  nearExpiryDays: undefined as number | undefined,
  blockSaleDaysBeforeExpiry: undefined as number | undefined,
  storageType: undefined as string | undefined,
  nearExpiryPriceCents: undefined as number | undefined
});

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  const cat = categoryFilter.value.trim();
  const rows = items.value.filter((row) => {
    if (saleTab.value === 'ACTIVE' && row.status !== 'ACTIVE') return false;
    if (cat && !categoryMatches(row.category, cat)) return false;
    if (!q) return true;
    return [row.skuCode, row.skuId, row.skuName, row.barcode, row.brand, categoryLabel(row.category)]
      .some((x) => String(x || '').toLowerCase().includes(q));
  });
  return sortById(rows, (r) => r.skuCode ?? r.skuId);
});

const skuEmptyText = computed(() => {
  if (saleTab.value === 'ACTIVE') {
    if (keyword.value.trim() || categoryFilter.value.trim()) return '在售列表无匹配商品，可清空筛选或切换到「所有商品」';
    return '暂无在售商品，可切换到「所有商品」查看已下架项';
  }
  if (keyword.value.trim() || categoryFilter.value.trim()) return '无匹配商品';
  return '暂无商品';
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

watch([keyword, categoryFilter, saleTab], () => {
  page.value = 1;
});

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection, selectedKeys } =
  useTableSelection<SkuCatalog>((r) => r.skuId);

function canAccessPath(path: string) {
  const nav = findNavByPath(path);
  if (!nav?.perm) return true;
  return auth.hasPerm(nav.perm);
}

function goVision() {
  router.push('/sku-vision');
}

function onThumbError(e: Event) {
  const img = e.target as HTMLImageElement | null;
  if (!img) return;
  const placeholder = document.createElement('div');
  placeholder.className = 'sku-thumb sku-thumb--empty';
  placeholder.textContent = '无图';
  img.replaceWith(placeholder);
}

async function onImageUpload(options: UploadRequestOptions) {
  const file = options.file as File;
  if (!file) return;
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('单张图片不能超过 5MB');
    options.onError?.(new Error('too large') as never);
    return;
  }
  imageUploading.value = true;
  try {
    const token = localStorage.getItem('admin_token');
    const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || window.location.origin;
    const formData = new FormData();
    formData.append('file', file);
    const res = await fetch(`${base}/api/v2/ops/admin/skus/image`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok || json.code !== 0) {
      throw new Error(json.message || `上传失败 (${res.status})`);
    }
    const uploaded = json.data as FileAttachmentDto;
    if (!uploaded?.url) {
      throw new Error('上传成功但未返回图片地址');
    }
    form.imageUrl = uploaded.url;
    ElMessage.success('主图已上传');
    options.onSuccess?.(uploaded as never);
  } catch (e) {
    const msg = e instanceof Error ? e.message : '上传失败';
    ElMessage.error(msg);
    options.onError?.(e as never);
  } finally {
    imageUploading.value = false;
  }
}

function onSaleTab() {
  clearSelection();
  syncRouteQuery();
}

function skuStatusLabel(status?: string) {
  if (status === 'ACTIVE') return '上架';
  if (status === 'INACTIVE') return '下架';
  return displayLabel('sku_status', status, '未知状态');
}

function toUpsertBody(row: SkuCatalog, status: string): UpsertSkuRequest {
  return {
    skuId: row.skuId,
    skuName: row.skuName || row.skuId,
    priceCents: row.priceCents || 1,
    weightGrams: row.weightGrams,
    visionEnabled: row.visionEnabled ?? true,
    imageUrl: row.imageUrl,
    description: row.description,
    category: row.category,
    barcode: row.barcode,
    brand: row.brand,
    spec: row.spec,
    unit: row.unit || '件',
    status,
    shelfLifeDays: row.shelfLifeDays,
    nearExpiryDays: row.nearExpiryDays,
    blockSaleDaysBeforeExpiry: row.blockSaleDaysBeforeExpiry,
    storageType: row.storageType,
    purchaseCostCents: row.purchaseCostCents,
    nearExpiryPriceCents: row.nearExpiryPriceCents,
    minChargeConfidence: row.minChargeConfidence,
    yoloClassName: row.yoloClassName,
    visionEnrollmentStatus: row.visionEnrollmentStatus as UpsertSkuRequest['visionEnrollmentStatus'],
    detectionMinConfidence: row.detectionMinConfidence,
    referenceImageUrlsJson: row.referenceImageUrlsJson
  };
}

async function batchDelist() {
  const targets = items.value.filter(
    (d) => selectedKeys.value.map(String).includes(d.skuId) && d.status === 'ACTIVE'
  );
  if (!targets.length) {
    ElMessage.warning('请勾选在售商品');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认将 ${targets.length} 个商品下架？下架后不再出现在在售列表。`,
      '批量下架',
      { type: 'warning', confirmButtonText: '确认下架' }
    );
  } catch {
    return;
  }
  batchDelisting.value = true;
  let ok = 0;
  let fail = 0;
  try {
    for (const row of targets) {
      try {
        await api.request(`/api/v2/ops/admin/skus/${encodeURIComponent(row.skuId)}`, 'PUT', toUpsertBody(row, 'INACTIVE'));
        const idx = items.value.findIndex((x) => x.skuId === row.skuId);
        if (idx >= 0) items.value[idx] = { ...items.value[idx], status: 'INACTIVE' };
        ok += 1;
      } catch {
        fail += 1;
      }
    }
    if (fail === 0) ElMessage.success(`已下架 ${ok} 个商品`);
    else ElMessage.warning(`下架完成：成功 ${ok}，失败 ${fail}`);
    clearSelection();
  } finally {
    batchDelisting.value = false;
  }
}

const skuStatusByLabel: Record<string, string> = {
  上架: 'ACTIVE',
  下架: 'INACTIVE',
  停用: 'DISABLED',
  ACTIVE: 'ACTIVE',
  INACTIVE: 'INACTIVE',
  DISABLED: 'DISABLED'
};

const { importing, importInput, onExport, onDownloadTemplate, triggerImport, onImportFile } = useListCsv({
  filePrefix: '商品',
  headers: ['编号', '条码', '名称', '品牌', '规格', '单位', '售价', '成本', '类目', '商品状态', '添加时间', '操作人'],
  toRows: () =>
    pickSelected(filtered.value).map((row) => [
      row.skuCode != null ? String(row.skuCode) : '',
      row.barcode || '',
      row.skuName,
      row.brand || '',
      row.spec || '',
      row.unit || '件',
      ((row.priceCents || 0) / 100).toFixed(2),
      row.purchaseCostCents != null ? (row.purchaseCostCents / 100).toFixed(2) : '',
      categoryLabel(row.category) || '',
      skuStatusLabel(row.status),
      formatDateTime(row.createdAt),
      row.updatedByName || ''
    ]),
  onImportRows: async (rows) => {
    let ok = 0;
    for (const row of rows) {
      const skuName = (row['名称'] || row.skuName || '').trim();
      if (!skuName) continue;
      const barcode = (row['条码'] || row.barcode || '').trim() || undefined;
      const priceCents = Math.round((Number(row['售价'] || row.priceYuan) || 0) * 100);
      const status = skuStatusByLabel[row['商品状态'] || row.status] || 'ACTIVE';
      const costRaw = row['成本'] ?? row.purchaseCostYuan;
      const purchaseCostCents =
        costRaw != null && String(costRaw).trim() !== ''
          ? Math.round((Number(costRaw) || 0) * 100)
          : undefined;
      const existing = barcode
        ? items.value.find((i) => i.barcode && i.barcode === barcode)
        : undefined;
      const body: UpsertSkuRequest = {
        skuId: existing?.skuId,
        skuName,
        priceCents: priceCents || 350,
        barcode,
        brand: (row['品牌'] || row.brand || '').trim() || undefined,
        spec: (row['规格'] || row.spec || '').trim() || undefined,
        unit: (row['单位'] || row.unit || '').trim() || '件',
        category: normalizeCategoryToCode(row['类目'] || row.category) || undefined,
        purchaseCostCents,
        status,
        visionEnabled: existing?.visionEnabled ?? true,
        yoloClassName: existing?.yoloClassName,
        visionEnrollmentStatus: existing?.visionEnrollmentStatus as UpsertSkuRequest['visionEnrollmentStatus'],
        minChargeConfidence: existing?.minChargeConfidence,
        detectionMinConfidence: existing?.detectionMinConfidence,
        referenceImageUrlsJson: existing?.referenceImageUrlsJson
      };
      if (existing) {
        await api.request(`/api/v2/ops/admin/skus/${encodeURIComponent(existing.skuId)}`, 'PUT', body);
      } else {
        await api.request('/api/v2/ops/admin/skus', 'POST', body);
      }
      ok++;
    }
    clearSelection();
    await load();
    return ok;
  }
});

function skuActions(row: SkuCatalog): TableAction[] {
  const acts: TableAction[] = [];
  if (canEdit.value) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  return acts;
}

function onSkuAction(key: string, row: SkuCatalog) {
  if (key === 'edit') openEdit(row);
}

function openEdit(row?: SkuCatalog) {
  if (row) {
    form.existing = true;
    form.skuId = row.skuId;
    form.skuCode = row.skuCode;
    form.skuName = row.skuName;
    form.barcode = row.barcode || '';
    form.brand = row.brand || '';
    form.spec = row.spec || '';
    form.unit = row.unit || '件';
    form.priceYuan = (row.priceCents || 0) / 100;
    form.costYuan = row.purchaseCostCents != null ? row.purchaseCostCents / 100 : undefined;
    form.category = normalizeCategoryToCode(row.category);
    form.weightGrams = row.weightGrams;
    form.status = row.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE';
    form.imageUrl = row.imageUrl || '';
    form.description = row.description || '';
    form.visionEnabled = row.visionEnabled ?? true;
    form.minChargeConfidence = row.minChargeConfidence;
    form.yoloClassName = row.yoloClassName;
    form.visionEnrollmentStatus = row.visionEnrollmentStatus;
    form.detectionMinConfidence = row.detectionMinConfidence;
    form.referenceImageUrlsJson = row.referenceImageUrlsJson;
    form.shelfLifeDays = row.shelfLifeDays;
    form.nearExpiryDays = row.nearExpiryDays;
    form.blockSaleDaysBeforeExpiry = row.blockSaleDaysBeforeExpiry;
    form.storageType = row.storageType;
    form.nearExpiryPriceCents = row.nearExpiryPriceCents;
  } else {
    form.existing = false;
    form.skuId = '';
    form.skuCode = undefined;
    form.skuName = '';
    form.barcode = '';
    form.brand = '';
    form.spec = '';
    form.unit = '件';
    form.priceYuan = 3.5;
    form.costYuan = undefined;
    form.category = '';
    form.weightGrams = undefined;
    form.status = 'ACTIVE';
    form.imageUrl = '';
    form.description = '';
    form.visionEnabled = true;
    form.minChargeConfidence = undefined;
    form.yoloClassName = undefined;
    form.visionEnrollmentStatus = 'DRAFT';
    form.detectionMinConfidence = undefined;
    form.referenceImageUrlsJson = undefined;
    form.shelfLifeDays = undefined;
    form.nearExpiryDays = undefined;
    form.blockSaleDaysBeforeExpiry = undefined;
    form.storageType = undefined;
    form.nearExpiryPriceCents = undefined;
  }
  editDialog.value = true;
}

async function saveEdit() {
  if (!form.skuName.trim()) {
    ElMessage.warning('请填写商品名称');
    return;
  }
  if (!form.priceYuan || form.priceYuan <= 0) {
    ElMessage.warning('请填写有效售价');
    return;
  }
  saving.value = true;
  try {
    const body: UpsertSkuRequest = {
      skuId: form.existing ? form.skuId : undefined,
      skuName: form.skuName.trim(),
      priceCents: Math.round(form.priceYuan * 100),
      barcode: form.barcode.trim() || undefined,
      brand: form.brand.trim() || undefined,
      spec: form.spec.trim() || undefined,
      unit: form.unit.trim() || '件',
      category: normalizeCategoryToCode(form.category) || undefined,
      weightGrams: form.weightGrams,
      status: form.status,
      imageUrl: form.imageUrl.trim() || undefined,
      description: form.description.trim() || undefined,
      purchaseCostCents:
        form.costYuan != null && !Number.isNaN(form.costYuan)
          ? Math.round(form.costYuan * 100)
          : undefined,
      visionEnabled: form.visionEnabled,
      minChargeConfidence: form.minChargeConfidence,
      yoloClassName: form.yoloClassName,
      visionEnrollmentStatus: form.visionEnrollmentStatus as UpsertSkuRequest['visionEnrollmentStatus'],
      detectionMinConfidence: form.detectionMinConfidence,
      referenceImageUrlsJson: form.referenceImageUrlsJson,
      shelfLifeDays: form.shelfLifeDays,
      nearExpiryDays: form.nearExpiryDays,
      blockSaleDaysBeforeExpiry: form.blockSaleDaysBeforeExpiry,
      storageType: form.storageType,
      nearExpiryPriceCents: form.nearExpiryPriceCents
    };
    let updated: SkuCatalog;
    if (form.existing) {
      updated = await api.request<SkuCatalog>(
        `/api/v2/ops/admin/skus/${encodeURIComponent(form.skuId)}`,
        'PUT',
        body
      );
    } else {
      updated = await api.request<SkuCatalog>('/api/v2/ops/admin/skus', 'POST', body);
    }
    const idx = items.value.findIndex((i) => i.skuId === updated.skuId);
    if (idx >= 0) items.value[idx] = updated;
    else items.value.push(updated);
    items.value.sort((a, b) => (a.skuCode ?? 0) - (b.skuCode ?? 0));
    editDialog.value = false;
    ElMessage.success(form.existing ? '已保存商品' : `已新建，编号 ${updated.skuCode ?? updated.skuId}`);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (categoryFilter.value.trim()) query.category = categoryFilter.value.trim();
  if (saleTab.value && saleTab.value !== 'ACTIVE') query.sale = saleTab.value;
  router.replace({ query });
}

function search() {
  page.value = 1;
  syncRouteQuery();
}

function resetFilters() {
  keyword.value = '';
  categoryFilter.value = '';
  saleTab.value = 'ACTIVE';
  page.value = 1;
  syncRouteQuery();
}

function applyRouteQuery() {
  let changed = false;
  const qKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (qKeyword !== keyword.value) {
    keyword.value = qKeyword;
    changed = true;
  }
  const qCat = typeof route.query.category === 'string' ? route.query.category : '';
  if (qCat !== categoryFilter.value) {
    categoryFilter.value = qCat;
    changed = true;
  }
  const qSale = typeof route.query.sale === 'string' && route.query.sale ? route.query.sale : 'ACTIVE';
  if (qSale !== saleTab.value) {
    saleTab.value = qSale;
    changed = true;
  }
  return changed;
}

async function load() {
  loading.value = true;
  try {
    items.value = await api.request<SkuCatalog[]>('/api/v2/ops/admin/skus', 'GET');
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
}

watch(
  () => [route.query.keyword, route.query.category, route.query.sale] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  load();
});
onActivated(() => {
  void reloadFromRouteQuery();
});
</script>

<style scoped>
.status-tabs { margin: 0 0 10px; }
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
.sku-thumb {
  width: 40px;
  height: 40px;
  border-radius: 6px;
  object-fit: cover;
  display: block;
  margin: 0 auto;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
}
.sku-thumb--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  border-style: dashed;
  object-fit: unset;
}
.sku-image-field {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
}
.sku-image-field .el-upload {
  display: inline-flex;
}
.field-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.sku-preview {
  margin-top: 4px;
  width: 96px;
  height: 96px;
  border-radius: 8px;
  object-fit: contain;
  display: block;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
}
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  font: inherit;
  font-weight: 650;
}
.link-cell:hover { text-decoration: underline; }
.sku-table { font-size: 14px; }
.hidden-input { display: none; }
</style>
