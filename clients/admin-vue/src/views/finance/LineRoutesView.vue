<template>
  <div class="line-routes-page">
    <el-tabs v-model="activeTab">
      <!-- 线路管理 -->
      <el-tab-pane label="线路管理" name="routes">
        <div class="toolbar">
          <span v-if="chiefRaw !== null" class="chief-line">
            总线路负责人（多个，可随时改指）：
            <el-tag v-for="c in chiefList" :key="c" size="small" class="chief-tag">{{ c }}</el-tag>
          </span>
          <el-button type="primary" @click="openChiefEditor">编辑总负责人</el-button>
          <el-button type="primary" @click="openCreate">新建线路</el-button>
          <el-button @click="loadRoutes">刷新</el-button>
        </div>
        <el-table :data="routes" border size="small" v-loading="loadingRoutes">
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
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" @click="openMembers(row)">成员</el-button>
              <el-button
                v-if="row.status === 'ACTIVE'"
                size="small"
                type="warning"
                @click="archiveRoute(row)"
              >
                归档
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 岗位月合计 -->
      <el-tab-pane label="月合计（财务对账）" name="monthly">
        <div class="toolbar">
          <el-date-picker
            v-model="summaryMonth"
            type="month"
            value-format="YYYY-MM"
            placeholder="选择账期"
            :clearable="false"
          />
          <el-button type="primary" @click="loadSummary">查询</el-button>
        </div>
        <h4>线长销售提成（线路柜销售额合计）</h4>
        <el-table :data="summaryManagers" border size="small" v-loading="loadingSummary">
          <el-table-column prop="managerId" label="线长 userId" width="140" />
          <el-table-column prop="orderCount" label="订单数" width="100" />
          <el-table-column label="销售额（元）" width="140">
            <template #default="{ row }">{{ yuan(row.gmvCents) }}</template>
          </el-table-column>
          <el-table-column label="提成（元）">
            <template #default="{ row }">{{ yuan(row.commissionCents) }}</template>
          </el-table-column>
        </el-table>
        <h4>补货员佣金（按实际补货留痕）</h4>
        <el-table :data="summaryStaffs" border size="small">
          <el-table-column prop="staffUserId" label="补货员 userId" width="140" />
          <el-table-column prop="restockQty" label="补货件数" width="120" />
          <el-table-column label="佣金（元）">
            <template #default="{ row }">{{ yuan(row.commissionCents) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 补货员钱包 -->
      <el-tab-pane label="补货员钱包" name="wallets">
        <div class="toolbar">
          <el-button @click="loadWallets">刷新</el-button>
        </div>
        <el-table :data="wallets" border size="small" v-loading="loadingWallets">
          <el-table-column prop="staffUserId" label="补货员 userId" width="140" />
          <el-table-column label="余额（元）" width="140">
            <template #default="{ row }">{{ yuan(row.balanceCents) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="openPayout(row)">标记打款</el-button>
              <el-button size="small" @click="openLedger(row)">流水</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

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
      <el-table :data="members" border size="small" v-loading="loadingMembers">
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
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" type="danger" @click="removeMember(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
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
      <el-table :data="ledger" border size="small" v-loading="loadingLedger">
        <el-table-column prop="createdAt" label="时间" width="170">
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
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';

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
  createdAt: string;
  entryType: string;
  amountCents: number;
  balanceAfter: number;
  remark: string | null;
}

const activeTab = ref('routes');
const routes = ref<LineRoute[]>([]);
const loadingRoutes = ref(false);
const chiefRaw = ref<string | null>(null);
const chiefList = ref<string[]>([]);

const routeDialogVisible = ref(false);
const routeForm = ref({ id: 0, routeName: '', managerId: 1, remark: '' });

const membersDialogVisible = ref(false);
const activeRoute = ref<LineRoute | null>(null);
const members = ref<RouteMember[]>([]);
const loadingMembers = ref(false);
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
const loadingSummary = ref(false);
const summaryManagers = ref<
  { managerId: number; orderCount: number; gmvCents: number; commissionCents: number }[]
>([]);
const summaryStaffs = ref<{ staffUserId: number; restockQty: number; commissionCents: number }[]>(
  []
);

const wallets = ref<Wallet[]>([]);
const loadingWallets = ref(false);
const payoutDialogVisible = ref(false);
const activeWallet = ref<Wallet | null>(null);
const payoutYuan = ref(0);
// 🔴 幂等键（审计 🔴5）：每次打开打款弹窗生成，重复提交/网络重试只扣一次
const payoutRequestNo = ref('');
const payoutRemark = ref('');
const ledgerDialogVisible = ref(false);
const ledger = ref<LedgerRow[]>([]);
const loadingLedger = ref(false);

function yuan(cents?: number | null): string {
  return ((cents ?? 0) / 100).toFixed(2);
}
function basisText(basis: string): string {
  return basis === 'RESTOCK_QTY' ? '按补货数量' : '按销售额';
}

async function loadRoutes(): Promise<void> {
  loadingRoutes.value = true;
  try {
    routes.value = await api.request<LineRoute[]>(AdminEndpoints.lineRoutes, 'GET');
  } finally {
    loadingRoutes.value = false;
  }
}

async function loadChief(): Promise<void> {
  const res = await api.request<{ raw: string }>(AdminEndpoints.lineRouteChiefManagers, 'GET');
  chiefRaw.value = res.raw;
  chiefList.value = res.raw && res.raw !== '0' ? res.raw.split(',').filter(Boolean) : [];
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
  await loadRoutes();
}

async function archiveRoute(row: LineRoute): Promise<void> {
  await api.request(AdminEndpoints.lineRouteArchive(row.id), 'POST');
  ElMessage.success('已归档（可增减，不物理删除）');
  await loadRoutes();
}

async function openMembers(row: LineRoute): Promise<void> {
  activeRoute.value = row;
  membersDialogVisible.value = true;
  loadingMembers.value = true;
  try {
    members.value = await api.request<RouteMember[]>(
      AdminEndpoints.lineRouteMembers(row.id),
      'GET'
    );
  } finally {
    loadingMembers.value = false;
  }
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
  await openMembers(activeRoute.value);
}

async function removeMember(row: RouteMember): Promise<void> {
  if (!activeRoute.value) return;
  await api.request(AdminEndpoints.lineRouteMemberRemove(activeRoute.value.id, row.id), 'DELETE');
  ElMessage.success('已移除');
  await openMembers(activeRoute.value);
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

async function loadSummary(): Promise<void> {
  loadingSummary.value = true;
  try {
    const res = await api.request<{
      managers: typeof summaryManagers.value;
      staffs: typeof summaryStaffs.value;
    }>(AdminEndpoints.lineCommissionMonthlySummary(summaryMonth.value), 'GET');
    summaryManagers.value = res.managers ?? [];
    summaryStaffs.value = res.staffs ?? [];
  } finally {
    loadingSummary.value = false;
  }
}

async function loadWallets(): Promise<void> {
  loadingWallets.value = true;
  try {
    wallets.value = await api.request<Wallet[]>(AdminEndpoints.staffWallets, 'GET');
  } finally {
    loadingWallets.value = false;
  }
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
    await loadWallets();
  } catch {
    // 余额不足等错误由后端返回「余额不足，提现失败」，全局拦截器已提示
  }
}

async function openLedger(row: Wallet): Promise<void> {
  activeWallet.value = row;
  ledgerDialogVisible.value = true;
  loadingLedger.value = true;
  try {
    ledger.value = await api.request<LedgerRow[]>(
      AdminEndpoints.staffWalletLedger(row.staffUserId),
      'GET'
    );
  } finally {
    loadingLedger.value = false;
  }
}

onMounted(() => {
  void loadRoutes();
  void loadChief();
  void loadSummary();
  void loadWallets();
});
</script>

<style scoped>
.line-routes-page {
  padding: 8px 4px;
}
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}
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
