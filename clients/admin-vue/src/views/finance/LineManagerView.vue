<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">线长钱包</span>
            <span class="hint">线长可自主提现 · 与商户平台分账解耦 · 默认 Mock 打款</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:line-manager:edit']" type="primary" @click="openCreate"
            >新建线长</el-button
          >
          <el-button
            :icon="Refresh"
            :loading="tab === 'withdraws' ? withdrawsLoading : managersLoading"
            @click="reload"
            >刷新</el-button
          >
        </div>
      </div>
    </template>

    <el-tabs v-model="tab" class="line-tabs" @tab-change="onTab">
      <el-tab-pane label="线长成员" name="managers">
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="searchManagers">
          <el-form-item label="关键词">
            <el-input
              v-model="keyword"
              clearable
              placeholder="姓名 / 手机"
              style="width: 180px"
              @keyup.enter="searchManagers"
            />
          </el-form-item>
          <el-form-item label="状态">
            <el-select
              v-model="mStatus"
              clearable
              placeholder="全部"
              style="width: 120px"
              @change="searchManagers"
            >
              <el-option
                v-for="item in dictOptions('line_manager_status')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="searchManagers">查询</el-button>
          </el-form-item>
        </el-form>

        <div class="table-scroll">
          <el-table
            :data="managers"
            v-loading="managersLoading"
            stripe
            border
            class="report-table"
            empty-text=" "
            :default-sort="idDefaultSort"
            @sort-change="onIdSortChange"
          >
            <template #empty
              ><el-empty v-if="managersHydrated && !managersLoading" description="暂无线长"
            /></template>
            <el-table-column
              prop="managerId"
              label="经理编号"
              width="100"
              align="center"
              class-name="col-text"
              sortable="custom"
            >
              <template #default="{ row }">
                <span class="cell-id">{{ row.managerId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="姓名" width="110" align="center" class-name="col-text">
              <template #default="{ row }">{{ row.managerName || '无' }}</template>
            </el-table-column>
            <el-table-column prop="phone" label="手机" width="120" align="center" />
            <el-table-column
              prop="orgName"
              label="组织"
              min-width="110"
              show-overflow-tooltip
              align="center"
            />
            <el-table-column prop="userId" label="绑定用户" width="110" align="center" />
            <el-table-column
              prop="wxOpenid"
              label="openid"
              min-width="140"
              show-overflow-tooltip
              align="center"
            />
            <el-table-column label="余额(元)" width="100" align="center">
              <template #default="{ row }">{{ yuan(row.balanceCents) }}</template>
            </el-table-column>
            <el-table-column label="冻结(元)" width="100" align="center">
              <template #default="{ row }">{{ yuan(row.frozenCents) }}</template>
            </el-table-column>
            <el-table-column label="绑柜" min-width="160" show-overflow-tooltip align="center">
              <template #default="{ row }">{{ (row.deviceIds || []).join(', ') || '无' }}</template>
            </el-table-column>
            <el-table-column prop="commissionRateBps" label="佣金bps" width="90" align="center" />
            <el-table-column
              prop="commissionFixedCents"
              label="固定分/单"
              width="100"
              align="center"
            />
            <el-table-column prop="status" label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ dictLabel('line_manager_status', row.status) || row.status || '未知状态' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="170" align="center">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="300" align="center" class-name="col-action">
              <template #default="{ row }">
                <el-button
                  v-hasPermi="['ops:line-manager:edit']"
                  link
                  type="primary"
                  @click="openBind(row)"
                  >绑柜</el-button
                >
                <el-button v-hasPermi="['ops:line-manager:edit']" link @click="adjust(row)"
                  >调账</el-button
                >
                <el-button link @click="showLedgers(row)">流水</el-button>
                <el-button
                  v-hasPermi="['ops:line-manager:edit']"
                  link
                  type="warning"
                  @click="proxyWithdraw(row)"
                  >代提现</el-button
                >
              </template>
            </el-table-column>
          </el-table>
        </div>
        <PagePager
          :hydrated="managersHydrated"
          v-model:current-page="mPage"
          v-model:page-size="mSize"
          :total="mTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadManagers"
          @size-change="onManagerSizeChange"
        />
      </el-tab-pane>

      <el-tab-pane label="提现审核" name="withdraws">
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="searchWithdraws">
          <el-form-item label="状态">
            <el-select
              v-model="wStatus"
              clearable
              placeholder="全部"
              style="width: 160px"
              @change="searchWithdraws"
            >
              <el-option
                v-for="item in withdrawStatusOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="searchWithdraws">查询</el-button>
          </el-form-item>
        </el-form>

        <div class="table-scroll">
          <el-table
            :data="withdraws"
            v-loading="withdrawsLoading"
            stripe
            border
            class="report-table"
            empty-text=" "
          >
            <template #empty
              ><el-empty v-if="withdrawsHydrated && !withdrawsLoading" description="暂无提现申请"
            /></template>
            <el-table-column prop="requestId" label="单号" width="80" align="center" />
            <el-table-column
              prop="requestNo"
              label="幂等号"
              min-width="160"
              show-overflow-tooltip
              align="center"
            />
            <el-table-column prop="managerName" label="线长" width="100" align="center" />
            <el-table-column prop="phone" label="手机" width="120" align="center" />
            <el-table-column label="金额(元)" width="100" align="center">
              <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="120" align="center">
              <template #default="{ row }">{{ withdrawStatusLabel(row.status) }}</template>
            </el-table-column>
            <el-table-column prop="payChannel" label="通道" width="120" align="center" />
            <el-table-column
              prop="payoutRef"
              label="回执"
              min-width="140"
              show-overflow-tooltip
              align="center"
            />
            <el-table-column
              prop="payoutMessage"
              label="打款说明"
              min-width="140"
              show-overflow-tooltip
              align="center"
            />
            <el-table-column
              prop="reviewRemark"
              label="审核备注"
              min-width="120"
              show-overflow-tooltip
              align="center"
            />
            <el-table-column label="申请时间" width="170" align="center">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="打款时间" width="170" align="center">
              <template #default="{ row }">{{ formatDateTime(row.paidAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" align="center" class-name="col-action">
              <template #default="{ row }">
                <template
                  v-if="row.status === 'PENDING_REVIEW' && auth.hasPerm('ops:line-withdraw:review')"
                >
                  <el-button link type="success" @click="review(row, true)">通过并打款</el-button>
                  <el-button link type="danger" @click="review(row, false)">驳回</el-button>
                </template>
                <el-button
                  v-else-if="
                    (row.status === 'APPROVED' || row.status === 'FAILED') &&
                    auth.hasPerm('ops:line-withdraw:review')
                  "
                  link
                  type="primary"
                  @click="payout(row)"
                  >重试打款</el-button
                >
              </template>
            </el-table-column>
          </el-table>
        </div>
        <PagePager
          :hydrated="withdrawsHydrated"
          v-model:current-page="wPage"
          v-model:page-size="wSize"
          :total="wTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadWithdraws"
          @size-change="onWithdrawSizeChange"
        />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="createVisible" title="新建线长" width="520px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="姓名" required><el-input v-model="form.managerName" /></el-form-item>
        <el-form-item label="手机" required><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="组织"><el-input v-model="form.orgName" /></el-form-item>
        <el-form-item label="openid"
          ><el-input v-model="form.wxOpenid" placeholder="提现到零钱"
        /></el-form-item>
        <el-form-item label="绑定用户ID"
          ><el-input v-model="form.userId" placeholder="可选，商户小程序 userId"
        /></el-form-item>
        <el-form-item label="佣金 bps"
          ><el-input-number v-model="form.commissionRateBps" :min="0" :max="5000"
        /></el-form-item>
        <el-form-item label="固定分/单"
          ><el-input-number v-model="form.commissionFixedCents" :min="0" :max="100000"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="bindVisible" title="绑柜" width="480px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="线长"
          >{{ bindTarget?.managerName }} · {{ bindTarget?.phone }}</el-form-item
        >
        <el-form-item label="设备" required>
          <el-select
            v-model="bindDeviceId"
            filterable
            clearable
            placeholder="从设备列表选择"
            style="width: 100%"
          >
            <el-option
              v-for="d in deviceOptions"
              :key="d.deviceId"
              :label="`${d.deviceName || d.deviceId}（${d.deviceId}）`"
              :value="d.deviceId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bindVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="confirmBind">确定</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="ledgerVisible" title="钱包流水" size="520px">
      <el-table v-loading="!ledgerHydrated" :data="ledgers" size="small" stripe empty-text=" ">
        <template #empty>
          <el-empty v-if="ledgerHydrated" description="暂无流水" :image-size="64" />
        </template>
        <el-table-column label="类型" width="130" align="center">
          <template #default="{ row }">{{
            dictLabel('wallet_entry_type', row.entryType) || row.entryType || '未知'
          }}</template>
        </el-table-column>
        <el-table-column label="变动(元)" width="100" align="center">
          <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
        </el-table-column>
        <el-table-column label="余额后" width="100" align="center">
          <template #default="{ row }">{{ yuan(row.balanceAfter) }}</template>
        </el-table-column>
        <el-table-column label="冻结后" width="100" align="center">
          <template #default="{ row }">{{ yuan(row.frozenAfter) }}</template>
        </el-table-column>
        <el-table-column prop="refType" label="关联" width="110" align="center" />
        <el-table-column
          prop="remark"
          label="备注"
          min-width="140"
          show-overflow-tooltip
          align="center"
        />
        <el-table-column label="时间" width="160" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import PagePager from '@/components/PagePager.vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { useDeviceOptions } from '@/composables/useDeviceOptions';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { useDictOptions } from '@/composables/useDictOptions';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

interface Manager {
  managerId: number;
  managerName: string;
  phone: string;
  orgName?: string;
  userId?: number;
  wxOpenid?: string;
  balanceCents?: number;
  frozenCents?: number;
  deviceIds?: string[];
  commissionRateBps?: number;
  commissionFixedCents?: number;
  status?: string;
  createdAt?: string;
}

interface Withdraw {
  requestId: number;
  requestNo: string;
  managerName?: string;
  phone?: string;
  amountCents: number;
  status: string;
  payChannel?: string;
  payoutRef?: string;
  payoutMessage?: string;
  reviewRemark?: string;
  createdAt?: string;
  paidAt?: string;
}

const auth = useAuthStore();
const tab = ref('managers');
const managersLoading = ref(false);
const withdrawsLoading = ref(false);
const managersHydrated = ref(false);
const withdrawsHydrated = ref(false);
const saving = ref(false);
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('managerId', {
  onChange: () => {
    managers.value = sortById([...managers.value]);
  }
});

const managers = ref<Manager[]>([]);
const mPage = ref(1);
const mSize = ref(20);
const mTotal = ref(0);
const keyword = ref('');
const mStatus = ref('');
const withdraws = ref<Withdraw[]>([]);
const wPage = ref(1);
const wSize = ref(20);
const wTotal = ref(0);
const wStatus = ref('');
const createVisible = ref(false);
const bindVisible = ref(false);
const bindTarget = ref<Manager | null>(null);
const bindDeviceId = ref('');
const { deviceOptions, loadDeviceOptions } = useDeviceOptions();
const ledgerVisible = ref(false);
const ledgerHydrated = ref(false);
const ledgers = ref<any[]>([]);
const form = reactive({
  managerName: '',
  phone: '',
  orgName: '',
  wxOpenid: '',
  userId: '',
  commissionRateBps: 200,
  commissionFixedCents: 0
});

const withdrawStatusOptions = useDictOptions('line_withdraw_status');

function yuan(cents?: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}
function withdrawStatusLabel(s?: string) {
  return dictLabel('line_withdraw_status', s) || s || '未知状态';
}

async function loadManagers() {
  managersLoading.value = true;
  try {
    const q = new URLSearchParams({ page: String(mPage.value - 1), size: String(mSize.value) });
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim());
    if (mStatus.value) q.set('status', mStatus.value);
    const res = await api.request<{ items: Manager[]; total: number }>(
      `/api/v2/ops/admin/line-managers?${q}`,
      'GET'
    );
    managers.value = sortById(res.items || []);
    mTotal.value = Number(res.total || 0);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    managersHydrated.value = true;
    managersLoading.value = false;
  }
}

async function loadWithdraws() {
  withdrawsLoading.value = true;
  try {
    const q = new URLSearchParams({ page: String(wPage.value - 1), size: String(wSize.value) });
    if (wStatus.value) q.set('status', wStatus.value);
    const res = await api.request<{ items: Withdraw[]; total: number }>(
      `/api/v2/ops/admin/line-withdraws?${q}`,
      'GET'
    );
    withdraws.value = res.items || [];
    wTotal.value = Number(res.total || 0);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    withdrawsHydrated.value = true;
    withdrawsLoading.value = false;
  }
}

function searchManagers() {
  mPage.value = 1;
  loadManagers();
}
function onManagerSizeChange() {
  mPage.value = 1;
  loadManagers();
}
function searchWithdraws() {
  wPage.value = 1;
  loadWithdraws();
}
function onWithdrawSizeChange() {
  wPage.value = 1;
  loadWithdraws();
}
function onTab() {
  if (tab.value === 'managers') loadManagers();
  else loadWithdraws();
}
function reload() {
  onTab();
}

function openCreate() {
  form.managerName = '';
  form.phone = '';
  form.orgName = '';
  form.wxOpenid = '';
  form.userId = '';
  form.commissionRateBps = 200;
  form.commissionFixedCents = 0;
  createVisible.value = true;
}

async function create() {
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/line-managers', 'POST', {
      managerName: form.managerName,
      phone: form.phone,
      orgName: form.orgName || null,
      wxOpenid: form.wxOpenid || null,
      userId: form.userId ? Number(form.userId) : null,
      commissionRateBps: form.commissionRateBps,
      commissionFixedCents: form.commissionFixedCents
    });
    ElMessage.success('已创建');
    createVisible.value = false;
    await loadManagers();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  } finally {
    saving.value = false;
  }
}

function openBind(row: Manager) {
  bindTarget.value = row;
  bindDeviceId.value = '';
  bindVisible.value = true;
  if (!deviceOptions.value.length) void loadDeviceOptions();
}

async function confirmBind() {
  if (!bindTarget.value || !bindDeviceId.value) {
    ElMessage.warning('请选择设备');
    return;
  }
  saving.value = true;
  try {
    await api.request(
      `/api/v2/ops/admin/line-managers/${bindTarget.value.managerId}/devices`,
      'POST',
      {
        deviceId: bindDeviceId.value
      }
    );
    ElMessage.success('已绑定');
    bindVisible.value = false;
    await loadManagers();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '失败');
  } finally {
    saving.value = false;
  }
}

async function adjust(row: Manager) {
  try {
    const { value } = await ElMessageBox.prompt('调账金额（元，可负）', '调账', {
      inputPattern: /^-?\d+(\.\d{1,2})?$/,
      inputErrorMessage: '请输入金额'
    });
    const amountCents = Math.round(Number(value) * 100);
    await ElMessageBox.confirm(
      `确认对线长 ${row.managerName || row.managerId} 调账 ¥${(amountCents / 100).toFixed(2)}？该操作将写入审计日志。`,
      '调账二次确认',
      { type: 'warning', confirmButtonText: '确认调账', cancelButtonText: '取消' }
    );
    await api.request(`/api/v2/ops/admin/line-managers/${row.managerId}/adjust`, 'POST', {
      amountCents,
      remark: '运营调账'
    });
    ElMessage.success('已调账');
    await loadManagers();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e instanceof Error ? e.message : '失败');
  }
}

async function showLedgers(row: Manager) {
  ledgers.value = [];
  ledgerVisible.value = true;
  try {
    ledgers.value = await api.request(
      `/api/v2/ops/admin/line-managers/${row.managerId}/ledgers?limit=50`,
      'GET'
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载流水失败');
    ledgers.value = [];
  } finally {
    ledgerHydrated.value = true;
  }
}

async function proxyWithdraw(row: Manager) {
  try {
    const { value } = await ElMessageBox.prompt('代发起提现金额（元）', '代提现', {
      inputPattern: /^\d+(\.\d{1,2})?$/,
      inputErrorMessage: '请输入金额'
    });
    const amountCents = Math.round(Number(value) * 100);
    await ElMessageBox.confirm(
      `确认代线长 ${row.managerName || row.managerId} 发起提现 ¥${(amountCents / 100).toFixed(2)}？`,
      '代提现二次确认',
      { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '取消' }
    );
    await api.request(`/api/v2/ops/admin/line-managers/${row.managerId}/withdraw`, 'POST', {
      amountCents
    });
    ElMessage.success('已提交提现');
    tab.value = 'withdraws';
    wStatus.value = '';
    await loadWithdraws();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e instanceof Error ? e.message : '失败');
  }
}

async function review(row: Withdraw, approve: boolean) {
  try {
    await ElMessageBox.confirm(
      approve ? `确认通过该提现申请并打款 ¥${yuan(row.amountCents)}？` : `确认驳回该提现申请？`,
      approve ? '通过并打款' : '驳回申请',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    );
    await api.request(`/api/v2/ops/admin/line-withdraws/${row.requestId}/review`, 'POST', {
      approve,
      remark: approve ? '审核通过' : '审核驳回'
    });
    ElMessage.success(approve ? '已通过并尝试打款' : '已驳回');
    await loadWithdraws();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '失败');
  }
}

async function payout(row: Withdraw) {
  try {
    await ElMessageBox.confirm(
      `确认对该提现申请（¥${yuan(row.amountCents)}）重新打款？`,
      '重试打款',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    );
    await api.request(`/api/v2/ops/admin/line-withdraws/${row.requestId}/payout`, 'POST', {});
    ElMessage.success('已触发打款');
    await loadWithdraws();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '失败');
  }
}

onMounted(async () => {
  await loadDeviceOptions();
  await loadManagers();
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
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
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.line-tabs {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
</style>
