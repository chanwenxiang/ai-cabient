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
          <el-switch v-model="mineOnly" active-text="仅看我的" @change="onMineChange" />
        </div>
      </div>
    </template>

    <!-- CB-021④ 敏感操作汇总：对齐思迅「异常操作」报表形态，只记录不处理 -->
    <el-collapse class="sensitive-summary">
      <el-collapse-item name="summary">
        <template #title>
          <span class="sensitive-summary__title">
            敏感操作汇总（近 {{ sensitiveDays }} 天）
            <span class="sensitive-summary__hint">资金 / 权限 / 凭据 / 数据直改，只记录不处理</span>
          </span>
        </template>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table :data="sensitiveSummary" size="small" empty-text="时间窗内无敏感操作">
              <el-table-column label="动作" min-width="180" class-name="col-text">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain">{{ auditActionLabel(row.action) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="次数" width="100" align="center" class-name="col-status">
                <template #default="{ row }">{{ row.total }}</template>
              </el-table-column>
              <el-table-column label="最近一次" width="168" align="center" class-name="col-status">
                <template #default="{ row }">
                  <span class="cell-datetime">{{ formatDateTime(row.lastAt) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>

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
        <CrudTable
          :table="crud"
          row-key="logId"
          selectable
          empty-text="暂无审计日志"
          sort-field-label="日志编号"
          :csv="csvOptions"
        >
          <el-table-column prop="logId" label="日志编号" width="100" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.logId }}</span>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="时间"
            width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作人ID" width="100" class-name="col-text">
            <template #default="{ row }">{{ row.operatorId ?? '暂无' }}</template>
          </el-table-column>
          <el-table-column label="操作人" min-width="120" class-name="col-text">
            <template #default="{ row }">{{ operatorLabel(row) }}</template>
          </el-table-column>
          <el-table-column
            align="center"
            label="动作"
            min-width="160"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ auditActionLabel(row.action) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="对象类型" min-width="110" class-name="col-text">
            <template #default="{ row }">{{ auditTargetLabel(row.targetType) }}</template>
          </el-table-column>
          <el-table-column label="对象ID" min-width="120" class-name="col-text">
            <template #default="{ row }">
              <span v-if="row.targetId" class="cell-id">{{ displayBizNo(row.targetId) }}</span>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="详情" min-width="220" class-name="col-text">
            <template #default="{ row }">{{ formatOpsActionDetail(row.detail) }}</template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
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
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';

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
const sensitiveDays = ref(7);
const sensitiveSummary = ref<{ action: string; total: number; lastAt: string }[]>([]);

async function loadSensitiveSummary() {
  try {
    sensitiveSummary.value = await api.request<{ action: string; total: number; lastAt: string }[]>(
      AdminEndpoints.auditSensitiveSummary(sensitiveDays.value),
      'GET'
    );
  } catch {
    // 汇总失败不打断明细页主功能（无 ops:audit:list 权限的账号本就到不了此页）
    sensitiveSummary.value = [];
  }
}
const mineOnly = ref(false);
const actionFilter = ref('');
const targetFilter = ref('');

// 列表状态机统一交给 CrudTable：分页 / 排序 / 竞态 / 空态 / 刷新 全部内建。
// 审计日志为只读流水：无行操作列；多选仅服务于 CSV「导出选中」。
const crud = useCrudTable<AuditRow>({
  rowKey: (r) => r.logId,
  // 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由 onMounted 显式首查
  autoLoad: false,
  fetchPage: (params) => {
    const q = new URLSearchParams({
      page: String(params.page),
      size: String(params.size),
      sortDir: params.sortDir ?? 'asc'
    });
    if (mineOnly.value) q.set('mine', 'true');
    if (actionFilter.value) q.set('action', actionFilter.value);
    if (targetFilter.value) q.set('target', targetFilter.value);
    return api.request<PageResult<AuditRow>>(AdminEndpoints.auditLogsList(q), 'GET');
  },
  // 后端固定按 logId 排序、仅接收方向（默认升序）；方向由壳内「升/降序」按钮驱动重查
  sort: { prop: 'logId', mode: 'server', defaultDir: 'asc' }
});

// 导出移入 CrudTable 内建工具条；勾选行时仅导出选中（原 pickSelected 语义）
const csvOptions: CrudCsvOptions = {
  filePrefix: '审计日志',
  exportPerm: 'ops:audit:export',
  headers: ['ID', '时间', '操作人', '动作', '对象类型', '对象ID', '详情'],
  toRows: (rows) =>
    rows.map((row) => [
      row.logId,
      formatDateTime(row.createdAt),
      operatorLabel(row),
      auditActionLabel(row.action),
      auditTargetLabel(row.targetType),
      row.targetId || '无',
      formatOpsActionDetail(row.detail)
    ])
};

function operatorLabel(row: AuditRow) {
  return actorDisplayName({
    name: row.operatorName,
    phone: row.operatorPhone,
    operatorId: row.operatorId
  });
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

function search() {
  syncRouteQuery();
  void crud.search();
}

function reset() {
  actionFilter.value = '';
  targetFilter.value = '';
  mineOnly.value = false;
  syncRouteQuery();
  void crud.search();
}

function onMineChange() {
  search();
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  await crud.search();
}

watch(
  () => [route.query.mine, route.query.action, route.query.target] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  void crud.load();
  void loadSensitiveSummary();
});
onActivated(() => {
  void reloadFromRouteQuery();
  void loadSensitiveSummary();
});
</script>

<style scoped>
.sensitive-summary {
  margin-bottom: 12px;
}
.sensitive-summary__title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.sensitive-summary__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
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
