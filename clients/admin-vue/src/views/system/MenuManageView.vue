<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">菜单管理</span>
            <span class="hint"
              >运营侧栏叶子菜单与本树 ACTIVE「菜单 C」一一对应（一级目录 =
              侧栏分组）。停用菜单会从侧栏隐藏。商户树仅用于角色授权；小程序导航由功能包裁剪。</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-radio-group v-model="scope" size="small" @change="syncRouteQuery">
            <el-radio-button value="ops">运营侧栏</el-radio-button>
            <el-radio-button value="merchant">商户权限</el-radio-button>
            <el-radio-button value="all">全部</el-radio-button>
          </el-radio-group>
          <el-switch v-model="showInactive" active-text="含停用" @change="syncRouteQuery" />
          <el-button link type="primary" @click="selectAllRows">全选</el-button>
          <el-button link @click="clearAllRows">清空</el-button>
          <el-button v-hasPermi="['ops:rbac:menu:add']" type="primary" @click="openCreate()"
            >新增</el-button
          >
          <el-button v-hasPermi="['ops:rbac:menu:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent>
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="名称 / 权限标识 / 路由"
          style="width: 220px"
          @clear="syncRouteQuery"
          @keyup.enter="syncRouteQuery"
        />
      </el-form-item>
      <el-form-item label="类型">
        <el-select
          v-model="typeFilter"
          clearable
          placeholder="全部"
          style="width: 120px"
          @change="syncRouteQuery"
        >
          <el-option label="目录 M" value="M" />
          <el-option label="菜单 C" value="C" />
          <el-option label="按钮 F" value="F" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="syncRouteQuery">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
        <el-button link type="primary" @click="setExpandAll(true)">展开</el-button>
        <el-button link type="primary" @click="setExpandAll(false)">收起</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          ref="tableRef"
          v-loading="loading"
          :data="tableRows"
          row-key="permissionId"
          default-expand-all
          :indent="22"
          :tree-props="{ children: 'children' }"
          stripe
          border
          class="report-table menu-tree-table"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty>
            <el-empty v-if="listHydrated && !loading" description="暂无菜单" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            label="名称"
            min-width="200"
            class-name="col-tree"
            label-class-name="col-tree"
            align="left"
            header-align="left"
          >
            <template #default="{ row }">
              <strong class="name-only">{{ row.permName }}</strong>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="typeTag(row.permType)" size="small">{{
                typeText(row.permType)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="权限标识" min-width="200" align="center" class-name="col-text">
            <template #default="{ row }"
              ><span class="cell-id">{{ row.permCode }}</span></template
            >
          </el-table-column>
          <el-table-column
            label="路由"
            min-width="140"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.path || '无' }}</template>
          </el-table-column>
          <el-table-column prop="sortOrder" label="排序" width="72" align="center" />
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions :actions="menuActions(row)" @action="(k) => onMenuAction(k, row)" />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog
      v-model="dlg"
      :title="form.permissionId ? '编辑菜单' : '新增菜单'"
      width="520px"
      destroy-on-close
    >
      <el-form label-width="88px">
        <el-form-item label="上级">
          <el-select
            v-model="form.parentId"
            filterable
            clearable
            placeholder="顶级"
            style="width: 100%"
          >
            <el-option :value="0" label="顶级目录" />
            <el-option
              v-for="p in parentOptions"
              :key="p.permissionId"
              :value="p.permissionId"
              :label="parentOptionLabel(p)"
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
            :placeholder="scope === 'merchant' ? '如 merchant:devices:list' : '如 ops:device:list'"
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
import { computed, nextTick, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Delete, EditPen, Plus, Refresh, CircleCheck } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { buildPermTree, flattenForParentSelect, type PermRow } from '@/utils/rbac-tree';

type MenuScope = 'ops' | 'merchant' | 'all';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const typeFilter = ref('');
const keyword = ref('');
const scope = ref<MenuScope>('ops');
const showInactive = ref(false);
const tree = ref<PermRow[]>([]);
const tableRef = ref<TableInstance>();
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

const depthMap = computed(() => {
  const map = new Map<number, number>();
  const walk = (nodes: PermRow[], depth: number) => {
    for (const n of nodes) {
      map.set(n.permissionId, depth);
      if (n.children?.length) walk(n.children, depth + 1);
    }
  };
  walk(tree.value, 0);
  return map;
});

const parentOptions = computed(() =>
  flattenForParentSelect(tree.value, form.value.permissionId || undefined).filter(
    (p) => p.permType === 'M' || p.permType === 'C'
  )
);

function typeText(t: string) {
  if (t === 'M') return '目录';
  if (t === 'C') return '菜单';
  if (t === 'F') return '按钮';
  return t;
}
function typeTag(t: string) {
  if (t === 'M') return 'warning';
  if (t === 'C') return 'success';
  return 'info';
}

function parentOptionLabel(p: PermRow) {
  const depth = depthMap.value.get(p.permissionId) ?? 0;
  return `${'· '.repeat(depth)}[${typeText(p.permType)}] ${p.permName} (${p.permCode})`;
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
  if (auth.hasPerm('ops:rbac:menu:edit') && row.status !== 'ACTIVE') {
    acts.push({ key: 'enable', label: '启用', icon: CircleCheck, type: 'success', overflow: true });
  }
  return acts;
}

function onMenuAction(key: string, row: PermRow) {
  if (key === 'edit') openEdit(row);
  else if (key === 'add') openCreate(row.permissionId);
  else if (key === 'remove') onRemove(row);
  else if (key === 'enable') onEnable(row);
}

function codeInScope(code: string, s: MenuScope): boolean {
  if (s === 'all') return true;
  if (s === 'ops') return code === 'ops' || code.startsWith('ops:');
  return code === 'merchant' || code.startsWith('merchant:');
}

function matchesKeyword(n: PermRow, q: string): boolean {
  if (!q) return true;
  return (
    n.permName.toLowerCase().includes(q) ||
    n.permCode.toLowerCase().includes(q) ||
    (n.path || '').toLowerCase().includes(q)
  );
}

function filterTree(
  nodes: PermRow[],
  type: string,
  inactive: boolean,
  s: MenuScope,
  q: string
): PermRow[] {
  const walk = (list: PermRow[]): PermRow[] =>
    list
      .map((n) => {
        if (!codeInScope(n.permCode, s)) return null;
        const children = n.children?.length ? walk(n.children) : [];
        const selfMatch = matchesKeyword(n, q);
        const typeOk = !type || n.permType === type || children.length > 0;
        const statusOk = inactive || n.status === 'ACTIVE' || children.length > 0;
        const keywordOk = !q || selfMatch || children.length > 0;
        if (typeOk && statusOk && keywordOk) {
          const keepSelf =
            (!type || n.permType === type) &&
            (inactive || n.status === 'ACTIVE') &&
            (!q || selfMatch || children.length > 0);
          // 关键词命中子孙时保留祖先作为骨架，即使祖先自身不匹配类型筛选
          if (keepSelf || children.length) {
            return { ...n, children };
          }
        }
        return null;
      })
      .filter(Boolean) as PermRow[];
  return walk(nodes);
}

const tableRows = computed(() => {
  const rows = filterTree(
    tree.value,
    typeFilter.value,
    showInactive.value,
    scope.value,
    keyword.value.trim().toLowerCase()
  );
  sortPermTreeInPlace(rows);
  return rows;
});

/** 同级按「排序」升序，相同则按 permissionId 升序 */
function sortPermTreeInPlace(nodes: PermRow[]) {
  nodes.sort(
    (a, b) =>
      (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || (a.permissionId ?? 0) - (b.permissionId ?? 0)
  );
  for (const n of nodes) {
    if (n.children?.length) sortPermTreeInPlace(n.children);
  }
}

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

function setExpandAll(expand: boolean) {
  const rows = flattenTableRows(tableRows.value);
  nextTick(() => {
    for (const row of rows) {
      tableRef.value?.toggleRowExpansion(row, expand);
    }
  });
}

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<PermRow>((r) => r.permissionId);

function selectAllRows() {
  const rows = flattenTableRows(tableRows.value);
  nextTick(() => {
    for (const row of rows) {
      tableRef.value?.toggleRowSelection(row, true);
    }
  });
}

function clearAllRows() {
  tableRef.value?.clearSelection();
  clearSelection();
}

const { onExport } = useListCsv({
  filePrefix: '菜单',
  headers: ['名称', '类型', '权限标识', '路由', '排序', '状态'],
  toRows: () =>
    pickSelected(flattenTableRows(tableRows.value)).map((row) => [
      row.permName,
      typeText(row.permType),
      row.permCode,
      row.path || '无',
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
    listHydrated.value = true;
    loading.value = false;
  }
}

function openCreate(parentId = 0) {
  let prefix = '';
  if (scope.value === 'merchant') prefix = 'merchant:';
  else if (scope.value === 'ops') prefix = 'ops:';
  form.value = {
    permissionId: null,
    parentId,
    permCode: prefix,
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
    await auth.refreshPermissions();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function onRemove(row: PermRow) {
  try {
    await ElMessageBox.confirm(
      `确认停用「${row.permName}」？停用后该权限码对用户失效（运营侧栏会隐藏对应项；商户端 API 亦不可用）。可在「含停用」中启用。`,
      '停用菜单',
      { type: 'warning' }
    );
    await api.request(`/api/v2/ops/admin/rbac/permissions/${row.permissionId}`, 'DELETE');
    ElMessage.success('已停用（勾选「含停用」可查看并启用）');
    showInactive.value = true;
    syncRouteQuery();
    await load();
    await auth.refreshPermissions();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '停用失败');
    }
  }
}

async function onEnable(row: PermRow) {
  try {
    await ElMessageBox.confirm(`确认启用「${row.permName}」？`, '启用菜单', { type: 'info' });
    await api.request(`/api/v2/ops/admin/rbac/permissions/${row.permissionId}`, 'PUT', {
      parentId: row.parentId || 0,
      permName: row.permName,
      permType: row.permType,
      path: row.permType === 'F' ? null : row.path || null,
      sortOrder: row.sortOrder ?? 0,
      status: 'ACTIVE'
    });
    ElMessage.success('已启用');
    await load();
    await auth.refreshPermissions();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '启用失败');
    }
  }
}

function parseScope(q: typeof route.query): MenuScope {
  const s = typeof q.scope === 'string' ? q.scope : '';
  if (s === 'merchant' || s === 'all' || s === 'ops') return s;
  // 兼容旧 query：opsOnly=0 表示全部
  if (q.opsOnly === '0') return 'all';
  return 'ops';
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (scope.value !== 'ops') query.scope = scope.value;
  if (typeFilter.value) query.type = typeFilter.value;
  if (keyword.value.trim()) query.q = keyword.value.trim();
  if (showInactive.value) query.inactive = '1';
  router.replace({ query });
}

function resetFilters() {
  typeFilter.value = '';
  keyword.value = '';
  syncRouteQuery();
}

function applyRouteQuery() {
  let changed = false;
  const nextScope = parseScope(route.query);
  if (nextScope !== scope.value) {
    scope.value = nextScope;
    changed = true;
  }
  const qType = typeof route.query.type === 'string' ? route.query.type : '';
  if (qType !== typeFilter.value) {
    typeFilter.value = qType;
    changed = true;
  }
  const qKw = typeof route.query.q === 'string' ? route.query.q : '';
  if (qKw !== keyword.value) {
    keyword.value = qKw;
    changed = true;
  }
  const inactive = route.query.inactive === '1' || route.query.inactive === 'true';
  if (inactive !== showInactive.value) {
    showInactive.value = inactive;
    changed = true;
  }
  return changed;
}

function reloadFromRouteQuery() {
  applyRouteQuery();
}

watch(
  () =>
    [
      route.query.scope,
      route.query.opsOnly,
      route.query.type,
      route.query.q,
      route.query.inactive
    ] as const,
  () => {
    reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  load();
});
onActivated(() => {
  reloadFromRouteQuery();
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
.page-card-head__meta {
  min-width: 0;
  flex: 1;
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
  line-height: 1.45;
  max-width: 52rem;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.name-only {
  font-weight: 650;
}
.cell-id {
  color: var(--el-text-color-secondary);
}
:deep(.menu-tree-table .el-table__cell) {
  vertical-align: middle;
}
:deep(.menu-tree-table .el-table__indent),
:deep(.menu-tree-table .el-table__placeholder),
:deep(.menu-tree-table .el-table__expand-icon) {
  display: inline-flex;
  align-items: center;
  vertical-align: middle;
  flex-shrink: 0;
}
:deep(.menu-tree-table .el-table__expand-icon) {
  height: 20px;
  line-height: 20px;
}
:deep(.menu-tree-table td.col-tree > .cell) {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 0 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: left !important;
  min-height: 24px;
}
</style>
