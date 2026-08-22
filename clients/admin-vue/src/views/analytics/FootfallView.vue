<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">客流坪效</span>
            <span class="hint">开门客流 · 转化 · 时段热区 · 柜机坪效 · 货道热区</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-select v-model="days" style="width: 110px" @change="load">
            <el-option label="近 7 天" :value="7" />
            <el-option label="近 30 天" :value="30" />
            <el-option label="近 90 天" :value="90" />
          </el-select>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div v-loading="loading">
      <div v-if="data" class="kpi-grid">
        <div class="kpi-card">
          <text class="kpi-n">{{ data.overview.totalOpens }}</text
          ><text class="kpi-l">开门次数（客流）</text>
        </div>
        <div class="kpi-card">
          <text class="kpi-n">{{ data.overview.totalPaidOrders }}</text
          ><text class="kpi-l">支付订单</text>
        </div>
        <div class="kpi-card">
          <text class="kpi-n">{{ (data.overview.conversionRate ?? 0).toFixed(1) }}%</text
          ><text class="kpi-l">开门转化率</text>
        </div>
        <div class="kpi-card">
          <text class="kpi-n"
            >¥{{ ((data.overview.avgOrderValueCents ?? 0) / 100).toFixed(2) }}</text
          ><text class="kpi-l">客单价</text>
        </div>
        <div class="kpi-card">
          <text class="kpi-n">{{ data.overview.repeatBuyers }}</text
          ><text class="kpi-l">复购用户</text>
        </div>
        <div class="kpi-card">
          <text class="kpi-n">{{ data.overview.deviceCount }}</text
          ><text class="kpi-l">柜机数</text>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head"><h4>时段热区（订单量 / 24h）</h4></div>
        <div class="hour-bars">
          <div v-for="h in data?.hourly || []" :key="h.hour" class="hour-bar-col">
            <div
              class="hour-bar"
              :style="{ height: barHeight(h.orders) }"
              :title="`${h.hour} 时：${h.orders} 单 · ¥${(h.revenueCents / 100).toFixed(2)}`"
            />
            <span class="hour-label">{{ h.hour }}</span>
          </div>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head"><h4>柜机坪效排行（营收/开门/转化）</h4></div>
        <el-table
          :data="data?.devices || []"
          size="small"
          border
          stripe
          style="width: 100%"
          max-height="360"
        >
          <el-table-column prop="deviceName" label="柜机" min-width="160" show-overflow-tooltip />
          <el-table-column prop="opens" label="开门" width="88" align="center" />
          <el-table-column prop="orders" label="订单" width="88" align="center" />
          <el-table-column label="转化率" width="100" align="center">
            <template #default="{ row }">{{ row.conversionRate.toFixed(1) }}%</template>
          </el-table-column>
          <el-table-column label="营收" min-width="120" align="center">
            <template #default="{ row }">¥{{ (row.revenueCents / 100).toFixed(2) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-head"><h4>商品热区（TOP 20）</h4></div>
        <el-table
          :data="data?.topSkus || []"
          size="small"
          border
          stripe
          style="width: 100%"
          max-height="360"
        >
          <el-table-column prop="skuName" label="商品" min-width="180" show-overflow-tooltip />
          <el-table-column prop="skuId" label="SKU" width="110" show-overflow-tooltip />
          <el-table-column prop="qtySold" label="销量" width="88" align="center" />
          <el-table-column label="营收" min-width="110" align="center">
            <template #default="{ row }">¥{{ (row.revenueCents / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="件均价" width="100" align="center">
            <template #default="{ row }">
              {{
                row.qtySold > 0
                  ? `¥${(row.revenueCents / row.qtySold / 100).toFixed(2)}`
                  : '暂无'
              }}
            </template>
          </el-table-column>
          <el-table-column label="营收占比" width="100" align="center">
            <template #default="{ row }">
              {{
                topSkuRevenueTotal > 0
                  ? `${((row.revenueCents / topSkuRevenueTotal) * 100).toFixed(1)}%`
                  : '暂无'
              }}
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-head">
          <h4>货道热区（选择柜机）</h4>
          <div class="slot-controls">
            <el-select
              v-model="slotDeviceId"
              filterable
              placeholder="选择柜机"
              style="width: 220px"
              @change="loadSlotHeat"
            >
              <el-option
                v-for="d in data?.devices || []"
                :key="d.deviceId"
                :label="`${d.deviceName}（${d.deviceId}）`"
                :value="d.deviceId"
              />
            </el-select>
            <span class="legend"
              ><i class="dot h3" />热 <i class="dot h2" /><i class="dot h1" /><i
                class="dot h0"
              />冷</span
            >
          </div>
        </div>
        <div v-if="slotHeat.length" class="slot-grid">
          <div
            v-for="s in slotHeat"
            :key="s.slotId"
            class="slot-cell"
            :class="`heat-${s.heatLevel}`"
            :title="`${s.slotId} · ${s.skuName} · ${s.qtySold} 件 · ¥${(s.revenueCents / 100).toFixed(2)}`"
          >
            <span class="slot-code">{{ s.slotId }}</span>
            <span class="slot-sku">{{ s.skuName }}</span>
            <span class="slot-qty">{{ s.qtySold }} 件</span>
          </div>
        </div>
        <p v-else class="muted">选择柜机查看货道热区；暂无带货道明细的订单</p>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import type { FootfallAnalytics, SlotHeat } from '@aicabinet/shared-types';

const loading = ref(false);
const days = ref(7);
const data = ref<FootfallAnalytics | null>(null);
const slotDeviceId = ref('');
const slotHeat = ref<SlotHeat[]>([]);

const topSkuRevenueTotal = computed(() =>
  (data.value?.topSkus || []).reduce((sum, row) => sum + Number(row.revenueCents || 0), 0)
);

onMounted(load);

async function load() {
  loading.value = true;
  try {
    data.value = await api.request<FootfallAnalytics>(
      `/api/v2/ops/admin/analytics/footfall?days=${days.value}`,
      'GET'
    );
    if (data.value?.devices?.length && !slotDeviceId.value) {
      slotDeviceId.value = data.value.devices[0].deviceId;
      await loadSlotHeat();
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadSlotHeat() {
  if (!slotDeviceId.value) return;
  try {
    slotHeat.value =
      (await api.request<SlotHeat[]>(
        `/api/v2/ops/admin/analytics/footfall/slots?deviceId=${encodeURIComponent(slotDeviceId.value)}&days=${days.value}`,
        'GET'
      )) || [];
  } catch {
    slotHeat.value = [];
  }
}

function barHeight(orders: number) {
  const max = Math.max(1, ...(data.value?.hourly || []).map((h) => h.orders));
  return `${Math.max(2, Math.round((orders / max) * 120))}px`;
}
</script>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.kpi-card {
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
}
.kpi-n,
.kpi-l {
  display: block;
}
.kpi-n {
  font-size: 24px;
  font-weight: 700;
  color: var(--el-color-primary);
}
.kpi-l {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.panel {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: 14px;
  padding: 14px;
  border: 1px solid var(--layout-border, var(--el-border-color-lighter));
  border-radius: 10px;
  background: var(--layout-card, var(--el-bg-color, #fff));
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.panel :deep(.el-table) {
  width: 100% !important;
  --el-table-border-color: var(--layout-border, var(--el-border-color-lighter));
}
.panel :deep(.el-table__inner-wrapper),
.panel :deep(.el-table__header),
.panel :deep(.el-table__body) {
  width: 100% !important;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.panel-head h4 {
  margin: 0;
  font-size: 15px;
}
.hour-bars {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 150px;
}
.hour-bar-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  height: 100%;
}
.hour-bar {
  width: 100%;
  max-width: 18px;
  background: linear-gradient(180deg, #f97316, #f59e0b);
  border-radius: 3px 3px 0 0;
}
.hour-label {
  margin-top: 4px;
  font-size: 10px;
  color: var(--el-text-color-secondary);
}
.slot-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}
.legend {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
  margin: 0 2px 0 6px;
}
.dot.h3 {
  background: #dc2626;
}
.dot.h2 {
  background: #f97316;
}
.dot.h1 {
  background: #fbbf24;
}
.dot.h0 {
  background: #e5e7eb;
}
.slot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 10px;
  width: 100%;
}
.slot-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px;
  border-radius: 8px;
  border: 1px solid var(--el-border-color-lighter);
}
.slot-cell.heat-3 {
  background: #fee2e2;
}
.slot-cell.heat-2 {
  background: #ffedd5;
}
.slot-cell.heat-1 {
  background: #fef3c7;
}
.slot-cell.heat-0 {
  background: #f9fafb;
}
.slot-code {
  font-size: 12px;
  font-weight: 700;
}
.slot-sku {
  font-size: 12px;
  color: #334155;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.slot-qty {
  font-size: 12px;
  font-weight: 700;
  color: #0f172a;
  font-variant-numeric: tabular-nums;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
