<script setup lang="ts">
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';

export type WarehousePurchaseOrderRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  rows: WarehousePurchaseOrderRow[];
  canEdit: boolean;
  canProcurementList: boolean;
  skuName: (skuId?: string) => string;
  supplierName: (supplierId: string) => string;
  warehouseName: (warehouseId: string) => string;
  money: (cents: number) => string;
  canReviewPurchaseRow: (row: WarehousePurchaseOrderRow) => boolean;
}>();

const emit = defineEmits<{
  'selection-change': [rows: WarehousePurchaseOrderRow[]];
  print: [row: WarehousePurchaseOrderRow];
  'review-approve': [row: WarehousePurchaseOrderRow];
  'review-reject': [row: WarehousePurchaseOrderRow];
  receive: [row: WarehousePurchaseOrderRow];
}>();
</script>

<template>
  <div class="table-scroll">
    <div class="table-scroll-inner">
      <el-table
        class="report-table"
        v-loading="loading"
        :data="rows"
        stripe
        border
        row-key="purchaseOrderId"
        empty-text=" "
        @selection-change="emit('selection-change', $event)"
      >
        <el-table-column
          type="selection"
          width="48"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          type="expand"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <div class="expand-panel">
              <el-table :data="row.lines || []" size="small" border class="line-table">
                <el-table-column
                  label="商品"
                  min-width="180"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="scope">
                    {{ skuName(scope.row.skuId) }}
                  </template>
                </el-table-column>
                <el-table-column
                  prop="batchNo"
                  label="批次"
                  min-width="140"
                  class-name="col-text"
                  label-class-name="col-text"
                />
                <el-table-column
                  prop="orderedQty"
                  label="采购数"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  prop="receivedQty"
                  label="已收数"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  prop="returnedQty"
                  label="已退数"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="成本"
                  min-width="96"
                  align="center"
                  class-name="col-money"
                  label-class-name="col-money"
                >
                  <template #default="scope">¥{{ money(scope.row.unitCostCents) }}</template>
                </el-table-column>
                <el-table-column
                  prop="expiryDate"
                  label="到期日期"
                  min-width="120"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          prop="purchaseOrderId"
          label="采购单"
          min-width="96"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          prop="refNo"
          label="外部单号"
          min-width="140"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <span v-if="row.refNo">{{ row.refNo }}</span>
            <span v-else class="muted">未填写</span>
          </template>
        </el-table-column>
        <el-table-column
          label="供应商"
          min-width="160"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">
            {{ supplierName(String(row.supplierId || '')) }}
          </template>
        </el-table-column>
        <el-table-column
          label="入库仓库"
          min-width="160"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            {{ warehouseName(String(row.warehouseId || '')) }}
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          min-width="120"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag :type="dictTagType(row.status)" size="small">{{
              dictLabel('purchase_order_status', row.status)
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          v-if="canProcurementList"
          label="审批节点"
          min-width="140"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING_APPROVAL' && row.approvalCurrentNodeName">
              <span>{{ row.approvalCurrentNodeName }}</span>
              <span v-if="row.approvalPendingForMe === false" class="muted"> （待他人处理） </span>
            </template>
            <span v-else-if="row.status === 'PENDING_APPROVAL'" class="muted">待审批</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="canEdit"
          label="操作"
          min-width="220"
          class-name="col-action"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              v-if="row.status !== 'PENDING_APPROVAL'"
              link
              type="primary"
              class="print-btn"
              @click="emit('print', row)"
              >打印收货单</el-button
            >
            <el-button
              v-if="row.status === 'PENDING_APPROVAL' && canReviewPurchaseRow(row)"
              link
              type="success"
              @click="emit('review-approve', row)"
              >通过</el-button
            >
            <el-button
              v-if="row.status === 'PENDING_APPROVAL' && canReviewPurchaseRow(row)"
              link
              type="danger"
              @click="emit('review-reject', row)"
              >驳回</el-button
            >
            <el-button
              v-if="['CREATED', 'PARTIAL_RECEIVED'].includes(row.status)"
              link
              type="primary"
              @click="emit('receive', row)"
              >采购收货</el-button
            >
          </template>
        </el-table-column>
        <template #empty>
          <el-empty v-if="hydrated && !loading" description="暂无采购单" />
        </template>
      </el-table>
    </div>
  </div>
</template>
