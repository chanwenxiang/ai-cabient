<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">营销活动</span>
        <div class="actions">
          <el-button v-if="auth.hasPerm('ops:promotion:create')" @click="onExport">导出</el-button>
          <el-button v-if="auth.hasPerm('ops:promotion:create')" @click="onDownloadTemplate">导入模板</el-button>
          <el-button v-if="auth.hasPerm('ops:promotion:create')" :loading="importing" @click="triggerImport">导入</el-button>
          <input ref="importInput" type="file" accept=".csv,text/csv" class="hidden-input" @change="onImportFile" />
          <el-button
            v-if="selectedIds.length && auth.hasPerm('ops:promotion:stop')"
            type="warning"
            @click="batchDisable"
          >
            批量停用 ({{ selectedIds.length }})
          </el-button>
          <el-button v-if="auth.hasPerm('ops:promotion:create')" type="primary" @click="openCreate">新建活动</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 980px">
        <el-table
          v-loading="loading"
          :data="list"
          stripe
          border
          row-key="activityId"
          @selection-change="onSelectionChange"
        >
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column prop="activityId" label="ID" width="80" />
          <el-table-column prop="activityName" label="活动名称" min-width="160" show-overflow-tooltip />
          <el-table-column label="类型" width="110">
            <template #default="{ row }">{{ typeMap[row.activityType] || row.activityType }}</template>
          </el-table-column>
          <el-table-column label="时间" min-width="200">
            <template #default="{ row }">{{ formatTime(row.startTime) }} ~ {{ formatTime(row.endTime) }}</template>
          </el-table-column>
          <el-table-column label="预算" width="110">
            <template #default="{ row }">¥{{ yuan(row.budgetCents) }}</template>
          </el-table-column>
          <el-table-column label="已使用" width="110">
            <template #default="{ row }">¥{{ yuan(row.usedCents) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="88">
            <template #default="{ row }">
              <el-tag :type="isEnabled(row.status) ? 'success' : 'info'" size="small">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                v-if="rowActions(row).length"
                :actions="rowActions(row)"
                :max-primary="1"
                @action="(k) => onAction(String(k), row)"
              />
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无活动" /></template>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="showDialog" :title="editingId ? '编辑活动' : '新建活动'" width="560px" destroy-on-close>
      <el-form :model="form" label-width="96px">
        <el-form-item label="活动名称" required><el-input v-model="form.activityName" maxlength="80" /></el-form-item>
        <el-form-item label="活动类型">
          <el-select v-model="form.activityType" style="width: 100%">
            <el-option label="满减" value="FULL_REDUCE" />
            <el-option label="折扣" value="DISCOUNT" />
            <el-option label="买赠" value="BUY_GIFT" />
            <el-option label="第二件半价" value="SECOND_HALF" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间"><el-date-picker v-model="form.startTime" type="datetime" style="width: 100%" /></el-form-item>
        <el-form-item label="结束时间"><el-date-picker v-model="form.endTime" type="datetime" style="width: 100%" /></el-form-item>
        <el-form-item label="预算(元)">
          <el-input-number v-model="form.budgetYuan" :min="0" :step="1" :precision="2" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="每人限制"><el-input-number v-model="form.userLimit" :min="1" :max="100" controls-position="right" style="width: 100%" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { EditPen, Refresh, SwitchButton } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useAuthStore } from '@/stores/auth';
import { csvFileName, csvRowsToObjects, downloadCsv, parseCsv } from '@/utils/csv';

const auth = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const importing = ref(false);
const list = ref<any[]>([]);
const selectedIds = ref<number[]>([]);
const showDialog = ref(false);
const editingId = ref<number | null>(null);
const importInput = ref<HTMLInputElement | null>(null);

const CSV_HEADERS = ['活动名称', '类型', '开始时间', '结束时间', '预算(元)', '每人限制', '描述', '状态'];

const emptyForm = () => ({
  activityName: '',
  activityType: 'FULL_REDUCE',
  startTime: '' as string | Date,
  endTime: '' as string | Date,
  budgetYuan: 0,
  userLimit: 1,
  description: ''
});
const form = ref(emptyForm());

const typeMap: Record<string, string> = {
  FULL_REDUCE: '满减',
  DISCOUNT: '折扣',
  BUY_GIFT: '买赠',
  SECOND_HALF: '第二件半价'
};
const typeCodeByLabel: Record<string, string> = {
  满减: 'FULL_REDUCE',
  折扣: 'DISCOUNT',
  买赠: 'BUY_GIFT',
  第二件半价: 'SECOND_HALF',
  FULL_REDUCE: 'FULL_REDUCE',
  DISCOUNT: 'DISCOUNT',
  BUY_GIFT: 'BUY_GIFT',
  SECOND_HALF: 'SECOND_HALF'
};

function yuan(cents: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}
function formatTime(t: string) {
  if (!t) return '';
  return t.substring(0, 16).replace('T', ' ');
}
function isEnabled(status?: string) {
  return status === 'ACTIVE';
}
function statusLabel(status?: string) {
  return isEnabled(status) ? '启用' : '停用';
}

function rowActions(row: any): TableAction[] {
  const acts: TableAction[] = [];
  if (!isEnabled(row.status) && row.status !== 'ENDED' && auth.hasPerm('ops:promotion:edit')) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (auth.hasPerm('ops:promotion:stop') && row.status !== 'ENDED') {
    acts.push({
      key: 'toggle',
      label: isEnabled(row.status) ? '停用' : '启用',
      icon: SwitchButton,
      type: isEnabled(row.status) ? 'warning' : 'success',
      overflow: true
    });
  }
  return acts;
}

function onSelectionChange(rows: any[]) {
  selectedIds.value = rows.map((r) => r.activityId).filter(Boolean);
}

async function onAction(key: string, row: any) {
  if (key === 'edit') openEdit(row);
  else if (key === 'toggle') await onToggleStatus(row);
}

async function load() {
  loading.value = true;
  try {
    list.value = await api.request<any[]>('/api/v2/ops/promotions', 'GET');
    selectedIds.value = [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editingId.value = null;
  form.value = emptyForm();
  showDialog.value = true;
}

function openEdit(row: any) {
  editingId.value = row.activityId;
  form.value = {
    activityName: row.activityName,
    activityType: row.activityType,
    startTime: row.startTime ? new Date(row.startTime) : '',
    endTime: row.endTime ? new Date(row.endTime) : '',
    budgetYuan: (Number(row.budgetCents) || 0) / 100,
    userLimit: row.userLimit ?? 1,
    description: row.description || ''
  };
  showDialog.value = true;
}

async function onSubmit() {
  const f = form.value;
  if (!f.activityName?.trim()) return ElMessage.warning('请填写活动名称');
  if (!f.startTime || !f.endTime) return ElMessage.warning('请选择活动时间');
  const start = new Date(f.startTime);
  const end = new Date(f.endTime);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return ElMessage.warning('活动时间无效');
  if (end <= start) return ElMessage.warning('结束时间需晚于开始时间');
  const body = {
    activityName: f.activityName.trim(),
    activityType: f.activityType,
    startTime: start.toISOString(),
    endTime: end.toISOString(),
    budgetCents: Math.round((Number(f.budgetYuan) || 0) * 100),
    userLimit: f.userLimit,
    description: f.description
  };
  saving.value = true;
  try {
    if (editingId.value) {
      await api.request(`/api/v2/ops/promotions/${editingId.value}`, 'PUT', body);
      ElMessage.success('已更新');
    } else {
      await api.request('/api/v2/ops/promotions', 'POST', body);
      ElMessage.success('创建成功');
    }
    showDialog.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function onToggleStatus(row: any) {
  const enable = !isEnabled(row.status);
  const action = enable ? '启用' : '停用';
  try {
    await ElMessageBox.confirm(`确认${action}活动「${row.activityName}」？`, '活动状态', { type: 'warning' });
    if (enable) {
      await api.request(`/api/v2/ops/promotions/${row.activityId}/launch`, 'POST');
    } else {
      await api.request(`/api/v2/ops/promotions/${row.activityId}/stop`, 'POST');
    }
    ElMessage.success(`已${action}`);
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : `${action}失败`);
    }
  }
}

async function batchDisable() {
  const targets = list.value.filter((r) => selectedIds.value.includes(r.activityId) && isEnabled(r.status));
  if (!targets.length) return ElMessage.warning('请勾选已启用的活动');
  await ElMessageBox.confirm(`确认停用选中的 ${targets.length} 个活动？`, '批量停用');
  try {
    for (const row of targets) {
      await api.request(`/api/v2/ops/promotions/${row.activityId}/stop`, 'POST');
    }
    ElMessage.success(`已停用 ${targets.length} 个活动`);
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '批量停用失败');
  }
}

function toExportRows(items: any[]) {
  return items.map((row) => [
    row.activityName,
    typeMap[row.activityType] || row.activityType,
    formatTime(row.startTime),
    formatTime(row.endTime),
    yuan(row.budgetCents),
    row.userLimit ?? 1,
    row.description || '',
    statusLabel(row.status)
  ]);
}

function onExport() {
  const rows = selectedIds.value.length
    ? list.value.filter((r) => selectedIds.value.includes(r.activityId))
    : list.value;
  if (!rows.length) return ElMessage.warning('暂无数据可导出');
  downloadCsv(csvFileName('营销活动'), CSV_HEADERS, toExportRows(rows));
  ElMessage.success(`已导出 ${rows.length} 条`);
}

function onDownloadTemplate() {
  downloadCsv(csvFileName('营销活动导入模板'), CSV_HEADERS, [
    ['示例满减活动', '满减', '2026-07-16 00:00', '2026-08-16 23:59', '1000', '1', '示例描述', '停用']
  ]);
}

function triggerImport() {
  importInput.value?.click();
}

function parseImportTime(raw: string): Date | null {
  if (!raw) return null;
  const normalized = raw.includes('T') ? raw : raw.replace(' ', 'T');
  const d = new Date(normalized.length === 16 ? `${normalized}:00` : normalized);
  return Number.isNaN(d.getTime()) ? null : d;
}

function wantsEnabled(statusRaw: string) {
  const s = (statusRaw || '').trim();
  return s === '启用' || s.toUpperCase() === 'ACTIVE';
}

async function onImportFile(ev: Event) {
  const input = ev.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  importing.value = true;
  try {
    const text = await file.text();
    const objects = csvRowsToObjects(parseCsv(text));
    if (!objects.length) return ElMessage.warning('CSV 无有效数据行');
    let ok = 0;
    for (const row of objects) {
      const name = row['活动名称'] || row.activityName;
      if (!name) continue;
      const type = typeCodeByLabel[row['类型'] || row.activityType] || 'FULL_REDUCE';
      const start = parseImportTime(row['开始时间'] || row.startTime);
      const end = parseImportTime(row['结束时间'] || row.endTime);
      if (!start || !end || end <= start) {
        throw new Error(`活动「${name}」时间无效`);
      }
      const created = await api.request<any>('/api/v2/ops/promotions', 'POST', {
        activityName: name,
        activityType: type,
        startTime: start.toISOString(),
        endTime: end.toISOString(),
        budgetCents: Math.round((Number(row['预算(元)'] || row.budgetYuan) || 0) * 100),
        userLimit: Number(row['每人限制'] || row.userLimit) || 1,
        description: row['描述'] || row.description || ''
      });
      if (wantsEnabled(row['状态'] || row.status) && created?.activityId) {
        await api.request(`/api/v2/ops/promotions/${created.activityId}/launch`, 'POST');
      }
      ok++;
    }
    ElMessage.success(`导入成功 ${ok} 条`);
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导入失败');
  } finally {
    importing.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.title { font-weight: 600; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
.muted { color: var(--layout-muted); font-size: 13px; }
.hidden-input { display: none; }
</style>
