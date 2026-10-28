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
import { displayLabel } from '@aicabinet/shared-dict';
import { EMPTY_TEXT } from '@/utils/display';

interface WarehouseOption {
  warehouseId: string;
  warehouseName: string;
}

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

// 🔴 仓库候选来自 `warehouse` 表（warehouseListAll），**不是 sys_dict**：
//    字典只有 warehouse_status 之类的枚举，没有仓库主数据。此前这里是自由文本框，
//    逼运营手敲仓库 id ⇒ 既敲不对，也和列表里那列的 id 对不上眼。
const warehouses = ref<WarehouseOption[]>([]);
const warehousesLoading = ref(false);
async function loadWarehouses() {
  warehousesLoading.value = true;
  try {
    const data = await api.request<{ items: WarehouseOption[] }>(
      AdminEndpoints.warehouseListAll,
      'GET'
    );
    warehouses.value = data.items || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? `仓库列表加载失败：${e.message}` : '仓库列表加载失败');
  } finally {
    warehousesLoading.value = false;
  }
}
// 无仓库权限时不拉：否则进页面先吃一个 403 红条，而页面本来就会显示「没有仓库查看权限」
if (canList.value) void loadWarehouses();

const warehouseNameById = computed(() => {
  const map = new Map<string, string>();
  for (const w of warehouses.value) map.set(w.warehouseId, w.warehouseName);
  return map;
});

/** 仓库列：id→名称。查不到（仓库被删 / 超出目录 500 上限）时退回原 id，不显示「暂无」掩盖问题。 */
function warehouseLabel(id?: string | null): string {
  if (!id) return EMPTY_TEXT;
  return warehouseNameById.value.get(id) ?? id;
}

const crud = useCrudTable<ExpiryAlertRow>({
  rowKey: (r) => r.inventoryId,
  fetchPage: async (params) => {
    const q = new URLSearchParams({
      page: String(params.page), // 0 起（useCrudTable 已换算）
      size: String(params.size)
    });
    // 下拉选择（clearable 可能置空）；不再 .trim()——值不是自由文本了
    if (warehouseId.value) q.set('warehouseId', warehouseId.value);
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

// 🔴 文案统一走 shared-dict（expiry_urgency），与后端 WarehouseExpiryAlert.urgency 一一对应。
//    此前在此硬编码 4 个中文标签，触犯 local/no-hardcoded-status-label（CI lint 会红）。
const URGENCY_LABEL: Record<string, string> = {
  EXPIRED: displayLabel('expiry_urgency', 'EXPIRED'),
  URGENT: displayLabel('expiry_urgency', 'URGENT'),
  SOON: displayLabel('expiry_urgency', 'SOON'),
  NORMAL: displayLabel('expiry_urgency', 'NORMAL')
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
  <!-- 🔴 标准页壳 page-card：统一外层。本页此前用裸 div.page（该类无任何样式定义）⇒
       内容铺灰底、`.page-card .el-table` 表格规范样式接不上，与待索赔台账同类问题。 -->
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">仓库近效期预警</span>
            <span class="hint">
              这里只看仓库里的批次；柜机批次的到期预警在「补货预警」页（数据源不同）。本页含已过期批次——已过期的往往比临期更需要立刻处理。
            </span>
          </div>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="onFilterChange">
      <el-form-item label="仓库">
        <el-select
          v-model="warehouseId"
          placeholder="全部仓库"
          clearable
          filterable
          style="width: 200px"
          :loading="warehousesLoading"
          data-testid="wea-warehouse-select"
          @change="onFilterChange"
        >
          <el-option
            v-for="w in warehouses"
            :key="w.warehouseId"
            :label="w.warehouseName"
            :value="w.warehouseId"
          />
        </el-select>
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
      >
        <template #default="{ row }">{{ warehouseLabel(row.warehouseId) }}</template>
      </el-table-column>
    </CrudTable>
    <el-alert v-else type="warning" :closable="false" show-icon title="没有仓库查看权限" />
  </el-card>
</template>
