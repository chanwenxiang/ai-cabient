<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { errorMessage } from '@/utils/error-message';
import { yuanText } from '@/utils/display';

export type WarehouseMonthlyCloseRow = AdminDynamicRow;

const props = defineProps<{
  loading: boolean;
  hydrated: boolean;
  hint: string;
  /** 分仓主责人姓名；未绑定为空。🔴 只是归属展示，不是权限闸（口径见 CB-068）。 */
  managerName: string;
  /** 归属该仓的柜机台数（按 device_info.home_warehouse_id）。 */
  deviceCount: number;
  rows: WarehouseMonthlyCloseRow[];
  /** 领货列下钻：取该商品本账期发运流水的柜机归属明细（含上柜实收与领而未上）。 */
  loadRestockDetail: (skuId: string) => Promise<AdminDynamicRow[]>;
}>();

const detailVisible = ref(false);
const detailLoading = ref(false);
const detailRows = ref<AdminDynamicRow[]>([]);
const detailSkuName = ref('');
const detailExpectedQty = ref(0);

async function openRestockDetail(row: WarehouseMonthlyCloseRow) {
  detailSkuName.value = String(row.skuName ?? row.skuId ?? '');
  detailExpectedQty.value = Number(row.restockQty) || 0;
  detailRows.value = [];
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    detailRows.value = await props.loadRestockDetail(String(row.skuId ?? ''));
  } catch (e) {
    ElMessage.error(errorMessage(e, '领货明细加载失败'));
  } finally {
    detailLoading.value = false;
  }
}

/** 明细合计与「领货」列值不一致时要点破：下钻按发运流水取数，理论上必然相等。 */
function detailDiffers(): boolean {
  const sum = detailRows.value.reduce((acc, r) => acc + (Number(r.qty) || 0), 0);
  return sum !== detailExpectedQty.value;
}

/** 领而未上合计：这一列不是损耗、也不是在途，货还在补货员手上（CB-074）。 */
function detailRemainderTotal(): number {
  return detailRows.value.reduce((acc, r) => acc + (Number(r.remainderQty) || 0), 0);
}
</script>

<template>
  <p class="muted tip">{{ hint || '件数月结，与结算金额无关。' }}</p>
  <p class="muted tip">
    分仓归属：<span>{{ managerName ? `主责人 ${managerName}` : '主责人未绑定' }}</span> · 名下柜机
    {{ deviceCount }} 台（月结按仓计件，柜机维度请点「领货」列下钻）
  </p>
  <div class="table-scroll">
    <div class="table-scroll-inner">
      <el-table
        class="report-table"
        v-loading="loading"
        :data="rows"
        stripe
        border
        row-key="skuId"
        empty-text="本月无进出流水"
      >
        <el-table-column
          label="商品名称"
          min-width="220"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">
            <el-tooltip :content="`SKU ID：${row.skuId}`" placement="top">
              <span>{{ row.skuName || row.skuId }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column
          label="条码"
          min-width="126"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">{{ row.barcode || '—' }}</template>
        </el-table-column>
        <el-table-column
          prop="openingQty"
          label="上期"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="purchaseInQty"
          label="采购入库"
          min-width="88"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="transferInQty"
          label="调入"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="returnQty"
          label="退货"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="transferOutQty"
          label="调出"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column min-width="86" class-name="col-num" label-class-name="col-num">
          <template #header>
            <el-tooltip
              content="本账期从分仓带出去的件数（出库领货量）。进了柜机的算「上柜」，没进的算「领而未上」——点数字下钻"
              placement="top"
            >
              <span>领货</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-button
              v-if="Number(row.restockQty) > 0"
              link
              type="primary"
              @click="openRestockDetail(row)"
              >{{ row.restockQty }}</el-button
            >
            <span v-else>0</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="rockLossQty"
          label="摇机损耗"
          min-width="80"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="expiredLossQty"
          label="过期损耗"
          min-width="80"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="guestWaterQty"
          label="客情用水"
          min-width="80"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="feeDeductQty"
          label="费用抵扣"
          min-width="80"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="lossQty"
          label="其他损耗"
          min-width="80"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          prop="expectedQty"
          label="应有"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        />
        <el-table-column
          label="实盘"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        >
          <template #default="{ row }">{{
            row.countedQty == null ? '未盘' : row.countedQty
          }}</template>
        </el-table-column>
        <el-table-column
          label="差异"
          min-width="72"
          class-name="col-num"
          label-class-name="col-num"
        >
          <template #default="{ row }">{{ row.gapQty == null ? '—' : row.gapQty }}</template>
        </el-table-column>
        <el-table-column
          label="单价"
          min-width="76"
          class-name="col-num"
          label-class-name="col-num"
        >
          <template #default="{ row }">{{
            row.unitPriceCents == null ? '—' : yuanText(row.unitPriceCents)
          }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>

  <el-dialog v-model="detailVisible" :title="`领货明细 · ${detailSkuName}`" width="820px">
    <p v-if="detailDiffers()" class="muted tip">
      ⚠️ 明细合计与「领货」列不一致（列值 {{ detailExpectedQty }}）——请核对领货流水是否被改单冲销。
    </p>
    <p v-else-if="detailRemainderTotal() > 0" class="muted tip">
      本账期该商品领而未上 {{ detailRemainderTotal() }} 件：货已出分仓、尚未进柜，
      既不是损耗也不该退回仓库，下次现场上架后由补货任务入账。
    </p>
    <el-table
      class="report-table"
      v-loading="detailLoading"
      :data="detailRows"
      stripe
      border
      empty-text="本账期该商品无领货流水"
    >
      <el-table-column
        label="柜机"
        min-width="180"
        class-name="col-text"
        label-class-name="col-text"
      >
        <template #default="{ row }">{{ row.deviceName || row.deviceId || '未归属柜机' }}</template>
      </el-table-column>
      <el-table-column
        prop="qty"
        label="领货"
        min-width="72"
        class-name="col-num"
        label-class-name="col-num"
      />
      <el-table-column label="上柜" min-width="72" class-name="col-num" label-class-name="col-num">
        <template #default="{ row }">{{ row.shelvedQty ?? row.qty }}</template>
      </el-table-column>
      <el-table-column
        label="领而未上"
        min-width="90"
        class-name="col-num"
        label-class-name="col-num"
      >
        <template #default="{ row }">
          <span :class="{ warned: Number(row.remainderQty) > 0 }">{{
            Number(row.remainderQty) || 0
          }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="出库单"
        min-width="120"
        class-name="col-text"
        label-class-name="col-text"
      >
        <template #default="{ row }">{{ row.outboundIds || '—' }}</template>
      </el-table-column>
      <el-table-column
        label="领货时间"
        min-width="130"
        class-name="col-text"
        label-class-name="col-text"
      >
        <template #default="{ row }">{{ row.lastShippedAt || '—' }}</template>
      </el-table-column>
    </el-table>
    <template #footer>
      <span class="muted">按领货流水逐条汇总，与「领货」列同源；领而未上取自出库单行上的差额</span>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip {
  margin: 0 0 8px;
}
.muted {
  color: var(--layout-muted);
}
.warned {
  color: var(--el-color-warning);
  font-weight: 600;
}
</style>
