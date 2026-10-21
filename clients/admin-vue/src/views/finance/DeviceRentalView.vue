<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备租赁</span>
            <span class="hint"
              >押金收据化 + 月租应收账单（CB-034）；登记即按收取方式处置：ONLINE
              从商户余额真扣、OFFLINE 只做收据化登记</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-hasPermi="['ops:rental:manage']"
            type="primary"
            @click="generateVisible = true"
            >生成月租账单</el-button
          >
          <el-button v-hasPermi="['ops:rental:manage']" @click="openRegisterDeposit"
            >登记押金</el-button
          >
          <el-button :icon="Refresh" :loading="tabBusy" @click="reload">刷新</el-button>
        </div>
      </div>
    </template>

    <el-alert
      type="info"
      closable
      show-icon
      class="t1-alert"
      title="押金是负债不是收入：登记→扣款抵欠（累计不超过本金）→退租结算返还；月租账单按月出账，逾期未付自动锁柜（欠租解锁不影响维修/风控锁）"
    />

    <el-tabs v-model="tab">
      <!-- 月租账单 -->
      <el-tab-pane label="月租账单" name="bills">
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="billsCrud.search()">
          <el-form-item label="账期月">
            <el-date-picker
              v-model="billMonth"
              type="month"
              value-format="YYYY-MM"
              placeholder="全部"
              clearable
              style="width: 140px"
            />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="billStatus" clearable placeholder="全部" style="width: 120px">
              <el-option label="未付" value="UNPAID" />
              <el-option label="已付" value="PAID" />
              <el-option label="已作废" value="VOIDED" />
            </el-select>
          </el-form-item>
          <el-form-item label="设备">
            <el-input
              v-model="billDeviceId"
              clearable
              placeholder="设备编号"
              style="width: 160px"
              @keyup.enter="billsCrud.search()"
              @clear="billsCrud.search()"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="billsCrud.search()">查询</el-button>
          </el-form-item>
        </el-form>
        <CrudTable
          :table="billsCrud"
          row-key="billId"
          :show-refresh="false"
          :actions="billActions"
          :action-width="110"
          empty-text="暂无账单——点右上「生成月租账单」按设备月租出账"
          @action="onBillAction"
        >
          <el-table-column
            prop="billId"
            label="ID"
            width="70"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column prop="deviceId" label="设备" width="130" />
          <el-table-column prop="deviceName" label="设备名称" min-width="130">
            <template #default="{ row }">{{ row.deviceName || '无' }}</template>
          </el-table-column>
          <el-table-column prop="merchantId" label="商户" width="120" />
          <el-table-column
            prop="billMonth"
            label="账期月"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column label="月租" width="110" align="right">
            <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="billTagType(row.status)" size="small">
                {{ billLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="锁柜"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag v-if="row.lockAt" type="danger" size="small">已锁</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </CrudTable>
      </el-tab-pane>

      <!-- 押金台账 -->
      <el-tab-pane label="押金台账" name="deposits">
        <CrudTable
          :table="depositsCrud"
          row-key="depositId"
          :show-refresh="false"
          :actions="depositActions"
          :action-width="110"
          empty-text="暂无押金——设备置为 RENTAL 模式后在此登记（线上=从商户余额扣，线下=收据化）"
          @action="onDepositAction"
        >
          <el-table-column
            prop="depositId"
            label="ID"
            width="70"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column prop="deviceId" label="设备" width="130" />
          <el-table-column prop="merchantId" label="商户" width="120" />
          <el-table-column label="押金本金" width="120" align="right">
            <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
          </el-table-column>
          <el-table-column
            label="可用余额"
            width="120"
            align="right"
            class-name="col-money"
            label-class-name="col-money"
          >
            <template #default="{ row }">{{ yuan(row.balanceCents) }}</template>
          </el-table-column>
          <el-table-column
            label="撤机安装费"
            width="110"
            align="right"
            class-name="col-money"
            label-class-name="col-money"
          >
            <template #default="{ row }">{{ yuan(row.installFeeDeductCents ?? 0) }}</template>
          </el-table-column>
          <el-table-column
            label="收取方式"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="row.chargeMode === 'OFFLINE' ? 'info' : 'success'" size="small">
                {{ row.chargeMode === 'OFFLINE' ? '线下' : '线上' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="depositTagType(row.status)" size="small">
                {{ depositLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="登记时间"
            width="170"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
        </CrudTable>
      </el-tab-pane>
    </el-tabs>

    <!-- 生成月租账单 -->
    <el-dialog v-model="generateVisible" title="生成月租账单" width="420px">
      <el-form label-width="90px">
        <el-form-item label="账期月" required>
          <el-date-picker v-model="generateForm.billMonth" type="month" value-format="YYYY-MM" />
        </el-form-item>
        <el-form-item label="设备">
          <el-input v-model="generateForm.deviceId" placeholder="留空=全部 RENTAL 设备" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 登记押金 -->
    <el-dialog v-model="registerVisible" title="登记押金" width="420px">
      <el-form label-width="90px">
        <el-form-item label="设备编号" required>
          <el-input v-model="registerForm.deviceId" placeholder="RENTAL 模式设备" />
        </el-form-item>
        <el-form-item label="押金(元)" required>
          <el-input-number v-model="registerForm.yuan" :min="0.01" :precision="2" :step="100" />
        </el-form-item>
        <!-- CB-044 三件套按柜配置（2026-10-17 用户拍板「费用默认为 0、做成按柜子配置」）：
             老系统是全局硬编码 100 元，我方开箱不收、要收就逐柜填 -->
        <el-form-item label="撤机安装费">
          <el-input-number
            v-model="registerForm.installFeeYuan"
            :min="0"
            :precision="2"
            :step="50"
          />
          <span class="hint">0 = 不收（默认）；撤机结算时从押金扣</span>
        </el-form-item>
        <el-form-item label="合同年数">
          <el-input-number v-model="registerForm.contractYears" :min="1" :max="20" :step="1" />
          <span class="hint">
            用于期满判定：押金已按月退完且满期才发期满奖励；余额未退完时结算仍扣安装费
          </span>
        </el-form-item>
        <el-form-item label="未满期倒扣">
          <el-switch v-model="registerForm.installFeeClawbackEnabled" />
          <span class="hint">押金已退完且未满期时向商户余额追扣（资损面，默认关）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="registerForm.remark" placeholder="收据号 / 收款方式" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRegister">登记</el-button>
      </template>
    </el-dialog>

    <!-- 押金扣款 -->
    <el-dialog v-model="deductVisible" title="押金扣款" width="440px">
      <el-form label-width="90px">
        <el-form-item label="设备">
          <span>{{ deductTarget?.deviceId }}</span>
        </el-form-item>
        <el-form-item label="可用余额">
          <span>{{ yuan(deductTarget?.balanceCents) }}</span>
        </el-form-item>
        <el-form-item label="扣款(元)" required>
          <el-input-number v-model="deductForm.yuan" :min="0.01" :precision="2" :step="100" />
        </el-form-item>
        <el-form-item label="扣款依据" required>
          <el-input v-model="deductForm.reason" placeholder="如：欠租 2026-09 抵扣 / 验机损坏" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deductVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitDeduct">确认扣款</el-button>
      </template>
    </el-dialog>

    <!-- 押金流水 -->
    <el-dialog v-model="eventsVisible" :title="`押金流水 — ${eventsDeviceLabel}`" width="760px">
      <CrudTable
        :table="eventsCrud"
        row-key="eventId"
        :show-refresh="false"
        max-height="440"
        empty-text="暂无流水"
      >
        <el-table-column
          prop="eventId"
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
            <el-tag :type="row.eventType === 'REFUND' ? 'success' : 'warning'" size="small">
              {{ eventLabel(row.eventType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="金额"
          width="120"
          align="right"
          class-name="col-money"
          label-class-name="col-money"
        >
          <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="依据" min-width="150">
          <template #default="{ row }">{{ row.reason || '—' }}</template>
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
import { yuanTextZero as yuan, yuanToCents } from '@/utils/display';
import { computed, reactive, ref, watch } from 'vue';
import { CircleCheck, CircleClose, Money, Refresh, Tickets } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';

interface RentalBill {
  billId: number;
  deviceId: string;
  deviceName?: string;
  merchantId?: string;
  billMonth: string;
  amountCents: number;
  status: string;
  lockAt?: string;
  paidAt?: string;
}

interface RentalDeposit {
  depositId: number;
  deviceId: string;
  merchantId?: string;
  amountCents: number;
  balanceCents: number;
  status: string;
  createdAt?: string;
  /** 撤机安装费（分）：登记时逐柜填，0=不收（V383 默认） */
  installFeeDeductCents?: number;
  /** ONLINE=线上（押金已在商户余额里扣过）/ OFFLINE=线下收缴登记 */
  chargeMode?: string;
  contractYears?: number;
}

interface DepositEvent {
  eventId: number;
  eventType: string;
  amountCents: number;
  reason?: string;
  createdAt?: string;
}

const tab = ref('bills');
const submitting = ref(false);

function formatTime(v?: string) {
  // Instant(UTC) → 北京时间；此前截断把 UTC 当本地展示，慢 8 小时（2026-10-14 巡检）
  if (!v) return '无';
  return formatDateTimeMinute(v);
}

const tabBusy = computed(() => (tab.value === 'bills' ? billsCrud.loading : depositsCrud.loading));

function reload() {
  if (tab.value === 'bills') void billsCrud.refresh();
  else void depositsCrud.load();
}

// ── 月租账单（CrudTable 化：分页/空态/操作列统一收口） ───────────────────
const billMonth = ref('');
const billStatus = ref('');
const billDeviceId = ref('');

const billsCrud = useCrudTable<RentalBill>({
  rowKey: (r) => r.billId,
  fetchPage: async (p) => {
    const q = new URLSearchParams();
    if (billMonth.value) q.set('billMonth', billMonth.value);
    if (billStatus.value) q.set('status', billStatus.value);
    if (billDeviceId.value.trim()) q.set('deviceId', billDeviceId.value.trim());
    // 账单接口 page 从 1 起（其余列表多为 0 基），useCrudTable 给的是 page-1
    q.set('page', String(p.page + 1));
    q.set('size', String(p.size));
    return api.request<{ items: RentalBill[]; total: number }>(
      AdminEndpoints.rentalBills(q),
      'GET'
    );
  },
  errorMessage: '账单加载失败'
});

const BILL_LABELS: Record<string, string> = {
  UNPAID: '未付',
  PAID: '已付',
  VOIDED: '已作废'
};

function billLabel(s: string) {
  return BILL_LABELS[s] || s;
}

function billTagType(s: string) {
  if (s === 'PAID') return 'success';
  if (s === 'VOIDED') return 'info';
  return 'danger';
}

function billActions(row: RentalBill): CrudRowAction[] {
  if (row.status !== 'UNPAID') return [];
  return [
    {
      key: 'markPaid',
      label: '标记已付',
      icon: CircleCheck,
      type: 'success',
      perm: 'ops:rental:manage'
    },
    { key: 'void', label: '作废', icon: CircleClose, type: 'danger', perm: 'ops:rental:manage' }
  ];
}

function onBillAction({ key, row }: { key: string; row: RentalBill }) {
  if (key === 'markPaid') void markPaid(row);
  else if (key === 'void') void voidBill(row);
}

async function markPaid(row: RentalBill) {
  try {
    await ElMessageBox.confirm(
      `确认设备 ${row.deviceId} ${row.billMonth} 月租 ${yuan(row.amountCents)} 已线下收款？`,
      '标记已付',
      { type: 'warning' }
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    await api.request(AdminEndpoints.rentalBillMarkPaid(row.billId), 'POST', {});
    ElMessage.success('已标记已付');
    await billsCrud.refresh();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  } finally {
    submitting.value = false;
  }
}

async function voidBill(row: RentalBill) {
  try {
    await ElMessageBox.confirm(
      `作废后不可恢复：设备 ${row.deviceId} ${row.billMonth} 账单`,
      '作废账单',
      { type: 'warning' }
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    await api.request(AdminEndpoints.rentalBillVoid(row.billId), 'POST', {});
    ElMessage.success('已作废');
    await billsCrud.refresh();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  } finally {
    submitting.value = false;
  }
}

// ── 押金 ──────────────────────────────────────────────────────────────
const DEPOSIT_LABELS: Record<string, string> = {
  HELD: '冻结中',
  SETTLED: '已结算'
};

function depositLabel(s: string) {
  return DEPOSIT_LABELS[s] || s;
}

function depositTagType(s: string) {
  return s === 'SETTLED' ? 'info' : 'warning';
}

const depositsCrud = useCrudTable<RentalDeposit>({
  rowKey: (r) => r.depositId,
  autoLoad: false,
  fetchPage: async () => {
    const data = await api.request<RentalDeposit[]>(AdminEndpoints.rentalDeposits, 'GET');
    return Array.isArray(data) ? data : [];
  },
  errorMessage: '押金加载失败'
});

function depositActions(row: RentalDeposit): CrudRowAction[] {
  const acts: CrudRowAction[] = [{ key: 'events', label: '流水', icon: Tickets, type: 'info' }];
  if (row.status === 'HELD') {
    acts.push(
      { key: 'deduct', label: '扣款', icon: Money, type: 'warning', perm: 'ops:rental:manage' },
      {
        key: 'settle',
        label: '结算返还',
        icon: CircleCheck,
        type: 'success',
        perm: 'ops:rental:manage'
      }
    );
  }
  return acts;
}

function onDepositAction({ key, row }: { key: string; row: RentalDeposit }) {
  if (key === 'events') void openEvents(row);
  else if (key === 'deduct') openDeduct(row);
  else if (key === 'settle') void settle(row);
}

const registerVisible = ref(false);
const registerForm = reactive({
  deviceId: '',
  yuan: 0,
  remark: '',
  installFeeYuan: 0,
  contractYears: 5,
  installFeeClawbackEnabled: false
});

function openRegisterDeposit() {
  registerForm.deviceId = '';
  registerForm.yuan = 0;
  registerForm.remark = '';
  // 三件套默认：安装费 0=不收、合同 5 年、倒扣关（2026-10-17 拍板，对齐 V383/V376 列默认）
  registerForm.installFeeYuan = 0;
  registerForm.contractYears = 5;
  registerForm.installFeeClawbackEnabled = false;
  registerVisible.value = true;
}

async function submitRegister() {
  if (!registerForm.deviceId.trim() || registerForm.yuan <= 0) {
    ElMessage.warning('请填写设备编号与押金金额');
    return;
  }
  submitting.value = true;
  try {
    await api.request(AdminEndpoints.rentalDeposits, 'POST', {
      deviceId: registerForm.deviceId.trim(),
      amountCents: yuanToCents(registerForm.yuan),
      remark: registerForm.remark.trim() || null,
      installFeeDeductCents: yuanToCents(registerForm.installFeeYuan),
      contractYears: registerForm.contractYears,
      installFeeClawbackEnabled: registerForm.installFeeClawbackEnabled
    });
    ElMessage.success('押金已登记');
    registerVisible.value = false;
    tab.value = 'deposits';
    await depositsCrud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '登记失败');
  } finally {
    submitting.value = false;
  }
}

const deductVisible = ref(false);
const deductTarget = ref<RentalDeposit | null>(null);
const deductForm = reactive({ yuan: 0, reason: '' });

function openDeduct(row: RentalDeposit) {
  deductTarget.value = row;
  deductForm.yuan = 0;
  deductForm.reason = '';
  deductVisible.value = true;
}

async function submitDeduct() {
  const target = deductTarget.value;
  if (!target) return;
  if (deductForm.yuan <= 0 || !deductForm.reason.trim()) {
    ElMessage.warning('请填写扣款金额与依据');
    return;
  }
  submitting.value = true;
  try {
    await api.request(AdminEndpoints.rentalDepositDeduct(target.depositId), 'POST', {
      amountCents: Math.round(deductForm.yuan * 100),
      reason: deductForm.reason.trim()
    });
    ElMessage.success('扣款已登记');
    deductVisible.value = false;
    await depositsCrud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '扣款失败');
  } finally {
    submitting.value = false;
  }
}

async function settle(row: RentalDeposit) {
  const refund = Math.max(0, Number(row.balanceCents ?? 0));
  const online = (row.chargeMode ?? 'ONLINE') === 'ONLINE';
  // 与 DeviceRentalService#settle 同口径：只有线上押金扣撤机安装费，且扣额不超过余额
  const fee = online ? Math.min(Math.max(0, Number(row.installFeeDeductCents ?? 0)), refund) : 0;
  const detail = online
    ? `押金余额 ${yuan(refund)}，${fee > 0 ? `扣撤机安装费 ${yuan(fee)}，` : '不收撤机安装费，'}实退 ${yuan(refund - fee)} 退入商户余额`
    : `线下收缴押金：台账记全额退还 ${yuan(refund)}，系统不动钱——须确认已线下退还（撤机安装费线下另收）`;
  try {
    await ElMessageBox.confirm(`设备 ${row.deviceId} ${detail}。确认结算？`, '结算返还', {
      type: 'warning'
    });
  } catch {
    return;
  }
  submitting.value = true;
  try {
    await api.request(AdminEndpoints.rentalDepositSettle(row.depositId), 'POST', {});
    ElMessage.success('押金已结算');
    await depositsCrud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '结算失败');
  } finally {
    submitting.value = false;
  }
}

// ── 押金流水（弹窗表） ─────────────────────────────────────────────────
const eventsVisible = ref(false);
const eventsDeviceLabel = ref('');
const eventsDepositId = ref(0);

const eventsCrud = useCrudTable<DepositEvent>({
  rowKey: (r) => r.eventId,
  autoLoad: false,
  fetchPage: async () => {
    const data = await api.request<DepositEvent[]>(
      AdminEndpoints.rentalDepositEvents(eventsDepositId.value),
      'GET'
    );
    return Array.isArray(data) ? data : [];
  },
  errorMessage: '流水加载失败'
});

const EVENT_LABELS: Record<string, string> = {
  REGISTER: '登记',
  DEDUCT: '扣款',
  REFUND: '返还'
};

function eventLabel(t: string) {
  return EVENT_LABELS[t] || t;
}

async function openEvents(row: RentalDeposit) {
  eventsDeviceLabel.value = row.deviceId;
  eventsDepositId.value = row.depositId;
  eventsVisible.value = true;
  await eventsCrud.load({ resetPage: true });
}

// ── 生成账单 ──────────────────────────────────────────────────────────
const generateVisible = ref(false);
const generateForm = reactive({ billMonth: '', deviceId: '' });

async function submitGenerate() {
  if (!generateForm.billMonth) {
    ElMessage.warning('请选择账期月');
    return;
  }
  submitting.value = true;
  try {
    const body: Record<string, string> = { billMonth: generateForm.billMonth };
    if (generateForm.deviceId.trim()) body.deviceId = generateForm.deviceId.trim();
    await api.request(AdminEndpoints.rentalBillsGenerate, 'POST', body);
    ElMessage.success('账单已生成');
    generateVisible.value = false;
    tab.value = 'bills';
    await billsCrud.refresh();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成失败');
  } finally {
    submitting.value = false;
  }
}

watch(tab, (v) => {
  if (v === 'deposits' && !depositsCrud.items.length) void depositsCrud.load();
});
</script>

<style scoped>
.t1-alert {
  margin-bottom: 12px;
}
</style>
