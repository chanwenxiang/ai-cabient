<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import PagePager from '@/components/PagePager.vue';
import type { BalanceRefundRequestDto, PageResult } from '@aicabinet/shared-types';

const auth = useAuthStore();
const loading = ref(false);
const hydrated = ref(false);
const rows = ref<BalanceRefundRequestDto[]>([]);
const status = ref('PENDING_REVIEW');
const userId = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);

function yuan(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function statusLabel(s?: string) {
  switch (String(s || '').toUpperCase()) {
    case 'PENDING_REVIEW':
      return '待审核';
    case 'REFUNDED':
      return '已退款';
    case 'REJECTED':
      return '已驳回';
    case 'FAILED':
      return '失败';
    default:
      return s || '—';
  }
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    if (status.value && status.value !== 'ALL') q.set('status', status.value);
    if (userId.value.trim()) q.set('userId', userId.value.trim());
    const res = await api.request<PageResult<BalanceRefundRequestDto>>(
      `/api/v2/ops/admin/balance-refunds?${q}`
    );
    rows.value = res?.items || [];
    total.value = Number(res?.total || 0);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
    hydrated.value = true;
  }
}

async function review(row: BalanceRefundRequestDto, approve: boolean) {
  const action = approve ? '通过并原路退款' : '驳回';
  try {
    const { value } = await ElMessageBox.prompt(
      approve
        ? `确认通过申请 ${row.requestNo}？将按充值单 FIFO 原路退回微信/支付宝 ¥${yuan(row.amountCents)}，并扣减用户余额。`
        : `确认驳回申请 ${row.requestNo}？将释放冻结金额。`,
      action,
      {
        confirmButtonText: action,
        cancelButtonText: '取消',
        inputPlaceholder: '审核备注（可选）',
        inputValue: ''
      }
    );
    await api.request(`/api/v2/ops/admin/balance-refunds/${row.requestId}/review`, 'POST', {
      approve,
      remark: value || undefined
    });
    ElMessage.success(approve ? '已退款' : '已驳回');
    await load();
  } catch (e) {
    if (e === 'cancel') return;
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

onMounted(load);
</script>

<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">余额退款申请</span>
            <span class="hint">用户在充值页提交 · 审核通过后按充值单原路退微信/支付宝</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="load">
      <el-form-item label="状态">
        <el-select v-model="status" style="width: 140px" @change="load">
          <el-option label="待审核" value="PENDING_REVIEW" />
          <el-option label="已退款" value="REFUNDED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="失败" value="FAILED" />
          <el-option label="全部" value="ALL" />
        </el-select>
      </el-form-item>
      <el-form-item label="用户ID">
        <el-input v-model="userId" clearable placeholder="可选" style="width: 140px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <el-table :data="rows" v-loading="loading" stripe border empty-text=" ">
        <template #empty>
          <el-empty v-if="hydrated && !loading" description="暂无申请" />
        </template>
        <el-table-column prop="requestNo" label="申请号" min-width="160" align="center" />
        <el-table-column prop="userId" label="用户" width="100" align="center" />
        <el-table-column label="金额(元)" width="110" align="center">
          <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">{{ statusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="申请原因" min-width="140" show-overflow-tooltip />
        <el-table-column prop="reviewRemark" label="审核备注" min-width="120" show-overflow-tooltip />
        <el-table-column prop="failReason" label="失败原因" min-width="140" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="申请时间" min-width="160" align="center" />
        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING_REVIEW' && auth.hasPerm('ops:balance-refund:review')">
              <el-button link type="primary" @click="review(row, true)">通过</el-button>
              <el-button link type="danger" @click="review(row, false)">驳回</el-button>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <PagePager
      :hydrated="hydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      @current-change="load"
      @size-change="load"
    />
  </el-card>
</template>

<style scoped>
.muted {
  color: #94a3b8;
}
</style>
