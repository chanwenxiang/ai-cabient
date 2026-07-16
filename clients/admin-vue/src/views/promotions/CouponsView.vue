<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">优惠券</span>
        <div class="actions">
          <el-button v-if="auth.hasPerm('ops:coupon:create')" @click="onExport">导出</el-button>
          <el-button v-if="auth.hasPerm('ops:coupon:create')" @click="onDownloadTemplate(['示例优惠券', '满减券', '5', '0', '90', '30', '100', '示例描述', '停用'])">导入模板</el-button>
          <el-button v-if="auth.hasPerm('ops:coupon:create')" :loading="importing" @click="triggerImport">导入</el-button>
          <input ref="importInput" type="file" accept=".csv,text/csv" class="hidden-input" @change="onImportFile" />
          <el-button v-if="auth.hasPerm('ops:coupon:create')" type="primary" @click="openCreate">新建优惠券</el-button>
          <el-button v-if="auth.hasPerm('ops:coupon:create')" @click="showIssue = true">手动发券</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 930px">
        <el-table v-loading="loading" :data="list" stripe border>
      <el-table-column prop="couponDefId" label="ID" width="80" />
      <el-table-column prop="couponName" label="名称" min-width="150" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ typeMap[row.couponType] || row.couponType }}</template>
      </el-table-column>
      <el-table-column label="面值" width="100">
        <template #default="{ row }">¥{{ yuan(row.denominationCents) }}</template>
      </el-table-column>
      <el-table-column label="最低消费" width="110">
        <template #default="{ row }">¥{{ yuan(row.minSpendCents) }}</template>
      </el-table-column>
      <el-table-column label="有效期" width="80">
        <template #default="{ row }">{{ row.validityDays }}天</template>
      </el-table-column>
      <el-table-column label="发行/总量" width="120">
        <template #default="{ row }">{{ row.issuedCount }}/{{ row.maxIssueCount || '不限' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="88">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
            {{ statusMap[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" class-name="col-action" align="center">
        <template #default="{ row }">
          <TableActions
            v-if="rowActions(row).length"
            :actions="rowActions(row)"
            :max-primary="1"
            @action="(k) => onAction(String(k), row)"
          />
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无优惠券" /></template>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="showCreate" title="新建优惠券" width="500px" destroy-on-close>
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="名称" required><el-input v-model="createForm.couponName" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="createForm.couponType" style="width: 100%">
            <el-option label="满减券" value="AMOUNT_OFF" />
            <el-option label="折扣券" value="PERCENT_OFF" />
            <el-option label="兑换券" value="EXCHANGE" />
          </el-select>
        </el-form-item>
        <el-form-item label="面值(元)">
          <el-input-number v-model="createForm.denominationYuan" :min="0.01" :step="0.5" :precision="2" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="最低消费(元)">
          <el-input-number v-model="createForm.minSpendYuan" :min="0" :step="1" :precision="2" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="折扣百分比">
          <el-input-number v-model="createForm.discountPercent" :min="1" :max="99" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="有效天数">
          <el-input-number v-model="createForm.validityDays" :min="1" :max="365" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="总量限制">
          <el-input-number v-model="createForm.maxIssueCount" :min="0" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="createForm.description" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreateSubmit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showIssue" title="手动发券" width="450px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="优惠券">
          <el-select v-model="issueForm.couponDefId" style="width: 100%">
            <el-option
              v-for="d in activeCoupons"
              :key="d.couponDefId"
              :label="d.couponName"
              :value="d.couponDefId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="用户ID">
          <el-input-number v-model="issueForm.userId" :min="1" controls-position="right" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showIssue = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onIssueSubmit">发放</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh, SwitchButton, Ticket } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const list = ref<any[]>([]);
const showCreate = ref(false);
const showIssue = ref(false);

const createForm = ref({
  couponName: '',
  couponType: 'AMOUNT_OFF',
  denominationYuan: 1,
  minSpendYuan: 0,
  discountPercent: 90,
  validityDays: 30,
  maxIssueCount: 0,
  description: ''
});
const issueForm = ref<{ couponDefId: number | null; userId: number | null }>({
  couponDefId: null,
  userId: null
});

const typeMap: Record<string, string> = {
  AMOUNT_OFF: '满减券',
  PERCENT_OFF: '折扣券',
  EXCHANGE: '兑换券',
  FREE_SHIPPING: '免运费'
};
const statusMap: Record<string, string> = { ACTIVE: '启用', INACTIVE: '停用', DISABLED: '停用' };
const typeCodeByLabel: Record<string, string> = {
  满减券: 'AMOUNT_OFF',
  折扣券: 'PERCENT_OFF',
  兑换券: 'EXCHANGE',
  AMOUNT_OFF: 'AMOUNT_OFF',
  PERCENT_OFF: 'PERCENT_OFF',
  EXCHANGE: 'EXCHANGE'
};
const activeCoupons = computed(() => list.value.filter((d) => d.status === 'ACTIVE'));

const CSV_HEADERS = ['名称', '类型', '面值(元)', '最低消费(元)', '折扣百分比', '有效天数', '总量限制', '描述', '状态'];

const { importing, importInput, onExport, onDownloadTemplate, triggerImport, onImportFile } = useListCsv({
  filePrefix: '优惠券',
  headers: CSV_HEADERS,
  toRows: () =>
    list.value.map((row) => [
      row.couponName,
      typeMap[row.couponType] || row.couponType,
      yuan(row.denominationCents),
      yuan(row.minSpendCents),
      row.discountPercent ?? '',
      row.validityDays,
      row.maxIssueCount || 0,
      row.description || '',
      statusMap[row.status] || row.status
    ]),
  onImportRows: async (rows) => {
    let ok = 0;
    for (const row of rows) {
      const name = row['名称'] || row.couponName;
      if (!name?.trim()) continue;
      const created = await api.request<any>('/api/v2/coupons/definitions', 'POST', {
        couponName: name.trim(),
        couponType: typeCodeByLabel[row['类型'] || row.couponType] || 'AMOUNT_OFF',
        denominationCents: Math.round((Number(row['面值(元)'] || row.denominationYuan) || 0) * 100),
        minSpendCents: Math.round((Number(row['最低消费(元)'] || row.minSpendYuan) || 0) * 100),
        discountPercent: Number(row['折扣百分比'] || row.discountPercent) || 90,
        validityDays: Number(row['有效天数'] || row.validityDays) || 30,
        maxIssueCount: Number(row['总量限制'] || row.maxIssueCount) || 0,
        description: row['描述'] || row.description || ''
      });
      const statusRaw = (row['状态'] || row.status || '').trim();
      const wantsActive = statusRaw === '启用' || statusRaw.toUpperCase() === 'ACTIVE';
      if (!wantsActive && created?.couponDefId) {
        await api.request(
          `/api/v2/coupons/definitions/${created.couponDefId}/status?status=INACTIVE`,
          'PUT'
        );
      }
      ok++;
    }
    await load();
    return ok;
  }
});

function yuan(cents: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}

function rowActions(row: any): TableAction[] {
  const acts: TableAction[] = [];
  if (auth.hasPerm('ops:coupon:create') && row.status === 'ACTIVE') {
    acts.push({ key: 'issue', label: '发券', icon: Ticket, type: 'primary' });
  }
  if (auth.hasPerm('ops:coupon:edit')) {
    acts.push({
      key: 'toggle',
      label: row.status === 'ACTIVE' ? '停用' : '启用',
      icon: SwitchButton,
      type: row.status === 'ACTIVE' ? 'warning' : 'success',
      overflow: true
    });
  }
  return acts;
}

async function onAction(key: string, row: any) {
  if (key === 'issue') {
    issueForm.value.couponDefId = row.couponDefId;
    showIssue.value = true;
  } else if (key === 'toggle') {
    await onToggleStatus(row);
  }
}

function openCreate() {
  createForm.value = {
    couponName: '',
    couponType: 'AMOUNT_OFF',
    denominationYuan: 1,
    minSpendYuan: 0,
    discountPercent: 90,
    validityDays: 30,
    maxIssueCount: 0,
    description: ''
  };
  showCreate.value = true;
}

async function load() {
  loading.value = true;
  try {
    list.value = await api.request<any[]>('/api/v2/coupons/definitions', 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function onCreateSubmit() {
  if (!createForm.value.couponName.trim()) return ElMessage.warning('请填写名称');
  saving.value = true;
  try {
    await api.request('/api/v2/coupons/definitions', 'POST', {
      couponName: createForm.value.couponName.trim(),
      couponType: createForm.value.couponType,
      denominationCents: Math.round((Number(createForm.value.denominationYuan) || 0) * 100),
      minSpendCents: Math.round((Number(createForm.value.minSpendYuan) || 0) * 100),
      discountPercent: createForm.value.discountPercent,
      validityDays: createForm.value.validityDays,
      maxIssueCount: createForm.value.maxIssueCount,
      description: createForm.value.description
    });
    ElMessage.success('创建成功');
    showCreate.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  } finally {
    saving.value = false;
  }
}

async function onIssueSubmit() {
  if (!issueForm.value.couponDefId || !issueForm.value.userId) {
    return ElMessage.warning('请选择优惠券并填写用户 ID');
  }
  saving.value = true;
  try {
    await api.request('/api/v2/coupons/issue', 'POST', issueForm.value);
    ElMessage.success('发券成功');
    showIssue.value = false;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发券失败');
  } finally {
    saving.value = false;
  }
}

async function onToggleStatus(row: any) {
  const next = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
  const action = next === 'INACTIVE' ? '停用' : '启用';
  try {
    await ElMessageBox.confirm(`确认${action}优惠券「${row.couponName}」？`, '优惠券状态', { type: 'warning' });
    await api.request(`/api/v2/coupons/definitions/${row.couponDefId}/status?status=${next}`, 'PUT');
    ElMessage.success(`已${action}`);
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : `${action}失败`);
    }
  }
}

onMounted(load);
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.title { font-weight: 600; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
.hidden-input { display: none; }
</style>
