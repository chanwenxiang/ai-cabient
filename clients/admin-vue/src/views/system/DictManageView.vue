<template>
  <div class="page-fill dict-page">
    <div class="dict-split" :style="{ gridTemplateColumns: `${typesWidth}px 6px minmax(0, 1fr)` }">
      <section class="dict-col dict-col--types">
        <el-card class="page-card report-page dict-card" shadow="never">
          <template #header>
            <div class="page-card-head">
              <div class="page-card-head__meta">
                <div class="page-card-head__title">
                  <span class="title">字典类型</span>
                  <span class="hint">左侧选中后编辑右侧字典项</span>
                </div>
              </div>
              <div class="page-card-head__actions">
                <el-button v-hasPermi="['ops:dict:edit']" type="primary" size="small" @click="openType()">新增类型</el-button>
              </div>
            </div>
          </template>
          <el-input
            v-model="typeQuery"
            clearable
            placeholder="搜索类型 / 名称"
            class="type-search"
          />
          <div class="table-scroll dict-type-scroll">
            <div class="table-scroll-inner">
              <el-table
                ref="typeTableRef"
                v-loading="loadingTypes"
                :data="filteredTypes"
                highlight-current-row
                height="100%"
                border
                class="report-table"
                empty-text=" "
                table-layout="auto"
                row-key="dictType"
                :default-sort="typeDefaultSort"
                @sort-change="onTypeSortChange"
                @current-change="onSelectType"
              >
                <template #empty>
                  <el-empty v-if="typesHydrated && !loadingTypes" description="暂无字典类型" :image-size="64" />
                </template>
                <el-table-column prop="dictType" label="字典类型" min-width="120" align="center" class-name="col-text" show-overflow-tooltip sortable="custom">
                  <template #default="{ row }">
                    <span class="cell-id">{{ row.dictType }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="类型" min-width="120" align="center" class-name="col-text" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.dictName || '无' }}</template>
                </el-table-column>
                <el-table-column prop="itemCount" label="项数" width="56" align="center" />
                <el-table-column v-if="canEdit" label="操作" width="64" class-name="col-action" align="center" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" @click.stop="openType(row)">编辑</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-card>
      </section>
      <div
        class="dict-splitter"
        title="拖动调整宽度"
        @mousedown="startResize"
      />
      <section ref="detailColRef" class="dict-col dict-col--items">
        <el-card class="page-card report-page dict-card" shadow="never">
          <template #header>
            <div class="page-card-head">
              <div class="page-card-head__meta">
                <div class="page-card-head__title">
                  <span class="title">字典数据{{ selected ? ` · ${selected.dictName}` : '' }}</span>
                  <span class="hint">{{ selected ? selected.dictType : '请先选择左侧类型' }}</span>
                </div>
              </div>
              <div class="page-card-head__actions">
                <el-button size="small" :loading="loadingTypes || loadingItems" @click="refreshAll">刷新</el-button>
                <el-button v-hasPermi="['ops:dict:export']" size="small" :disabled="!selected" @click="onExport">{{ exportButtonLabel }}</el-button>
                <el-button v-hasPermi="['ops:dict:import']" size="small" :disabled="!selected" @click="onDownloadTemplate(['DEMO', '示例标签', '0', '启用'])">导入模板</el-button>
                <el-button v-hasPermi="['ops:dict:import']" size="small" :disabled="!selected" :loading="importing" @click="triggerImport">导入</el-button>
                <input ref="importInput" type="file" accept=".csv,text/csv" class="hidden-input" @change="onImportFile" />
                <el-button v-hasPermi="['ops:dict:edit']" type="primary" size="small" :disabled="!selected" @click="openItem()">新增字典项</el-button>
              </div>
            </div>
          </template>
          <div class="table-scroll dict-item-scroll">
            <div class="table-scroll-inner">
              <el-table
                v-loading="loadingItems"
                :data="displayItems"
                stripe
                border
                height="100%"
                class="report-table"
                table-layout="auto"
                row-key="dictDataId"
                empty-text=" "
                :default-sort="itemDefaultSort"
                @sort-change="onItemSortChange"
                @selection-change="onSelectionChange"
              >
                <template #empty>
                  <el-empty
                    v-if="itemsHydrated && !loadingItems"
                    :description="selected ? '暂无字典项' : '请先选择左侧字典类型'"
                    :image-size="64"
                  />
                </template>
                <el-table-column type="selection" width="48" align="center" />
                <el-table-column prop="dictDataId" label="数据编号" width="80" align="center" class-name="col-text" sortable="custom">
                  <template #default="{ row }">
                    <span class="cell-id">{{ row.dictDataId }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="字典项" min-width="120" align="center" class-name="col-text" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.dictLabel || '无' }}</template>
                </el-table-column>
                <el-table-column label="值" min-width="100" align="center" class-name="col-text" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="cell-id">{{ row.dictValue }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="sortOrder" label="排序" width="72" align="center" />
                <el-table-column label="状态" width="88" align="center">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                      {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column v-if="canEdit" label="操作" width="120" class-name="col-action" align="center" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="openItem(row)">编辑</el-button>
                    <el-button link type="danger" @click="removeItem(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-card>
      </section>
    </div>

    <el-dialog v-model="typeDlg" :title="typeForm.dictType && types.some(t => t.dictType === typeForm.dictType) ? '编辑字典类型' : '新增字典类型'" width="480px">
      <el-form label-width="88px">
        <el-form-item label="类型编码"><el-input v-model="typeForm.dictType" :disabled="!!editingType" placeholder="如 order_status（英文编码）" /></el-form-item>
        <el-form-item label="类型名称"><el-input v-model="typeForm.dictName" placeholder="中文名称" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="typeForm.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="typeForm.status"><el-radio value="ACTIVE">启用</el-radio><el-radio value="INACTIVE">停用</el-radio></el-radio-group>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="typeForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDlg = false">取消</el-button>
        <el-button v-hasPermi="['ops:dict:edit']" type="primary" :loading="saving" @click="saveType">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="itemDlg" :title="itemForm.dictDataId ? '编辑字典项' : '新增字典项'" width="480px">
      <el-form label-width="88px">
        <el-form-item label="字典值"><el-input v-model="itemForm.dictValue" :disabled="!!itemForm.dictDataId" placeholder="如 COMPLETED（英文枚举值）" /></el-form-item>
        <el-form-item label="显示标签"><el-input v-model="itemForm.dictLabel" placeholder="中文标签" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="itemForm.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="itemForm.status"><el-radio value="ACTIVE">启用</el-radio><el-radio value="INACTIVE">停用</el-radio></el-radio-group>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="itemForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDlg = false">取消</el-button>
        <el-button
          v-if="!itemForm.dictDataId"
          v-hasPermi="['ops:dict:edit']"
          :loading="saving"
          @click="saveItem(true)"
        >保存并继续</el-button>
        <el-button v-hasPermi="['ops:dict:edit']" type="primary" :loading="saving" @click="saveItem(false)">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus';
import { api } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { loadRuntimeDict } from '@/stores/dict-runtime';
import { useAuthStore } from '@/stores/auth';

interface DictTypeRow {
  dictType: string;
  dictName: string;
  status: string;
  remark?: string;
  sortOrder: number;
  itemCount: number;
}

interface DictItemRow {
  dictDataId: number;
  dictType: string;
  dictValue: string;
  dictLabel: string;
  sortOrder: number;
  status: string;
  remark?: string;
}

const TYPES_WIDTH_KEY = 'admin_dict_types_width';
const TYPES_WIDTH_MIN = 260;
const TYPES_WIDTH_MAX = 640;

function readTypesWidth(): number {
  const n = Number(localStorage.getItem(TYPES_WIDTH_KEY) || '');
  if (Number.isFinite(n) && n >= TYPES_WIDTH_MIN && n <= TYPES_WIDTH_MAX) return Math.round(n);
  return 360;
}

const auth = useAuthStore();
const canEdit = computed(() => auth.hasPerm('ops:dict:edit'));
const {
  defaultSort: typeDefaultSort,
  onSortChange: onTypeSortChange,
  sortById: sortTypesById
} = useIdColumnSort<DictTypeRow>('dictType');
const {
  defaultSort: itemDefaultSort,
  onSortChange: onItemSortChange,
  sortById: sortItemsById
} = useIdColumnSort<DictItemRow>('dictDataId');

const loadingTypes = ref(false);
const loadingItems = ref(false);
const typesHydrated = ref(false);
const itemsHydrated = ref(false);
const saving = ref(false);
const types = ref<DictTypeRow[]>([]);
const items = ref<DictItemRow[]>([]);
const selected = ref<DictTypeRow | null>(null);
const typeTableRef = ref<TableInstance | null>(null);
const detailColRef = ref<HTMLElement | null>(null);
const route = useRoute();
const router = useRouter();
const typeQuery = ref('');
const typeDlg = ref(false);
const itemDlg = ref(false);
const editingType = ref(false);
const typesWidth = ref(readTypesWidth());
const typeForm = reactive({ dictType: '', dictName: '', status: 'ACTIVE', remark: '', sortOrder: 0 });
const itemForm = reactive({ dictDataId: 0, dictValue: '', dictLabel: '', status: 'ACTIVE', remark: '', sortOrder: 0 });
/** 刷新左侧表时忽略 current-change(null)，避免跳到别的类型 */
let suppressTypeClear = false;

const filteredTypes = computed(() => {
  const q = typeQuery.value.trim().toLowerCase();
  const list = !q
    ? types.value
    : types.value.filter((row) => row.dictType.toLowerCase().includes(q) || row.dictName.toLowerCase().includes(q));
  return sortTypesById(list);
});
const displayItems = computed(() => sortItemsById(items.value));

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<DictItemRow>((r) => r.dictDataId);

const statusByLabel: Record<string, string> = {
  启用: 'ACTIVE',
  停用: 'INACTIVE',
  ACTIVE: 'ACTIVE',
  INACTIVE: 'INACTIVE'
};

const { importing, importInput, onExport, onDownloadTemplate, triggerImport, onImportFile } = useListCsv({
  filePrefix: '字典项',
  headers: ['值', '标签', '排序', '状态'],
  toRows: () =>
    pickSelected(items.value).map((row) => [
      row.dictValue,
      row.dictLabel,
      row.sortOrder,
      row.status === 'ACTIVE' ? '启用' : '停用'
    ]),
  onImportRows: async (rows) => {
    if (!selected.value) throw new Error('请先选择字典类型');
    let ok = 0;
    const t = encodeURIComponent(selected.value.dictType);
    for (const row of rows) {
      const dictValue = (row['值'] || row.dictValue || '').trim();
      const dictLabel = (row['标签'] || row.dictLabel || '').trim();
      if (!dictValue || !dictLabel) continue;
      const sortRaw = (row['排序'] || row.sortOrder || '0').trim();
      await api.request(`/api/v2/ops/admin/dicts/${t}/items`, 'POST', {
        dictValue,
        dictLabel,
        sortOrder: Number(sortRaw) || 0,
        status: statusByLabel[row['状态'] || row.status] || 'ACTIVE',
        remark: ''
      });
      ok++;
    }
    clearSelection();
    await Promise.all([loadItems(), loadTypes(), loadRuntimeDict()]);
    return ok;
  }
});

async function loadTypes() {
  loadingTypes.value = true;
  const keepType = selected.value?.dictType;
  suppressTypeClear = true;
  try {
    types.value = await api.request<DictTypeRow[]>('/api/v2/ops/admin/dicts', 'GET');
    if (keepType) {
      const hit = types.value.find((t) => t.dictType === keepType) || null;
      selected.value = hit;
      await nextTick();
      if (hit) typeTableRef.value?.setCurrentRow?.(hit);
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    typesHydrated.value = true;
    loadingTypes.value = false;
    // 等表格 current-change(null) 冒完再解除
    await nextTick();
    suppressTypeClear = false;
  }
}

async function loadItems() {
  if (!selected.value) {
    items.value = [];
    itemsHydrated.value = true;
    return;
  }
  loadingItems.value = true;
  try {
    items.value = await api.request<DictItemRow[]>(`/api/v2/ops/admin/dicts/${encodeURIComponent(selected.value.dictType)}/items`, 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载字典项失败');
    items.value = [];
  } finally {
    itemsHydrated.value = true;
    loadingItems.value = false;
  }
}

/** 列表 + 运行时字典一并刷新，使其他页下拉立即按 ACTIVE 项更新 */
async function refreshAll() {
  await Promise.all([
    loadTypes(),
    selected.value ? loadItems() : Promise.resolve(),
    loadRuntimeDict()
  ]);
  ElMessage.success('已刷新字典');
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (selected.value?.dictType) query.type = selected.value.dictType;
  router.replace({ query });
}

function scrollDetailIntoViewIfStacked() {
  // 仅在极窄叠栏时滚到右侧；与样式断点 720px 对齐
  if (typeof window === 'undefined' || !window.matchMedia('(max-width: 720px)').matches) return;
  void nextTick(() => {
    const el = detailColRef.value as HTMLElement | null;
    el?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  });
}

function onSelectType(row: DictTypeRow | null, opts?: { sync?: boolean }) {
  if (!row) {
    if (suppressTypeClear) return;
    selected.value = null;
    clearSelection();
    items.value = [];
    itemsHydrated.value = true;
    if (opts?.sync !== false) syncRouteQuery();
    return;
  }
  if (selected.value?.dictType === row.dictType) {
    // 刷新后 setCurrentRow 可能再次触发；勿重复滚屏
    selected.value = row;
    return;
  }
  selected.value = row;
  clearSelection();
  loadItems();
  if (opts?.sync !== false) syncRouteQuery();
  scrollDetailIntoViewIfStacked();
}

function applyRouteQuery() {
  const qType = typeof route.query.type === 'string' ? route.query.type : '';
  if (!qType) return false;
  if (selected.value?.dictType === qType) return false;
  const hit = types.value.find((t) => t.dictType === qType);
  if (hit) {
    onSelectType(hit, { sync: false });
    void nextTick(() => typeTableRef.value?.setCurrentRow?.(hit));
    return true;
  }
  return false;
}

async function reloadFromRouteQuery() {
  if (!types.value.length) await loadTypes();
  if (!applyRouteQuery() && !selected.value && types.value.length) {
    onSelectType(types.value[0]);
    void nextTick(() => typeTableRef.value?.setCurrentRow?.(types.value[0]));
  }
}

function openType(row?: DictTypeRow) {
  editingType.value = !!row;
  typeForm.dictType = row?.dictType || '';
  typeForm.dictName = row?.dictName || '';
  typeForm.status = row?.status || 'ACTIVE';
  typeForm.remark = row?.remark || '';
  typeForm.sortOrder = row?.sortOrder ?? types.value.length + 1;
  typeDlg.value = true;
}

function openItem(row?: DictItemRow) {
  itemForm.dictDataId = row?.dictDataId || 0;
  itemForm.dictValue = row?.dictValue || '';
  itemForm.dictLabel = row?.dictLabel || '';
  itemForm.status = row?.status || 'ACTIVE';
  itemForm.remark = row?.remark || '';
  itemForm.sortOrder = row?.sortOrder ?? items.value.length + 1;
  itemDlg.value = true;
}

function resetItemFormForContinue() {
  itemForm.dictDataId = 0;
  itemForm.dictValue = '';
  itemForm.dictLabel = '';
  itemForm.status = 'ACTIVE';
  itemForm.remark = '';
  itemForm.sortOrder = items.value.length + 1;
}

async function saveType() {
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/dicts/types', 'POST', { ...typeForm });
    ElMessage.success('已保存');
    typeDlg.value = false;
    await loadTypes();
    await loadRuntimeDict();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function saveItem(continueAdd = false) {
  if (!selected.value) return;
  saving.value = true;
  try {
    const body = {
      dictValue: itemForm.dictValue,
      dictLabel: itemForm.dictLabel,
      status: itemForm.status,
      remark: itemForm.remark,
      sortOrder: itemForm.sortOrder
    };
    const t = encodeURIComponent(selected.value.dictType);
    const isEdit = !!itemForm.dictDataId;
    if (isEdit) {
      await api.request(`/api/v2/ops/admin/dicts/${t}/items/${itemForm.dictDataId}`, 'PUT', body);
    } else {
      await api.request(`/api/v2/ops/admin/dicts/${t}/items`, 'POST', body);
    }
    ElMessage.success('已保存');
    await Promise.all([loadItems(), loadTypes(), loadRuntimeDict()]);
    if (!isEdit && continueAdd) {
      resetItemFormForContinue();
      itemDlg.value = true;
    } else {
      itemDlg.value = false;
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function removeItem(row: DictItemRow) {
  try {
    await ElMessageBox.confirm(`确认删除字典项「${row.dictLabel}」？`, '删除确认');
    await api.request(`/api/v2/ops/admin/dicts/items/${row.dictDataId}`, 'DELETE');
    ElMessage.success('已删除');
    await Promise.all([loadItems(), loadTypes(), loadRuntimeDict()]);
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败');
    }
  }
}

function startResize(ev: MouseEvent) {
  ev.preventDefault();
  const startX = ev.clientX;
  const startW = typesWidth.value;
  const onMove = (e: MouseEvent) => {
    const next = Math.min(TYPES_WIDTH_MAX, Math.max(TYPES_WIDTH_MIN, startW + (e.clientX - startX)));
    typesWidth.value = next;
  };
  const onUp = () => {
    localStorage.setItem(TYPES_WIDTH_KEY, String(typesWidth.value));
    window.removeEventListener('mousemove', onMove);
    window.removeEventListener('mouseup', onUp);
    document.body.style.cursor = '';
    document.body.style.userSelect = '';
  };
  document.body.style.cursor = 'col-resize';
  document.body.style.userSelect = 'none';
  window.addEventListener('mousemove', onMove);
  window.addEventListener('mouseup', onUp);
}

watch(
  () => route.query.type,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(async () => {
  await loadTypes();
  if (!applyRouteQuery() && types.value.length) {
    onSelectType(types.value[0]);
    await nextTick();
    typeTableRef.value?.setCurrentRow?.(types.value[0]);
  }
});
onActivated(() => {
  void reloadFromRouteQuery();
});
onBeforeUnmount(() => {
  document.body.style.cursor = '';
  document.body.style.userSelect = '';
});
</script>

<style scoped>
/* 固定双栏：不依赖 el-col md 断点，窗口缩小仍并排，避免右侧被挤到下方 */
.dict-page {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}
.dict-split {
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
  display: grid;
  gap: 0 10px;
  align-items: stretch;
}
.dict-splitter {
  width: 6px;
  margin: 0 -2px;
  cursor: col-resize;
  border-radius: 4px;
  background: transparent;
  position: relative;
  z-index: 2;
  align-self: stretch;
}
.dict-splitter::after {
  content: '';
  position: absolute;
  top: 12%;
  bottom: 12%;
  left: 2px;
  width: 2px;
  border-radius: 1px;
  background: var(--layout-border, #ebeef5);
}
.dict-splitter:hover::after,
.dict-splitter:active::after {
  background: var(--el-color-primary);
}
.dict-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
}
.dict-card {
  flex: 1 1 auto;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  margin-bottom: 0;
}
.dict-card :deep(.el-card__header) {
  flex-shrink: 0;
}
.dict-card :deep(.el-card__body) {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.type-search {
  flex-shrink: 0;
  margin-bottom: 12px;
}
.dict-type-scroll,
.dict-item-scroll {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.dict-type-scroll .table-scroll-inner,
.dict-item-scroll .table-scroll-inner {
  flex: 1;
  min-height: 0;
  height: 100%;
}
.dict-type-scroll :deep(.el-table),
.dict-item-scroll :deep(.el-table) {
  height: 100% !important;
  max-height: none !important;
}
.dict-type-scroll :deep(.el-table__inner-wrapper),
.dict-item-scroll :deep(.el-table__inner-wrapper) {
  height: 100% !important;
  display: flex !important;
  flex-direction: column;
}
.dict-type-scroll :deep(.el-table__body-wrapper),
.dict-item-scroll :deep(.el-table__body-wrapper) {
  flex: 1 1 auto !important;
  min-height: 0 !important;
  height: auto !important;
  max-height: none !important;
}
.dict-type-scroll :deep(.el-table .el-scrollbar),
.dict-item-scroll :deep(.el-table .el-scrollbar) {
  height: 100% !important;
}
/* 双栏字典：恢复表内纵滚（压过全局「只横滚」） */
.dict-type-scroll :deep(.el-table .el-scrollbar__wrap),
.dict-item-scroll :deep(.el-table .el-scrollbar__wrap) {
  overflow-x: auto !important;
  overflow-y: auto !important;
}
.dict-type-scroll :deep(.el-table .el-scrollbar__bar.is-vertical),
.dict-item-scroll :deep(.el-table .el-scrollbar__bar.is-vertical) {
  display: block !important;
  opacity: 1 !important;
  width: 8px !important;
}

/* 极窄屏才叠栏；左侧限高，选中后右侧仍在附近 */
@media (max-width: 720px) {
  .dict-page {
    height: auto;
    overflow: auto;
  }
  .dict-split {
    grid-template-columns: 1fr !important;
    height: auto;
    gap: 16px;
  }
  .dict-splitter {
    display: none;
  }
  .dict-col,
  .dict-card {
    height: auto;
  }
  .dict-col--types {
    max-height: min(42vh, 360px);
  }
  .dict-col--items {
    min-height: 360px;
  }
  .dict-type-scroll {
    min-height: 180px;
  }
  .dict-item-scroll {
    min-height: 280px;
  }
}

.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
  flex-wrap: wrap;
}
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 2px; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.35; }
.page-card-head__actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.hidden-input { display: none; }
</style>
