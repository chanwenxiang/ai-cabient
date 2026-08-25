<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">优惠券</span>
            <span class="hint">券定义与发券；面值 / 门槛右对齐</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:coupon:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button
            v-hasPermi="['ops:coupon:import']"
            @click="
              onDownloadTemplate([
                '示例优惠券',
                '满减券',
                '5',
                '0',
                '90',
                '30',
                '100',
                '示例描述',
                '停用'
              ])
            "
            >导入模板</el-button
          >
          <el-button v-hasPermi="['ops:coupon:import']" :loading="importing" @click="triggerImport"
            >导入</el-button
          >
          <input
            ref="importInput"
            type="file"
            accept=".csv,text/csv"
            class="hidden-input"
            @change="onImportFile"
          />
          <el-button
            v-if="selectedIds.length && auth.hasPerm('ops:coupon:edit')"
            type="warning"
            @click="batchDisable"
          >
            批量停用 ({{ selectedIds.length }})
          </el-button>
          <el-button v-hasPermi="['ops:coupon:create']" type="primary" @click="openCreate"
            >新建优惠券</el-button
          >
          <el-button v-hasPermi="['ops:coupon:create']" @click="showIssue = true"
            >手动发券</el-button
          >
          <el-button v-hasPermi="['ops:coupon:create']" @click="openBatchIssue">批量发券</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="名称 / ID"
          style="width: 180px"
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
          <el-option label="启用" value="ACTIVE" />
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
          :data="paged"
          stripe
          border
          class="report-table"
          row-key="couponDefId"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty
            ><el-empty v-if="listHydrated && !loading" description="暂无优惠券"
          /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="couponDefId"
            label="券定义编号"
            width="100"
            align="center"
            class-name="col-text"
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.couponDefId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="优惠券" min-width="150" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.couponName || '无' }}</template>
          </el-table-column>
          <el-table-column label="类型" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{
                typeMap[row.couponType] || row.couponType
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="面值" width="96" align="center" class-name="col-money">
            <template #default="{ row }">
              <template v-if="row.couponType === 'PERCENT' || Number(row.discountPercent) > 0">
                {{ row.discountPercent || 0 }}%
              </template>
              <template v-else>¥{{ yuan(row.denominationCents) }}</template>
            </template>
          </el-table-column>
          <el-table-column label="最低消费" width="100" align="center" class-name="col-money">
            <template #default="{ row }">¥{{ yuan(row.minSpendCents) }}</template>
          </el-table-column>
          <el-table-column label="有效期" width="88" align="center">
            <template #default="{ row }">{{ row.validityDays }}天</template>
          </el-table-column>
          <el-table-column label="发行/总量" width="110" align="center">
            <template #default="{ row }"
              >{{ row.issuedCount }}/{{ row.maxIssueCount || '不限' }}</template
            >
          </el-table-column>
          <el-table-column label="剩余" width="88" align="center">
            <template #default="{ row }">
              <span v-if="!row.maxIssueCount">不限</span>
              <span v-else>{{
                Math.max(0, Number(row.maxIssueCount) - Number(row.issuedCount || 0))
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="140" align="center" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '暂无' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ displayLabel('enable_status', row.status, '未知状态') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            v-if="showActionColumn"
            label="操作"
            width="100"
            class-name="col-action"
            align="center"
          >
            <template #default="{ row }">
              <TableActions
                v-if="rowActions(row).length"
                :actions="rowActions(row)"
                @action="(k) => onAction(String(k), row)"
              />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="filtered.length"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      background
    />

    <el-dialog
      v-model="showCreate"
      :title="editingId ? '编辑优惠券' : '新建优惠券'"
      width="500px"
      destroy-on-close
    >
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="名称" required
          ><el-input v-model="createForm.couponName"
        /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="createForm.couponType" style="width: 100%">
            <el-option
              v-for="item in dictOptions('coupon_type')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="面值(元)">
          <el-input-number
            v-model="createForm.denominationYuan"
            :min="0.01"
            :step="0.5"
            :precision="2"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="最低消费(元)">
          <el-input-number
            v-model="createForm.minSpendYuan"
            :min="0"
            :step="1"
            :precision="2"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="折扣百分比">
          <el-input-number
            v-model="createForm.discountPercent"
            :min="1"
            :max="99"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="有效天数">
          <el-input-number
            v-model="createForm.validityDays"
            :min="1"
            :max="365"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="总量限制">
          <el-input-number
            v-model="createForm.maxIssueCount"
            :min="0"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="描述"
          ><el-input v-model="createForm.description" type="textarea"
        /></el-form-item>
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
          <el-input-number
            v-model="issueForm.userId"
            :min="1"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showIssue = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onIssueSubmit">发放</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchVisible" title="批量发券" width="480px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="优惠券">
          <el-select v-model="batchForm.couponDefId" style="width: 100%">
            <el-option
              v-for="d in activeCoupons"
              :key="d.couponDefId"
              :label="d.couponName"
              :value="d.couponDefId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="用户ID">
          <el-input
            v-model="batchForm.userIdsText"
            type="textarea"
            :rows="6"
            placeholder="每行一个用户ID"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onBatchIssueSubmit">批量发放</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh, SwitchButton, Ticket, EditPen } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('couponDefId');
const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const list = ref<any[]>([]);
const keyword = ref('');
const statusFilter = ref('');
const page = ref(1);
const size = ref(20);
const showCreate = ref(false);
const editingId = ref<number | null>(null);
const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  const rows = list.value.filter((row) => {
    if (statusFilter.value === 'ACTIVE' && row.status !== 'ACTIVE') return false;
    if (statusFilter.value === 'INACTIVE' && row.status === 'ACTIVE') return false;
    if (!q) return true;
    return [row.couponDefId, row.couponName, row.couponType].some((x) =>
      String(x || '')
        .toLowerCase()
        .includes(q)
    );
  });
  return sortById(rows);
});
const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});
watch([keyword, statusFilter], () => {
  page.value = 1;
});
const showIssue = ref(false);
const batchVisible = ref(false);
const batchForm = ref<{ couponDefId: number | null; userIdsText: string }>({
  couponDefId: null,
  userIdsText: ''
});

const {
  selectedKeys: selectedIds,
  onSelectionChange,
  pickSelected,
  exportButtonLabel,
  clearSelection
} = useTableSelection<any>((r) => r.couponDefId);

async function batchDisable() {
  const targets = list.value.filter(
    (r) => selectedIds.value.includes(r.couponDefId) && r.status === 'ACTIVE'
  );
  if (!targets.length) return ElMessage.warning('请勾选已启用的优惠券');
  try {
    await ElMessageBox.confirm(`确认停用选中的 ${targets.length} 张优惠券？`, '批量停用', {
      type: 'warning'
    });
    for (const row of targets) {
      await api.request(
        `/api/v2/coupons/definitions/${row.couponDefId}/status?status=INACTIVE`,
        'PUT'
      );
    }
    ElMessage.success(`已停用 ${targets.length} 张优惠券`);
    clearSelection();
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '批量停用失败');
    }
  }
}

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

const typeMap: Record<string, string> = Object.fromEntries(
  dictOptions('coupon_type').map((o) => [o.value, o.label])
);
const typeCodeByLabel: Record<string, string> = Object.fromEntries(
  dictOptions('coupon_type').flatMap(
    (o) =>
      [
        [o.label, o.value],
        [o.value, o.value]
      ] as [string, string][]
  )
);
const activeCoupons = computed(() => list.value.filter((d) => d.status === 'ACTIVE'));

const CSV_HEADERS = [
  '名称',
  '类型',
  '面值(元)',
  '最低消费(元)',
  '折扣百分比',
  '有效天数',
  '总量限制',
  '描述',
  '状态'
];

const { importing, importInput, onExport, onDownloadTemplate, triggerImport, onImportFile } =
  useListCsv({
    filePrefix: '优惠券',
    headers: CSV_HEADERS,
    toRows: () =>
      pickSelected(filtered.value).map((row) => [
        row.couponName,
        typeMap[row.couponType] || row.couponType,
        yuan(row.denominationCents),
        yuan(row.minSpendCents),
        row.discountPercent ?? '',
        row.validityDays,
        row.maxIssueCount || 0,
        row.description || '',
        displayLabel('enable_status', row.status, '未知状态')
      ]),
    onImportRows: async (rows) => {
      let ok = 0;
      for (const row of rows) {
        const name = row['名称'] || row.couponName;
        if (!name?.trim()) continue;
        const created = await api.request<any>('/api/v2/coupons/definitions', 'POST', {
          couponName: name.trim(),
          couponType: typeCodeByLabel[row['类型'] || row.couponType] || 'AMOUNT_OFF',
          denominationCents: Math.round(
            (Number(row['面值(元)'] || row.denominationYuan) || 0) * 100
          ),
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
  if (auth.hasPerm('ops:coupon:edit')) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (auth.hasPerm('ops:coupon:create') && row.status === 'ACTIVE') {
    acts.push({ key: 'issue', label: '发券', icon: Ticket, type: 'primary' });
  }
  if (auth.hasPerm('ops:coupon:edit')) {
    acts.push({
      key: 'toggle',
      label: row.status === 'ACTIVE' ? '停用' : '启用',
      icon: SwitchButton,
      type: row.status === 'ACTIVE' ? 'warning' : 'success'
    });
  }
  return acts;
}

const showActionColumn = computed(() => paged.value.some((row) => rowActions(row).length > 0));

async function onAction(key: string, row: any) {
  if (key === 'edit') {
    openEdit(row);
  } else if (key === 'issue') {
    issueForm.value.couponDefId = row.couponDefId;
    showIssue.value = true;
  } else if (key === 'toggle') {
    await onToggleStatus(row);
  }
}

function openCreate() {
  editingId.value = null;
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

function openEdit(row: any) {
  editingId.value = row.couponDefId;
  createForm.value = {
    couponName: row.couponName || '',
    couponType: row.couponType || 'AMOUNT_OFF',
    denominationYuan: Number(((Number(row.denominationCents) || 0) / 100).toFixed(2)),
    minSpendYuan: Number(((Number(row.minSpendCents) || 0) / 100).toFixed(2)),
    discountPercent: row.discountPercent ?? 90,
    validityDays: row.validityDays || 30,
    maxIssueCount: row.maxIssueCount || 0,
    description: row.description || ''
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
    listHydrated.value = true;
    loading.value = false;
  }
}

async function onCreateSubmit() {
  if (!createForm.value.couponName.trim()) return ElMessage.warning('请填写名称');
  saving.value = true;
  try {
    const body = {
      couponName: createForm.value.couponName.trim(),
      couponType: createForm.value.couponType,
      denominationCents: Math.round((Number(createForm.value.denominationYuan) || 0) * 100),
      minSpendCents: Math.round((Number(createForm.value.minSpendYuan) || 0) * 100),
      discountPercent: createForm.value.discountPercent,
      validityDays: createForm.value.validityDays,
      maxIssueCount: createForm.value.maxIssueCount,
      description: createForm.value.description
    };
    if (editingId.value) {
      await api.request(`/api/v2/coupons/definitions/${editingId.value}`, 'PUT', body);
      ElMessage.success('已更新');
    } else {
      await api.request('/api/v2/coupons/definitions', 'POST', body);
      ElMessage.success('创建成功');
    }
    showCreate.value = false;
    editingId.value = null;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function onIssueSubmit() {
  if (!issueForm.value.couponDefId || !issueForm.value.userId) {
    return ElMessage.warning('请选择优惠券并填写用户编号');
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

function openBatchIssue() {
  batchForm.value = { couponDefId: null, userIdsText: '' };
  batchVisible.value = true;
}

async function onBatchIssueSubmit() {
  if (!batchForm.value.couponDefId) {
    ElMessage.warning('请选择优惠券');
    return;
  }
  const userIds = batchForm.value.userIdsText
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter((s) => /^\d+$/.test(s))
    .map(Number);
  if (!userIds.length) {
    ElMessage.warning('请至少填写一个有效的用户ID');
    return;
  }
  saving.value = true;
  try {
    await api.request('/api/v2/coupons/batch-issue', 'POST', {
      couponDefId: batchForm.value.couponDefId,
      userIds
    });
    ElMessage.success(`已向 ${userIds.length} 个用户发券`);
    batchVisible.value = false;
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
    await ElMessageBox.confirm(`确认${action}优惠券「${row.couponName}」？`, '优惠券状态', {
      type: 'warning'
    });
    await api.request(
      `/api/v2/coupons/definitions/${row.couponDefId}/status?status=${next}`,
      'PUT'
    );
    ElMessage.success(`已${action}`);
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : `${action}失败`);
    }
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (statusFilter.value) query.status = statusFilter.value;
  router.replace({ query });
}

function search() {
  page.value = 1;
  syncRouteQuery();
}

function resetFilters() {
  keyword.value = '';
  statusFilter.value = '';
  page.value = 1;
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
  page.value = 1;
}

watch(
  () => [route.query.keyword, route.query.status] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  load();
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
</style>
