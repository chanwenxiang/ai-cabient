<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">充值管理</span>
            <span class="hint">按状态 / 用户筛选充值单；金额右对齐</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:recharge:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
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
        <el-table
          v-loading="loading"
          :data="displayItems"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          stripe
          border
          class="report-table"
          row-key="orderId"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty
            ><el-empty v-if="listHydrated && !loading" description="暂无充值记录"
          /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="orderId"
            label="充值单"
            min-width="168"
            align="center"
            class-name="col-text"
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ displayBizNo(row.orderId) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="100" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.userId ?? '无' }}</template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="center" class-name="col-money">
            <template #default="{ row }">¥{{ money(row.amountCents) }}</template>
          </el-table-column>
          <el-table-column label="渠道" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">
                {{ dictLabel('pay_channel', String(row.channel || '')) || row.channel || '未知' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="dictTagType(String(row.status || ''))" size="small">
                {{
                  dictLabel('recharge_status', String(row.status || '')) || row.status || '未知状态'
                }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(String(row.createdAt || '')) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            v-if="showActionColumn"
            label="操作"
            width="100"
            class-name="col-action"
            align="center"
          >
            <template #default="{ row }">
              <TableActions
                v-if="isRefundable(row)"
                :actions="[{ key: 'refund', label: '退款', icon: RefreshLeft, type: 'danger' }]"
                @action="() => refundRecharge(row)"
              />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh, RefreshLeft } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { useAuthStore } from '@/stores/auth';
import type { PageResult } from '@aicabinet/shared-types';
import {
  displayBizNo,
  formatDateTime
} from '@aicabinet/shared-uni/format';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const canRefund = computed(() => auth.hasPerm('ops:recharge:edit'));
const loading = ref(false);
const listHydrated = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const status = ref('');
const keyword = ref('');
const items = ref<Record<string, unknown>[]>([]);
const {
  defaultSort: idDefaultSort,
  onSortChange: onIdSortChange,
  sortById
} = useIdColumnSort<Record<string, unknown>>('orderId');
const displayItems = computed(() => sortById(items.value));

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } = useTableSelection<
  Record<string, unknown>
>((r) => String(r.orderId ?? ''));

const { onExport } = useListCsv({
  filePrefix: '充值',
  headers: ['充值单', '用户', '金额', '渠道', '状态', '时间'],
  toRows: () =>
    pickSelected(items.value).map((row) => [
      row.orderId,
      row.userId,
      money(row.amountCents),
      dictLabel('pay_channel', String(row.channel || '')),
      dictLabel('recharge_status', String(row.status || '')),
      formatDateTime(String(row.createdAt || ''))
    ])
});

function money(cents: unknown) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}

function isRefundable(row: Record<string, unknown>) {
  const s = String(row.status || '').toUpperCase();
  return s === 'PAID' || s === 'SUCCESS';
}

const showActionColumn = computed(
  () => canRefund.value && items.value.some((row) => isRefundable(row))
);

async function refundRecharge(row: Record<string, unknown>) {
  const orderId = String(row.orderId || '');
  if (!orderId) return;
  try {
    const { value } = await ElMessageBox.prompt('请输入退款原因（可选）', `退款 ${orderId}`, {
      confirmButtonText: '确认退款',
      cancelButtonText: '取消',
      inputPlaceholder: '退款原因'
    });
    await api.request(`/api/v2/ops/admin/recharge/${encodeURIComponent(orderId)}/refund`, 'POST', {
      reason: (value || '').trim() || undefined
    });
    ElMessage.success('已发起退款');
    await load();
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

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
    if (status.value) q.set('status', status.value);
    if (keyword.value.trim()) q.set('userId', keyword.value.trim());
    const data = await api.request<PageResult<Record<string, unknown>>>(
      `/api/v2/ops/admin/recharges?${q}`,
      'GET'
    );
    items.value = data.items || [];
    total.value = data.total || 0;
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load();
}

function reset() {
  status.value = '';
  keyword.value = '';
  page.value = 1;
  syncRouteQuery();
  load();
}

function onSizeChange() {
  page.value = 1;
  load();
}

function applyRouteQuery() {
  let changed = false;
  const qStatus = typeof route.query.status === 'string' ? route.query.status : '';
  if (qStatus !== status.value) {
    status.value = qStatus;
    changed = true;
  }
  const routeKeyword =
    typeof route.query.keyword === 'string'
      ? route.query.keyword
      : typeof route.query.userId === 'string'
        ? route.query.userId
        : '';
  if (routeKeyword !== keyword.value) {
    keyword.value = routeKeyword;
    changed = true;
  }
  return changed;
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await load();
}

watch(
  () => [route.query.status, route.query.keyword, route.query.userId] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  load();
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
  font-size: 15px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
}
</style>
