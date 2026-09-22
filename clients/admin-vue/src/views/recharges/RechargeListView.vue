<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">充值管理</span>
            <span class="hint">按状态 / 用户筛选充值单；金额居中展示</span>
          </div>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="用户编号（API 按 userId 筛选）"
          style="width: 260px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="status"
          clearable
          placeholder="全部"
          style="width: 140px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('recharge_status')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="orderId"
          manage-table="recharge_order"
          selectable
          :actions="showActionColumn ? rowActions : undefined"
          :action-width="100"
          empty-text="暂无充值记录"
          sort-field-label="充值单"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column prop="orderId" label="充值单" min-width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ displayBizNo(row.orderId) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="100" class-name="col-text">
            <template #default="{ row }">{{ row.userId ?? '无' }}</template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="center" class-name="col-money">
            <template #default="{ row }">¥{{ money(row.amountCents) }}</template>
          </el-table-column>
          <el-table-column
            label="渠道"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" effect="plain">
                {{ displayLabel('pay_channel', String(row.channel || ''), '未知') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="外部单号" min-width="140" class-name="col-text">
            <template #default="{ row }">
              <span class="mono">{{
                displayBizNo(row.wxTransactionId || row.alipayTradeNo || row.wxPrepayId, '无')
              }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="dictTagType(String(row.status || ''))" size="small">
                {{ displayLabel('recharge_status', String(row.status || ''), '未知状态') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="创建时间"
            width="150"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(String(row.createdAt || '')) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="支付时间"
            width="150"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span v-if="row.paidAt" class="cell-datetime">{{
                formatDateTime(String(row.paidAt))
              }}</span>
              <span v-else class="muted">暂无</span>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="退款时间"
            width="150"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span v-if="row.refundedAt" class="cell-datetime">{{
                formatDateTime(String(row.refundedAt))
              }}</span>
              <span v-else class="muted">暂无</span>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { RefreshLeft } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictOptions, dictTagType, displayLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useAuthStore } from '@/stores/auth';
import type { PageResult } from '@aicabinet/shared-types';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const canRefund = computed(() => auth.hasPerm('ops:recharge:edit'));
const status = ref('');
const keyword = ref('');

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 全部内建。
// 首查依赖路由 query 初始化筛选条件，故关闭 autoLoad、在 onMounted 显式首查。
const crud = useCrudTable<Record<string, unknown>>({
  rowKey: (r) => String(r.orderId ?? ''),
  autoLoad: false,
  fetchPage: (params) => {
    const q = new URLSearchParams({ page: String(params.page), size: String(params.size) });
    if (status.value) q.set('status', status.value);
    const userId = parseUserIdFilter(keyword.value);
    if (userId != null) q.set('userId', String(userId));
    return api.request<PageResult<Record<string, unknown>>>(AdminEndpoints.rechargesList(q), 'GET');
  },
  sort: { prop: 'orderId', mode: 'local' }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '充值',
  exportPerm: 'ops:recharge:export',
  headers: ['充值单', '用户', '金额', '渠道', '状态', '时间'],
  toRows: (rows) =>
    rows.map((row) => [
      row.orderId,
      row.userId,
      money(row.amountCents),
      displayLabel('pay_channel', String(row.channel || ''), '未知'),
      displayLabel('recharge_status', String(row.status || ''), '未知状态'),
      formatDateTime(String(row.createdAt || ''))
    ])
};

function money(cents: unknown) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}

function isRefundable(row: Record<string, unknown>) {
  const s = String(row.status || '').toUpperCase();
  return s === 'PAID' || s === 'SUCCESS';
}

/** 当前页无可退款行时整列隐藏（保持原行为：操作列按需出现） */
const showActionColumn = computed(
  () => canRefund.value && crud.items.some((row) => isRefundable(row))
);

function rowActions(row: Record<string, unknown>): CrudRowAction[] {
  if (!isRefundable(row)) return [];
  return [
    { key: 'refund', label: '退款', icon: RefreshLeft, type: 'danger', perm: 'ops:recharge:edit' }
  ];
}

function onAction({ key, row }: { key: string; row: Record<string, unknown> }) {
  if (key === 'refund') void refundRecharge(row);
}

async function refundRecharge(row: Record<string, unknown>) {
  const orderId = String(row.orderId || '');
  if (!orderId) return;
  try {
    const { value } = await ElMessageBox.prompt('请输入退款原因（可选）', `退款 ${orderId}`, {
      confirmButtonText: '确认退款',
      cancelButtonText: '取消',
      inputPlaceholder: '退款原因'
    });
    await api.request(AdminEndpoints.rechargeRefund(orderId), 'POST', {
      reason: (value || '').trim() || undefined
    });
    ElMessage.success('已发起退款');
    await crud.load();
  } catch (e) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '退款失败');
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (status.value) query.status = status.value;
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  router.replace({ query });
}

/** 关键词仅支持正整数用户编号；非法值不传 userId，避免后端 Long 转换 500。 */
function parseUserIdFilter(raw: string): number | null {
  const text = raw.trim();
  if (!text) return null;
  if (!/^\d+$/.test(text)) return null;
  const id = Number(text);
  if (!Number.isSafeInteger(id) || id <= 0) return null;
  return id;
}

function search() {
  const raw = keyword.value.trim();
  if (raw && parseUserIdFilter(raw) == null) {
    ElMessage.warning('用户编号须为正整数');
    return;
  }
  syncRouteQuery();
  void crud.search();
}

function reset() {
  status.value = '';
  keyword.value = '';
  syncRouteQuery();
  void crud.search();
}

function applyRouteQuery() {
  let changed = false;
  const qStatus = typeof route.query.status === 'string' ? route.query.status : '';
  if (qStatus !== status.value) {
    status.value = qStatus;
    changed = true;
  }
  let routeKeyword = '';
  if (typeof route.query.keyword === 'string') {
    routeKeyword = route.query.keyword;
  } else if (typeof route.query.userId === 'string') {
    routeKeyword = route.query.userId;
  }
  if (routeKeyword !== keyword.value) {
    keyword.value = routeKeyword;
    changed = true;
  }
  return changed;
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  await crud.search();
}

watch(
  () => [route.query.status, route.query.keyword, route.query.userId] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  // 首查前先用路由 query 初始化筛选条件（autoLoad 已关闭）
  applyRouteQuery();
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
</style>
