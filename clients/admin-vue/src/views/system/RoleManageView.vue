<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">角色管理</span>
            <span class="hint">角色与权限字符；商户权限码用于 API/角色授权，不驱动小程序导航</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:rbac:role:add']" type="primary" @click="openCreate"
            >新增角色</el-button
          >
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="名称 / 权限字符"
          style="width: 200px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="statusFilter"
          clearable
          placeholder="全部"
          style="width: 120px"
          @change="search"
        >
          <el-option :label="displayLabel('merchant_status', 'ACTIVE')" value="ACTIVE" />
          <el-option :label="displayLabel('merchant_status', 'INACTIVE')" value="INACTIVE" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="roleId"
          selectable
          :actions="rowActions"
          :action-width="160"
          actions-testid="role"
          empty-text="暂无角色"
          sort-field-label="角色编号"
          :csv="csvOptions"
          @action="onRowAction"
        >
          <el-table-column prop="roleId" label="角色编号" width="80" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.roleId }}</span>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="角色"
            min-width="140"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.roleName || row.roleKey || '无' }}</template>
          </el-table-column>
          <el-table-column label="权限字符" min-width="140" class-name="col-text">
            <template #default="{ row }"
              ><span class="cell-id">{{ row.roleKey }}</span></template
            >
          </el-table-column>
          <el-table-column
            label="状态"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ displayLabel('merchant_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="权限数"
            width="96"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ permissionCountLabel(row) }}</template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="160" class-name="col-text">
            <template #default="{ row }">{{ row.remark || '无' }}</template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog v-model="formDlg" :title="form.roleId ? '编辑角色' : '新增角色'" destroy-on-close>
      <el-form label-width="auto">
        <el-form-item label="权限字符" required>
          <el-input
            v-model="form.roleKey"
            :disabled="!!form.roleId"
            placeholder="如 ops_custom"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="角色名称" required>
          <el-input v-model="form.roleName" maxlength="64" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ACTIVE">正常</el-radio>
            <el-radio value="INACTIVE" :disabled="form.roleKey === 'admin'">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>

    <ResizableDrawer
      v-model="permDlg"
      :title="`分配权限 · ${permRole?.roleName || ''}`"
      storage-key="admin.drawer.roles.perms"
      :default-width="420"
      :min-width="380"
      destroy-on-close
    >
      <el-alert
        v-if="permRole?.roleKey === 'admin'"
        type="info"
        :closable="false"
        show-icon
        title="超级管理员拥有全部权限，不可修改"
        style="margin-bottom: 12px"
      />
      <div class="perm-toolbar">
        <el-radio-group
          v-model="permCheckMode"
          size="small"
          :disabled="permRole?.roleKey === 'admin'"
        >
          <el-radio-button value="cascade">全选联动</el-radio-button>
          <el-radio-button value="strict">独立勾选</el-radio-button>
        </el-radio-group>
        <div class="perm-toolbar__actions">
          <el-button
            link
            type="primary"
            :disabled="!permRole || permRole.roleKey === 'admin'"
            @click="selectAllPerms"
          >
            全选
          </el-button>
          <el-button
            link
            :disabled="!permRole || permRole.roleKey === 'admin'"
            @click="clearAllPerms"
          >
            清空
          </el-button>
        </div>
      </div>
      <div v-loading="loadingPerms" class="perm-tree-wrap">
        <el-tree
          v-if="permTree.length || loadingPerms"
          ref="treeRef"
          :data="permTree"
          node-key="permissionId"
          show-checkbox
          default-expand-all
          :check-strictly="permCheckMode === 'strict'"
          :props="{ label: 'label', children: 'children' }"
        />
        <el-empty v-else description="暂无权限树" :image-size="64" />
      </div>
      <template #footer>
        <el-button @click="permDlg = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!permRole || permRole.roleKey === 'admin'"
          :loading="saving"
          @click="savePerms"
        >
          保存
        </el-button>
      </template>
    </ResizableDrawer>
  </el-card>
</template>

<script setup lang="ts">
import { nextTick, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, Key, SwitchButton } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type ElTree } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import ResizableDrawer from '@/components/ResizableDrawer.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useAuthStore } from '@/stores/auth';
import { buildPermTree, type PermRow } from '@/utils/rbac-tree';
import { sortByPrimaryKey } from '@/utils/sort-by-pk';
import { displayLabel } from '@aicabinet/shared-dict';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

/** 后端 permissions 为展示文案列表，如 ["12 项权限"] */
function permissionCountLabel(row: RoleRow): string {
  const raw = (row.permissions || [])[0];
  if (!raw) return '0';
  const m = String(raw).match(/(\d+)/);
  return m ? m[1] : raw;
}

// 行操作权限判断转 perm 字段（CrudTable 内建过滤）；admin 角色保护逻辑保留行内条件
function rowActions(row: RoleRow): CrudRowAction[] {
  const acts: CrudRowAction[] = [
    { key: 'edit', label: '编辑', icon: EditPen, type: 'primary', perm: 'ops:rbac:role:edit' },
    {
      key: 'perms',
      label: '分配权限',
      icon: Key,
      type: 'success',
      disabled: row.roleKey === 'admin',
      perm: 'ops:rbac:role:perm'
    }
  ];
  if (row.roleKey !== 'admin') {
    const isActive = (row.status || 'ACTIVE') === 'ACTIVE';
    acts.push({
      key: 'toggle',
      label: displayLabel('enable_status', isActive ? 'INACTIVE' : 'ACTIVE'),
      icon: SwitchButton,
      type: isActive ? 'danger' : 'success',
      overflow: true,
      perm: 'ops:rbac:role:edit'
    });
  }
  return acts;
}

function onRowAction({ key, row }: { key: string; row: RoleRow }) {
  if (key === 'edit') openEdit(row);
  else if (key === 'perms') openPerms(row);
  else if (key === 'toggle') void onToggleStatus(row);
}

async function onToggleStatus(row: RoleRow) {
  if (row.roleKey === 'admin') {
    ElMessage.warning('系统管理员角色不可停用');
    return;
  }
  const isActive = (row.status || 'ACTIVE') === 'ACTIVE';
  const next = isActive ? 'INACTIVE' : 'ACTIVE';
  const label = displayLabel('enable_status', next);
  try {
    await ElMessageBox.confirm(`确认${label}角色「${row.roleName}」？`, `${label}角色`, {
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await api.request(AdminEndpoints.rbacRole(row.roleId), 'PUT', {
      roleName: row.roleName,
      remark: row.remark,
      status: next
    });
    ElMessage.success(`已${label}`);
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : `${label}失败`);
  }
}

interface RoleRow {
  roleId: number;
  roleKey: string;
  roleName: string;
  status?: string;
  remark?: string;
  permissions?: string[];
}

const loadingPerms = ref(false);
const saving = ref(false);
const keyword = ref('');
const statusFilter = ref('');
const permTree = ref<PermRow[]>([]);
const formDlg = ref(false);
const permDlg = ref(false);
const permRole = ref<RoleRow | null>(null);
const permCheckMode = ref<'cascade' | 'strict'>('cascade');
const treeRef = ref<InstanceType<typeof ElTree>>();
const form = ref({
  roleId: null as number | null,
  roleKey: '',
  roleName: '',
  remark: '',
  status: 'ACTIVE'
});

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 全部内建
// 后端无分页接口：fetchPage 拉全量后按关键词/状态过滤，按角色编号排序并前端切片
const crud = useCrudTable<RoleRow>({
  rowKey: (r) => r.roleId,
  // 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由 onMounted 显式首查
  autoLoad: false,
  fetchPage: async ({ page, size, sortDir }) => {
    const all = await api.request<RoleRow[]>(AdminEndpoints.rbacRoles, 'GET');
    const q = keyword.value.trim().toLowerCase();
    const rows = (all || []).filter((row) => {
      if (statusFilter.value && (row.status || 'ACTIVE') !== statusFilter.value) return false;
      if (!q) return true;
      return [row.roleId, row.roleName, row.roleKey, row.remark].some((x) =>
        String(x || '')
          .toLowerCase()
          .includes(q)
      );
    });
    const sorted = sortByPrimaryKey(rows, 'roleId', sortDir ?? 'asc');
    const start = page * size; // page 为 0 起（useCrudTable 已换算）
    return { items: sorted.slice(start, start + size), total: sorted.length };
  },
  // 角色编号排序（替代原 useIdColumnSort 表头排序，改由壳内「按角色编号 升/降序」切换）；
  // 用 server 模式让方向切换走重查，避免 local 模式只重排当前页切片
  sort: { prop: 'roleId', mode: 'server', defaultDir: 'asc' }
});

const statusByLabel: Record<string, string> = {
  正常: 'ACTIVE',
  停用: 'INACTIVE',
  ACTIVE: 'ACTIVE',
  INACTIVE: 'INACTIVE'
};

// 导出/导入移入 CrudTable 内建工具条；勾选行时仅导出选中（原 pickSelected 语义）
const csvOptions: CrudCsvOptions = {
  filePrefix: '角色',
  exportPerm: 'ops:rbac:role:export',
  importPerm: 'ops:rbac:role:import',
  headers: ['角色ID', '角色名称', '权限字符', '状态', '权限数', '备注'],
  templateSample: [
    '',
    '示例角色',
    'ops_demo',
    displayLabel('merchant_status', 'ACTIVE'),
    '',
    '备注'
  ],
  toRows: (rows) =>
    rows.map((row) => [
      row.roleId,
      row.roleName,
      row.roleKey,
      displayLabel('merchant_status', row.status || 'ACTIVE'),
      permissionCountLabel(row),
      row.remark || ''
    ]),
  onImportRows: async (rows) => {
    let ok = 0;
    for (const row of rows) {
      const roleKey = (row['权限字符'] || row.roleKey || '').trim();
      const roleName = (row['角色名称'] || row.roleName || '').trim();
      if (!roleKey || !roleName) continue;
      await api.request(AdminEndpoints.rbacRoles, 'POST', {
        roleKey,
        roleName,
        remark: (row['备注'] || row.remark || '').trim(),
        status: statusByLabel[row['状态'] || row.status] || 'ACTIVE'
      });
      ok++;
    }
    await crud.load(); // load 内部会清空勾选（原 clearSelection + loadRoles）
    return ok;
  }
};

async function loadPermTree() {
  const flat = await api.request<PermRow[]>(AdminEndpoints.rbacPermissions, 'GET');
  permTree.value = buildPermTree(flat);
}

function openCreate() {
  form.value = { roleId: null, roleKey: '', roleName: '', remark: '', status: 'ACTIVE' };
  formDlg.value = true;
}

function openEdit(row: RoleRow) {
  form.value = {
    roleId: row.roleId,
    roleKey: row.roleKey,
    roleName: row.roleName,
    remark: row.remark || '',
    status: row.status || 'ACTIVE'
  };
  formDlg.value = true;
}

async function saveRole() {
  const f = form.value;
  if (!f.roleName.trim()) return ElMessage.warning('请填写角色名称');
  if (!f.roleId && !f.roleKey.trim()) return ElMessage.warning('请填写权限字符');
  saving.value = true;
  try {
    if (f.roleId) {
      await api.request(AdminEndpoints.rbacRole(f.roleId), 'PUT', {
        roleName: f.roleName.trim(),
        remark: f.remark,
        status: f.status
      });
      ElMessage.success('角色已更新');
    } else {
      await api.request(AdminEndpoints.rbacRoles, 'POST', {
        roleKey: f.roleKey.trim(),
        roleName: f.roleName.trim(),
        remark: f.remark,
        status: f.status
      });
      ElMessage.success('角色已创建');
    }
    formDlg.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function treeApi() {
  const tree = treeRef.value as InstanceType<typeof ElTree> | undefined;
  return tree && typeof tree.setCheckedKeys === 'function' ? tree : null;
}

function collectPermIds(nodes: PermRow[]): number[] {
  const out: number[] = [];
  const walk = (list: PermRow[]) => {
    for (const n of list) {
      out.push(n.permissionId);
      if (n.children?.length) walk(n.children);
    }
  };
  walk(nodes);
  return out;
}

/** cascade 模式下勿把目录(M)写入 checkedKeys，否则会整棵子树显示「全勾」（OBS-008） */
function nonDirectoryCheckedIds(ids: number[]): number[] {
  const byId = new Map<number, PermRow>();
  const index = (nodes: PermRow[]) => {
    for (const n of nodes) {
      byId.set(n.permissionId, n);
      if (n.children?.length) index(n.children);
    }
  };
  index(permTree.value);
  return ids.filter((id) => {
    const n = byId.get(id);
    return !n || n.permType !== 'M';
  });
}

function selectAllPerms() {
  treeApi()?.setCheckedKeys(collectPermIds(permTree.value), false);
}

function clearAllPerms() {
  treeApi()?.setCheckedKeys([], false);
}

async function openPerms(row: RoleRow) {
  permRole.value = row;
  permCheckMode.value = 'cascade';
  permDlg.value = true;
  loadingPerms.value = true;
  await nextTick();
  treeApi()?.setCheckedKeys([], false);
  try {
    if (!permTree.value.length) await loadPermTree();
    const data = await api.request<{ permissionIds: number[] }>(
      AdminEndpoints.rbacRolePermissions(row.roleId),
      'GET'
    );
    await nextTick();
    treeApi()?.setCheckedKeys(nonDirectoryCheckedIds(data.permissionIds || []), false);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载权限失败');
    treeApi()?.setCheckedKeys([], false);
  } finally {
    loadingPerms.value = false;
  }
}

async function savePerms() {
  if (!permRole.value || permRole.value.roleKey === 'admin') return;
  const tree = treeApi();
  if (!tree) return ElMessage.warning('权限树未就绪');
  saving.value = true;
  try {
    const checked = (tree.getCheckedKeys(false) || []) as number[];
    const half =
      permCheckMode.value === 'cascade' ? ((tree.getHalfCheckedKeys() || []) as number[]) : [];
    await api.request(AdminEndpoints.rbacRolePermissions(permRole.value.roleId), 'PUT', [
      ...new Set([...checked, ...half])
    ]);
    ElMessage.success('权限已保存');
    permDlg.value = false;
    await Promise.all([crud.load(), auth.refreshPermissions()]);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (statusFilter.value) query.status = statusFilter.value;
  router.replace({ query });
}

function search() {
  syncRouteQuery();
  void crud.search();
}

function resetFilters() {
  keyword.value = '';
  statusFilter.value = '';
  syncRouteQuery();
  void crud.search();
}

function applyRouteQuery() {
  let changed = false;
  const qKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (qKeyword !== keyword.value) {
    keyword.value = qKeyword;
    changed = true;
  }
  const qStatus = typeof route.query.status === 'string' ? route.query.status : '';
  if (qStatus !== statusFilter.value) {
    statusFilter.value = qStatus;
    changed = true;
  }
  return changed;
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  await crud.search(); // 路由参数变化时重查（原为 computed 即时过滤，现由状态机拉取）
}

watch(
  () => [route.query.keyword, route.query.status] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(async () => {
  applyRouteQuery();
  await crud.load(); // 显式首查：autoLoad 已关闭，需先应用路由查询参数
  loadPermTree().catch(() => undefined);
});
onActivated(() => {
  void reloadFromRouteQuery();
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
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: var(--admin-font-size-title);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.perm-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.perm-toolbar__actions {
  display: flex;
  gap: 4px;
}
.perm-tree-wrap {
  min-height: 120px;
  max-height: calc(100vh - 260px);
  overflow: auto;
}
</style>
