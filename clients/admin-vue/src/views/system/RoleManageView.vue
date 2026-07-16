<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">角色管理</span>
        <div class="actions">
          <el-button v-if="auth.hasPerm('ops:rbac:role:add')" type="primary" @click="openCreate">新增角色</el-button>
          <el-button @click="onExport">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="loadRoles">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 900px">
        <el-table v-loading="loading" :data="roles" stripe border>
      <el-table-column prop="roleId" label="角色ID" width="90" />
      <el-table-column prop="roleName" label="角色名称" min-width="120" />
      <el-table-column prop="roleKey" label="权限字符" width="140">
        <template #default="{ row }"><code>{{ row.roleKey }}</code></template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
            {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="权限数" width="90">
        <template #default="{ row }">{{ (row.permissions || [])[0] || '-' }}</template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column label="操作" width="88" class-name="col-action" align="center">
        <template #default="{ row }">
          <TableActions :actions="roleActions(row)" :max-primary="1" @action="(k) => onRoleAction(k, row)" />
        </template>
      </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="formDlg" :title="form.roleId ? '编辑角色' : '新增角色'" width="480px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="权限字符" required>
          <el-input v-model="form.roleKey" :disabled="!!form.roleId" placeholder="如 ops_custom" maxlength="64" />
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

    <el-drawer v-model="permDlg" :title="`分配权限 · ${permRole?.roleName || ''}`" size="420px" destroy-on-close>
      <el-alert
        v-if="permRole?.roleKey === 'admin'"
        type="info"
        :closable="false"
        show-icon
        title="超级管理员拥有全部权限，不可修改"
        style="margin-bottom: 12px"
      />
      <div v-loading="loadingPerms" class="perm-tree-wrap">
        <el-tree
          ref="treeRef"
          :data="permTree"
          node-key="permissionId"
          show-checkbox
          default-expand-all
          :props="{ label: 'label', children: 'children' }"
        />
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
import { nextTick, onMounted, ref } from 'vue';
import { EditPen, Key, Refresh } from '@element-plus/icons-vue';
import { ElMessage, type ElTree } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import { buildPermTree, type PermRow } from '@/utils/rbac-tree';

const auth = useAuthStore();

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
      overflow: true,
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
const loadingPerms = ref(false);
const saving = ref(false);
const roles = ref<RoleRow[]>([]);
const permTree = ref<PermRow[]>([]);
const formDlg = ref(false);
const permDlg = ref(false);
const permRole = ref<RoleRow | null>(null);
const treeRef = ref<InstanceType<typeof ElTree>>();
const form = ref({
  roleId: null as number | null,
  roleKey: '',
  roleName: '',
  remark: '',
  status: 'ACTIVE'
});

const { onExport } = useListCsv({
  filePrefix: '角色',
  headers: ['角色ID', '角色名称', '权限字符', '状态', '权限数', '备注'],
  toRows: () =>
    roles.value.map((row) => [
      row.roleId,
      row.roleName,
      row.roleKey,
      row.status === 'ACTIVE' ? '正常' : '停用',
      (row.permissions || [])[0] || '-',
      row.remark || ''
    ])
});

async function loadRoles() {
  loading.value = true;
  try {
    roles.value = await api.request<RoleRow[]>('/api/v2/ops/admin/rbac/roles', 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载角色失败');
  } finally {
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

async function openPerms(row: RoleRow) {
  permRole.value = row;
  permDlg.value = true;
  loadingPerms.value = true;
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
    const half = (tree.getHalfCheckedKeys() || []) as number[];
    await api.request(
      `/api/v2/ops/admin/rbac/roles/${permRole.value.roleId}/permissions`,
      'PUT',
      [...checked, ...half]
    );
    ElMessage.success('权限已保存');
    permDlg.value = false;
    await loadRoles();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

onMounted(async () => {
  await loadRoles();
  loadPermTree().catch(() => undefined);
});
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.title { font-weight: 600; }
.actions { display: flex; gap: 8px; }
.perm-tree-wrap { max-height: calc(100vh - 220px); overflow: auto; }
code { font-size: 12px; }
</style>
