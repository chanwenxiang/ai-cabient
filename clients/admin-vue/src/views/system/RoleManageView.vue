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
          <el-button v-hasPermi="['ops:rbac:role:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button
            v-hasPermi="['ops:rbac:role:import']"
            @click="onDownloadTemplate(['', '示例角色', 'ops_demo', '正常', '', '备注'])"
            >导入模板</el-button
          >
          <el-button
            v-hasPermi="['ops:rbac:role:import']"
            :loading="importing"
            @click="triggerImport"
            >导入</el-button
          >
          <input
            ref="importInput"
            type="file"
            accept=".csv,text/csv"
            class="hidden-input"
            @change="onImportFile"
          />
          <el-button :icon="Refresh" :loading="loading" @click="loadRoles">刷新</el-button>
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
          <el-option label="正常" value="ACTIVE" />
          <el-option label="停用" value="INACTIVE" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="filteredRoles"
          stripe
          border
          class="report-table"
          row-key="roleId"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty
            ><el-empty v-if="listHydrated && !loading" description="暂无角色"
          /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="roleId"
            label="角色编号"
            width="80"
            align="center"
            class-name="col-text"
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.roleId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="角色" min-width="140" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.roleName || row.roleKey || '无' }}</template>
          </el-table-column>
          <el-table-column label="权限字符" min-width="140" align="center" class-name="col-text">
            <template #default="{ row }"
              ><span class="cell-id">{{ row.roleKey }}</span></template
            >
          </el-table-column>
          <el-table-column label="状态" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="权限数" width="96" align="center">
            <template #default="{ row }">{{ permissionCountLabel(row) }}</template>
          </el-table-column>
          <el-table-column
            prop="remark"
            label="备注"
            min-width="160"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.remark || '无' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions :actions="roleActions(row)" @action="(k) => onRoleAction(k, row)" />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog
      v-model="formDlg"
      :title="form.roleId ? '编辑角色' : '新增角色'"
      width="480px"
      destroy-on-close
    >
      <el-form label-width="88px">
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

    <el-drawer
      v-model="permDlg"
      :title="`分配权限 · ${permRole?.roleName || ''}`"
      size="420px"
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
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, Key, Refresh } from '@element-plus/icons-vue';
import { ElMessage, type ElTree } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { buildPermTree, type PermRow } from '@/utils/rbac-tree';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('roleId');

/** 后端 permissions 为展示文案列表，如 ["12 项权限"] */
function permissionCountLabel(row: RoleRow): string {
  const raw = (row.permissions || [])[0];
  if (!raw) return '0';
  const m = String(raw).match(/(\d+)/);
  return m ? m[1] : raw;
}

function roleActions(row: RoleRow): TableAction[] {
  const acts: TableAction[] = [];
  if (auth.hasPerm('ops:rbac:role:edit')) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (auth.hasPerm('ops:rbac:role:perm')) {
    acts.push({
      key: 'perms',
      label: '分配权限',
      icon: Key,
      type: 'success',
      disabled: row.roleKey === 'admin'
    });
  }
  return acts;
}

function onRoleAction(key: string, row: RoleRow) {
  if (key === 'edit') openEdit(row);
  else if (key === 'perms') openPerms(row);
}

interface RoleRow {
  roleId: number;
  roleKey: string;
  roleName: string;
  status?: string;
  remark?: string;
  permissions?: string[];
}

const loading = ref(false);
const listHydrated = ref(false);
const loadingPerms = ref(false);
const saving = ref(false);
const roles = ref<RoleRow[]>([]);
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

const filteredRoles = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  const rows = roles.value.filter((row) => {
    if (statusFilter.value && (row.status || 'ACTIVE') !== statusFilter.value) return false;
    if (!q) return true;
    return [row.roleId, row.roleName, row.roleKey, row.remark].some((x) =>
      String(x || '')
        .toLowerCase()
        .includes(q)
    );
  });
  return sortById(rows);
});

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<RoleRow>((r) => r.roleId);

const statusByLabel: Record<string, string> = {
  正常: 'ACTIVE',
  停用: 'INACTIVE',
  ACTIVE: 'ACTIVE',
  INACTIVE: 'INACTIVE'
};

const { importing, importInput, onExport, onDownloadTemplate, triggerImport, onImportFile } =
  useListCsv({
    filePrefix: '角色',
    headers: ['角色ID', '角色名称', '权限字符', '状态', '权限数', '备注'],
    toRows: () =>
      pickSelected(filteredRoles.value).map((row) => [
        row.roleId,
        row.roleName,
        row.roleKey,
        row.status === 'ACTIVE' ? '正常' : '停用',
        permissionCountLabel(row),
        row.remark || ''
      ]),
    onImportRows: async (rows) => {
      let ok = 0;
      for (const row of rows) {
        const roleKey = (row['权限字符'] || row.roleKey || '').trim();
        const roleName = (row['角色名称'] || row.roleName || '').trim();
        if (!roleKey || !roleName) continue;
        await api.request('/api/v2/ops/admin/rbac/roles', 'POST', {
          roleKey,
          roleName,
          remark: (row['备注'] || row.remark || '').trim(),
          status: statusByLabel[row['状态'] || row.status] || 'ACTIVE'
        });
        ok++;
      }
      clearSelection();
      await loadRoles();
      return ok;
    }
  });

async function loadRoles() {
  loading.value = true;
  try {
    roles.value = await api.request<RoleRow[]>('/api/v2/ops/admin/rbac/roles', 'GET');
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载角色失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

async function loadPermTree() {
  const flat = await api.request<PermRow[]>('/api/v2/ops/admin/rbac/permissions', 'GET');
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
      await api.request(`/api/v2/ops/admin/rbac/roles/${f.roleId}`, 'PUT', {
        roleName: f.roleName.trim(),
        remark: f.remark,
        status: f.status
      });
      ElMessage.success('角色已更新');
    } else {
      await api.request('/api/v2/ops/admin/rbac/roles', 'POST', {
        roleKey: f.roleKey.trim(),
        roleName: f.roleName.trim(),
        remark: f.remark,
        status: f.status
      });
      ElMessage.success('角色已创建');
    }
    formDlg.value = false;
    await loadRoles();
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
      `/api/v2/ops/admin/rbac/roles/${row.roleId}/permissions`,
      'GET'
    );
    await nextTick();
    treeApi()?.setCheckedKeys(data.permissionIds || [], false);
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
    await api.request(`/api/v2/ops/admin/rbac/roles/${permRole.value.roleId}/permissions`, 'PUT', [
      ...new Set([...checked, ...half])
    ]);
    ElMessage.success('权限已保存');
    permDlg.value = false;
    await Promise.all([loadRoles(), auth.refreshPermissions()]);
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
}

function resetFilters() {
  keyword.value = '';
  statusFilter.value = '';
  syncRouteQuery();
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
}

watch(
  () => [route.query.keyword, route.query.status] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(async () => {
  applyRouteQuery();
  await loadRoles();
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
  font-size: 15px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.hidden-input {
  display: none;
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
