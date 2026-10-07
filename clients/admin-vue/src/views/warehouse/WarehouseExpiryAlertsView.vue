<script setup lang="ts">
// V320：**仓库侧**近效期预警（仓储补货域缺口 #6）。
//
// 🔴 与系统原有的 `expiryAlerts`（运营台 → 补货预警）**不是同一份数据**：
//   那个走 `PullOffTask`，字段是 `deviceId`/`lotId` ⇒ **只覆盖设备侧批次**。
//   仓库里的货到期了，此前**完全没有提示** —— 而临期货在仓库里比在柜机里更致命
//   （柜机里过期是「一柜的损失」，仓库里过期是「整批的损失」）。
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { useAuthStore } from '@/stores/auth';
import CrudTable from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { errorMessage } from '@/utils/error-message';

interface ExpiryAlertRow {
  inventoryId: number;
  warehouseId?: string | null;
  skuId: string;
  batchNo?: string | null;
  expiryDate?: string | null;
  quantity: number;
  daysRemaining: number;
  expired: boolean;
  urgency: 'EXPIRED' | 'URGENT' | 'SOON' | 'NORMAL';
}

const auth = useAuthStore();
const canList = computed(
  () => auth.hasPerm('ops:warehouse:list') || auth.hasPerm('ops:replenishment:list')
);

/** 提前多少天算「近效期」。默认 30，与后端 `WarehouseExpiryAlert.DEFAULT_DAYS_AHEAD` 一致。 */
const daysAhead = ref(30);
/** 空 = 全部仓库。 */
const warehouseId = ref('');

const crud = useCrudTable<ExpiryAlertRow>({
  rowKey: (r) => r.inventoryId,
  fetchPage: async (params) => {
    const q = new URLSearchParams({
      page: String(params.page), // 0 起（useCrudTable 已换算）
      size: String(params.size)
    });
    if (warehouseId.value.trim()) q.set('warehouseId', warehouseId.value.trim());
    // 后端会夹取到 1..365；这里也夹一次，避免用户输 99999 时界面与实际不符
    q.set('daysAhead', String(Math.min(Math.max(Number(daysAhead.value) || 30, 1), 365)));
    const data = await api.request<{ items: ExpiryAlertRow[]; total: number }>(
      AdminEndpoints.warehouseExpiryAlerts(q)
    );
    return { items: data.items || [], total: Number(data.total) || 0 };
  }
});

/**
 * 剩余天数的展示。
 *
 * 🔴 已过期（负数）必须显式说「已过期 N 天」而不是显示「-N天」——
 * 负号在运营眼里是「数据不对」而不是「已经过期了」。
 */
function daysText(row: ExpiryAlertRow): string {
  const d = Number(row.daysRemaining);
  if (!Number.isFinite(d)) return '—';
  if (d < 0) return `已过期 ${Math.abs(d)} 天`;
  if (d === 0) return '今天到期';
  return `剩 ${d} 天`;
}

const URGENCY_LABEL: Record<string, string> = {
  EXPIRED: '已过期',
  URGENT: '紧急（≤7天）',
  SOON: '临近（≤30天）',
  NORMAL: '正常'
};

function onFilterChange() {
  crud.load();
}

function onDaysChange() {
  const n = Number(daysAhead.value);
  if (!Number.isFinite(n) || n < 1) {
    ElMessage.warning('提前天数须至少为 1');
    daysAhead.value = 30;
    return;
  }
  crud.load();
}
</script>

<template>
  <div class="page">
    <el-card>
      <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="onFilterChange">
        <el-form-item label="仓库">
          <el-input
            v-model="warehouseId"
            placeholder="留空 = 全部仓库"
            clearable
            style="width: 180px"
            @change="onFilterChange"
          />
        </el-form-item>
        <el-form-item label="提前天数">
          <!-- 🔴 上限 365：与后端夹取一致，避免界面显示 99999 但实际只查 365 天 -->
          <el-input-number
            v-model="daysAhead"
            :min="1"
            :max="365"
            controls-position="right"
            style="width: 140px"
            @change="onDaysChange"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onFilterChange">查询</el-button>
          <el-button @click="crud.load()">刷新</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="这里只看仓库里的批次"
        description="柜机批次的到期预警在「补货预警」页（数据源不同）。本页含已过期批次——已过期的往往比临期更需要立刻处理。"
        style="margin-bottom: 12px"
      />

      <CrudTable
        v-if="canList"
        :table="crud"
        row-key="inventoryId"
        :empty-text="`没有 ${daysAhead} 天内到期的批次`"
        data-testid="warehouse-expiry-alerts"
      >
        <el-table-column prop="skuId" label="商品" min-width="160">
          <template #default="{ row }">
            <span>{{ row.skuId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批次" min-width="140" />
        <!-- 🔴 中心对齐必须配 `class-name`（门禁 check:admin-table-align 的「裸 center」规则） -->
        <el-table-column
          prop="expiryDate"
          label="到期日"
          width="120"
          align="center"
          class-name="col-text"
          label-class-name="col-text"
        />
        <el-table-column
          prop="daysRemaining"
          label="剩余"
          width="130"
          align="center"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">{{ daysText(row) }}</template>
        </el-table-column>
        <el-table-column
          prop="urgency"
          label="紧急度"
          width="130"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag
              :type="row.expired ? 'danger' : row.urgency === 'URGENT' ? 'warning' : 'info'"
              size="small"
            >
              {{ URGENCY_LABEL[row.urgency] || row.urgency }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- 数量用右对齐 + col-money（复用全局金额/数值列样式约定） -->
        <el-table-column
          prop="quantity"
          label="数量"
          width="100"
          align="right"
          class-name="col-money"
          label-class-name="col-money"
        />
        <el-table-column
          prop="warehouseId"
          label="仓库"
          min-width="130"
          class-name="col-text"
          label-class-name="col-text"
        />
      </CrudTable>
      <el-alert v-else type="warning" :closable="false" show-icon title="没有仓库查看权限" />
    </el-card>
  </div>
</template>
