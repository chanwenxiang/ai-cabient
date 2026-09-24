<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">营销活动</span>
            <span class="hint"
              >满减 / 折扣等活动；预算用尽后仍可「启用」，发券会被拦截并显示「预算已满」</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-if="crud.selectedKeys.length"
            v-hasPermi="['ops:promotion:stop']"
            type="warning"
            @click="batchDisable"
          >
            批量停用 ({{ crud.selectedKeys.length }})
          </el-button>
          <el-button v-hasPermi="['ops:promotion:create']" type="primary" @click="openCreate"
            >新建活动</el-button
          >
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
          <el-option :label="displayLabel('enable_status', 'ACTIVE')" value="ACTIVE" />
          <el-option :label="displayLabel('enable_status', 'INACTIVE')" value="INACTIVE" />
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
          row-key="activityId"
          selectable
          :actions="rowActions"
          :action-width="100"
          actions-testid="promotion"
          empty-text="暂无活动"
          sort-field-label="活动编号"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column prop="activityId" label="活动编号" width="80" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.activityId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="活动" min-width="160" class-name="col-text">
            <template #default="{ row }">{{ row.activityName || '无' }}</template>
          </el-table-column>
          <el-table-column
            label="类型"
            width="120"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{
                displayLabel('promotion_type', row.activityType, '未知类型')
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="时间"
            min-width="200"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime"
                >{{ formatTime(row.startTime) }} ~ {{ formatTime(row.endTime) }}</span
              >
            </template>
          </el-table-column>
          <el-table-column label="预算" width="110" align="center" class-name="col-money">
            <template #default="{ row }">¥{{ yuan(row.budgetCents) }}</template>
          </el-table-column>
          <el-table-column label="已使用" width="110" align="center" class-name="col-money">
            <template #default="{ row }">¥{{ yuan(row.usedCents) }}</template>
          </el-table-column>
          <el-table-column label="剩余预算" width="120" align="center" class-name="col-money">
            <template #default="{ row }">
              <div class="budget-remain">
                <span
                  >¥{{
                    yuan(Math.max(0, Number(row.budgetCents || 0) - Number(row.usedCents || 0)))
                  }}</span
                >
                <el-tag
                  v-if="isBudgetExhausted(row)"
                  type="warning"
                  size="small"
                  effect="plain"
                  class="budget-full-tag"
                  >预算已满</el-tag
                >
              </div>
            </template>
          </el-table-column>
          <el-table-column
            label="每人限次"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.userLimit || '不限' }}</template>
          </el-table-column>
          <el-table-column label="适用柜" min-width="120" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="deviceScopeLabel(row) || ''">{{
                deviceScopeLabel(row)
              }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="isEnabled(row.status) ? 'success' : 'info'" size="small">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog
      v-model="showDialog"
      :title="editingId ? '编辑活动' : '新建活动'"
      destroy-on-close
      append-to-body
      align-center
      :close-on-click-modal="false"
      class="dialog-wide promo-dialog"
    >
      <el-form :model="form" label-width="auto">
        <el-form-item label="活动名称" required
          ><el-input v-model="form.activityName" maxlength="80"
        /></el-form-item>
        <el-form-item label="活动类型">
          <el-select v-model="form.activityType" style="width: 100%" teleported>
            <el-option
              v-for="item in dictOptions('promotion_type')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间"
          ><el-date-picker
            v-model="form.startTime"
            type="datetime"
            teleported
            placement="bottom-start"
            :z-index="5000"
            :popper-options="{ strategy: 'fixed' }"
            style="width: 100%"
        /></el-form-item>
        <el-form-item label="结束时间"
          ><el-date-picker
            v-model="form.endTime"
            type="datetime"
            teleported
            placement="bottom-start"
            :z-index="5000"
            :popper-options="{ strategy: 'fixed' }"
            style="width: 100%"
        /></el-form-item>
        <el-form-item label="预算(元)">
          <el-input-number
            v-model="form.budgetYuan"
            :min="0"
            :step="1"
            :precision="2"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="每人限制"
          ><el-input-number
            v-model="form.userLimit"
            :min="1"
            :max="100"
            controls-position="right"
            style="width: 100%"
        /></el-form-item>
        <el-form-item label="适用柜">
          <el-radio-group v-model="form.deviceScope">
            <el-radio value="ALL">全部设备</el-radio>
            <el-radio value="SPECIFIC">指定设备</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.deviceScope === 'SPECIFIC'" label="选择设备">
          <el-select
            v-model="form.deviceIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择柜机"
            style="width: 100%"
          >
            <el-option
              v-for="d in deviceOptions"
              :key="d.deviceId"
              :label="d.deviceName || d.deviceId"
              :value="d.deviceId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述"
          ><el-input v-model="form.description" type="textarea"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, SwitchButton } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { yuanToCents } from '@/utils/display';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { errorMessage } from '@/utils/error-message';
import type { OpenApiPromotionActivityDto } from '@aicabinet/shared-types';

const route = useRoute();
const router = useRouter();
const saving = ref(false);
const keyword = ref('');
const statusFilter = ref('');
const showDialog = ref(false);
const editingId = ref<number | null>(null);
const deviceOptions = ref<{ deviceId: string; deviceName?: string }[]>([]);

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 全部内建
const crud = useCrudTable<OpenApiPromotionActivityDto>({
  rowKey: (r) => r.activityId ?? 0,
  // 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由 onMounted 显式首查
  autoLoad: false,
  fetchPage: (params) => {
    const q = new URLSearchParams({ page: String(params.page), size: String(params.size) });
    if (keyword.value.trim()) q.set('q', keyword.value.trim());
    if (statusFilter.value) q.set('status', statusFilter.value);
    return api.request<{ items: OpenApiPromotionActivityDto[]; total: number }>(
      AdminEndpoints.promotionsList(q),
      'GET'
    );
  },
  sort: { prop: 'activityId', mode: 'local' }
});

const CSV_HEADERS = [
  '活动名称',
  '类型',
  '开始时间',
  '结束时间',
  '预算(元)',
  '每人限制',
  '描述',
  '状态'
];

const emptyForm = () => ({
  activityName: '',
  activityType: 'FULL_REDUCE',
  startTime: '' as string | Date,
  endTime: '' as string | Date,
  budgetYuan: 0,
  userLimit: 1,
  deviceScope: 'ALL',
  deviceIds: [] as string[],
  description: ''
});
const form = ref(emptyForm());

const typeCodeByLabel: Record<string, string> = Object.fromEntries(
  dictOptions('promotion_type').flatMap(
    (o) =>
      [
        [o.label, o.value],
        [o.value, o.value]
      ] as [string, string][]
  )
);

function yuan(cents: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}
function formatTime(t: string) {
  if (!t) return '';
  return t.substring(0, 16).replace('T', ' ');
}
function isEnabled(status?: string) {
  return status === 'ACTIVE';
}
/** 配置仍启用，但预算已占满：派生态（对齐投放侧「启用≠还能花」） */
function isBudgetExhausted(row: { status?: string; budgetCents?: number; usedCents?: number }) {
  if (!isEnabled(row.status)) return false;
  const budget = Number(row.budgetCents || 0);
  if (budget <= 0) return false;
  return Number(row.usedCents || 0) >= budget;
}
function statusLabel(status?: string) {
  return displayLabel('enable_status', status, '暂无');
}

function parseRuleDeviceIds(ruleConfig?: string | Record<string, unknown> | null): string[] {
  if (!ruleConfig) return [];
  try {
    const obj =
      typeof ruleConfig === 'string'
        ? (JSON.parse(ruleConfig) as Record<string, unknown>)
        : ruleConfig;
    const ids = obj.deviceIds;
    return Array.isArray(ids) ? ids.map(String).filter(Boolean) : [];
  } catch {
    return [];
  }
}

function deviceScopeLabel(row: { deviceScope?: string; ruleConfig?: string }) {
  if (!row.deviceScope || row.deviceScope === 'ALL') return '全部';
  if (row.deviceScope === 'SPECIFIC') {
    const n = parseRuleDeviceIds(row.ruleConfig).length;
    return n ? `${n} 台定向` : '指定设备';
  }
  return row.deviceScope;
}

async function loadDevices() {
  try {
    deviceOptions.value =
      (await api.request<{ deviceId: string; deviceName?: string }[]>(
        AdminEndpoints.devicesRef,
        'GET'
      )) || [];
  } catch {
    deviceOptions.value = [];
  }
}

function rowActions(row: OpenApiPromotionActivityDto): CrudRowAction[] {
  const acts: CrudRowAction[] = [];
  if (!isEnabled(row.status) && row.status !== 'ENDED') {
    acts.push({
      key: 'edit',
      label: '编辑',
      icon: EditPen,
      type: 'primary',
      perm: 'ops:promotion:edit'
    });
  }
  if (row.status !== 'ENDED') {
    if (isEnabled(row.status)) {
      acts.push({
        key: 'toggle',
        label: displayLabel('enable_status', 'INACTIVE'),
        icon: SwitchButton,
        type: 'warning',
        perm: 'ops:promotion:stop'
      });
    } else {
      acts.push({
        key: 'toggle',
        label: displayLabel('enable_status', 'ACTIVE'),
        icon: SwitchButton,
        type: 'success',
        perm: 'ops:promotion:launch'
      });
    }
  }
  return acts;
}

function onAction({ key, row }: { key: string; row: OpenApiPromotionActivityDto }) {
  if (key === 'edit') openEdit(row);
  else if (key === 'toggle') void onToggleStatus(row);
}

function openCreate() {
  editingId.value = null;
  form.value = emptyForm();
  showDialog.value = true;
}

function openEdit(row: OpenApiPromotionActivityDto) {
  editingId.value = row.activityId ?? null;
  const deviceIds = parseRuleDeviceIds(row.ruleConfig);
  let formDeviceIds: string[];
  if (deviceIds.length) {
    formDeviceIds = deviceIds;
  } else if (row.deviceScope && row.deviceScope !== 'ALL' && row.deviceScope !== 'SPECIFIC') {
    formDeviceIds = [row.deviceScope];
  } else {
    formDeviceIds = [];
  }
  form.value = {
    activityName: row.activityName || '',
    activityType: row.activityType || 'FULL_REDUCE',
    startTime: row.startTime ? new Date(row.startTime) : '',
    endTime: row.endTime ? new Date(row.endTime) : '',
    budgetYuan: (Number(row.budgetCents) || 0) / 100,
    userLimit: row.userLimit ?? 1,
    deviceScope: row.deviceScope === 'SPECIFIC' ? 'SPECIFIC' : 'ALL',
    deviceIds: formDeviceIds,
    description: row.description || ''
  };
  showDialog.value = true;
}

async function onSubmit() {
  const f = form.value;
  if (!f.activityName?.trim()) return ElMessage.warning('请填写活动名称');
  if (!f.startTime || !f.endTime) return ElMessage.warning('请选择活动时间');
  const start = new Date(f.startTime);
  const end = new Date(f.endTime);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()))
    return ElMessage.warning('活动时间无效');
  if (end <= start) return ElMessage.warning('结束时间需晚于开始时间');
  if (f.deviceScope === 'SPECIFIC' && !f.deviceIds.length) {
    return ElMessage.warning('指定设备时请至少选择一台柜机');
  }
  const body = {
    activityName: f.activityName.trim(),
    activityType: f.activityType,
    startTime: start.toISOString(),
    endTime: end.toISOString(),
    budgetCents: yuanToCents(f.budgetYuan) ?? 0,
    userLimit: f.userLimit,
    deviceScope: f.deviceScope,
    ruleConfig: JSON.stringify({
      deviceIds: f.deviceScope === 'SPECIFIC' ? f.deviceIds : []
    }),
    description: f.description
  };
  saving.value = true;
  try {
    if (editingId.value) {
      await api.request(AdminEndpoints.promotion(editingId.value), 'PUT', body);
      ElMessage.success('已更新');
    } else {
      await api.request(AdminEndpoints.promotions, 'POST', body);
      ElMessage.success('创建成功');
    }
    showDialog.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存失败'));
  } finally {
    saving.value = false;
  }
}

async function onToggleStatus(row: OpenApiPromotionActivityDto) {
  const enable = !isEnabled(row.status);
  const action = displayLabel('enable_status', enable ? 'ACTIVE' : 'INACTIVE');
  try {
    await ElMessageBox.confirm(`确认${action}活动「${row.activityName}」？`, '活动状态', {
      type: 'warning'
    });
    if (enable) {
      await api.request(AdminEndpoints.promotionLaunch(row.activityId ?? 0), 'POST');
    } else {
      await api.request(AdminEndpoints.promotionStop(row.activityId ?? 0), 'POST');
    }
    ElMessage.success(`已${action}`);
    await crud.load();
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : `${action}失败`);
    }
  }
}

async function batchDisable() {
  const targets = crud.items.filter(
    (r) => r.activityId != null && crud.selectedKeys.includes(r.activityId) && isEnabled(r.status)
  );
  if (!targets.length) return ElMessage.warning('请勾选已启用的活动');
  try {
    await ElMessageBox.confirm(`确认停用选中的 ${targets.length} 个活动？`, '批量停用');
    for (const row of targets) {
      await api.request(AdminEndpoints.promotionStop(row.activityId ?? 0), 'POST');
    }
    ElMessage.success(`已停用 ${targets.length} 个活动`);
    await crud.load();
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(errorMessage(e, '批量停用失败'));
    }
  }
}

const csvOptions: CrudCsvOptions = {
  filePrefix: '营销活动',
  exportPerm: 'ops:promotion:export',
  importPerm: 'ops:promotion:import',
  headers: CSV_HEADERS,
  toRows: (rows) =>
    rows.map((row) => [
      row.activityName || '',
      displayLabel('promotion_type', row.activityType, '未知类型'),
      formatTime(row.startTime || ''),
      formatTime(row.endTime || ''),
      yuan(row.budgetCents || 0),
      row.userLimit ?? 1,
      row.description || '',
      statusLabel(row.status)
    ]),
  templateSample: [
    '示例满减活动',
    '满减',
    '2026-07-16 00:00',
    '2026-08-16 23:59',
    '1000',
    '1',
    '示例描述',
    displayLabel('enable_status', 'INACTIVE')
  ],
  onImportRows: async (rows) => {
    let ok = 0;
    for (const row of rows) {
      const name = row['活动名称'] || row.activityName;
      if (!name) continue;
      const type = typeCodeByLabel[row['类型'] || row.activityType] || 'FULL_REDUCE';
      const start = parseImportTime(row['开始时间'] || row.startTime);
      const end = parseImportTime(row['结束时间'] || row.endTime);
      if (!start || !end || end <= start) {
        throw new Error(`活动「${name}」时间无效`);
      }
      const created = await api.request<OpenApiPromotionActivityDto>(
        AdminEndpoints.promotions,
        'POST',
        {
          activityName: name,
          activityType: type,
          startTime: start.toISOString(),
          endTime: end.toISOString(),
          budgetCents: yuanToCents(row['预算(元)'] || row.budgetYuan) ?? 0,
          userLimit: (() => {
            const n = Number(row['每人限制'] || row.userLimit);
            return Number.isFinite(n) && n > 0 ? n : 1;
          })(),
          description: row['描述'] || row.description || ''
        }
      );
      if (wantsEnabled(row['状态'] || row.status) && created?.activityId) {
        await api.request(AdminEndpoints.promotionLaunch(created.activityId), 'POST');
      }
      ok++;
    }
    await crud.load();
    return ok;
  }
};

function parseImportTime(raw: string): Date | null {
  if (!raw) return null;
  const normalized = raw.includes('T') ? raw : raw.replace(' ', 'T');
  const d = new Date(normalized.length === 16 ? `${normalized}:00` : normalized);
  return Number.isNaN(d.getTime()) ? null : d;
}

function wantsEnabled(statusRaw: string) {
  const s = (statusRaw || '').trim();
  return s === displayLabel('enable_status', 'ACTIVE') || s.toUpperCase() === 'ACTIVE';
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
  await crud.search();
}

watch(
  () => [route.query.keyword, route.query.status] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

// 首查前需先应用路由查询参数，故保留显式首查（crud 已配 autoLoad: false）
onMounted(() => {
  applyRouteQuery();
  void loadDevices();
  void crud.load();
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
.muted {
  color: var(--layout-muted);
  font-size: var(--admin-font-size-table);
}
.budget-remain {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  line-height: 1.2;
}
.budget-full-tag {
  margin: 0;
}
:global(.promo-dialog .el-dialog__body) {
  overflow: visible;
}
</style>
