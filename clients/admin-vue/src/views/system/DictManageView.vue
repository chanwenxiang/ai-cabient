<template>
  <div class="dict-page">
    <el-row :gutter="16" class="dict-row">
      <el-col :xs="24" :md="8">
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
            <div class="table-scroll-inner" style="min-width: 380px">
              <el-table
                v-loading="loadingTypes"
                :data="filteredTypes"
                highlight-current-row
                height="100%"
                border
                class="report-table"
                table-layout="auto"
                @current-change="onSelectType"
              >
                <template #empty><el-empty description="暂无字典类型" :image-size="64" /></template>
                <el-table-column label="类型" min-width="160" class-name="col-text">
                  <template #default="{ row }">
                    <div class="name-cell">
                      <strong>{{ row.dictName }}</strong>
                      <small class="cell-id">{{ row.dictType }}</small>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="itemCount" label="项数" width="64" align="center" />
                <el-table-column label="操作" width="70" class-name="col-action" align="center">
                  <template #default="{ row }">
                    <el-button v-hasPermi="['ops:dict:edit']" link type="primary" @click.stop="openType(row)">编辑</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="16">
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
                <el-button v-hasPermi="['ops:dict:export']" size="small" :disabled="!selected" @click="onExport">{{ exportButtonLabel }}</el-button>
                <el-button v-hasPermi="['ops:dict:import']" size="small" :disabled="!selected" @click="onDownloadTemplate(['DEMO', '示例标签', '0', '启用'])">导入模板</el-button>
                <el-button v-hasPermi="['ops:dict:import']" size="small" :disabled="!selected" :loading="importing" @click="triggerImport">导入</el-button>
                <input ref="importInput" type="file" accept=".csv,text/csv" class="hidden-input" @change="onImportFile" />
                <el-button v-hasPermi="['ops:dict:edit']" type="primary" size="small" :disabled="!selected" @click="openItem()">新增字典项</el-button>
              </div>
            </div>
          </template>
          <div class="table-scroll dict-item-scroll">
            <div class="table-scroll-inner" style="min-width: 620px">
              <el-table
                v-loading="loadingItems"
                :data="items"
                stripe
                border
                class="report-table"
                height="100%"
                table-layout="auto"
                row-key="dictDataId"
                empty-text="请选择左侧字典类型"
                @selection-change="onSelectionChange"
              >
                <template #empty>
                  <el-empty :description="selected ? '暂无字典项' : '请先选择左侧字典类型'" :image-size="64" />
                </template>
                <el-table-column type="selection" width="48" align="center" />
                <el-table-column label="字典项" min-width="180" class-name="col-text">
                  <template #default="{ row }">
                    <div class="name-cell">
                      <strong>{{ row.dictLabel }}</strong>
                      <small class="cell-id">{{ row.dictValue }}</small>
                    </div>
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
                <el-table-column label="操作" width="120" class-name="col-action" align="center">
                  <template #default="{ row }">
                    <el-button v-hasPermi="['ops:dict:edit']" link type="primary" @click="openItem(row)">编辑</el-button>
                    <el-button v-hasPermi="['ops:dict:edit']" link type="danger" @click="removeItem(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

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
        <el-button v-hasPermi="['ops:dict:edit']" type="primary" :loading="saving" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { loadRuntimeDict } from '@/stores/dict-runtime';

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

const loadingTypes = ref(false);
const loadingItems = ref(false);
const saving = ref(false);
const types = ref<DictTypeRow[]>([]);
const items = ref<DictItemRow[]>([]);
const selected = ref<DictTypeRow | null>(null);
const route = useRoute();
const router = useRouter();
const typeQuery = ref('');
const typeDlg = ref(false);
const itemDlg = ref(false);
const editingType = ref(false);
const typeForm = reactive({ dictType: '', dictName: '', status: 'ACTIVE', remark: '', sortOrder: 0 });
const itemForm = reactive({ dictDataId: 0, dictValue: '', dictLabel: '', status: 'ACTIVE', remark: '', sortOrder: 0 });

const filteredTypes = computed(() => {
  const q = typeQuery.value.trim().toLowerCase();
  if (!q) return types.value;
  return types.value.filter((t) => t.dictType.toLowerCase().includes(q) || t.dictName.toLowerCase().includes(q));
});

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
  try {
    types.value = await api.request<DictTypeRow[]>('/api/v2/ops/admin/dicts', 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loadingTypes.value = false;
  }
}

async function loadItems() {
  if (!selected.value) { items.value = []; return; }
  loadingItems.value = true;
  try {
    items.value = await api.request<DictItemRow[]>(`/api/v2/ops/admin/dicts/${encodeURIComponent(selected.value.dictType)}/items`, 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载字典项失败');
  } finally {
    loadingItems.value = false;
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (selected.value?.dictType) query.type = selected.value.dictType;
  router.replace({ query });
}

function onSelectType(row: DictTypeRow | null, opts?: { sync?: boolean }) {
  selected.value = row;
  clearSelection();
  loadItems();
  if (opts?.sync !== false) syncRouteQuery();
}

function applyRouteQuery() {
  const qType = typeof route.query.type === 'string' ? route.query.type : '';
  if (!qType) return false;
  if (selected.value?.dictType === qType) return false;
  const hit = types.value.find((t) => t.dictType === qType);
  if (hit) {
    onSelectType(hit, { sync: false });
    return true;
  }
  return false;
}

async function reloadFromRouteQuery() {
  if (!types.value.length) await loadTypes();
  if (!applyRouteQuery() && !selected.value && types.value.length) {
    onSelectType(types.value[0]);
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

async function saveItem() {
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
    if (itemForm.dictDataId) {
      await api.request(`/api/v2/ops/admin/dicts/${t}/items/${itemForm.dictDataId}`, 'PUT', body);
    } else {
      await api.request(`/api/v2/ops/admin/dicts/${t}/items`, 'POST', body);
    }
    ElMessage.success('已保存');
    itemDlg.value = false;
    await Promise.all([loadItems(), loadTypes(), loadRuntimeDict()]);
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

watch(
  () => route.query.type,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(async () => {
  await loadTypes();
  if (!applyRouteQuery() && types.value.length) onSelectType(types.value[0]);
});
onActivated(() => {
  void reloadFromRouteQuery();
});
</script>

<style scoped>
.dict-page {
  min-height: 0;
}
.dict-row {
  align-items: stretch;
}
.dict-card {
  height: 100%;
  min-height: 560px;
  display: flex;
  flex-direction: column;
}
.dict-card :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.dict-type-scroll,
.dict-item-scroll {
  flex: 1;
  min-height: 420px;
  display: flex;
  flex-direction: column;
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
.type-search { margin-bottom: 12px; }
.name-cell { display: grid; gap: 2px; line-height: 1.35; }
.name-cell strong { font-weight: 650; }
.name-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
.hidden-input { display: none; }
</style>
