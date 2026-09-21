<template>
  <el-card shadow="never" class="page-card">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">进件工作台</span>
            <span class="hint">仅登记外部门店号与进件状态，不推送到支付渠道</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            type="success"
            plain
            :disabled="!hasReviewableSelection"
            :loading="batching === 'approve'"
            @click="batchReview(true)"
          >
            批量通过
          </el-button>
          <el-button
            type="danger"
            plain
            :disabled="!hasReviewableSelection"
            :loading="batching === 'reject'"
            @click="batchReview(false)"
          >
            批量驳回
          </el-button>
          <el-button v-if="canEdit" type="primary" @click="openCreate">新建进件</el-button>
        </div>
      </div>
    </template>

    <el-alert
      :type="hints?.mockEnabled ? 'warning' : 'success'"
      :closable="false"
      show-icon
      class="mb"
      :title="hints?.hint || '加载支付模式…'"
    >
      <template #default>
        <span>
          {{ hints?.registryOnly ? '模式：仅登记 · ' : '' }}微信
          {{ hints?.wechatPayLive ? '正式' : '测试' }} · 支付宝
          {{ hints?.alipayPayLive ? '正式' : '测试' }} · 支付分
          {{ hints?.payScoreLive ? '正式' : '测试' }}
        </span>
      </template>
    </el-alert>

    <el-form inline class="filter-bar filter-bar--compact">
      <el-form-item label="商户编号">
        <el-input v-model="merchantId" clearable placeholder="精确匹配" style="width: 160px" />
      </el-form-item>
      <el-form-item label="渠道">
        <el-select v-model="channel" clearable placeholder="全部" style="width: 120px">
          <el-option value="WECHAT" label="微信" />
          <el-option value="ALIPAY" label="支付宝" />
          <el-option value="PAYSCORE" label="支付分" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 120px">
          <el-option value="DRAFT" label="草稿" />
          <el-option value="SUBMITTED" label="已提交" />
          <el-option value="ACTIVE" label="已生效" />
          <el-option value="REJECTED" label="已驳回" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="crud.search()">查询</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <!-- 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态 / 空态 / CSV / 刷新 全部内建（本页无服务端排序） -->
        <CrudTable
          :table="crud"
          row-key="onboardingId"
          selectable
          :actions="showActionColumn ? rowActions : undefined"
          :action-width="180"
          actions-testid="onboarding"
          empty-text="暂无进件记录"
          :row-class-name="rowClassName"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column prop="merchantId" label="商户" min-width="140">
            <template #default="{ row }">
              <div>{{ row.merchantName || row.merchantId }}</div>
              <div v-if="row.merchantName" class="muted">{{ row.merchantId }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="channel" label="渠道" width="100">
            <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{
                statusLabel(row.status)
              }}</el-tag>
              <div v-if="row.approvalStatus === 'PENDING'" class="muted">审批中</div>
            </template>
          </el-table-column>
          <el-table-column label="外部商户号" min-width="140" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.externalMchId || ''">{{
                row.externalMchId || ''
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="外部单号" min-width="120" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.externalRef || ''">{{
                row.externalRef || ''
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="支付模式" width="90">
            <template #default="{ row }">
              <el-tag :type="row.payLiveHint ? 'success' : 'info'" size="small">
                {{ row.payLiveHint ? '正式' : '测试' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="120" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.note || ''">{{ row.note || '' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最近同步" width="160">
            <template #default="{ row }">{{
              row.lastSyncedAt ? formatDateTime(row.lastSyncedAt) : ''
            }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="160">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) || '' }}</template>
          </el-table-column>
          <el-table-column label="更新时间" width="160">
            <template #default="{ row }">{{ formatDateTime(row.updatedAt) || '' }}</template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>
  </el-card>

  <el-dialog v-model="dlg" :title="form.onboardingId ? '编辑进件' : '新建进件'" destroy-on-close>
    <el-form label-width="auto">
      <el-form-item label="商户编号" required>
        <el-input v-model="form.merchantId" :disabled="!!form.onboardingId" />
      </el-form-item>
      <el-form-item label="渠道" required>
        <el-select v-model="form.channel" :disabled="!!form.onboardingId" style="width: 100%">
          <el-option value="WECHAT" label="微信" />
          <el-option value="ALIPAY" label="支付宝" />
          <el-option value="PAYSCORE" label="支付分" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="form.status" style="width: 100%">
          <el-option value="DRAFT" label="草稿" />
          <el-option value="SUBMITTED" label="提交审批" />
          <el-option
            v-if="form.onboardingId && form.status === 'ACTIVE'"
            value="ACTIVE"
            label="已生效"
            disabled
          />
          <el-option value="REJECTED" label="已驳回" disabled />
        </el-select>
      </el-form-item>
      <el-form-item label="外部商户号">
        <el-input v-model="form.externalMchId" placeholder="仅登记，不推送到渠道" />
      </el-form-item>
      <el-form-item label="外部单号/引用">
        <el-input v-model="form.externalRef" placeholder="仅登记" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.note" type="textarea" :rows="2" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dlg = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onActivated, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CircleCheck, CircleClose, Edit } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useAuthStore } from '@/stores/auth';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface OnboardRow {
  onboardingId: number;
  merchantId: string;
  merchantName?: string;
  channel: string;
  status: string;
  externalMchId?: string;
  externalRef?: string;
  note?: string;
  lastSyncedAt?: string;
  createdAt?: string;
  updatedAt?: string;
  payLiveHint?: boolean;
  approvalStatus?: string;
}

const route = useRoute();
const auth = useAuthStore();
const canEdit = computed(() => auth.hasPerm('ops:merchant:onboard:edit'));
const saving = ref(false);
/** 批量审批 loading：通过 / 驳回 */
const batching = ref<'approve' | 'reject' | ''>('');

const merchantId = ref('');
const channel = ref('');
const status = ref('');
const hints = ref<Record<string, any> | null>(null);
const highlightId = ref<number | null>(null);

// 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态 / 空态 / 刷新 / CSV 全部内建
const crud = useCrudTable<OnboardRow>({
  rowKey: (r) => r.onboardingId,
  fetchPage: async (params) => {
    const q = new URLSearchParams({
      page: String(params.page), // 0 起
      size: String(params.size)
    });
    if (merchantId.value.trim()) q.set('merchantId', merchantId.value.trim());
    if (channel.value) q.set('channel', channel.value);
    if (status.value) q.set('status', status.value);
    const [list, h] = await Promise.all([
      api.request<OnboardRow[] | { items: OnboardRow[]; total: number }>(
        AdminEndpoints.merchantOnboardingList(q),
        'GET'
      ),
      api
        .request<Record<string, any>>(AdminEndpoints.merchantOnboardingLiveHints, 'GET')
        .catch(() => null)
    ]);
    hints.value = h;
    applyRouteHighlight();
    return list;
  },
  errorMessage: '加载失败'
});

function rowHasAction(row: OnboardRow) {
  if (canEdit.value && row.status !== 'SUBMITTED') return true;
  return row.status === 'SUBMITTED' && row.approvalStatus === 'PENDING';
}

/** 当前页无可编辑/审批项时隐藏操作列 */
const showActionColumn = computed(() => crud.items.some(rowHasAction));

/** 勾选中可审批的行（已提交且审批中）；未勾选时为空。 */
const reviewableSelected = computed(() => {
  if (!crud.hasSelection) return [];
  return crud
    .pickSelected(crud.items)
    .filter((r) => r.status === 'SUBMITTED' && r.approvalStatus === 'PENDING');
});
const hasReviewableSelection = computed(() => reviewableSelected.value.length > 0);

const csvOptions: CrudCsvOptions = {
  filePrefix: '进件工作台',
  exportPerm: 'ops:merchant:export',
  headers: [
    '进件ID',
    '商户ID',
    '商户名',
    '渠道',
    '状态',
    '审批状态',
    '外部商户号',
    '外部单号',
    '支付模式',
    '备注',
    '最近同步',
    '创建时间',
    '更新时间'
  ],
  toRows: (rows: OnboardRow[]) =>
    rows.map((r) => [
      r.onboardingId,
      r.merchantId,
      r.merchantName || '',
      channelLabel(r.channel),
      statusLabel(r.status),
      r.approvalStatus || '',
      r.externalMchId || '',
      r.externalRef || '',
      r.payLiveHint ? '正式' : '测试',
      r.note || '',
      r.lastSyncedAt ? formatDateTime(r.lastSyncedAt) : '',
      formatDateTime(r.createdAt) || '',
      formatDateTime(r.updatedAt) || ''
    ])
};

function rowActions(row: OnboardRow): CrudRowAction[] {
  const actions: CrudRowAction[] = [];
  if (row.status !== 'SUBMITTED') {
    actions.push({
      key: 'edit',
      label: '编辑',
      icon: Edit,
      type: 'primary',
      perm: 'ops:merchant:onboard:edit'
    });
  }
  if (row.status === 'SUBMITTED' && row.approvalStatus === 'PENDING') {
    actions.push({ key: 'approve', label: '通过', icon: CircleCheck, type: 'success' });
    actions.push({ key: 'reject', label: '驳回', icon: CircleClose, type: 'danger' });
  }
  return actions;
}

function onAction({ key, row }: { key: string; row: OnboardRow }) {
  if (key === 'edit') openEdit(row);
  else if (key === 'approve') void review(row, true);
  else if (key === 'reject') void review(row, false);
}

const dlg = ref(false);
const form = reactive({
  onboardingId: null as number | null,
  merchantId: '',
  channel: 'WECHAT',
  status: 'DRAFT',
  externalMchId: '',
  externalRef: '',
  note: ''
});

function channelLabel(c?: string) {
  return (
    ({ WECHAT: '微信', ALIPAY: '支付宝', PAYSCORE: '支付分' } as Record<string, string>)[
      String(c || '')
    ] ||
    c ||
    ''
  );
}
function statusLabel(s?: string) {
  return (
    (
      { DRAFT: '草稿', SUBMITTED: '已提交', ACTIVE: '已生效', REJECTED: '已驳回' } as Record<
        string,
        string
      >
    )[String(s || '')] ||
    s ||
    ''
  );
}
function statusTag(s?: string): 'info' | 'warning' | 'success' | 'danger' {
  switch (String(s || '')) {
    case 'ACTIVE':
      return 'success';
    case 'SUBMITTED':
      return 'warning';
    case 'REJECTED':
      return 'danger';
    default:
      return 'info';
  }
}

/**
 * @param {{ row: OnboardRow }} param
 */
function rowClassName({ row }: { row: OnboardRow }) {
  return highlightId.value != null && row.onboardingId === highlightId.value
    ? 'is-highlight-row'
    : '';
}

/** 从审批历史深链带入 onboardingId 时高亮对应行。 */
function applyRouteHighlight() {
  const raw = route.query.onboardingId;
  const id = Number(Array.isArray(raw) ? raw[0] : raw);
  if (!Number.isFinite(id) || id <= 0) {
    highlightId.value = null;
    return;
  }
  highlightId.value = id;
}

function openCreate() {
  Object.assign(form, {
    onboardingId: null,
    merchantId: '',
    channel: 'WECHAT',
    status: 'DRAFT',
    externalMchId: '',
    externalRef: '',
    note: ''
  });
  dlg.value = true;
}

function openEdit(row: OnboardRow) {
  Object.assign(form, {
    onboardingId: row.onboardingId,
    merchantId: row.merchantId,
    channel: row.channel,
    status: row.status,
    externalMchId: row.externalMchId || '',
    externalRef: row.externalRef || '',
    note: row.note || ''
  });
  dlg.value = true;
}

async function save() {
  if (!form.merchantId.trim() || !form.channel) {
    ElMessage.warning('请填写商户与渠道');
    return;
  }
  saving.value = true;
  try {
    const body = {
      merchantId: form.merchantId.trim(),
      channel: form.channel,
      status: form.status,
      externalMchId: form.externalMchId,
      externalRef: form.externalRef,
      note: form.note
    };
    if (form.onboardingId) {
      await api.request(AdminEndpoints.merchantOnboardingItem(form.onboardingId), 'PUT', body);
    } else {
      await api.request(AdminEndpoints.merchantOnboarding, 'POST', body);
    }
    ElMessage.success(form.status === 'SUBMITTED' ? '已提交审批' : '已保存');
    dlg.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function review(row: OnboardRow, approve: boolean) {
  try {
    await ElMessageBox.confirm(
      approve
        ? `确认通过进件 ${row.merchantName || row.merchantId} · ${channelLabel(row.channel)}？`
        : '确认驳回该进件？',
      approve ? '审批通过' : '审批驳回',
      { type: approve ? 'info' : 'warning' }
    );
  } catch {
    return;
  }
  try {
    await api.request(AdminEndpoints.merchantOnboardingReview(row.onboardingId), 'POST', {
      approve,
      remark: approve ? '审批通过' : '审批驳回'
    });
    ElMessage.success(approve ? '已通过' : '已驳回');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '审批失败');
  }
}

/**
 * 批量审批勾选中的「已提交且审批中」进件。
 * @param approve 是否通过
 */
async function batchReview(approve: boolean) {
  const targets = reviewableSelected.value;
  if (!targets.length) {
    ElMessage.warning(
      crud.hasSelection ? '勾选行中没有「已提交且审批中」的进件' : '请先勾选需要审批的进件'
    );
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认对 ${targets.length} 条进件执行「${approve ? '通过' : '驳回'}」？`,
      approve ? '批量通过' : '批量驳回',
      { type: approve ? 'info' : 'warning' }
    );
  } catch {
    return;
  }
  batching.value = approve ? 'approve' : 'reject';
  let ok = 0;
  let fail = 0;
  try {
    for (const row of targets) {
      try {
        await api.request(AdminEndpoints.merchantOnboardingReview(row.onboardingId), 'POST', {
          approve,
          remark: approve ? '批量审批通过' : '批量审批驳回'
        });
        ok += 1;
      } catch {
        fail += 1;
      }
    }
    if (fail === 0) {
      ElMessage.success(approve ? `已通过 ${ok} 条` : `已驳回 ${ok} 条`);
    } else {
      ElMessage.warning(`成功 ${ok} 条，失败 ${fail} 条`);
    }
    await crud.load();
  } finally {
    batching.value = '';
  }
}

// H07：页面被 AdminLayout keep-alive 缓存，深链二次进入只触发 onActivated 不走 onMounted；
// 这里重读 query.onboardingId 刷新高亮（无参数时清掉旧高亮）；首查由 useCrudTable autoLoad 在 onMounted 触发。
onActivated(applyRouteHighlight);
</script>

<style scoped>
.mb {
  margin-bottom: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
}
:deep(.is-highlight-row) > td {
  background: rgba(245, 158, 11, 0.12) !important;
}
</style>
