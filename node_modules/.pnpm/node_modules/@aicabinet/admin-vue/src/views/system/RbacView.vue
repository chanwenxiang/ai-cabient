<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span>权限管理</span>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </div>
    </template>
    <el-tabs v-model="tab">
      <el-tab-pane label="角色权限" name="roles">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-table :data="roles" highlight-current-row height="520" @current-change="onRole">
              <el-table-column prop="roleName" label="角色" />
              <el-table-column prop="roleKey" label="标识" width="110"><template #default="{ row }"><code>{{ row.roleKey }}</code></template></el-table-column>
              <el-table-column label="权限" width="80"><template #default="{ row }">{{ (row.permissions || [])[0] || '-' }}</template></el-table-column>
            </el-table>
          </el-col>
          <el-col :span="16">
            <div class="perm-toolbar">
              <strong>{{ currentRole?.roleName || '选择角色' }}</strong>
              <el-button type="primary" size="small" :disabled="!currentRole" :loading="saving" @click="saveRolePerms">保存权限</el-button>
            </div>
            <el-tree
              v-loading="loadingPerms"
              ref="treeRef"
              :data="permTree"
              node-key="permissionId"
              show-checkbox
              default-expand-all
              :props="{ label: 'permName', children: 'children' }"
              style="max-height:480px; overflow:auto"
            />
          </el-col>
        </el-row>
      </el-tab-pane>
      <el-tab-pane label="用户授权" name="users">
        <el-form inline @submit.prevent="searchOps">
          <el-form-item label="手机号"><el-input v-model="phone" clearable placeholder="模糊查询" /></el-form-item>
          <el-form-item><el-button type="primary" @click="searchOps">查询</el-button></el-form-item>
        </el-form>
        <el-table v-loading="loadingOps" :data="operators" stripe>
          <el-table-column prop="userId" label="用户ID" width="100" />
          <el-table-column prop="name" label="姓名" />
          <el-table-column prop="phoneNumber" label="手机号" />
          <el-table-column label="角色" min-width="180">
            <template #default="{ row }">{{ (row.roleNames || []).join('、') || '未分配' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }"><el-button link type="primary" @click="openAssign(row)">授权</el-button></template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="prev,pager,next" style="margin-top:12px" @current-change="loadOperators" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="assignDlg" title="分配角色" width="420px">
      <el-checkbox-group v-model="assignRoleIds">
        <el-checkbox v-for="r in roles" :key="r.roleId" :label="r.roleId" style="display:block;margin:8px 0">
          {{ r.roleName }}（{{ r.roleKey }}）
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="assignDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAssign">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, type ElTree } from 'element-plus';
import { api } from '@/api/client';
import type { PageResult } from '@aicabinet/shared-types';

interface RoleRow {
  roleId: number;
  roleKey: string;
  roleName: string;
  permissions?: string[];
}

interface PermRow {
  permissionId: number;
  parentId: number;
  permCode: string;
  permName: string;
  permType: string;
  children?: PermRow[];
}

interface OperatorRow {
  userId: number;
  phoneNumber?: string;
  name?: string;
  roleNames?: string[];
  roleIds?: number[];
}

const tab = ref('roles');
const loading = ref(false);
const loadingPerms = ref(false);
const loadingOps = ref(false);
const saving = ref(false);
const roles = ref<RoleRow[]>([]);
const permissions = ref<PermRow[]>([]);
const permTree = ref<PermRow[]>([]);
const currentRole = ref<RoleRow | null>(null);
const treeRef = ref<InstanceType<typeof ElTree>>();
const operators = ref<OperatorRow[]>([]);
const phone = ref('');
const page = ref(1);
const size = 20;
const total = ref(0);
const assignDlg = ref(false);
const assignUserId = ref<number | null>(null);
const assignRoleIds = ref<number[]>([]);

function buildTree(flat: PermRow[]): PermRow[] {
  const map = new Map<number, PermRow>();
  flat.forEach((p) => map.set(p.permissionId, { ...p, children: [] }));
  const roots: PermRow[] = [];
  map.forEach((node) => {
    if (node.parentId && map.has(node.parentId)) {
      map.get(node.parentId)!.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  return roots;
}

async function loadRoles() {
  roles.value = await api.request<RoleRow[]>('/api/v2/ops/admin/rbac/roles', 'GET');
}

async function loadPermissions() {
  permissions.value = await api.request<PermRow[]>('/api/v2/ops/admin/rbac/permissions', 'GET');
  permTree.value = buildTree(permissions.value);
}

async function onRole(row: RoleRow | null) {
  currentRole.value = row;
  if (!row) return;
  loadingPerms.value = true;
  try {
    const data = await api.request<{ permissionIds: number[] }>(`/api/v2/ops/admin/rbac/roles/${row.roleId}/permissions`, 'GET');
    await nextTick();
    treeRef.value?.setCheckedKeys(data.permissionIds || [], false);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载角色权限失败');
  } finally {
    loadingPerms.value = false;
  }
}

async function saveRolePerms() {
  if (!currentRole.value) return;
  saving.value = true;
  try {
    const checked = (treeRef.value?.getCheckedKeys(false) || []) as number[];
    const half = (treeRef.value?.getHalfCheckedKeys() || []) as number[];
    await api.request(`/api/v2/ops/admin/rbac/roles/${currentRole.value.roleId}/permissions`, 'PUT', [...checked, ...half]);
    ElMessage.success('角色权限已保存');
    await loadRoles();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function loadOperators() {
  loadingOps.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size) });
    if (phone.value.trim()) q.set('phone', phone.value.trim());
    const data = await api.request<PageResult<OperatorRow>>(`/api/v2/ops/admin/rbac/operators?${q}`, 'GET');
    operators.value = data.items;
    total.value = data.total;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载运营账号失败');
  } finally {
    loadingOps.value = false;
  }
}

function searchOps() {
  page.value = 1;
  loadOperators();
}

function openAssign(row: OperatorRow) {
  assignUserId.value = row.userId;
  assignRoleIds.value = [...(row.roleIds || [])];
  assignDlg.value = true;
}

async function saveAssign() {
  if (assignUserId.value == null) return;
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/rbac/users/${assignUserId.value}/roles`, 'PUT', assignRoleIds.value);
    ElMessage.success('用户角色已更新');
    assignDlg.value = false;
    await loadOperators();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function reload() {
  loading.value = true;
  try {
    await Promise.all([loadRoles(), loadPermissions(), loadOperators()]);
    if (roles.value.length && !currentRole.value) await onRole(roles.value[0]);
  } finally {
    loading.value = false;
  }
}

onMounted(reload);
</script>

<style scoped>
.card-head, .perm-toolbar { display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; gap:8px; }
code { font-size:12px; }
</style>
