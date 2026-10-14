<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">平台资金池</span>
            <span class="hint"
              >渠道账户备付台账（CB-033）；余额只回答「今天够不够付」，以渠道实盘为准</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:fund:manage']" type="primary" @click="openCreate"
            >新建账户</el-button
          >
        </div>
      </div>
    </template>

    <el-alert
      type="info"
      closable
      show-icon
      class="t1-alert"
      title="商户提现打款前会校验对应渠道账户备付余额；未登记账户为软启动（只告警不拦截）。充值=公司入金，归集=商户销售额转入，调整为差错更正（可正可负）"
    />

    <CrudTable
      :table="accountCrud"
      row-key="accountId"
      :actions="accountActions"
      :action-width="120"
      empty-text="暂无账户——提现软启动期间可直接使用，需要拦截时先建账"
      @action="onAccountAction"
    >
      <el-table-column
        prop="accountId"
        label="ID"
        width="70"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
      <el-table-column prop="channel" label="渠道" width="140">
        <template #default="{ row }">
          <el-tag size="small">{{ row.channel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="accountName" label="账户名称" min-width="160" />
      <el-table-column
        label="备付余额"
        width="140"
        align="right"
        class-name="col-money"
        label-class-name="col-money"
      >
        <template #default="{ row }">¥{{ yuan(row.balanceCents) }}</template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="90"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
            {{ row.status === 'ACTIVE' ? '启用' : row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="更新时间"
        width="170"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
    </CrudTable>

    <!-- 新建账户 -->
    <el-dialog v-model="createVisible" title="新建资金池账户" width="440px">
      <el-form label-width="90px">
        <el-form-item label="渠道" required>
          <el-input v-model="createForm.channel" placeholder="如 WECHAT / ALIPAY / BANK" />
        </el-form-item>
        <el-form-item label="账户名称">
          <el-input v-model="createForm.accountName" placeholder="如 微信运营账户" />
        </el-form-item>
        <el-form-item label="期初余额">
          <el-input-number v-model="createForm.initialYuan" :min="0" :precision="2" :step="100" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 充值 / 归集 / 调整 -->
    <el-dialog v-model="amountVisible" :title="amountTitle" width="440px">
      <el-form label-width="90px">
        <el-form-item :label="amountKind === 'adjust' ? '调整金额' : '金额'" required>
          <el-input-number
            v-model="amountForm.yuan"
            :min="amountKind === 'adjust' ? -9999999 : 0.01"
            :precision="2"
            :step="100"
            style="width: 200px"
          />
          <span v-if="amountKind === 'adjust'" class="form-hint">调整支持负数（差错更正）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="amountForm.remark" placeholder="资金来源 / 依据" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="amountVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAmount">确认</el-button>
      </template>
    </el-dialog>

    <!-- 流水 -->
    <el-dialog v-model="flowsVisible" :title="`流水 — ${flowsAccountLabel}`" width="860px">
      <CrudTable
        :table="flowsCrud"
        row-key="flowId"
        :show-refresh="false"
        max-height="480"
        empty-text="暂无流水"
      >
        <el-table-column
          prop="flowId"
          label="ID"
          width="70"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="类型"
          width="90"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag :type="flowTagType(row.flowType)" size="small">{{
              flowLabel(row.flowType)
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="金额"
          width="130"
          align="right"
          class-name="col-money"
          label-class-name="col-money"
        >
          <template #default="{ row }">
            <span :class="row.amountCents >= 0 ? 'money-in' : 'money-out'">
              {{ row.amountCents >= 0 ? '+' : '' }}¥{{ yuan(row.amountCents) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          label="变动后余额"
          width="130"
          align="right"
          class-name="col-money"
          label-class-name="col-money"
        >
          <template #default="{ row }">¥{{ yuan(row.balanceAfterCents) }}</template>
        </el-table-column>
        <el-table-column
          prop="refType"
          label="关联"
          width="110"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ row.refType || '—' }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140">
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column
          label="时间"
          width="170"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
      </CrudTable>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { yuanText } from '@/utils/display';
import { computed, reactive, ref } from 'vue';
import { Download, EditPen, Plus, Tickets } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';

interface FundAccount {
  accountId: number;
  channel: string;
  accountName?: string;
  balanceCents: number;
  status: string;
  updatedAt?: string;
}

interface FundFlow {
  flowId: number;
  flowType: string;
  amountCents: number;
  balanceAfterCents: number;
  refType?: string;
  refId?: string;
  remark?: string;
  createdAt?: string;
}

const submitting = ref(false);

function yuan(cents?: number | null) {
  return yuanText(cents, '¥0.00');
}

function formatTime(v?: string) {
  // Instant(UTC) → 北京时间；此前截断把 UTC 当本地展示，慢 8 小时（2026-10-14 巡检）
  if (!v) return '无';
  return formatDateTimeMinute(v);
}

// ── 账户列表（CrudTable 化：分页壳 / 空态 / 刷新 / 操作列统一收口） ────────
const accountCrud = useCrudTable<FundAccount>({
  rowKey: (r) => r.accountId,
  fetchPage: async () => {
    const data = await api.request<FundAccount[]>(AdminEndpoints.platformFundAccounts, 'GET');
    // 全量接口（无分页参数）；useCrudTable 的 normalizeListPage 兼容纯数组
    return Array.isArray(data) ? data : [];
  },
  errorMessage: '账户加载失败'
});

function accountActions(_row: FundAccount): CrudRowAction[] {
  return [
    { key: 'recharge', label: '充值', icon: Plus, type: 'primary', perm: 'ops:fund:manage' },
    { key: 'sweep', label: '归集', icon: Download, type: 'primary', perm: 'ops:fund:manage' },
    { key: 'adjust', label: '调整', icon: EditPen, type: 'warning', perm: 'ops:fund:manage' },
    { key: 'flows', label: '流水', icon: Tickets, type: 'info' }
  ];
}

function onAccountAction({ key, row }: { key: string; row: FundAccount }) {
  if (key === 'flows') void openFlows(row);
  else openAmount(row, key as AmountKind);
}

// ── 新建账户 ──────────────────────────────────────────────────────────
const createVisible = ref(false);
const createForm = reactive({ channel: '', accountName: '', initialYuan: 0, remark: '' });

function openCreate() {
  createForm.channel = '';
  createForm.accountName = '';
  createForm.initialYuan = 0;
  createForm.remark = '';
  createVisible.value = true;
}

async function submitCreate() {
  if (!createForm.channel.trim()) {
    ElMessage.warning('请填写渠道');
    return;
  }
  submitting.value = true;
  try {
    await api.request(AdminEndpoints.platformFundAccounts, 'POST', {
      channel: createForm.channel.trim(),
      accountName: createForm.accountName.trim() || null,
      initialBalanceCents: Math.round(createForm.initialYuan * 100),
      remark: createForm.remark.trim() || null
    });
    ElMessage.success('账户已创建');
    createVisible.value = false;
    await accountCrud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  } finally {
    submitting.value = false;
  }
}

// ── 充值 / 归集 / 调整 ────────────────────────────────────────────────
type AmountKind = 'recharge' | 'sweep' | 'adjust';
const amountVisible = ref(false);
const amountKind = ref<AmountKind>('recharge');
const amountTarget = ref<FundAccount | null>(null);
const amountForm = reactive({ yuan: 0, remark: '' });

const amountTitle = computed(() => {
  const name = amountTarget.value ? `${amountTarget.value.channel} 账户` : '';
  return amountKind.value === 'recharge'
    ? `登记充值 — ${name}`
    : amountKind.value === 'sweep'
      ? `登记归集 — ${name}`
      : `差错调整 — ${name}`;
});

function openAmount(row: FundAccount, kind: AmountKind) {
  amountTarget.value = row;
  amountKind.value = kind;
  amountForm.yuan = 0;
  amountForm.remark = '';
  amountVisible.value = true;
}

async function submitAmount() {
  const target = amountTarget.value;
  if (!target) return;
  const cents = Math.round(amountForm.yuan * 100);
  if (amountKind.value !== 'adjust' && cents <= 0) {
    ElMessage.warning('金额必须大于 0');
    return;
  }
  submitting.value = true;
  try {
    const url =
      amountKind.value === 'recharge'
        ? AdminEndpoints.platformFundRecharge(target.accountId)
        : amountKind.value === 'sweep'
          ? AdminEndpoints.platformFundSweep(target.accountId)
          : AdminEndpoints.platformFundAdjust(target.accountId);
    // 调整支持负数（Math.round 保留符号）；充值/归集由 min 保证为正
    await api.request(url, 'POST', {
      amountCents: cents,
      remark: amountForm.remark.trim() || null
    });
    ElMessage.success('已登记');
    amountVisible.value = false;
    await accountCrud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '登记失败');
  } finally {
    submitting.value = false;
  }
}

// ── 流水（弹窗表：CrudTable 壳 + el-table max-height 保持紧凑） ──────────
const flowsVisible = ref(false);
const flowsAccountLabel = ref('');
const flowsAccountId = ref(0);

const flowsCrud = useCrudTable<FundFlow>({
  rowKey: (r) => r.flowId,
  autoLoad: false,
  fetchPage: async () => {
    const data = await api.request<FundFlow[]>(
      AdminEndpoints.platformFundFlows(flowsAccountId.value),
      'GET'
    );
    return Array.isArray(data) ? data : [];
  },
  errorMessage: '流水加载失败'
});

const FLOW_LABELS: Record<string, string> = {
  RECHARGE: '充值',
  SWEEP: '归集',
  ADJUST: '调整',
  PAYOUT: '提现打款'
};

function flowLabel(t: string) {
  return FLOW_LABELS[t] || t;
}

function flowTagType(t: string) {
  if (t === 'PAYOUT') return 'danger';
  if (t === 'ADJUST') return 'warning';
  return 'success';
}

async function openFlows(row: FundAccount) {
  flowsAccountLabel.value = `${row.channel}（${row.accountName || row.accountId}）`;
  flowsAccountId.value = row.accountId;
  flowsVisible.value = true;
  await flowsCrud.load({ resetPage: true });
}
</script>

<style scoped>
.t1-alert {
  margin-bottom: 12px;
}
.form-hint {
  margin-left: 8px;
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
.money-in {
  color: var(--el-color-success);
}
.money-out {
  color: var(--el-color-danger);
}
</style>
