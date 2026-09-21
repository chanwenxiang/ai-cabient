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
            :disabled="!crud.hasSelection"
            @click="batchSetStatus('ACTIVE')"
            >批量启用</el-button
          >
          <el-button
            v-hasPermi="['ops:dept:edit']"
            :disabled="!crud.hasSelection"
            @click="batchSetStatus('INACTIVE')"
            >批量停用</el-button
          >
          <el-button v-hasPermi="['ops:dept:edit']" type="primary" @click="openDept()"
            >新增部门</el-button
          >
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="deptId"
          selectable
          :actions="rowActions"
          :action-width="200"
          actions-testid="dept"
          empty-text="暂无部门"
          @action="onAction"
        >
          <el-table-column
            prop="deptKey"
            label="编码"
            width="120"
            class-name="col-text"
            label-class-name="col-text"
          />
          <el-table-column
            prop="deptName"
            label="名称"
            min-width="120"
            class-name="col-text"
            label-class-name="col-text"
          />
          <el-table-column
            label="上级"
            min-width="120"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ parentName(row.parentId) }}</template>
          </el-table-column>
          <el-table-column
            prop="memberCount"
            label="成员数"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            prop="sortOrder"
            label="排序"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            label="状态"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ displayLabel('enable_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            prop="remark"
            label="备注"
            min-width="160"
            class-name="col-text"
            label-class-name="col-text"
          />
        </CrudTable>
      </div>
    </div>
  </el-card>

  <el-dialog v-model="deptDlg" :title="deptForm.deptId ? '编辑部门' : '新增部门'" destroy-on-close>
    <el-form label-width="auto">
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
          <el-option value="ACTIVE" :label="displayLabel('enable_status', 'ACTIVE')" />
          <el-option value="INACTIVE" :label="displayLabel('enable_status', 'INACTIVE')" />
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
    class="dialog-wide"
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
import { computed, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Edit, User } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { displayLabel } from '@aicabinet/shared-dict';
import CrudTable, { type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable, type CrudPageParams } from '@/composables/useCrudTable';

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
  /** OPERATOR / CONSUMER；勿用 userId 区间猜测 */
  accountType?: string;
}

interface TransferItem {
  key: number;
  label: string;
  disabled?: boolean;
}

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

// 全量部门列表（接口一次返回整棵部门表，无服务端分页）；
// 「上级」名称与上级选项须跨页查找，故单独留存，分页只影响表格展示切片。
const allDepts = ref<DeptRow[]>([]);

// 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态 / 空态 / 刷新 全部内建
const crud = useCrudTable<DeptRow>({
  rowKey: (r) => r.deptId,
  fetchPage: fetchDeptPage,
  // 部门量级小，默认 50（一页上限）尽量一页展示完，最接近原「整表一次渲染」
  pageSize: 50
});

// 本页无服务端分页：fetchPage 拉全量部门 → 前端切片成一页；全量另存 allDepts 供上级名称/选项跨页取用
async function fetchDeptPage(params: CrudPageParams) {
  const list = (await api.request<DeptRow[]>(AdminEndpoints.departments, 'GET')) || [];
  allDepts.value = list;
  const start = params.page * params.size;
  return { items: list.slice(start, start + params.size), total: list.length };
}

const parentOptions = computed(() =>
  allDepts.value.filter((d) => d.deptId !== deptForm.deptId && d.status === 'ACTIVE')
);

function parentName(parentId?: number | null) {
  if (parentId == null) return '—';
  const p = allDepts.value.find((d) => d.deptId === parentId);
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

function rowActions(_row: DeptRow): CrudRowAction[] {
  return [
    { key: 'edit', label: '编辑', icon: Edit, type: 'primary', perm: 'ops:dept:edit' },
    { key: 'members', label: '成员', icon: User, type: 'primary', perm: 'ops:dept:edit' }
  ];
}

function onAction({ key, row }: { key: string; row: DeptRow }) {
  if (key === 'edit') openDept(row);
  else if (key === 'members') void openMembers(row);
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
      await api.request(AdminEndpoints.department(deptForm.deptId), 'PUT', body);
    } else {
      await api.request(AdminEndpoints.departments, 'POST', body);
    }
    ElMessage.success('已保存');
    deptDlg.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    savingDept.value = false;
  }
}

async function batchSetStatus(status: 'ACTIVE' | 'INACTIVE') {
  if (!crud.hasSelection) return;
  const targets = crud.pickSelected(crud.items);
  const label = displayLabel('enable_status', status);
  try {
    await ElMessageBox.confirm(
      `确认将选中的 ${targets.length} 个部门设为「${label}」？`,
      '批量操作'
    );
  } catch {
    return;
  }
  try {
    for (const row of targets) {
      await api.request(AdminEndpoints.department(row.deptId), 'PUT', {
        deptKey: row.deptKey,
        deptName: row.deptName,
        parentId: row.parentId ?? null,
        sortOrder: row.sortOrder,
        status,
        remark: row.remark
      });
    }
    ElMessage.success(`已批量${label}`);
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '批量操作失败');
  }
}

async function loadAllOperators() {
  operatorLoading.value = true;
  try {
    const page = await api.request<{ items?: OperatorRow[]; content?: OperatorRow[] }>(
      AdminEndpoints.rbacOperatorsPage(0, 200),
      'GET'
    );
    const list = page?.items || page?.content || [];
    // 以后端 accountType 为准，勿硬编码 userId >= 100000001
    allOperators.value = list.filter((op) => {
      const t = String(op.accountType || 'OPERATOR').toUpperCase();
      return t === 'OPERATOR';
    });
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
        AdminEndpoints.departmentMembers(row.deptId),
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
    await api.request(AdminEndpoints.departmentMembers(memberDept.value.deptId), 'PUT', {
      userIds: memberUserIds.value
    });
    ElMessage.success('成员已更新');
    memberDlg.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    savingMembers.value = false;
  }
}
</script>

<style scoped>
.cell-hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
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
