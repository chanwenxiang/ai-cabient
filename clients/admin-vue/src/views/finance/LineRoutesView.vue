<template>
  <div class="line-routes-page">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title">线路与提成</span>
              <span class="hint">线路与成员提成配置 · 月合计财务对账 · 补货员钱包打款</span>
            </div>
          </div>
        </div>
      </template>
      <el-tabs v-model="activeTab">
        <!-- 线路管理 -->
        <el-tab-pane label="线路管理" name="routes">
          <CrudTable
            :table="routesCrud"
            row-key="id"
            :actions="routeActions"
            actions-test-id="line-route"
            @action="onRouteAction"
          >
            <template #toolbar>
              <span v-if="chiefRaw !== null" class="chief-line">
                总线路负责人（多个，可随时改指）：
                <el-tag v-for="c in chiefList" :key="c" size="small" class="chief-tag">{{
                  c
                }}</el-tag>
              </span>
              <el-button type="primary" @click="openChiefEditor">编辑总负责人</el-button>
              <el-button type="primary" @click="openCreate">新建线路</el-button>
            </template>
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="routeName" label="线路名称" min-width="140" />
            <el-table-column prop="managerId" label="线长（负责人 userId）" width="180" />
            <el-table-column prop="remark" label="备注" min-width="140" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                  {{ row.status === 'ACTIVE' ? '启用' : '已归档' }}
                </el-tag>
              </template>
            </el-table-column>
          </CrudTable>
        </el-tab-pane>

        <!-- 岗位月合计 -->
        <el-tab-pane label="月合计（财务对账）" name="monthly">
          <h4>线长销售提成（线路柜销售额合计）</h4>
          <CrudTable :table="summaryManagersCrud" row-key="managerId" :show-refresh="false">
            <template #toolbar>
              <el-date-picker
                v-model="summaryMonth"
                type="month"
                value-format="YYYY-MM"
                placeholder="选择账期"
                :clearable="false"
              />
              <el-button type="primary" @click="loadSummary">查询</el-button>
            </template>
            <el-table-column prop="managerId" label="线长 userId" width="140" />
            <el-table-column prop="orderCount" label="订单数" width="100" />
            <el-table-column label="销售额（元）" width="140">
              <template #default="{ row }">{{ yuan(row.gmvCents) }}</template>
            </el-table-column>
            <el-table-column label="提成（元）">
              <template #default="{ row }">{{ yuan(row.commissionCents) }}</template>
            </el-table-column>
            <el-table-column label="其中退款冲回（元）" width="150">
              <template #default="{ row }">{{ yuan(row.clawbackCents) }}</template>
            </el-table-column>
          </CrudTable>
          <h4>补货员佣金（按实际补货留痕）</h4>
          <CrudTable :table="summaryStaffsCrud" row-key="staffUserId" :show-refresh="false">
            <el-table-column prop="staffUserId" label="补货员 userId" width="140" />
            <el-table-column prop="restockQty" label="补货件数" width="120" />
            <el-table-column label="佣金（元）">
              <template #default="{ row }">{{ yuan(row.commissionCents) }}</template>
            </el-table-column>
            <el-table-column label="其中退款冲回（元）" width="150">
              <template #default="{ row }">{{ yuan(row.clawbackCents) }}</template>
            </el-table-column>
          </CrudTable>
        </el-tab-pane>

        <!-- 补货员钱包 -->
        <el-tab-pane label="补货员钱包" name="wallets">
          <CrudTable
            :table="walletsCrud"
            row-key="staffUserId"
            :actions="walletActions"
            actions-test-id="staff-wallet"
            @action="onWalletAction"
          >
            <el-table-column prop="staffUserId" label="补货员 userId" width="140" />
            <el-table-column label="余额（元）" width="140">
              <template #default="{ row }">{{ yuan(row.balanceCents) }}</template>
            </el-table-column>
          </CrudTable>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 新建/编辑线路 -->
    <el-dialog
      v-model="routeDialogVisible"
      :title="routeForm.id ? '编辑线路' : '新建线路'"
      width="480px"
    >
      <el-form label-width="130px">
        <el-form-item label="线路名称" required>
          <el-input v-model="routeForm.routeName" placeholder="如：A 线路（可随时改）" />
        </el-form-item>
        <el-form-item label="线长 userId" required>
          <el-input-number v-model="routeForm.managerId" :min="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="routeForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="routeDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRoute">保存</el-button>
      </template>
    </el-dialog>

    <!-- 成员管理 -->
    <el-dialog
      v-model="membersDialogVisible"
      :title="`线路成员（${activeRoute?.routeName || ''}）`"
      width="720px"
    >
      <CrudTable
        :table="membersCrud"
        row-key="id"
        :actions="memberActions"
        actions-test-id="route-member"
        :show-refresh="false"
        @action="onMemberAction"
      >
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="userId" label="userId" width="110" />
        <el-table-column label="角色" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.role === 'MANAGER' ? 'danger' : 'primary'">
              {{ row.role === 'MANAGER' ? '线长' : '补货员' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提成基数" width="110">
          <template #default="{ row }">{{ basisText(row.commissionBasis) }}</template>
        </el-table-column>
        <el-table-column label="GMV 比例(bps)" width="120" prop="commissionRateBps" />
        <el-table-column label="每件佣金(元)" width="110">
          <template #default="{ row }">{{ yuan(row.commissionPerUnitCents) }}</template>
        </el-table-column>
      </CrudTable>
      <el-divider>添加/更新成员（同一人可既是线长又是补货员，两行并行）</el-divider>
      <el-form inline>
        <el-form-item label="userId">
          <el-input-number v-model="memberForm.userId" :min="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="memberForm.role" style="width: 110px">
            <el-option label="补货员" value="STAFF" />
            <el-option label="线长" value="MANAGER" />
          </el-select>
        </el-form-item>
        <template v-if="memberForm.role === 'STAFF'">
          <el-form-item label="提成基数">
            <el-select v-model="memberForm.commissionBasis" style="width: 130px">
              <el-option label="按销售额" value="GMV" />
              <el-option label="按补货数量" value="RESTOCK_QTY" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="memberForm.commissionBasis === 'GMV'" label="比例 bps">
            <el-input-number v-model="memberForm.commissionRateBps" :min="0" :max="10000" />
          </el-form-item>
          <el-form-item v-else label="每件佣金(分)">
            <el-input-number v-model="memberForm.commissionPerUnitCents" :min="0" />
          </el-form-item>
        </template>
        <el-form-item>
          <el-button type="primary" @click="saveMember">保存成员</el-button>
        </el-form-item>
      </el-form>
    </el-dialog>

    <!-- 总负责人编辑 -->
    <el-dialog v-model="chiefDialogVisible" title="总线路负责人（多个，逗号分隔）" width="420px">
      <el-input v-model="chiefDraft" placeholder="如：1001,1002（0=未指定）" />
      <template #footer>
        <el-button @click="chiefDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveChief">保存</el-button>
      </template>
    </el-dialog>

    <!-- 标记打款 -->
    <el-dialog v-model="payoutDialogVisible" title="标记线下打款出账" width="420px">
      <el-form label-width="120px">
        <el-form-item label="补货员 userId">
          <span>{{ activeWallet?.staffUserId }}</span>
        </el-form-item>
        <el-form-item label="可用余额（元）">
          <span>{{ yuan(activeWallet?.balanceCents) }}</span>
        </el-form-item>
        <el-form-item label="打款金额（元）" required>
          <el-input-number
            v-model="payoutYuan"
            :min="0.01"
            :precision="2"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="payoutRemark" placeholder="如：支付宝代发回执号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="payoutDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPayout">确认出账</el-button>
      </template>
    </el-dialog>

    <!-- 钱包流水 -->
    <el-dialog v-model="ledgerDialogVisible" title="钱包流水" width="640px">
      <CrudTable :table="ledgerCrud" row-key="ledgerId" :show-refresh="false">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTimeMinute(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="entryType" label="类型" width="150" />
        <el-table-column label="金额（元）" width="110">
          <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
        </el-table-column>
        <el-table-column label="余额（元）">
          <template #default="{ row }">{{ yuan(row.balanceAfter) }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" />
      </CrudTable>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { yuanTextZero as yuan } from '@/utils/display';
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Box, Edit, Money, Tickets, User } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';
import CrudTable, { type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';

interface LineRoute {
  id: number;
  routeName: string;
  managerId: number;
  remark: string | null;
  status: string;
}
interface RouteMember {
  id: number;
  userId: number;
  role: string;
  commissionBasis: string;
  commissionRateBps: number;
  commissionPerUnitCents: number;
}
interface Wallet {
  staffUserId: number;
  balanceCents: number;
}
interface LedgerRow {
  ledgerId: number;
  createdAt: string;
  entryType: string;
  amountCents: number;
  balanceAfter: number;
  remark: string | null;
}
interface ManagerSummary {
  managerId: number;
  orderCount: number;
  gmvCents: number;
  commissionCents: number;
  clawbackCents?: number;
}
interface StaffSummary {
  staffUserId: number;
  restockQty: number;
  commissionCents: number;
  clawbackCents?: number;
}

const activeTab = ref('routes');
const chiefRaw = ref<string | null>(null);
const chiefList = ref<string[]>([]);

const routeDialogVisible = ref(false);
const routeForm = ref({ id: 0, routeName: '', managerId: 1, remark: '' });

const membersDialogVisible = ref(false);
const activeRoute = ref<LineRoute | null>(null);
const memberForm = ref({
  userId: 1,
  role: 'STAFF',
  commissionBasis: 'GMV',
  commissionRateBps: 0,
  commissionPerUnitCents: 0
});

const chiefDialogVisible = ref(false);
const chiefDraft = ref('0');

const summaryMonth = ref(new Date().toISOString().slice(0, 7));

const payoutDialogVisible = ref(false);
const activeWallet = ref<Wallet | null>(null);
const payoutYuan = ref(0);
// 🔴 幂等键（审计 🔴5）：每次打开打款弹窗生成，重复提交/网络重试只扣一次
const payoutRequestNo = ref('');
const payoutRemark = ref('');
const ledgerDialogVisible = ref(false);

function basisText(basis: string): string {
  return basis === 'RESTOCK_QTY' ? '按补货数量' : '按销售额';
}

// 列表状态机统一交给 CrudTable（防回潮门禁：views 禁裸 el-table）。
// 后端均为全量 list 接口 ⇒ fetchPage 拉全量后按页切片。
const routesCrud = useCrudTable<LineRoute>({
  rowKey: (r) => r.id,
  sort: { prop: 'id', mode: 'local', defaultDir: 'desc' },
  fetchPage: async (p) => {
    const all = await api.request<LineRoute[]>(AdminEndpoints.lineRoutes, 'GET');
    const start = p.page * p.size;
    return { items: all.slice(start, start + p.size), total: all.length };
  }
});

const summaryManagersCrud = useCrudTable<ManagerSummary>({
  rowKey: (r) => r.managerId,
  fetchPage: async (p) => {
    const res = await api.request<{ managers: ManagerSummary[]; staffs: StaffSummary[] }>(
      AdminEndpoints.lineCommissionMonthlySummary(summaryMonth.value),
      'GET'
    );
    const all = res.managers ?? [];
    const start = p.page * p.size;
    return { items: all.slice(start, start + p.size), total: all.length };
  }
});

const summaryStaffsCrud = useCrudTable<StaffSummary>({
  rowKey: (r) => r.staffUserId,
  fetchPage: async (p) => {
    const res = await api.request<{ managers: ManagerSummary[]; staffs: StaffSummary[] }>(
      AdminEndpoints.lineCommissionMonthlySummary(summaryMonth.value),
      'GET'
    );
    const all = res.staffs ?? [];
    const start = p.page * p.size;
    return { items: all.slice(start, start + p.size), total: all.length };
  }
});

const walletsCrud = useCrudTable<Wallet>({
  rowKey: (r) => r.staffUserId,
  sort: { prop: 'staffUserId', mode: 'local', defaultDir: 'asc' },
  fetchPage: async (p) => {
    const all = await api.request<Wallet[]>(AdminEndpoints.staffWallets, 'GET');
    const start = p.page * p.size;
    return { items: all.slice(start, start + p.size), total: all.length };
  }
});

const membersCrud = useCrudTable<RouteMember>({
  rowKey: (r) => r.id,
  autoLoad: false,
  fetchPage: async (p) => {
    if (!activeRoute.value) return { items: [], total: 0 };
    const all = await api.request<RouteMember[]>(
      AdminEndpoints.lineRouteMembers(activeRoute.value.id),
      'GET'
    );
    const start = p.page * p.size;
    return { items: all.slice(start, start + p.size), total: all.length };
  }
});

const ledgerCrud = useCrudTable<LedgerRow>({
  rowKey: (r) => r.ledgerId,
  autoLoad: false,
  fetchPage: async (p) => {
    if (!activeWallet.value) return { items: [], total: 0 };
    const all = await api.request<LedgerRow[]>(
      AdminEndpoints.staffWalletLedger(activeWallet.value.staffUserId),
      'GET'
    );
    const start = p.page * p.size;
    return { items: all.slice(start, start + p.size), total: all.length };
  }
});

async function loadChief(): Promise<void> {
  const res = await api.request<{ raw: string }>(AdminEndpoints.lineRouteChiefManagers, 'GET');
  chiefRaw.value = res.raw;
  chiefList.value = res.raw && res.raw !== '0' ? res.raw.split(',').filter(Boolean) : [];
}

function loadSummary(): void {
  void summaryManagersCrud.load({ resetPage: true });
  void summaryStaffsCrud.load({ resetPage: true });
}

function openCreate(): void {
  routeForm.value = { id: 0, routeName: '', managerId: 1, remark: '' };
  routeDialogVisible.value = true;
}

function openEdit(row: LineRoute): void {
  routeForm.value = {
    id: row.id,
    routeName: row.routeName,
    managerId: row.managerId,
    remark: row.remark ?? ''
  };
  routeDialogVisible.value = true;
}

async function saveRoute(): Promise<void> {
  const body = {
    routeName: routeForm.value.routeName,
    managerId: routeForm.value.managerId,
    remark: routeForm.value.remark
  };
  if (routeForm.value.id) {
    await api.request(AdminEndpoints.lineRouteUpdate(routeForm.value.id), 'PUT', body);
  } else {
    await api.request(AdminEndpoints.lineRoutes, 'POST', body);
  }
  ElMessage.success('已保存');
  routeDialogVisible.value = false;
  await routesCrud.load();
}

async function archiveRoute(row: LineRoute): Promise<void> {
  await api.request(AdminEndpoints.lineRouteArchive(row.id), 'POST');
  ElMessage.success('已归档（可增减，不物理删除）');
  await routesCrud.load();
}

function routeActions(row: LineRoute): CrudRowAction[] {
  const actions: CrudRowAction[] = [
    { key: 'edit', label: '编辑', icon: Edit, perm: 'ops:replenishment:edit' },
    { key: 'members', label: '成员', icon: User, perm: 'ops:replenishment:list' }
  ];
  if (row.status === 'ACTIVE') {
    actions.push({
      key: 'archive',
      label: '归档',
      icon: Box,
      type: 'warning',
      perm: 'ops:replenishment:edit'
    });
  }
  return actions;
}

async function onRouteAction(payload: { key: string; row: LineRoute }): Promise<void> {
  if (payload.key === 'edit') openEdit(payload.row);
  else if (payload.key === 'members') await openMembers(payload.row);
  else if (payload.key === 'archive') await archiveRoute(payload.row);
}

async function openMembers(row: LineRoute): Promise<void> {
  activeRoute.value = row;
  membersDialogVisible.value = true;
  await membersCrud.load({ resetPage: true });
}

async function saveMember(): Promise<void> {
  if (!activeRoute.value) return;
  await api.request(AdminEndpoints.lineRouteMembers(activeRoute.value.id), 'PUT', {
    userId: memberForm.value.userId,
    role: memberForm.value.role,
    commissionBasis: memberForm.value.commissionBasis,
    commissionRateBps: memberForm.value.commissionRateBps,
    commissionPerUnitCents: memberForm.value.commissionPerUnitCents
  });
  ElMessage.success('成员已保存（费用项/比例随时可改）');
  await membersCrud.load();
}

function memberActions(): CrudRowAction[] {
  return [
    { key: 'remove', label: '移除', icon: Box, type: 'danger', perm: 'ops:replenishment:edit' }
  ];
}

async function onMemberAction(payload: { key: string; row: RouteMember }): Promise<void> {
  if (payload.key !== 'remove' || !activeRoute.value) return;
  await api.request(
    AdminEndpoints.lineRouteMemberRemove(activeRoute.value.id, payload.row.id),
    'DELETE'
  );
  ElMessage.success('已移除');
  await membersCrud.load();
}

function openChiefEditor(): void {
  chiefDraft.value = chiefRaw.value ?? '0';
  chiefDialogVisible.value = true;
}

async function saveChief(): Promise<void> {
  // 总负责人存于 sys_config（line_route.chief_manager_ids），走系统设置接口由运营台统一改；
  // 此处仅提示入口，避免越权直改配置
  ElMessage.info(`请在系统设置中修改 line_route.chief_manager_ids = ${chiefDraft.value}`);
  chiefDialogVisible.value = false;
}

function walletActions(): CrudRowAction[] {
  return [
    {
      key: 'payout',
      label: '标记打款',
      icon: Money,
      type: 'primary',
      // 审计 T6（2026-10-15）：对齐后端 OpsStaffWalletController（payout=ops:line-manager:edit）
      perm: 'ops:line-manager:edit'
    },
    { key: 'ledger', label: '流水', icon: Tickets, perm: 'ops:line-manager:list' }
  ];
}

async function onWalletAction(payload: { key: string; row: Wallet }): Promise<void> {
  if (payload.key === 'payout') openPayout(payload.row);
  else if (payload.key === 'ledger') await openLedger(payload.row);
}

function openPayout(row: Wallet): void {
  activeWallet.value = row;
  payoutYuan.value = Math.max(0.01, (row.balanceCents ?? 0) / 100);
  payoutRemark.value = '';
  payoutRequestNo.value =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `STAFF_PAYOUT:${row.staffUserId}:${Date.now()}`;
  payoutDialogVisible.value = true;
}

async function submitPayout(): Promise<void> {
  if (!activeWallet.value) return;
  try {
    await api.request(AdminEndpoints.staffWalletPayout(activeWallet.value.staffUserId), 'POST', {
      amountCents: Math.round(payoutYuan.value * 100),
      remark: payoutRemark.value,
      requestNo: payoutRequestNo.value
    });
    ElMessage.success('已标记出账');
    payoutDialogVisible.value = false;
    await walletsCrud.load();
  } catch {
    // 余额不足等错误由后端返回「余额不足，提现失败」，全局拦截器已提示
  }
}

async function openLedger(row: Wallet): Promise<void> {
  activeWallet.value = row;
  ledgerDialogVisible.value = true;
  await ledgerCrud.load({ resetPage: true });
}

onMounted(() => {
  void loadChief();
});
</script>

<style scoped>
.chief-line {
  margin-right: 8px;
  font-size: 13px;
}
.chief-tag {
  margin-right: 4px;
}
h4 {
  margin: 14px 0 6px;
}
</style>
