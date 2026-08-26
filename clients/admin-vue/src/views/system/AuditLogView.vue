<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">审计日志</span>
            <span class="hint">管理写操作留痕；ID 默认升序，点击表头可切换升降序</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:audit:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-switch v-model="mineOnly" active-text="仅看我的" @change="onMineChange" />
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="动作">
        <el-select
          v-model="actionFilter"
          clearable
          filterable
          placeholder="全部"
          style="width: 180px"
          @change="search"
        >
          <el-option
            v-for="(label, key) in AUDIT_ACTION_LABELS"
            :key="key"
            :label="label"
            :value="key"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="对象">
        <el-select
          v-model="targetFilter"
          clearable
          placeholder="全部"
          style="width: 150px"
          @change="search"
        >
          <el-option
            v-for="(label, key) in AUDIT_TARGET_LABELS"
            :key="key"
            :label="label"
            :value="key"
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
          stripe
          border
          class="report-table"
          row-key="logId"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty>
            <el-empty v-if="listHydrated && !loading" description="暂无审计日志" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="logId"
            label="日志编号"
            width="100"
            align="center"
            class-name="col-text"
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.logId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作人ID" width="100" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.operatorId ?? '暂无' }}</template>
          </el-table-column>
          <el-table-column
            label="操作人"
            min-width="120"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ operatorLabel(row) }}</template>
          </el-table-column>
          <el-table-column
            label="动作"
            min-width="160"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ auditActionLabel(row.action) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="对象类型"
            min-width="110"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ auditTargetLabel(row.targetType) }}</template>
          </el-table-column>
          <el-table-column
            label="对象ID"
            min-width="120"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span v-if="row.targetId" class="cell-id">{{ displayBizNo(row.targetId) }}</span>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column
            label="详情"
            min-width="220"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ formatOpsActionDetail(row.detail) }}</template>
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
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import PagePager from '@/components/PagePager.vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import {
  AUDIT_ACTION_LABELS,
  AUDIT_TARGET_LABELS,
  actorDisplayName,
  auditActionLabel,
  auditTargetLabel,
  formatOpsActionDetail
} from '@aicabinet/shared-dict';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';
import type { PageResult } from '@aicabinet/shared-types';
import { api } from '@/api/client';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';

interface AuditRow {
  logId: number;
  operatorId?: number;
  operatorPhone?: string;
  operatorName?: string;
  action?: string;
  targetType?: string;
  targetId?: string;
  createdAt?: string;
  detail?: string;
}

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const listHydrated = ref(false);
const mineOnly = ref(false);
const actionFilter = ref('');
const targetFilter = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<AuditRow[]>([]);

const { idSortDir, idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('logId', {
  onChange: () => {
    page.value = 1;
    void load();
  }
});

const displayItems = computed(() => items.value);

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<AuditRow>((r) => r.logId);

const { onExport } = useListCsv({
  filePrefix: '审计日志',
  headers: ['ID', '时间', '操作人', '动作', '对象类型', '对象ID', '详情'],
  toRows: () =>
    pickSelected(displayItems.value).map((row) => [
      row.logId,
      formatDateTime(row.createdAt),
      operatorLabel(row),
      auditActionLabel(row.action),
      auditTargetLabel(row.targetType),
      row.targetId || '无',
      formatOpsActionDetail(row.detail)
    ])
});

function operatorLabel(row: AuditRow) {
  return actorDisplayName({
    name: row.operatorName,
    phone: row.operatorPhone,
    operatorId: row.operatorId
  });
}

function matchFilters(row: AuditRow) {
  if (actionFilter.value && row.action !== actionFilter.value) return false;
  if (targetFilter.value && row.targetType !== targetFilter.value) return false;
  return true;
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (mineOnly.value) query.mine = '1';
  if (actionFilter.value) query.action = actionFilter.value;
  if (targetFilter.value) query.target = targetFilter.value;
  router.replace({ query });
}

function applyRouteQuery() {
  let changed = false;
  const mine = route.query.mine === '1' || route.query.mine === 'true';
  if (mine !== mineOnly.value) {
    mineOnly.value = mine;
    changed = true;
  }
  const qAction = typeof route.query.action === 'string' ? route.query.action : '';
  if (qAction !== actionFilter.value) {
    actionFilter.value = qAction;
    changed = true;
  }
  const qTarget = typeof route.query.target === 'string' ? route.query.target : '';
  if (qTarget !== targetFilter.value) {
    targetFilter.value = qTarget;
    changed = true;
  }
  return changed;
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value),
      sortDir: idSortDir.value
    });
    if (mineOnly.value) q.set('mine', 'true');
    if (actionFilter.value) q.set('action', actionFilter.value);
    if (targetFilter.value) q.set('target', targetFilter.value);
    const data = await api.request<PageResult<AuditRow>>(
      `/api/v2/ops/admin/audit-logs?${q}`,
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
  actionFilter.value = '';
  targetFilter.value = '';
  mineOnly.value = false;
  page.value = 1;
  syncRouteQuery();
  load();
}

function onMineChange() {
  page.value = 1;
  syncRouteQuery();
  load();
}

function onPageChange() {
  void load();
}

function onSizeChange() {
  page.value = 1;
  load();
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await load();
}

watch(
  () => [route.query.mine, route.query.action, route.query.target] as const,
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
  align-items: center;
  flex-wrap: wrap;
}
.muted {
  color: var(--el-text-color-secondary);
}
</style>
