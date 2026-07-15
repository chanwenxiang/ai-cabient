<template>
  <div class="dict-page">
    <el-row :gutter="16">
      <el-col :xs="24" :md="8">
        <el-card class="page-card" shadow="never">
          <template #header>
            <div class="card-head">
              <span>字典类型</span>
              <el-button type="primary" size="small" @click="openType()">新增类型</el-button>
            </div>
          </template>
          <el-input v-model="typeQuery" clearable placeholder="搜索类型 / 名称" style="margin-bottom:12px" />
          <el-table v-loading="loadingTypes" :data="filteredTypes" highlight-current-row height="560" @current-change="onSelectType">
            <el-table-column prop="dictName" label="名称" min-width="120" />
            <el-table-column prop="dictType" label="类型" min-width="120"><template #default="{ row }"><code>{{ row.dictType }}</code></template></el-table-column>
            <el-table-column prop="itemCount" label="项数" width="70" />
            <el-table-column label="操作" width="70">
              <template #default="{ row }"><el-button link type="primary" @click.stop="openType(row)">编辑</el-button></template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="16">
        <el-card class="page-card" shadow="never">
          <template #header>
            <div class="card-head">
              <span>字典数据{{ selected ? ` · ${selected.dictName}` : '' }}</span>
              <el-button type="primary" size="small" :disabled="!selected" @click="openItem()">新增字典项</el-button>
            </div>
          </template>
          <el-table v-loading="loadingItems" :data="items" stripe empty-text="请选择左侧字典类型">
            <el-table-column prop="dictValue" label="值" min-width="140"><template #default="{ row }"><code>{{ row.dictValue }}</code></template></el-table-column>
            <el-table-column prop="dictLabel" label="标签" min-width="160" />
            <el-table-column prop="sortOrder" label="排序" width="80" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openItem(row)">编辑</el-button>
                <el-button link type="danger" @click="removeItem(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="typeDlg" :title="typeForm.dictType && types.some(t => t.dictType === typeForm.dictType) ? '编辑字典类型' : '新增字典类型'" width="480px">
      <el-form label-width="88px">
        <el-form-item label="类型编码"><el-input v-model="typeForm.dictType" :disabled="!!editingType" placeholder="如 order_status" /></el-form-item>
        <el-form-item label="类型名称"><el-input v-model="typeForm.dictName" placeholder="中文名称" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="typeForm.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="typeForm.status"><el-radio label="ACTIVE">启用</el-radio><el-radio label="INACTIVE">停用</el-radio></el-radio-group>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="typeForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveType">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="itemDlg" :title="itemForm.dictDataId ? '编辑字典项' : '新增字典项'" width="480px">
      <el-form label-width="88px">
        <el-form-item label="字典值"><el-input v-model="itemForm.dictValue" :disabled="!!itemForm.dictDataId" placeholder="如 COMPLETED" /></el-form-item>
        <el-form-item label="显示标签"><el-input v-model="itemForm.dictLabel" placeholder="中文标签" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="itemForm.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="itemForm.status"><el-radio label="ACTIVE">启用</el-radio><el-radio label="INACTIVE">停用</el-radio></el-radio-group>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="itemForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
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

function onSelectType(row: DictTypeRow | null) {
  selected.value = row;
  loadItems();
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
  await ElMessageBox.confirm(`确认删除字典项「${row.dictLabel}」？`, '删除确认');
  try {
    await api.request(`/api/v2/ops/admin/dicts/items/${row.dictDataId}`, 'DELETE');
    ElMessage.success('已删除');
    await Promise.all([loadItems(), loadTypes(), loadRuntimeDict()]);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

onMounted(async () => {
  await loadTypes();
  if (types.value.length) onSelectType(types.value[0]);
});
</script>

<style scoped>
.card-head { display:flex; justify-content:space-between; align-items:center; gap:8px; }
code { font-size:12px; }
</style>
