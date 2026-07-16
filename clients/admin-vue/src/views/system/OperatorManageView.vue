<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">运营账号</span>
        <div class="actions">
          <el-button @click="onExport">导出</el-button>
          <el-button v-if="auth.hasPerm('ops:rbac:assign:add')" type="primary" @click="openCreate">新增账号</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="filter-bar">
      <el-form inline @submit.prevent="search">
        <el-form-item label="手机号">
          <el-input v-model="phone" clearable placeholder="模糊查询" style="width: 180px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 900px">
        <el-table v-loading="loading" :data="operators" stripe border>
      <el-table-column prop="userId" label="用户ID" width="120" />
      <el-table-column prop="name" label="姓名" min-width="100" />
      <el-table-column prop="phoneNumber" label="手机号" width="140" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
            {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ (row.roleNames || []).join('、') || '未分配' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" class-name="col-action" align="center">
        <template #default="{ row }">
          <TableActions :actions="rowActions(row)" :max-primary="2" @action="(k) => onRowAction(k, row)" />
        </template>
      </el-table-column>
        </el-table>
      </div>
    </div>
    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="loadOperators"
      />
    </div>

    <el-dialog v-model="formDlg" :title="form.userId ? '编辑账号' : '新增账号'" width="460px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="手机号" required>
          <el-input v-model="form.phoneNumber" maxlength="11" placeholder="11位手机号" />
        </el-form-item>
        <el-form-item :label="form.userId ? '新密码' : '密码'" :required="!form.userId">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="form.userId ? '不填则不修改' : '至少6位'"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ACTIVE">正常</el-radio>
            <el-radio value="INACTIVE" :disabled="form.userId === Number(auth.userId)">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="!form.userId" label="角色">
          <el-checkbox-group v-model="form.roleIds">
            <el-checkbox v-for="r in activeRoles" :key="r.roleId" :label="r.roleId" style="display: block; margin: 6px 0">
              {{ r.roleName }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDlg" title="分配角色" width="420px" destroy-on-close>
      <el-checkbox-group v-model="roleIds">
        <el-checkbox
          v-for="r in activeRoles"
          :key="r.roleId"
          :label="r.roleId"
          style="display: block; margin: 8px 0"
        >
          {{ r.roleName }}（{{ r.roleKey }}）
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRoles">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="merchantDlg" title="商户范围" width="520px" destroy-on-close>
      <el-checkbox-group v-model="merchantIds" class="merchant-group">
        <el-checkbox v-for="m in merchants" :key="m.merchantId" :label="m.merchantId">
          {{ m.merchantName }}（{{ m.merchantId }}）
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="merchantDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMerchants">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Delete, EditPen, Key, OfficeBuilding, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import type { PageResult } from '@aicabinet/shared-types';

const auth = useAuthStore();

interface RoleRow {
  roleId: number;
  roleKey: string;
  roleName: string;
  status?: string;
}

interface OperatorRow {
  userId: number;
  phoneNumber?: string;
  name?: string;
  status?: string;
  roleNames?: string[];
  roleIds?: number[];
}

interface MerchantRow {
  merchantId: string;
  merchantName: string;
}

const loading = ref(false);
const saving = ref(false);
const operators = ref<OperatorRow[]>([]);
const roles = ref<RoleRow[]>([]);
const merchants = ref<MerchantRow[]>([]);
const phone = ref('');
const page = ref(1);
const size = 20;
const total = ref(0);
const formDlg = ref(false);
const roleDlg = ref(false);
const merchantDlg = ref(false);
const currentUserId = ref<number | null>(null);
const roleIds = ref<number[]>([]);
const merchantIds = ref<string[]>([]);
const form = ref({
  userId: null as number | null,
  name: '',
  phoneNumber: '',
  password: '',
  status: 'ACTIVE',
  roleIds: [] as number[]
});

const activeRoles = computed(() => roles.value.filter((r) => (r.status || 'ACTIVE') === 'ACTIVE'));

const { onExport } = useListCsv({
  filePrefix: '运营账号',
  headers: ['用户ID', '姓名', '手机号', '状态', '角色'],
  toRows: () =>
    operators.value.map((row) => [
      row.userId,
      row.name,
      row.phoneNumber,
      row.status === 'ACTIVE' ? '正常' : '停用',
      (row.roleNames || []).join('、') || '未分配'
    ])
});

function rowActions(row: OperatorRow): TableAction[] {
  const acts: TableAction[] = [];
  if (auth.hasPerm('ops:rbac:assign:edit')) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (auth.hasPerm('ops:rbac:assign:role')) {
    acts.push({ key: 'roles', label: '分配角色', icon: Key, type: 'success', overflow: true });
  }
  if (auth.hasPerm('ops:rbac:assign:merchant')) {
    acts.push({ key: 'merchants', label: '商户范围', icon: OfficeBuilding, overflow: true });
  }
  if (auth.hasPerm('ops:rbac:assign:disable') && row.status === 'ACTIVE' && row.userId !== Number(auth.userId)) {
    acts.push({ key: 'disable', label: '停用', icon: Delete, type: 'danger', overflow: true });
  }
  return acts;
}

function onRowAction(key: string, row: OperatorRow) {
  if (key === 'edit') openEdit(row);
  else if (key === 'roles') openRoles(row);
  else if (key === 'merchants') openMerchants(row);
  else if (key === 'disable') onDisable(row);
}

async function loadRoles() {
  roles.value = await api.request<RoleRow[]>('/api/v2/ops/admin/rbac/roles', 'GET');
}

async function loadMerchants() {
  try {
    merchants.value = await api.request<MerchantRow[]>('/api/v2/ops/admin/merchants', 'GET');
  } catch {
    merchants.value = [];
  }
}

async function loadOperators() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size) });
    if (phone.value.trim()) q.set('phone', phone.value.trim());
    const data = await api.request<PageResult<OperatorRow>>(`/api/v2/ops/admin/rbac/operators?${q}`, 'GET');
    operators.value = data.items;
    total.value = data.total;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载运营账号失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  loadOperators();
}

function resetFilter() {
  phone.value = '';
  search();
}

function openCreate() {
  form.value = { userId: null, name: '', phoneNumber: '', password: '', status: 'ACTIVE', roleIds: [] };
  formDlg.value = true;
}

function openEdit(row: OperatorRow) {
  form.value = {
    userId: row.userId,
    name: row.name || '',
    phoneNumber: row.phoneNumber || '',
    password: '',
    status: row.status || 'ACTIVE',
    roleIds: []
  };
  formDlg.value = true;
}

async function saveForm() {
  const f = form.value;
  if (!f.name.trim()) return ElMessage.warning('请填写姓名');
  if (!/^1\d{10}$/.test(f.phoneNumber.trim())) return ElMessage.warning('请填写正确手机号');
  if (!f.userId && (!f.password || f.password.length < 6)) return ElMessage.warning('密码至少6位');
  saving.value = true;
  try {
    if (f.userId) {
      await api.request(`/api/v2/ops/admin/rbac/operators/${f.userId}`, 'PUT', {
        name: f.name.trim(),
        phoneNumber: f.phoneNumber.trim(),
        password: f.password || null,
        status: f.status
      });
      ElMessage.success('已更新');
    } else {
      await api.request('/api/v2/ops/admin/rbac/operators', 'POST', {
        name: f.name.trim(),
        phoneNumber: f.phoneNumber.trim(),
        password: f.password,
        status: f.status,
        roleIds: f.roleIds
      });
      ElMessage.success('已创建');
    }
    formDlg.value = false;
    await loadOperators();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function onDisable(row: OperatorRow) {
  await ElMessageBox.confirm(`确认停用账号「${row.name || row.phoneNumber}」？`, '停用账号', { type: 'warning' });
  try {
    await api.request(`/api/v2/ops/admin/rbac/operators/${row.userId}`, 'DELETE');
    ElMessage.success('已停用');
    await loadOperators();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '停用失败');
  }
}

function openRoles(row: OperatorRow) {
  currentUserId.value = row.userId;
  roleIds.value = [...(row.roleIds || [])];
  roleDlg.value = true;
}

async function saveRoles() {
  if (currentUserId.value == null) return;
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/rbac/users/${currentUserId.value}/roles`, 'PUT', roleIds.value);
    ElMessage.success('角色已更新');
    roleDlg.value = false;
    await loadOperators();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function openMerchants(row: OperatorRow) {
  currentUserId.value = row.userId;
  if (!merchants.value.length) await loadMerchants();
  try {
    const data = await api.request<{ merchantIds: string[] }>(
      `/api/v2/ops/admin/rbac/users/${row.userId}/merchants`,
      'GET'
    );
    merchantIds.value = [...(data.merchantIds || [])];
    merchantDlg.value = true;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载商户范围失败');
  }
}

async function saveMerchants() {
  if (currentUserId.value == null) return;
  saving.value = true;
  try {
    await api.request(
      `/api/v2/ops/admin/rbac/users/${currentUserId.value}/merchants`,
      'PUT',
      merchantIds.value
    );
    ElMessage.success('商户范围已更新');
    merchantDlg.value = false;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function reload() {
  await Promise.all([loadRoles(), loadMerchants(), loadOperators()]);
}

onMounted(reload);
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.title { font-weight: 600; }
.actions { display: flex; gap: 8px; }
.merchant-group { display: flex; flex-direction: column; gap: 8px; max-height: 360px; overflow: auto; }
</style>
