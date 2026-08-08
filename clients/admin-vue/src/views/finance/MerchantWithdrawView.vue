<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">商户提现</span>
            <span class="hint">商户可提现余额 · LEDGER_ONLY 分账自动入账 · 默认 Mock 打款</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            :icon="Refresh"
            :loading="tab === 'withdraws' ? withdrawsLoading : walletsLoading"
            @click="reload"
            >刷新</el-button
          >
        </div>
      </div>
    </template>

    <el-tabs v-model="tab" @tab-change="onTab">
      <el-tab-pane label="商户钱包" name="wallets">
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="searchWallets">
          <el-form-item label="关键词">
            <el-input
              v-model="keyword"
              clearable
              placeholder="商户编号 / 名称 / 手机"
              style="width: 220px"
              @keyup.enter="searchWallets"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="searchWallets">查询</el-button>
          </el-form-item>
        </el-form>

        <div class="table-scroll">
          <el-table
            :data="wallets"
            v-loading="walletsLoading"
            stripe
            border
            class="report-table"
            empty-text=" "
          >
            <template #empty
              ><el-empty v-if="walletsHydrated && !walletsLoading" description="暂无商户钱包"
            /></template>
            <el-table-column prop="merchantId" label="商户ID" min-width="120" align="center" />
            <el-table-column
              prop="merchantName"
              label="名称"
              min-width="140"
              show-overflow-tooltip
              align="center"
            />
            <el-table-column prop="contactPhone" label="联系电话" width="130" align="center" />
            <el-table-column label="余额(元)" width="110" align="center">
              <template #default="{ row }">{{ yuan(row.balanceCents) }}</template>
            </el-table-column>
            <el-table-column label="冻结(元)" width="110" align="center">
              <template #default="{ row }">{{ yuan(row.frozenCents) }}</template>
            </el-table-column>
            <el-table-column label="可用(元)" width="110" align="center">
              <template #default="{ row }">{{ yuan(row.availableCents) }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="90" align="center" />
            <el-table-column label="操作" width="260" align="center" class-name="col-action">
              <template #default="{ row }">
                <el-button
                  v-hasPermi="['ops:merchant-withdraw:adjust']"
                  link
                  type="primary"
                  @click="openAdjust(row)"
                  >调账</el-button
                >
                <el-button link @click="showLedgers(row)">流水</el-button>
                <el-button
                  v-hasPermi="['ops:merchant-withdraw:adjust']"
                  link
                  type="warning"
                  @click="openWithdraw(row)"
                  >代提现</el-button
                >
              </template>
            </el-table-column>
          </el-table>
        </div>
        <PagePager
          :hydrated="walletsHydrated"
          v-model:current-page="wPage"
          v-model:page-size="wSize"
          :total="wTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadWallets"
          @size-change="onWalletSizeChange"
        />
      </el-tab-pane>

      <el-tab-pane label="提现审核" name="withdraws">
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="searchWithdraws">
          <el-form-item label="状态">
            <el-select
              v-model="wdStatus"
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
            <el-table-column prop="merchantId" label="商户ID" width="120" align="center" />
            <el-table-column
              prop="merchantName"
              label="商户"
              min-width="120"
              show-overflow-tooltip
              align="center"
            />
            <el-table-column label="金额(元)" width="100" align="center">
              <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="120" align="center">
              <template #default="{ row }">{{ withdrawStatusLabel(row.status) }}</template>
            </el-table-column>
            <el-table-column prop="payChannel" label="通道" width="100" align="center" />
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
            <el-table-column label="操作" width="200" align="center" class-name="col-action">
              <template #default="{ row }">
                <template
                  v-if="
                    row.status === 'PENDING_REVIEW' && auth.hasPerm('ops:merchant-withdraw:review')
                  "
                >
                  <el-button link type="success" @click="review(row, true)">通过并打款</el-button>
                  <el-button link type="danger" @click="review(row, false)">驳回</el-button>
                </template>
                <el-button
                  v-else-if="
                    (row.status === 'APPROVED' || row.status === 'FAILED') &&
                    auth.hasPerm('ops:merchant-withdraw:review')
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
          v-model:current-page="wdPage"
          v-model:page-size="wdSize"
          :total="wdTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadWithdraws"
          @size-change="onWdSizeChange"
        />
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="ledgerVisible" title="钱包流水" size="520px">
      <el-table v-loading="!ledgerHydrated" :data="ledgers" size="small" stripe empty-text=" ">
        <template #empty>
          <el-empty v-if="ledgerHydrated" description="暂无流水" :image-size="64" />
        </template>
        <el-table-column prop="entryType" label="类型" width="130" align="center" />
        <el-table-column label="变动(元)" width="100" align="center">
          <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
        </el-table-column>
        <el-table-column label="余额后" width="100" align="center">
          <template #default="{ row }">{{ yuan(row.balanceAfter) }}</template>
        </el-table-column>
        <el-table-column label="冻结后" width="100" align="center">
          <template #default="{ row }">{{ yuan(row.frozenAfter) }}</template>
        </el-table-column>
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

    <el-dialog
      v-model="adjustVisible"
      title="商户调账"
      width="460px"
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
    >
      <div v-if="adjustTarget" class="dialog-merchant">
        <div class="dialog-merchant__name">
          {{ adjustTarget.merchantName || adjustTarget.merchantId }}
        </div>
        <div class="dialog-merchant__id">商户 {{ adjustTarget.merchantId }}</div>
        <div class="dialog-balance">
          <span
            >当前余额 <b>¥{{ yuan(adjustTarget.balanceCents) }}</b></span
          >
          <span
            >可用 <b>¥{{ yuan(adjustTarget.availableCents) }}</b></span
          >
        </div>
      </div>
      <el-form label-position="top" @submit.prevent="submitAdjust">
        <el-form-item label="调整金额（元，负数扣减）" required>
          <el-input-number
            v-model="adjustForm.amount"
            :precision="2"
            :step="10"
            :min="-1000000"
            :max="1000000"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="adjustForm.remark" maxlength="100" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjustSaving" @click="submitAdjust">确认调账</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="withdrawVisible"
      title="代商户提现"
      width="460px"
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
    >
      <div v-if="withdrawTarget" class="dialog-merchant">
        <div class="dialog-merchant__name">
          {{ withdrawTarget.merchantName || withdrawTarget.merchantId }}
        </div>
        <div class="dialog-merchant__id">商户 {{ withdrawTarget.merchantId }}</div>
        <div class="dialog-balance">
          <span
            >可用余额 <b>¥{{ yuan(withdrawTarget.availableCents) }}</b></span
          >
          <span
            >冻结 <b>¥{{ yuan(withdrawTarget.frozenCents) }}</b></span
          >
        </div>
      </div>
      <el-form label-position="top" @submit.prevent="submitWithdraw">
        <el-form-item label="提现金额（元）" required>
          <el-input-number
            v-model="withdrawForm.amount"
            :precision="2"
            :step="10"
            :min="1"
            :max="10000000"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="withdrawVisible = false">取消</el-button>
        <el-button type="warning" :loading="withdrawSaving" @click="submitWithdraw"
          >确认提现</el-button
        >
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import PagePager from '@/components/PagePager.vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { displayLabel } from '@aicabinet/shared-dict';
import { useDictOptions } from '@/composables/useDictOptions';

interface WalletRow {
  merchantId: string;
  merchantName?: string;
  contactPhone?: string;
  status?: string;
  balanceCents?: number;
  frozenCents?: number;
  availableCents?: number;
}

interface Withdraw {
  requestId: number;
  requestNo: string;
  merchantId: string;
  merchantName?: string;
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
const tab = ref('wallets');
const walletsLoading = ref(false);
const withdrawsLoading = ref(false);
const walletsHydrated = ref(false);
const withdrawsHydrated = ref(false);
const wallets = ref<WalletRow[]>([]);
const wPage = ref(1);
const wSize = ref(20);
const wTotal = ref(0);
const keyword = ref('');
const withdraws = ref<Withdraw[]>([]);
const wdPage = ref(1);
const wdSize = ref(20);
const wdTotal = ref(0);
const wdStatus = ref('');
const ledgerVisible = ref(false);
const ledgerHydrated = ref(false);
const ledgers = ref<any[]>([]);
const adjustVisible = ref(false);
const adjustSaving = ref(false);
const adjustTarget = ref<WalletRow | null>(null);
const adjustForm = ref({ amount: 0, remark: '' });
const withdrawVisible = ref(false);
const withdrawSaving = ref(false);
const withdrawTarget = ref<WalletRow | null>(null);
const withdrawForm = ref({ amount: 0 });

const withdrawStatusOptions = useDictOptions('merchant_withdraw_status');

function yuan(cents?: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}

function withdrawStatusLabel(status?: string) {
  return displayLabel('merchant_withdraw_status', status, '未知状态');
}

function onTab() {
  reload();
}

function reload() {
  if (tab.value === 'withdraws') loadWithdraws();
  else loadWallets();
}

function searchWallets() {
  wPage.value = 1;
  loadWallets();
}

function onWalletSizeChange() {
  wPage.value = 1;
  loadWallets();
}

function searchWithdraws() {
  wdPage.value = 1;
  loadWithdraws();
}

function onWdSizeChange() {
  wdPage.value = 1;
  loadWithdraws();
}

async function loadWallets() {
  walletsLoading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(wPage.value - 1),
      size: String(wSize.value)
    });
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim());
    const res = await api.request<{ items: WalletRow[]; total: number }>(
      `/api/v2/ops/admin/merchant-wallets?${q}`,
      'GET'
    );
    wallets.value = res.items || [];
    wTotal.value = res.total || 0;
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败');
  } finally {
    walletsHydrated.value = true;
    walletsLoading.value = false;
  }
}

async function loadWithdraws() {
  withdrawsLoading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(wdPage.value - 1),
      size: String(wdSize.value)
    });
    if (wdStatus.value) q.set('status', wdStatus.value);
    const res = await api.request<{ items: Withdraw[]; total: number }>(
      `/api/v2/ops/admin/merchant-withdraws?${q}`,
      'GET'
    );
    withdraws.value = res.items || [];
    wdTotal.value = res.total || 0;
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败');
  } finally {
    withdrawsHydrated.value = true;
    withdrawsLoading.value = false;
  }
}

function openAdjust(row: WalletRow) {
  adjustTarget.value = row;
  adjustForm.value = { amount: 0, remark: '' };
  adjustVisible.value = true;
}

async function submitAdjust() {
  if (!adjustTarget.value) return;
  if (!adjustForm.value.amount || Number.isNaN(adjustForm.value.amount)) {
    ElMessage.warning('请输入调整金额');
    return;
  }
  adjustSaving.value = true;
  try {
    const amountCents = Math.round(Number(adjustForm.value.amount) * 100);
    await api.request(
      `/api/v2/ops/admin/merchant-wallets/${encodeURIComponent(adjustTarget.value.merchantId)}/adjust`,
      'POST',
      { amountCents, remark: adjustForm.value.remark.trim() || '运营调账' }
    );
    ElMessage.success('已调账');
    adjustVisible.value = false;
    await loadWallets();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '调账失败');
  } finally {
    adjustSaving.value = false;
  }
}

async function showLedgers(row: WalletRow) {
  ledgers.value = [];
  ledgerVisible.value = true;
  try {
    ledgers.value = await api.request(
      `/api/v2/ops/admin/merchant-wallets/${encodeURIComponent(row.merchantId)}/ledgers?limit=50`,
      'GET'
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载流水失败');
    ledgers.value = [];
  } finally {
    ledgerHydrated.value = true;
  }
}

function openWithdraw(row: WalletRow) {
  withdrawTarget.value = row;
  withdrawForm.value = { amount: 0 };
  withdrawVisible.value = true;
}

async function submitWithdraw() {
  if (!withdrawTarget.value) return;
  if (!withdrawForm.value.amount || Number(withdrawForm.value.amount) <= 0) {
    ElMessage.warning('请输入大于 0 的提现金额');
    return;
  }
  withdrawSaving.value = true;
  try {
    const amountCents = Math.round(Number(withdrawForm.value.amount) * 100);
    await api.request(
      `/api/v2/ops/admin/merchant-wallets/${encodeURIComponent(withdrawTarget.value.merchantId)}/withdraw`,
      'POST',
      { amountCents }
    );
    ElMessage.success('已提交提现');
    withdrawVisible.value = false;
    tab.value = 'withdraws';
    await loadWithdraws();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '代提现失败');
  } finally {
    withdrawSaving.value = false;
  }
}

async function review(row: Withdraw, approve: boolean) {
  try {
    await ElMessageBox.confirm(
      approve ? `确认通过该提现申请并打款 ¥${yuan(row.amountCents)}？` : `确认驳回该提现申请？`,
      approve ? '通过并打款' : '驳回申请',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    );
    await api.request(`/api/v2/ops/admin/merchant-withdraws/${row.requestId}/review`, 'POST', {
      approve,
      remark: approve ? '审核通过' : '审核驳回'
    });
    ElMessage.success(approve ? '已通过' : '已驳回');
    await loadWithdraws();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '审核失败');
    }
  }
}

async function payout(row: Withdraw) {
  try {
    await ElMessageBox.confirm(
      `确认对该提现申请（¥${yuan(row.amountCents)}）重新打款？`,
      '重试打款',
      {
        type: 'warning',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      }
    );
    await api.request(`/api/v2/ops/admin/merchant-withdraws/${row.requestId}/payout`, 'POST', {});
    ElMessage.success('已重试打款');
    await loadWithdraws();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '打款失败');
    }
  }
}

onMounted(reload);
</script>

<style scoped>
.dialog-merchant {
  padding: 12px 14px;
  margin-bottom: 16px;
  border: 1px solid var(--layout-border);
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.dialog-merchant__name {
  font-weight: 600;
  font-size: 15px;
}
.dialog-merchant__id {
  color: var(--layout-muted);
  font-size: 12px;
  margin-top: 2px;
}
.dialog-balance {
  display: flex;
  gap: 20px;
  margin-top: 10px;
  font-size: 13px;
  color: var(--layout-muted);
}
.dialog-balance b {
  color: var(--layout-text);
  font-variant-numeric: tabular-nums;
}
</style>
