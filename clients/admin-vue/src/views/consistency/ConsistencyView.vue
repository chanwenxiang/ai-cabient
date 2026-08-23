<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">数据一致性</span>
            <span class="hint">巡检订单/支付/库存/积分/发券；未通过项可按规则修复</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-if="canRun" type="primary" :loading="running" @click="runCheck"
            >立即巡检</el-button
          >
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="t1-alert"
      title="一致性巡检说明"
      description="默认只记录未通过项、不自动改数。覆盖订单/支付/库存/积分/发券/钱包/退款/行金额/券关联九类。订单金额与库存汇总可点「修复」；支付与退款偏差请走退款/调账。"
    />

    <div class="kpi-tags">
      <el-tag size="small" type="danger">未通过 {{ listHydrated ? failCount : '…' }}</el-tag>
      <el-tag v-if="listHydrated && severityCounts.high > 0" size="small" type="danger">
        高优先级 {{ severityCounts.high }}
      </el-tag>
      <el-tag size="small" type="info">本页 {{ listHydrated ? paged.length : '…' }}</el-tag>
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
        <el-select
          v-model="typeFilter"
          clearable
          placeholder="全部"
          style="width: 140px"
          @change="onSearch"
        >
          <el-option
            v-for="item in consistencyTypeOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="paged"
          stripe
          border
          class="report-table"
          row-key="id"
          empty-text=" "
        >
          <template #empty>
            <el-empty v-if="listHydrated && !loading" :description="emptyText" />
          </template>
          <el-table-column label="类型" width="160" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="typeTag(row.checkType)">{{
                typeLabel(row.checkType)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="键"
            min-width="200"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
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
          <el-table-column
            prop="tableName"
            label="表"
            width="140"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          />
          <el-table-column label="级别" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="severityTag(row)">{{ severityLabel(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="基准" min-width="90" align="center" class-name="col-text">
            <template #default="{ row }">
              <span :title="valueHint(row)">{{ row.expectedValue }}</span>
            </template>
          </el-table-column>
          <el-table-column label="对照" min-width="100" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="is-mismatch" :title="actualHint(row)">{{ row.actualValue }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="说明"
            min-width="160"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span v-if="row.errorMessage" class="err-msg">{{ row.errorMessage }}</span>
              <span v-else class="muted">暂无</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" type="danger">{{ statusLabel(row.status) }}</el-tag>
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
              <span v-else-if="canFix" class="muted" title="该类仅巡检记录，需人工核对处理"
                >需人工</span
              >
              <span v-else class="muted">暂无</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    <PagePager
      :hydrated="listHydrated"
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
import { dictLabel, dictOptions, displayLabel } from '@aicabinet/shared-dict';

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
const canRun = computed(() => auth.hasPerm('ops:consistency:run'));
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
const consistencyTypeOptions = dictOptions('consistency_check_type');

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  const type = typeFilter.value.trim();
  return items.value.filter((row) => {
    if (type && row.checkType !== type) return false;
    if (!q) return true;
    return [row.checkKey, row.tableName, row.errorMessage].some((x) =>
      String(x || '')
        .toLowerCase()
        .includes(q)
    );
  });
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

const failCount = computed(() => filtered.value.length);

const severityCounts = computed(() => {
  let high = 0;
  for (const row of filtered.value) {
    if (rowSeverity(row) === 'high') high++;
  }
  return { high };
});

function rowSeverity(row: Row): 'high' | 'medium' | 'low' {
  if (row.checkType === 'PAYMENT_AMOUNT' || row.checkType === 'WALLET_BALANCE') return 'high';
  const msg = String(row.errorMessage || '');
  if (msg.includes('实付') && msg.includes('均不符')) return 'high';
  if (msg.includes('券抵扣超过明细') || msg.includes('券字段未生效')) return 'medium';
  if (row.checkType === 'ORDER_AMOUNT' || row.checkType === 'INVENTORY_MISMATCH') return 'medium';
  return 'low';
}

function severityLabel(row: Row) {
  const s = rowSeverity(row);
  if (s === 'high') return '高';
  if (s === 'medium') return '中';
  return '低';
}

function severityTag(row: Row) {
  const s = rowSeverity(row);
  if (s === 'high') return 'danger';
  if (s === 'medium') return 'warning';
  return 'info';
}

function fixPreview(row: Row): string {
  if (row.checkType === 'INVENTORY_MISMATCH') {
    return `将汇总库存改为在架批次合计 ${row.actualValue ?? '—'}`;
  }
  if (row.checkType === 'ORDER_AMOUNT') {
    const msg = String(row.errorMessage || '');
    if (msg.includes('券字段未生效')) {
      return '实付与明细一致：清除未生效的券/折扣字段，并尝试退还券占用';
    }
    if (msg.includes('实付已与折后入账')) {
      return `将订单头同步为按明细应收 ${row.actualValue ?? '—'}`;
    }
    return '按服务端规则对齐订单头/明细（无匹配策略时将拒绝修复）';
  }
  return '按服务端规则修复';
}

const emptyText = computed(() => {
  if (keyword.value.trim() || typeFilter.value.trim()) return '无匹配未通过记录，可清空筛选';
  if (!canRun.value) return '当前无未通过记录';
  return '当前无未通过记录，点击「立即巡检」可再跑一轮';
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
  return displayLabel('consistency_check_type', t, '未知类型');
}

function valueHint(row: Row) {
  switch (row.checkType) {
    case 'ORDER_AMOUNT':
      return '订单头金额';
    case 'PAYMENT_AMOUNT':
      return '期望净入账';
    case 'INVENTORY_MISMATCH':
      return '汇总库存';
    default:
      return '期望值';
  }
}

function actualHint(row: Row) {
  switch (row.checkType) {
    case 'ORDER_AMOUNT':
      return '按明细折后应收';
    case 'PAYMENT_AMOUNT':
      return '实际净入账';
    case 'INVENTORY_MISMATCH':
      return '在架批次合计';
    default:
      return '实际值';
  }
}

function statusLabel(s: string) {
  if (s === 'FAIL') return '未通过';
  if (s === 'PASS') return '通过';
  if (/^[A-Z][A-Z0-9_]*$/.test(String(s || ''))) return '未知';
  return s || '暂无';
}

function typeTag(t: string) {
  switch (t) {
    case 'ORDER_AMOUNT':
      return 'warning';
    case 'PAYMENT_AMOUNT':
      return 'danger';
    case 'INVENTORY_MISMATCH':
      return 'info';
    case 'WALLET_BALANCE':
    case 'REFUND_AMOUNT':
      return 'danger';
    case 'ORDER_LINE_SUM':
    case 'COUPON_USED_LINK':
      return 'warning';
    default:
      return '';
  }
}

function isFixable(t: string) {
  return t === 'ORDER_AMOUNT' || t === 'INVENTORY_MISMATCH'
    || t === 'ORDER_LINE_SUM' || t === 'COUPON_USED_LINK' || t === 'PAYMENT_AMOUNT';
}

function keyLink(row: Row): 'order' | 'device' | 'member' | 'coupon' | null {
  if (!row.checkKey) return null;
  if (row.checkType === 'ORDER_AMOUNT' || row.checkType === 'PAYMENT_AMOUNT'
      || row.checkType === 'REFUND_AMOUNT' || row.checkType === 'COUPON_USED_LINK') {
    return 'order';
  }
  if (row.checkType === 'ORDER_LINE_SUM' && row.checkKey.includes('|')) {
    return 'order';
  }
  if (row.checkType === 'INVENTORY_MISMATCH' && row.checkKey.includes('|')) return 'device';
  if (row.checkType === 'POINTS_BALANCE') return 'member';
  if (row.checkType === 'COUPON_ISSUED') return 'coupon';
  return null;
}

function openKey(row: Row) {
  const kind = keyLink(row);
  if (kind === 'order') {
    const orderId = row.checkType === 'ORDER_LINE_SUM'
      ? row.checkKey.split('|', 2)[0]
      : row.checkKey;
    void router.push({ path: '/orders', query: { orderId } });
    return;
  }
  if (kind === 'device') {
    const deviceId = row.checkKey.split('|', 2)[0];
    if (deviceId) {
      void router.push({
        path: `/devices/${encodeURIComponent(deviceId)}`,
        query: { id: deviceId }
      });
    }
    return;
  }
  if (kind === 'member') {
    void router.push({ path: '/member-levels', query: { memberId: row.checkKey } });
    return;
  }
  if (kind === 'coupon') {
    void router.push({ path: '/coupons', query: { defId: row.checkKey } });
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
    if (n === 0) ElMessage.success('巡检完成：全部通过');
    else ElMessage.warning(`巡检完成：仍有 ${n} 条未通过`);
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
      `确认修复 ${typeLabel(row.checkType)}「${row.checkKey}」？\n${fixPreview(row)}`,
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
  font-family: var(--app-font-mono);
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
