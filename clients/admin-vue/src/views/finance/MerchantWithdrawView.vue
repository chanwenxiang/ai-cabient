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
          <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="tab" @tab-change="onTab">
      <el-tab-pane label="商户钱包" name="wallets">
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="searchWallets">
          <el-form-item label="关键词">
            <el-input v-model="keyword" clearable placeholder="商户ID/名称/手机" style="width: 200px" @keyup.enter="searchWallets" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="searchWallets">查询</el-button>
          </el-form-item>
        </el-form>

        <div class="table-scroll">
          <el-table :data="wallets" v-loading="loading" stripe border class="report-table">
            <el-table-column prop="merchantId" label="商户ID" min-width="120" />
            <el-table-column prop="merchantName" label="名称" min-width="140" show-overflow-tooltip />
            <el-table-column prop="contactPhone" label="联系电话" width="130" />
            <el-table-column label="余额(元)" width="110" align="right">
              <template #default="{ row }">{{ yuan(row.balanceCents) }}</template>
            </el-table-column>
            <el-table-column label="冻结(元)" width="110" align="right">
              <template #default="{ row }">{{ yuan(row.frozenCents) }}</template>
            </el-table-column>
            <el-table-column label="可用(元)" width="110" align="right">
              <template #default="{ row }">{{ yuan(row.availableCents) }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="90" />
            <el-table-column label="操作" width="260" fixed="right" class-name="col-action">
              <template #default="{ row }">
                <el-button v-hasPermi="['ops:merchant-withdraw:adjust']" link type="primary" @click="adjust(row)">调账</el-button>
                <el-button link @click="showLedgers(row)">流水</el-button>
                <el-button v-hasPermi="['ops:merchant-withdraw:adjust']" link type="warning" @click="proxyWithdraw(row)">代提现</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div class="page-pager">
          <el-pagination
            v-model:current-page="wPage"
            v-model:page-size="wSize"
            layout="total, prev, pager, next"
            :total="wTotal"
            @current-change="loadWallets"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="提现审核" name="withdraws">
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="searchWithdraws">
          <el-form-item label="状态">
            <el-select v-model="wdStatus" clearable placeholder="全部" style="width: 160px" @change="searchWithdraws">
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
          <el-table :data="withdraws" v-loading="loading" stripe border class="report-table">
            <el-table-column prop="requestId" label="单号" width="80" />
            <el-table-column prop="requestNo" label="幂等号" min-width="160" show-overflow-tooltip />
            <el-table-column prop="merchantId" label="商户ID" width="120" />
            <el-table-column prop="merchantName" label="商户" min-width="120" show-overflow-tooltip />
            <el-table-column label="金额(元)" width="100" align="right">
              <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="120">
              <template #default="{ row }">{{ withdrawStatusLabel(row.status) }}</template>
            </el-table-column>
            <el-table-column prop="payChannel" label="通道" width="100" />
            <el-table-column prop="payoutRef" label="回执" min-width="140" show-overflow-tooltip />
            <el-table-column prop="payoutMessage" label="打款说明" min-width="140" show-overflow-tooltip />
            <el-table-column prop="reviewRemark" label="审核备注" min-width="120" show-overflow-tooltip />
            <el-table-column label="申请时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right" class-name="col-action">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING_REVIEW' && auth.hasPerm('ops:merchant-withdraw:review')">
                  <el-button link type="success" @click="review(row, true)">通过并打款</el-button>
                  <el-button link type="danger" @click="review(row, false)">驳回</el-button>
                </template>
                <el-button
                  v-else-if="(row.status === 'APPROVED' || row.status === 'FAILED') && auth.hasPerm('ops:merchant-withdraw:review')"
                  link
                  type="primary"
                  @click="payout(row)"
                >重试打款</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div class="page-pager">
          <el-pagination
            v-model:current-page="wdPage"
            v-model:page-size="wdSize"
            layout="total, prev, pager, next"
            :total="wdTotal"
            @current-change="loadWithdraws"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="ledgerVisible" title="钱包流水" size="520px">
      <el-table :data="ledgers" size="small" stripe>
        <el-table-column prop="entryType" label="类型" width="130" />
        <el-table-column label="变动(元)" width="100" align="right">
          <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
        </el-table-column>
        <el-table-column label="余额后" width="100" align="right">
          <template #default="{ row }">{{ yuan(row.balanceAfter) }}</template>
        </el-table-column>
        <el-table-column label="冻结后" width="100" align="right">
          <template #default="{ row }">{{ yuan(row.frozenAfter) }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { dictLabel, dictOptions, displayLabel } from '@aicabinet/shared-dict';

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
const loading = ref(false);
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
const ledgers = ref<any[]>([]);

const withdrawStatusOptions = computed(() => dictOptions('merchant_withdraw_status'));

function yuan(cents?: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}

function withdrawStatusLabel(status?: string) {
  return displayLabel('merchant_withdraw_status', status, '-');
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

function searchWithdraws() {
  wdPage.value = 1;
  loadWithdraws();
}

async function loadWallets() {
  loading.value = true;
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
    loading.value = false;
  }
}

async function loadWithdraws() {
  loading.value = true;
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
    loading.value = false;
  }
}

async function adjust(row: WalletRow) {
  const { value } = await ElMessageBox.prompt('调账金额（元，负数扣减）', `调账 · ${row.merchantName || row.merchantId}`, {
    inputPattern: /^-?\d+(\.\d{1,2})?$/,
    inputErrorMessage: '请输入金额'
  });
  const amountCents = Math.round(Number(value) * 100);
  await api.request(`/api/v2/ops/admin/merchant-wallets/${encodeURIComponent(row.merchantId)}/adjust`, 'POST', {
    amountCents,
    remark: '运营调账'
  });
  ElMessage.success('已调账');
  await loadWallets();
}

async function showLedgers(row: WalletRow) {
  ledgers.value = await api.request(
    `/api/v2/ops/admin/merchant-wallets/${encodeURIComponent(row.merchantId)}/ledgers?limit=50`,
    'GET'
  );
  ledgerVisible.value = true;
}

async function proxyWithdraw(row: WalletRow) {
  const { value } = await ElMessageBox.prompt('代提现金额（元）', `代提现 · ${row.merchantName || row.merchantId}`, {
    inputPattern: /^\d+(\.\d{1,2})?$/,
    inputErrorMessage: '请输入金额'
  });
  const amountCents = Math.round(Number(value) * 100);
  await api.request(`/api/v2/ops/admin/merchant-wallets/${encodeURIComponent(row.merchantId)}/withdraw`, 'POST', {
    amountCents
  });
  ElMessage.success('已提交提现');
  tab.value = 'withdraws';
  await loadWithdraws();
}

async function review(row: Withdraw, approve: boolean) {
  await api.request(`/api/v2/ops/admin/merchant-withdraws/${row.requestId}/review`, 'POST', {
    approve,
    remark: approve ? '审核通过' : '审核驳回'
  });
  ElMessage.success(approve ? '已通过' : '已驳回');
  await loadWithdraws();
}

async function payout(row: Withdraw) {
  await api.request(`/api/v2/ops/admin/merchant-withdraws/${row.requestId}/payout`, 'POST', {});
  ElMessage.success('已重试打款');
  await loadWithdraws();
}

onMounted(reload);
</script>
