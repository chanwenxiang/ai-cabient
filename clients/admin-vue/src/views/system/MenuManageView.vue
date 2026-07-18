<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">菜单管理</span>
            <span class="hint">目录 / 菜单 / 按钮树；默认仅运营权限</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-switch v-model="opsOnly" active-text="仅运营" @change="syncRouteQuery" />
          <el-switch v-model="showInactive" active-text="含停用" @change="syncRouteQuery" />
          <el-button v-if="auth.hasPerm('ops:rbac:menu:add')" type="primary" @click="openCreate()">新增</el-button>
          <el-button @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent>
      <el-form-item label="类型">
        <el-select v-model="typeFilter" clearable placeholder="全部" style="width: 120px" @change="syncRouteQuery">
          <el-option label="目录 M" value="M" />
          <el-option label="菜单 C" value="C" />
          <el-option label="按钮 F" value="F" />
        </el-select>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 900px">
        <el-table
          v-loading="loading"
          :data="tableRows"
          row-key="permissionId"
          default-expand-all
          :tree-props="{ children: 'children' }"
          stripe
          border
          class="report-table"
          @selection-change="onSelectionChange"
        >
          <template #empty><el-empty description="暂无菜单" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="名称" min-width="180" class-name="col-text" align="left" header-align="left">
            <template #default="{ row }">
              <div class="name-cell">
                <strong>{{ row.permName }}</strong>
                <small class="cell-id">{{ row.permCode }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="typeTag(row.permType)" size="small">{{ typeText(row.permType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="权限标识" min-width="180" class-name="col-text">
            <template #default="{ row }"><span class="cell-id">{{ row.permCode }}</span></template>
          </el-table-column>
          <el-table-column label="路由" min-width="120" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">{{ row.path || '-' }}</template>
          </el-table-column>
          <el-table-column prop="sortOrder" label="排序" width="72" align="center" />
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" class-name="col-action" align="center" fixed="right">
            <template #default="{ row }">
              <TableActions :actions="menuActions(row)" @action="(k) => onMenuAction(k, row)" />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="dlg" :title="form.permissionId ? '编辑菜单' : '新增菜单'" width="520px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="上级">
          <el-select v-model="form.parentId" filterable clearable placeholder="顶级" style="width: 100%">
            <el-option :value="0" label="顶级目录" />
            <el-option
              v-for="p in parentOptions"
              :key="p.permissionId"
              :value="p.permissionId"
              :label="`${'　'.repeat(depthHint(p))}[${typeText(p.permType)}] ${p.permName}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="form.permType">
            <el-radio value="M">目录</el-radio>
            <el-radio value="C">菜单</el-radio>
            <el-radio value="F">按钮</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.permName" maxlength="64" />
        </el-form-item>
        <el-form-item label="权限标识" required>
          <el-input
            v-model="form.permCode"
            :disabled="!!form.permissionId"
            placeholder="如 ops:device:list"
            maxlength="128"
          />
        </el-form-item>
        <el-form-item v-if="form.permType !== 'F'" label="路由">
          <el-input v-model="form.path" placeholder="如 /devices" maxlength="128" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ACTIVE">正常</el-radio>
            <el-radio value="INACTIVE">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Delete, EditPen, Plus, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { buildPermTree, flattenForParentSelect, type PermRow } from '@/utils/rbac-tree';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const typeFilter = ref('');
const opsOnly = ref(true);
const showInactive = ref(false);
const tree = ref<PermRow[]>([]);
const dlg = ref(false);
const form = ref({
  permissionId: null as number | null,
  parentId: 0 as number,
  permCode: '',
  permName: '',
  permType: 'C',
  path: '',
  sortOrder: 0,
  status: 'ACTIVE'
});

const parentOptions = computed(() =>
  flattenForParentSelect(tree.value, form.value.permissionId || undefined).filter(
    (p) => p.permType === 'M' || p.permType === 'C'
  )
);

function typeText(t: string) {
  return t === 'M' ? '目录' : t === 'C' ? '菜单' : t === 'F' ? '按钮' : t;
}
function typeTag(t: string) {
  return t === 'M' ? 'warning' : t === 'C' ? 'success' : 'info';
}

function menuActions(row: PermRow): TableAction[] {
  const acts: TableAction[] = [];
  if (auth.hasPerm('ops:rbac:menu:edit')) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (auth.hasPerm('ops:rbac:menu:add') && row.permType !== 'F') {
    acts.push({ key: 'add', label: '新增', icon: Plus, type: 'success' });
  }
  if (auth.hasPerm('ops:rbac:menu:remove') && row.status === 'ACTIVE') {
    acts.push({ key: 'remove', label: '停用', icon: Delete, type: 'danger', overflow: true });
  }
  return acts;
}

function onMenuAction(key: string, row: PermRow) {
  if (key === 'edit') openEdit(row);
  else if (key === 'add') openCreate(row.permissionId);
  else if (key === 'remove') onRemove(row);
}

function depthHint(row: PermRow) {
  let d = 0;
  let pid = row.parentId;
  const map = new Map<number, PermRow>();
  const walk = (nodes: PermRow[]) => nodes.forEach((n) => { map.set(n.permissionId, n); if (n.children) walk(n.children); });
  walk(tree.value);
  while (pid && map.has(pid)) {
    d += 1;
    pid = map.get(pid)!.parentId;
    if (d > 6) break;
  }
  return d;
}

function filterTree(nodes: PermRow[], type: string, inactive: boolean, onlyOps: boolean): PermRow[] {
  const walk = (list: PermRow[]): PermRow[] =>
    list
      .map((n) => {
        if (onlyOps && !n.permCode.startsWith('ops')) return null;
        const children = n.children?.length ? walk(n.children) : [];
        const typeOk = !type || n.permType === type || children.length > 0;
        const statusOk = inactive || n.status === 'ACTIVE' || children.length > 0;
        if (typeOk && statusOk) {
          const keepSelf = (!type || n.permType === type) && (inactive || n.status === 'ACTIVE');
          return keepSelf || children.length ? { ...n, children } : null;
        }
        return null;
      })
      .filter(Boolean) as PermRow[];
  return walk(nodes);
}

const tableRows = computed(() =>
  filterTree(tree.value, typeFilter.value, showInactive.value, opsOnly.value)
);

function flattenTableRows(nodes: PermRow[]): PermRow[] {
  const out: PermRow[] = [];
  const walk = (list: PermRow[]) => {
    for (const node of list) {
      out.push(node);
      if (node.children?.length) walk(node.children);
    }
  };
  walk(nodes);
  return out;
}

const { onSelectionChange, pickSelected, exportButtonLabel } = useTableSelection<PermRow>(
  (r) => r.permissionId
);

const { onExport } = useListCsv({
  filePrefix: '菜单',
  headers: ['名称', '类型', '权限标识', '路由', '排序', '状态'],
  toRows: () =>
    pickSelected(flattenTableRows(tableRows.value)).map((row) => [
      row.permName,
      typeText(row.permType),
      row.permCode,
      row.path || '-',
      row.sortOrder ?? 0,
      row.status === 'ACTIVE' ? '正常' : '停用'
    ])
});

async function load() {
  loading.value = true;
  try {
    const flat = await api.request<PermRow[]>(
      '/api/v2/ops/admin/rbac/permissions?includeInactive=true',
      'GET'
    );
    tree.value = buildPermTree(flat);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function openCreate(parentId = 0) {
  form.value = {
    permissionId: null,
    parentId,
    permCode: '',
    permName: '',
    permType: 'C',
    path: '',
    sortOrder: 0,
    status: 'ACTIVE'
  };
  dlg.value = true;
}

function openEdit(row: PermRow) {
  form.value = {
    permissionId: row.permissionId,
    parentId: row.parentId || 0,
    permCode: row.permCode,
    permName: row.permName,
    permType: row.permType,
    path: row.path || '',
    sortOrder: row.sortOrder ?? 0,
    status: row.status || 'ACTIVE'
  };
  dlg.value = true;
}

async function save() {
  const f = form.value;
  if (!f.permName.trim()) return ElMessage.warning('请填写名称');
  if (!f.permissionId && !f.permCode.trim()) return ElMessage.warning('请填写权限标识');
  saving.value = true;
  try {
    if (f.permissionId) {
      await api.request(`/api/v2/ops/admin/rbac/permissions/${f.permissionId}`, 'PUT', {
        parentId: f.parentId || 0,
        permName: f.permName.trim(),
        permType: f.permType,
        path: f.permType === 'F' ? null : f.path || null,
        sortOrder: f.sortOrder,
        status: f.status
      });
      ElMessage.success('已更新');
    } else {
      await api.request('/api/v2/ops/admin/rbac/permissions', 'POST', {
        parentId: f.parentId || 0,
        permCode: f.permCode.trim(),
        permName: f.permName.trim(),
        permType: f.permType,
        path: f.permType === 'F' ? null : f.path || null,
        sortOrder: f.sortOrder,
        status: f.status
      });
      ElMessage.success('已创建');
    }
    dlg.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function onRemove(row: PermRow) {
  await ElMessageBox.confirm(`确认停用「${row.permName}」？`, '停用菜单', { type: 'warning' });
  try {
    await api.request(`/api/v2/ops/admin/rbac/permissions/${row.permissionId}`, 'DELETE');
    ElMessage.success('已停用');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '停用失败');
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (typeFilter.value) query.type = typeFilter.value;
  if (!opsOnly.value) query.opsOnly = '0';
  if (showInactive.value) query.inactive = '1';
  router.replace({ query });
}

function applyRouteQuery() {
  let changed = false;
  if (typeof route.query.type === 'string' && route.query.type !== typeFilter.value) {
    typeFilter.value = route.query.type;
    changed = true;
  }
  const ops = route.query.opsOnly !== '0';
  if (ops !== opsOnly.value) {
    opsOnly.value = ops;
    changed = true;
  }
  const inactive = route.query.inactive === '1' || route.query.inactive === 'true';
  if (inactive !== showInactive.value) {
    showInactive.value = inactive;
    changed = true;
  }
  return changed;
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
.page-card-head__actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.name-cell { display: grid; gap: 2px; line-height: 1.35; }
.name-cell strong { font-weight: 650; }
.name-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
</style>
