<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">数据一致性</span>
            <span class="hint">巡检订单金额 / 支付净额 / 柜机库存；FAIL 可显式修复</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-if="canRun" type="primary" :loading="running" @click="runCheck">立即巡检</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="t1-alert"
      title="三端一致性说明"
      description="默认只记录 FAIL、不自动改数。ORDER_AMOUNT / INVENTORY_MISMATCH 可点「修复」；支付净额偏差请走退款/调账人工处理。"
    />

    <div class="kpi-tags">
      <el-tag size="small" type="danger">FAIL {{ listHydrated ? failCount : '—' }}</el-tag>
      <el-tag size="small" type="info">本页 {{ listHydrated ? paged.length : '—' }}</el-tag>
      <el-tag v-if="lastRunAt" size="small" type="success">上次巡检 {{ lastRunAt }}</el-tag>
    </div>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="onSearch">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="键 / 表 / 说明"
          style="width: 220px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="typeFilter" clearable placeholder="全部" style="width: 140px" @change="onSearch">
          <el-option label="订单金额" value="ORDER_AMOUNT" />
          <el-option label="支付净额" value="PAYMENT_AMOUNT" />
          <el-option label="库存汇总" value="INVENTORY_MISMATCH" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table v-loading="loading" :data="paged" stripe border class="report-table" row-key="id" empty-text=" ">
          <template #empty>
            <el-empty v-if="listHydrated && !loading" :description="emptyText" />
          </template>
          <el-table-column label="类型" width="160" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="typeTag(row.checkType)">{{ typeLabel(row.checkType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="键" min-width="200" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <el-button
                v-if="keyLink(row)"
                type="primary"
                link
                class="key-link"
                @click="openKey(row)"
              >
                <code class="mono">{{ row.checkKey }}</code>
              </el-button>
              <code v-else class="mono">{{ row.checkKey }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="tableName" label="表" width="140" align="center" class-name="col-text" show-overflow-tooltip />
          <el-table-column label="期望" min-width="100" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.expectedValue }}</template>
          </el-table-column>
          <el-table-column label="实际" min-width="100" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="is-mismatch">{{ row.actualValue }}</span>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="160" align="center" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.errorMessage" class="err-msg">{{ row.errorMessage }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" type="danger">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="检出时间" width="170" align="center">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.checkedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="center">
            <template #default="{ row }">
              <el-button
                v-if="canFix && isFixable(row.checkType)"
                type="primary"
                link
                :loading="fixingId === row.id"
                @click="fixRow(row)"
              >
                修复
              </el-button>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    <PagePager :hydrated="listHydrated"
        v-model:current-page="page"
        v-model:page-size="size"
        :total="filtered.length"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
      />
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import PagePager from '@/components/PagePager.vue';
import { useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = {
  id: number;
  checkType: string;
  tableName?: string;
  checkKey: string;
  expectedValue?: string;
  actualValue?: string;
  status: string;
  errorMessage?: string;
  checkedAt?: string;
  fixedAt?: string;
};

type RunResult = {
  failCount: number;
  failures?: Row[];
};

type FixResult = {
  recordId: number;
  fixed: boolean;
  message?: string;
};

const router = useRouter();
const auth = useAuthStore();
const canRun = computed(
  () =>
    auth.hasPerm('ops:consistency:run') ||
    auth.hasPerm('ops:order:list') ||
    auth.hasPerm('ops:finance:view')
);
const canFix = computed(
  () => auth.hasPerm('ops:consistency:fix') || auth.hasPerm('ops:order:refund')
);

const loading = ref(false);
const listHydrated = ref(false);
const running = ref(false);
const fixingId = ref<number | null>(null);
const items = ref<Row[]>([]);
const lastRunAt = ref('');
const keyword = ref('');
const typeFilter = ref('');
const page = ref(1);
const size = ref(20);

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  const type = typeFilter.value.trim();
  return items.value.filter((row) => {
    if (type && row.checkType !== type) return false;
    if (!q) return true;
    return [row.checkKey, row.tableName, row.errorMessage]
      .some((x) => String(x || '').toLowerCase().includes(q));
  });
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

const failCount = computed(() => filtered.value.length);

const emptyText = computed(() => {
  if (keyword.value.trim() || typeFilter.value.trim()) return '无匹配 FAIL 记录，可清空筛选';
  return '当前无 FAIL 记录，点击「立即巡检」可再跑一轮';
});

watch([keyword, typeFilter], () => {
  page.value = 1;
});

function onSearch() {
  page.value = 1;
}

function resetFilters() {
  keyword.value = '';
  typeFilter.value = '';
  page.value = 1;
}

function typeLabel(t: string) {
  switch (t) {
    case 'ORDER_AMOUNT':
      return '订单金额';
    case 'PAYMENT_AMOUNT':
      return '支付净额';
    case 'INVENTORY_MISMATCH':
      return '库存汇总';
    default:
      return t || '未知';
  }
}

function typeTag(t: string) {
  switch (t) {
    case 'ORDER_AMOUNT':
      return 'warning';
    case 'PAYMENT_AMOUNT':
      return 'danger';
    case 'INVENTORY_MISMATCH':
      return 'info';
    default:
      return '';
  }
}

function isFixable(t: string) {
  return t === 'ORDER_AMOUNT' || t === 'INVENTORY_MISMATCH';
}

function keyLink(row: Row): 'order' | 'device' | null {
  if (!row.checkKey) return null;
  if (row.checkType === 'ORDER_AMOUNT' || row.checkType === 'PAYMENT_AMOUNT') return 'order';
  if (row.checkType === 'INVENTORY_MISMATCH' && row.checkKey.includes('|')) return 'device';
  return null;
}

function openKey(row: Row) {
  const kind = keyLink(row);
  if (kind === 'order') {
    void router.push({ path: '/orders', query: { orderId: row.checkKey } });
    return;
  }
  if (kind === 'device') {
    const deviceId = row.checkKey.split('|', 2)[0];
    if (deviceId) {
      void router.push({ path: `/devices/${encodeURIComponent(deviceId)}`, query: { id: deviceId } });
    }
  }
}

async function load() {
  loading.value = true;
  try {
    items.value = (await api.request<Row[]>('/api/v2/ops/admin/consistency/failures', 'GET')) || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

async function runCheck() {
  running.value = true;
  try {
    const res = await api.request<RunResult>('/api/v2/ops/admin/consistency/run', 'POST');
    items.value = res?.failures || [];
    lastRunAt.value = formatDateTime(new Date().toISOString());
    page.value = 1;
    const n = res?.failCount ?? items.value.length;
    if (n === 0) ElMessage.success('巡检完成：无 FAIL');
    else ElMessage.warning(`巡检完成：仍有 ${n} 条 FAIL`);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '巡检失败');
  } finally {
    listHydrated.value = true;
    running.value = false;
  }
}

async function fixRow(row: Row) {
  try {
    await ElMessageBox.confirm(
      `确认修复 ${typeLabel(row.checkType)}「${row.checkKey}」？将按服务端规则改写期望侧数据。`,
      '显式修复',
      { type: 'warning', confirmButtonText: '修复', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  fixingId.value = row.id;
  try {
    const res = await api.request<FixResult>(`/api/v2/ops/admin/consistency/${row.id}/fix`, 'POST');
    if (res?.fixed) {
      ElMessage.success(res.message || '已修复');
      await load();
    } else {
      ElMessage.warning(res?.message || '未能自动修复（可能需人工补明细）');
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '修复失败');
  } finally {
    fixingId.value = null;
  }
}

onMounted(load);
onActivated(load);
</script>

<style scoped>
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
.key-link {
  padding: 0;
  height: auto;
  vertical-align: baseline;
}
.is-mismatch {
  color: var(--el-color-danger);
  font-weight: 600;
}
.err-msg {
  color: var(--el-text-color-regular);
  font-size: 12px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.kpi-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 12px 0 16px;
}
.t1-alert {
  margin-bottom: 4px;
}
</style>
