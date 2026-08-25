<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">用户分析</span>
            <span class="hint">活跃 / 新增 / 复购 / 沉睡 / 客单价（基于订单数据，无额外埋点）</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-radio-group v-model="days" @change="load">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
            <el-radio-button :value="90">近 90 天</el-radio-button>
          </el-radio-group>
          <el-button @click="onExportDormant">导出沉睡名单</el-button>
          <el-button @click="onExportRepeat">导出复购榜</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div v-loading="loading" class="kpi-grid">
      <div class="kpi-card">
        <text class="kpi-label">活跃用户</text>
        <text class="kpi-value">{{ s?.activeUsers7d ?? '暂无' }}</text>
        <text class="kpi-sub">30 天 {{ s?.activeUsers30d ?? '暂无' }}</text>
      </div>
      <div class="kpi-card">
        <text class="kpi-label">新增用户</text>
        <text class="kpi-value">{{ s?.newUsers7d ?? '暂无' }}</text>
        <text class="kpi-sub">30 天 {{ s?.newUsers30d ?? '暂无' }}</text>
      </div>
      <div class="kpi-card">
        <text class="kpi-label">复购用户</text>
        <text class="kpi-value">{{ s?.repeatBuyer7d ?? '暂无' }}</text>
        <text class="kpi-sub">复购率 {{ pct(s?.repeatPurchaseRate7d) }}</text>
      </div>
      <div class="kpi-card">
        <text class="kpi-label">沉睡用户(30-90天)</text>
        <text class="kpi-value warn">{{ s?.dormantUsers30d ?? '暂无' }}</text>
        <text class="kpi-sub">近 30 天无消费</text>
      </div>
      <div class="kpi-card">
        <text class="kpi-label">累计用户</text>
        <text class="kpi-value">{{ s?.totalUsers ?? '暂无' }}</text>
        <text class="kpi-sub">有订单用户</text>
      </div>
      <div class="kpi-card">
        <text class="kpi-label">订单 / 营收</text>
        <text class="kpi-value">{{ s?.totalOrders ?? '暂无' }}</text>
        <text class="kpi-sub">{{ yuan(s?.totalRevenueCents) }}</text>
      </div>
      <div class="kpi-card">
        <text class="kpi-label">客单价</text>
        <text class="kpi-value">{{ yuan(s?.avgOrderValueCents) }}</text>
        <text class="kpi-sub">窗口期平均</text>
      </div>
    </div>

    <div class="split-grid">
      <el-card shadow="never" class="inner-card">
        <template #header><span class="inner-title">复购用户 TOP10（按累计消费）</span></template>
        <el-table :data="s?.topRepeatBuyers || []" size="small" empty-text="暂无数据">
          <el-table-column prop="userId" label="用户ID" width="100" align="center" />
          <el-table-column label="姓名/手机" min-width="120">
            <template #default="{ row }">{{ row.name || row.phone || '暂无' }}</template>
          </el-table-column>
          <el-table-column prop="orderCount" label="订单数" width="80" align="center" />
          <el-table-column label="累计消费" width="110" align="center">
            <template #default="{ row }">{{
              yuan(
                row.totalSpentCents != null
                  ? row.totalSpentCents
                  : Math.round(Number(row.totalSpent ?? 0) * 100)
              )
            }}</template>
          </el-table-column>
          <el-table-column label="客单价" width="100" align="center">
            <template #default="{ row }">{{ avgTicket(row) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="inner-card">
        <template #header>
          <div class="inner-head">
            <span class="inner-title">沉睡用户（30-90 天未消费）</span>
            <el-button
              v-if="canRecall"
              type="primary"
              size="small"
              :disabled="!s?.dormantUsers?.length"
              @click="openRecall"
              >一键召回</el-button
            >
          </div>
        </template>
        <el-table :data="s?.dormantUsers || []" size="small" empty-text="暂无数据">
          <el-table-column prop="userId" label="用户ID" width="100" align="center" />
          <el-table-column label="姓名/手机" min-width="120">
            <template #default="{ row }">{{ row.name || row.phone || '暂无' }}</template>
          </el-table-column>
          <el-table-column prop="orderCount" label="累计订单" width="90" align="center" />
          <el-table-column label="上次消费" width="140" align="center">
            <template #default="{ row }">{{ lastTime(row.lastOrderAt) }}</template>
          </el-table-column>
          <el-table-column label="累计消费" width="110" align="center">
            <template #default="{ row }">{{
              yuan(
                row.totalSpentCents != null
                  ? row.totalSpentCents
                  : Math.round(Number(row.totalSpent ?? 0) * 100)
              )
            }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-dialog v-model="recallVisible" title="沉睡用户召回" width="460px" destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="召回优惠券" required>
          <el-select
            v-model="recallCouponDefId"
            filterable
            placeholder="选择要发放的优惠券"
            style="width: 100%"
          >
            <el-option
              v-for="c in couponDefs"
              :key="c.couponDefId"
              :label="`${c.couponName}（${(c.denominationCents / 100).toFixed(2)}元）`"
              :value="c.couponDefId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标人数">
          <span
            >{{ s?.dormantUsers?.length ?? 0 }} 人（近 {{ days }} 天沉睡名单，最多 1000 人）</span
          >
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="recallVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="recalling"
          :disabled="!recallCouponDefId"
          @click="doRecall"
          >确认发放并通知</el-button
        >
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { useListCsv } from '@/composables/useListCsv';

type Row = {
  userId: number;
  phone?: string;
  name?: string;
  orderCount: number;
  totalSpentCents?: number;
  lastOrderAt?: number | null;
};

type Summary = {
  activeUsers7d: number;
  activeUsers30d: number;
  newUsers7d: number;
  newUsers30d: number;
  repeatBuyer7d: number;
  repeatPurchaseRate7d: number;
  dormantUsers30d: number;
  totalUsers: number;
  totalOrders: number;
  totalRevenueCents: number;
  avgOrderValueCents: number;
  topRepeatBuyers: Row[];
  dormantUsers: Row[];
};

const loading = ref(false);
const days = ref(30);
const s = ref<Summary | null>(null);
const couponDefs = ref<CouponDef[]>([]);
const recallVisible = ref(false);
const recalling = ref(false);
const recallCouponDefId = ref<number | undefined>(undefined);
const auth = useAuthStore();
const canRecall = computed(
  () => auth.hasPerm('ops:user-analysis:view') && auth.hasPerm('ops:coupon:create')
);

const { onExport: onExportDormant } = useListCsv({
  filePrefix: '沉睡用户名单',
  headers: ['用户ID', '姓名', '手机', '累计订单', '累计消费(元)', '上次消费'],
  toRows: () =>
    (s.value?.dormantUsers || []).map((r) => [
      r.userId,
      r.name || '',
      r.phone || '',
      r.orderCount,
      yuan(r.totalSpentCents ?? 0),
      lastTime(r.lastOrderAt)
    ])
});

const { onExport: onExportRepeat } = useListCsv({
  filePrefix: '复购用户TOP10',
  headers: ['用户ID', '姓名', '手机', '累计订单', '累计消费(元)'],
  toRows: () =>
    (s.value?.topRepeatBuyers || []).map((r) => [
      r.userId,
      r.name || '',
      r.phone || '',
      r.orderCount,
      yuan(r.totalSpentCents ?? 0)
    ])
});

onMounted(async () => {
  await Promise.all([load(), loadCouponDefs()]);
});

type CouponDef = {
  couponDefId: number;
  couponName: string;
  denominationCents: number;
};

async function load() {
  loading.value = true;
  try {
    s.value = await api.request<Summary>(
      `/api/v2/ops/admin/growth/user-analysis?days=${days.value}`
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadCouponDefs() {
  try {
    couponDefs.value = await api.request<CouponDef[]>('/api/v2/coupons/definitions');
  } catch {
    couponDefs.value = [];
  }
}

function openRecall() {
  recallCouponDefId.value = undefined;
  recallVisible.value = true;
}

async function doRecall() {
  if (!recallCouponDefId.value) return;
  recalling.value = true;
  try {
    const result = await api.request<{ issuedCount: number; notifiedCount: number }>(
      '/api/v2/ops/admin/growth/user-recall',
      'POST',
      { couponDefId: recallCouponDefId.value, days: days.value }
    );
    ElMessage.success(`已向 ${result.issuedCount} 位用户发券并通知 ${result.notifiedCount} 人`);
    recallVisible.value = false;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '召回失败');
  } finally {
    recalling.value = false;
  }
}

function pct(v?: number) {
  if (v == null || !Number.isFinite(v)) return '暂无';
  return `${(v * 100).toFixed(1)}%`;
}
function yuan(cents?: number) {
  if (cents == null) return '暂无';
  return `¥${(cents / 100).toFixed(2)}`;
}
function avgTicket(row: { orderCount?: number; totalSpentCents?: number; totalSpent?: number }) {
  const n = Number(row.orderCount || 0);
  if (n <= 0) return '暂无';
  const cents =
    row.totalSpentCents != null
      ? Number(row.totalSpentCents)
      : Math.round(Number(row.totalSpent ?? 0) * 100);
  return yuan(Math.round(cents / n));
}
function lastTime(value?: string | number | null) {
  if (value == null || value === '') return '暂无';
  const d = typeof value === 'number' ? new Date(value) : new Date(String(value));
  if (Number.isNaN(d.getTime())) return '暂无';
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
    d.getDate()
  ).padStart(2, '0')}`;
}
</script>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 14px;
}
.kpi-card {
  padding: 18px;
  border-radius: 14px;
  background: #f7faf8;
  border: 1px solid #eef2ef;
}
.kpi-label {
  display: block;
  font-size: 13px;
  color: #6b7280;
}
.kpi-value {
  display: block;
  margin-top: 6px;
  font-size: 26px;
  font-weight: 700;
  color: #065f46;
}
.kpi-value.warn {
  color: #b45309;
}
.kpi-sub {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #9aa4a0;
}
.split-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-top: 18px;
}
@media (max-width: 1100px) {
  .split-grid {
    grid-template-columns: 1fr;
  }
}
.inner-card {
  border: 1px solid #eef2ef;
}
.inner-title {
  font-weight: 600;
}
.inner-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
