<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">部门管理</span>
            <span class="hint"
              >组织树 + 成员；审批可按部门指派。交易数据范围仍在「运营账号」里配商户/货柜</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-hasPermi="['ops:dept:edit']"
            :disabled="!selected.length"
            @click="batchSetStatus('ACTIVE')"
            >批量启用</el-button
          >
          <el-button
            v-hasPermi="['ops:dept:edit']"
            :disabled="!selected.length"
            @click="batchSetStatus('INACTIVE')"
            >批量停用</el-button
          >
          <el-button v-hasPermi="['ops:dept:edit']" type="primary" @click="openDept()"
            >新增部门</el-button
          >
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="rows"
          stripe
          border
          class="report-table"
          row-key="deptId"
          @selection-change="onSelectionChange"
        >
          <template #empty>
            <el-empty v-if="hydrated && !loading" description="暂无部门" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column prop="deptKey" label="编码" width="120" align="center" />
          <el-table-column prop="deptName" label="名称" min-width="120" align="center" />
          <el-table-column label="上级" min-width="120" align="center">
            <template #default="{ row }">{{ parentName(row.parentId) }}</template>
          </el-table-column>
          <el-table-column prop="memberCount" label="成员数" width="90" align="center" />
          <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            prop="remark"
            label="备注"
            min-width="160"
            show-overflow-tooltip
            align="center"
          />
          <el-table-column
            label="操作"
            width="200"
            align="center"
            class-name="col-action"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button v-hasPermi="['ops:dept:edit']" link type="primary" @click="openDept(row)"
                >编辑</el-button
              >
              <el-button
                v-hasPermi="['ops:dept:edit']"
                link
                type="primary"
                @click="openMembers(row)"
              >
                成员
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </el-card>

  <el-dialog
    v-model="deptDlg"
    :title="deptForm.deptId ? '编辑部门' : '新增部门'"
    width="480px"
    destroy-on-close
  >
    <el-form label-width="90px">
      <el-form-item label="编码" required>
        <el-input
          v-model="deptForm.deptKey"
          :disabled="!!deptForm.deptId"
          placeholder="如 FINANCE"
        />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="deptForm.deptName" placeholder="如 财务部" />
      </el-form-item>
      <el-form-item label="上级部门">
        <el-select
          v-model="deptForm.parentId"
          clearable
          placeholder="无（根部门）"
          style="width: 100%"
        >
          <el-option
            v-for="d in parentOptions"
            :key="d.deptId"
            :label="`${d.deptName} (${d.deptKey})`"
            :value="d.deptId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="排序">
        <el-input-number v-model="deptForm.sortOrder" :min="0" :max="9999" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="deptForm.status" style="width: 100%">
          <el-option value="ACTIVE" label="启用" />
          <el-option value="INACTIVE" label="停用" />
        </el-select>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="deptForm.remark" type="textarea" :rows="2" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="deptDlg = false">取消</el-button>
      <el-button type="primary" :loading="savingDept" @click="saveDept">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="memberDlg"
    :title="`部门成员 · ${memberDept?.deptName || ''}`"
    width="720px"
    destroy-on-close
  >
    <div class="cell-hint" style="margin-bottom: 12px">
      左侧勾选运营账号，点中间箭头加入右侧部门成员（可多选）
    </div>
    <div v-loading="operatorLoading" class="member-transfer-wrap">
      <el-transfer
        v-model="memberUserIds"
        filterable
        :data="transferData"
        :titles="['可选账号', '部门成员']"
        :button-texts="['移除', '加入']"
        :props="{ key: 'key', label: 'label' }"
        filter-placeholder="搜索姓名 / 手机号"
      />
    </div>
    <template #footer>
      <el-button @click="memberDlg = false">取消</el-button>
      <el-button type="primary" :loading="savingMembers" @click="saveMembers">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';

interface DeptRow {
  deptId: number;
  deptKey: string;
  deptName: string;
  parentId?: number | null;
  sortOrder: number;
  status: string;
  remark?: string;
  memberCount: number;
}

interface OperatorRow {
  userId: number;
  name?: string;
  phoneNumber?: string;
  status?: string;
}

interface TransferItem {
  key: number;
  label: string;
  disabled?: boolean;
}

const loading = ref(false);
const hydrated = ref(false);
const rows = ref<DeptRow[]>([]);
const selected = ref<DeptRow[]>([]);
const deptDlg = ref(false);
const savingDept = ref(false);
const deptForm = reactive({
  deptId: null as number | null,
  deptKey: '',
  deptName: '',
  parentId: null as number | null,
  sortOrder: 0,
  status: 'ACTIVE',
  remark: ''
});

const parentOptions = computed(() =>
  rows.value.filter((d) => d.deptId !== deptForm.deptId && d.status === 'ACTIVE')
);

function parentName(parentId?: number | null) {
  if (parentId == null) return '—';
  const p = rows.value.find((d) => d.deptId === parentId);
  return p ? p.deptName : String(parentId);
}

const memberDlg = ref(false);
const memberDept = ref<DeptRow | null>(null);
const memberUserIds = ref<number[]>([]);
const allOperators = ref<OperatorRow[]>([]);
const operatorLoading = ref(false);
const savingMembers = ref(false);

const transferData = computed<TransferItem[]>(() =>
  allOperators.value.map((op) => ({
    key: op.userId,
    label: `${op.name || '未命名'} · ${op.phoneNumber || op.userId}`,
    disabled: op.status === 'INACTIVE'
  }))
);

function onSelectionChange(list: DeptRow[]) {
  selected.value = list;
}

async function load() {
  loading.value = true;
  try {
    rows.value = (await api.request<DeptRow[]>('/api/v2/ops/admin/departments', 'GET')) || [];
    selected.value = [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
    hydrated.value = true;
  }
}

function openDept(row?: DeptRow) {
  Object.assign(deptForm, {
    deptId: row?.deptId ?? null,
    deptKey: row?.deptKey ?? '',
    deptName: row?.deptName ?? '',
    parentId: row?.parentId ?? null,
    sortOrder: row?.sortOrder ?? 0,
    status: row?.status ?? 'ACTIVE',
    remark: row?.remark ?? ''
  });
  deptDlg.value = true;
}

async function saveDept() {
  if (!deptForm.deptName.trim()) {
    ElMessage.warning('请填写部门名称');
    return;
  }
  savingDept.value = true;
  try {
    const body = {
      deptKey: deptForm.deptKey,
      deptName: deptForm.deptName,
      parentId: deptForm.parentId,
      sortOrder: deptForm.sortOrder,
      status: deptForm.status,
      remark: deptForm.remark
    };
    if (deptForm.deptId) {
      await api.request(`/api/v2/ops/admin/departments/${deptForm.deptId}`, 'PUT', body);
    } else {
      await api.request('/api/v2/ops/admin/departments', 'POST', body);
    }
    ElMessage.success('已保存');
    deptDlg.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    savingDept.value = false;
  }
}

async function batchSetStatus(status: 'ACTIVE' | 'INACTIVE') {
  if (!selected.value.length) return;
  const label = status === 'ACTIVE' ? '启用' : '停用';
  try {
    await ElMessageBox.confirm(
      `确认将选中的 ${selected.value.length} 个部门设为「${label}」？`,
      '批量操作'
    );
  } catch {
    return;
  }
  try {
    for (const row of selected.value) {
      await api.request(`/api/v2/ops/admin/departments/${row.deptId}`, 'PUT', {
        deptKey: row.deptKey,
        deptName: row.deptName,
        parentId: row.parentId ?? null,
        sortOrder: row.sortOrder,
        status,
        remark: row.remark
      });
    }
    ElMessage.success(`已批量${label}`);
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '批量操作失败');
  }
}

async function loadAllOperators() {
  operatorLoading.value = true;
  try {
    const page = await api.request<{ items?: OperatorRow[]; content?: OperatorRow[] }>(
      '/api/v2/ops/admin/rbac/operators?page=0&size=200',
      'GET'
    );
    const list = page?.items || page?.content || [];
    allOperators.value = list.filter((op) => op.userId >= 100000001);
  } catch (e) {
    allOperators.value = [];
    ElMessage.error(e instanceof Error ? e.message : '加载运营账号失败');
  } finally {
    operatorLoading.value = false;
  }
}

async function openMembers(row: DeptRow) {
  memberDept.value = row;
  memberDlg.value = true;
  memberUserIds.value = [];
  try {
    const [data] = await Promise.all([
      api.request<{ userIds: number[]; userNames: string[] }>(
        `/api/v2/ops/admin/departments/${row.deptId}/members`,
        'GET'
      ),
      loadAllOperators()
    ]);
    memberUserIds.value = [...(data?.userIds || [])];
    // ensure current members appear even if not in operators page
    const known = new Set(allOperators.value.map((o) => o.userId));
    (data?.userIds || []).forEach((id, i) => {
      if (!known.has(id)) {
        allOperators.value.push({
          userId: id,
          name: data?.userNames?.[i] || String(id),
          phoneNumber: ''
        });
      }
    });
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载成员失败');
  }
}

async function saveMembers() {
  if (!memberDept.value) return;
  savingMembers.value = true;
  try {
    await api.request(`/api/v2/ops/admin/departments/${memberDept.value.deptId}/members`, 'PUT', {
      userIds: memberUserIds.value
    });
    ElMessage.success('成员已更新');
    memberDlg.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    savingMembers.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.cell-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.member-transfer-wrap {
  display: flex;
  justify-content: center;
  min-height: 320px;
}
.member-transfer-wrap :deep(.el-transfer) {
  --el-transfer-panel-width: 260px;
  --el-transfer-panel-body-height: 280px;
}
</style>
