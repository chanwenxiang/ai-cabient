<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">运营账号</span>
            <span class="hint">按手机号筛选；可分配角色与商户数据范围（绑定后仅可见对应设备）</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:rbac:assign:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button v-hasPermi="['ops:rbac:assign:import']" @click="onDownloadTemplate(['', '张三', '13900000099', 'Passw0rd', '正常', ''])">导入模板</el-button>
          <el-button v-hasPermi="['ops:rbac:assign:import']" :loading="importing" @click="triggerImport">导入</el-button>
          <input ref="importInput" type="file" accept=".csv,text/csv" class="hidden-input" @change="onImportFile" />
          <el-button v-hasPermi="['ops:rbac:assign:add']" type="primary" @click="openCreate">新增账号</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="手机号">
        <el-input
          v-model="phone"
          clearable
          placeholder="模糊查询"
          style="width: 180px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="operators"
          stripe
          border
          class="report-table"
          row-key="userId"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
        >
          <template #empty><el-empty description="暂无运营账号" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column prop="userId" label="ID" width="100" align="center" class-name="col-text" sortable="custom">
            <template #default="{ row }">
              <span class="cell-id">{{ row.userId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="账号" min-width="120" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.name || row.phoneNumber || '无' }}</template>
          </el-table-column>
          <el-table-column label="手机号" width="140" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.phoneNumber || '无' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="角色" min-width="160" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <template v-if="(row.roleNames || []).length">
                <el-tag
                  v-for="name in row.roleNames"
                  :key="name"
                  size="small"
                  effect="plain"
                  class="role-tag"
                >
                  {{ name }}
                </el-tag>
              </template>
              <span v-else class="muted">未分配</span>
            </template>
          </el-table-column>
          <el-table-column label="数据范围" min-width="180" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <template v-if="(row.merchantNames || row.merchantIds || []).length">
                <el-tag
                  v-for="(name, idx) in (row.merchantNames || row.merchantIds)"
                  :key="(row.merchantIds || [])[idx] || name"
                  size="small"
                  type="warning"
                  effect="plain"
                  class="role-tag"
                >
                  {{ name }}
                </el-tag>
              </template>
              <el-tag v-else size="small" type="success" effect="plain">全局</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" class-name="col-action" align="center" fixed="right">
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
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="loadOperators"
        @size-change="onSizeChange"
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

    <el-dialog v-model="merchantDlg" title="商户范围（设备数据范围）" width="560px" destroy-on-close>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="scope-alert"
        title="数据范围说明"
        description="勾选商户后，该账号仅可见这些商户下的设备及相关订单/补货/看板数据。不勾选任何商户 = 全局可见（admin 角色始终全局）。可再通过「货柜范围」限定到部分柜机。"
      />
      <el-checkbox-group v-model="merchantIds" class="merchant-group">
        <el-checkbox v-for="m in merchants" :key="m.merchantId" :label="m.merchantId">
          {{ m.merchantName }}（{{ m.merchantId }}）
          <span class="muted">· {{ m.deviceCount ?? 0 }} 台设备</span>
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="merchantDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMerchants">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="deviceDlg" title="货柜范围" width="640px" destroy-on-close>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="scope-alert"
        title="人员货柜范围"
        description="全部货柜 = 商户范围内所有柜；部分货柜 = 仅勾选柜机可见（理货员常用）。"
      />
      <el-radio-group v-model="deviceScopeMode" style="margin: 12px 0">
        <el-radio value="ALL">全部货柜</el-radio>
        <el-radio value="PARTIAL">部分货柜</el-radio>
      </el-radio-group>
      <el-checkbox-group v-if="deviceScopeMode === 'PARTIAL'" v-model="deviceIds" class="merchant-group">
        <el-checkbox v-for="d in allDevices" :key="d.deviceId" :label="d.deviceId">
          {{ d.deviceName || d.deviceId }}（{{ d.deviceId }}）
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="deviceDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDevices">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Delete, EditPen, Key, Monitor, OfficeBuilding, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import type { PageResult } from '@aicabinet/shared-types';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

const route = useRoute();
const router = useRouter();
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
  merchantIds?: string[];
  merchantNames?: string[];
}

interface MerchantRow {
  merchantId: string;
  merchantName: string;
  deviceCount?: number;
}

const loading = ref(false);
const saving = ref(false);
const operators = ref<OperatorRow[]>([]);
const roles = ref<RoleRow[]>([]);
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('userId', {
  onChange: () => {
    operators.value = sortById([...operators.value], 'userId');
  }
});
const merchants = ref<MerchantRow[]>([]);
const phone = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const formDlg = ref(false);
const roleDlg = ref(false);
const merchantDlg = ref(false);
const deviceDlg = ref(false);
const merchantIds = ref<string[]>([]);
const deviceIds = ref<string[]>([]);
const deviceScopeMode = ref('ALL');
const allDevices = ref<{ deviceId: string; deviceName?: string }[]>([]);
const currentUserId = ref<number | null>(null);
const roleIds = ref<number[]>([]);
const form = ref({
  userId: null as number | null,
  name: '',
  phoneNumber: '',
  password: '',
  status: 'ACTIVE',
  roleIds: [] as number[]
});

const activeRoles = computed(() => roles.value.filter((r) => (r.status || 'ACTIVE') === 'ACTIVE'));

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<OperatorRow>((r) => r.userId);

const statusByLabel: Record<string, string> = {
  正常: 'ACTIVE',
  停用: 'INACTIVE',
  ACTIVE: 'ACTIVE',
  INACTIVE: 'INACTIVE'
};

const { importing, importInput, onExport, onDownloadTemplate, triggerImport, onImportFile } = useListCsv({
  filePrefix: '运营账号',
  headers: ['用户ID', '姓名', '手机号', '密码', '状态', '角色', '数据范围'],
  toRows: () =>
    pickSelected(operators.value).map((row) => [
      row.userId,
      row.name,
      row.phoneNumber,
      '',
      row.status === 'ACTIVE' ? '正常' : '停用',
      (row.roleNames || []).join('、') || '未分配',
      (row.merchantNames || row.merchantIds || []).length
        ? (row.merchantNames || row.merchantIds || []).join('、')
        : '全局'
    ]),
  onImportRows: async (rows) => {
    let ok = 0;
    const roleByName = new Map(activeRoles.value.map((r) => [r.roleName, r.roleId]));
    for (const row of rows) {
      const name = (row['姓名'] || row.name || '').trim();
      const phoneNumber = (row['手机号'] || row.phoneNumber || '').trim();
      const password = (row['密码'] || row.password || '').trim();
      if (!name || !/^1\d{10}$/.test(phoneNumber) || password.length < 6) continue;
      const roleLabel = (row['角色'] || row.roleNames || '').trim();
      const roleIds = roleLabel
        ? roleLabel
            .split(/[,，、]/)
            .map((s) => s.trim())
            .filter(Boolean)
            .map((n) => roleByName.get(n))
            .filter((id): id is number => id != null)
        : [];
      await api.request('/api/v2/ops/admin/rbac/operators', 'POST', {
        name,
        phoneNumber,
        password,
        status: statusByLabel[row['状态'] || row.status] || 'ACTIVE',
        roleIds
      });
      ok++;
    }
    clearSelection();
    await loadOperators();
    return ok;
  }
});

function rowActions(row: OperatorRow): TableAction[] {
  const acts: TableAction[] = [];
  if (auth.hasPerm('ops:rbac:assign:edit')) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (auth.hasPerm('ops:rbac:assign:role')) {
    acts.push({ key: 'roles', label: '分配角色', icon: Key, type: 'success' });
  }
  if (auth.hasPerm('ops:rbac:assign:merchant') || auth.hasPerm('ops:rbac:assign:device') || auth.hasPerm('ops:rbac:assign')) {
    acts.push({ key: 'merchants', label: '商户范围', icon: OfficeBuilding, overflow: true });
    acts.push({ key: 'devices', label: '货柜范围', icon: Monitor, overflow: true });
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
  else if (key === 'devices') openDevices(row);
  else if (key === 'disable') onDisable(row);
}

async function loadRoles() {
  try {
    roles.value = await api.request<RoleRow[]>('/api/v2/ops/admin/rbac/roles', 'GET');
  } catch (e) {
    roles.value = [];
    ElMessage.error(e instanceof Error ? e.message : '加载角色失败');
  }
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
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
    if (phone.value.trim()) q.set('phone', phone.value.trim());
    const data = await api.request<PageResult<OperatorRow>>(`/api/v2/ops/admin/rbac/operators?${q}`, 'GET');
    operators.value = sortById(data.items || [], 'userId');
    total.value = data.total;
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载运营账号失败');
  } finally {
    loading.value = false;
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (phone.value.trim()) query.phone = phone.value.trim();
  router.replace({ query });
}

function applyRouteQuery() {
  const qPhone = typeof route.query.phone === 'string' ? route.query.phone : '';
  if (qPhone !== phone.value) {
    phone.value = qPhone;
    return true;
  }
  return false;
}

function search() {
  page.value = 1;
  syncRouteQuery();
  loadOperators();
}

function resetFilter() {
  phone.value = '';
  page.value = 1;
  syncRouteQuery();
  loadOperators();
}

function onSizeChange() {
  page.value = 1;
  loadOperators();
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
  try {
    await ElMessageBox.confirm(`确认停用账号「${row.name || row.phoneNumber}」？`, '停用账号', { type: 'warning' });
    await api.request(`/api/v2/ops/admin/rbac/operators/${row.userId}`, 'DELETE');
    ElMessage.success('已停用');
    await loadOperators();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '停用失败');
    }
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
    const tasks: Promise<unknown>[] = [loadOperators()];
    if (String(currentUserId.value) === String(auth.userId)) {
      tasks.push(auth.refreshPermissions());
    }
    await Promise.all(tasks);
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
    await loadOperators();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function openDevices(row: OperatorRow) {
  currentUserId.value = row.userId;
  try {
    if (!allDevices.value.length) {
      const list = await api.request<{ items: any[] }>('/api/v2/ops/admin/devices?page=0&size=200', 'GET');
      allDevices.value = (list.items || []).map((d: any) => ({ deviceId: d.deviceId, deviceName: d.deviceName }));
    }
    const data = await api.request<{ scopeMode: string; deviceIds: string[] }>(
      `/api/v2/ops/admin/rbac/users/${row.userId}/devices`,
      'GET'
    );
    deviceScopeMode.value = data.scopeMode || 'ALL';
    deviceIds.value = [...(data.deviceIds || [])];
    deviceDlg.value = true;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载货柜范围失败');
  }
}

async function saveDevices() {
  if (currentUserId.value == null) return;
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/rbac/users/${currentUserId.value}/devices`, 'PUT', {
      userId: currentUserId.value,
      scopeMode: deviceScopeMode.value,
      deviceIds: deviceScopeMode.value === 'PARTIAL' ? deviceIds.value : []
    });
    ElMessage.success('货柜范围已更新');
    deviceDlg.value = false;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function reload() {
  await Promise.all([loadRoles(), loadMerchants(), loadOperators()]);
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await loadOperators();
}

watch(
  () => route.query.phone,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  reload();
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
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; flex-wrap: wrap; }
.role-tag { margin: 0 4px 4px 0; }
.muted { color: var(--el-text-color-secondary); }
.scope-alert { margin-bottom: 12px; }
.hidden-input { display: none; }
.merchant-group { display: flex; flex-direction: column; gap: 8px; max-height: 360px; overflow: auto; }
</style>
