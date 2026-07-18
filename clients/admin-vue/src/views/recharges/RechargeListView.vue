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
          <el-button v-hasPermi="['ops:recharge:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="用户">
        <el-input
          v-model="userId"
          clearable
          placeholder="用户 ID"
          style="width: 140px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 140px" @change="search">
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
      <div class="table-scroll-inner" style="min-width: 920px">
        <el-table
          v-loading="loading"
          :data="items"
          stripe
          border
          class="report-table"
          row-key="orderId"
          @selection-change="onSelectionChange"
        >
          <template #empty><el-empty description="暂无充值记录" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="充值单" min-width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.orderId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="100" class-name="col-text">
            <template #default="{ row }">{{ row.userId ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="right" class-name="col-money">
            <template #default="{ row }">¥{{ money(row.amountCents) }}</template>
          </el-table-column>
          <el-table-column label="渠道" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">
                {{ dictLabel('pay_channel', String(row.channel || '')) || row.channel || '-' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="dictTagType(String(row.status || ''))" size="small">
                {{ dictLabel('recharge_status', String(row.status || '')) || row.status || '-' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(String(row.createdAt || '')) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const status = ref('');
const userId = ref('');
const items = ref<Record<string, unknown>[]>([]);

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

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (status.value) query.status = status.value;
  if (userId.value.trim()) query.userId = userId.value.trim();
  router.replace({ query });
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
    if (status.value) q.set('status', status.value);
    if (userId.value.trim()) q.set('userId', userId.value.trim());
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
  userId.value = '';
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
  if (typeof route.query.status === 'string' && route.query.status !== status.value) {
    status.value = route.query.status;
    changed = true;
  }
  if (typeof route.query.userId === 'string' && route.query.userId !== userId.value) {
    userId.value = route.query.userId;
    changed = true;
  }
  return changed;
}

onMounted(() => {
  applyRouteQuery();
  load();
});
onActivated(() => {
  if (applyRouteQuery()) {
    page.value = 1;
    load();
  }
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
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; }
</style>
