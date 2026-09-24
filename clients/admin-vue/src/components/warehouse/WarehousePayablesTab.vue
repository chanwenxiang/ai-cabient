<script setup lang="ts">
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { formatDateTime } from '@aicabinet/shared-uni/format';

export type WarehousePayableRow = AdminDynamicRow;

defineProps<{
  loading: boolean;
  hydrated: boolean;
  rows: WarehousePayableRow[];
  summaryText: string;
  canEdit: boolean;
  money: (cents: number) => string;
  payableStatusType: (status: string) => string;
  payableStatusText: (status: string) => string;
}>();

const emit = defineEmits<{
  'selection-change': [rows: WarehousePayableRow[]];
  pay: [row: WarehousePayableRow];
}>();
</script>

<template>
  <el-alert
    v-if="hydrated && !loading"
    :closable="false"
    show-icon
    type="info"
    class="payable-summary"
    :title="summaryText"
  />
  <div class="table-scroll">
    <div class="table-scroll-inner">
      <el-table
        class="report-table"
        v-loading="loading"
        :data="rows"
        stripe
        border
        row-key="payableId"
        empty-text=" "
        @selection-change="emit('selection-change', $event)"
      >
        <el-table-column
          type="expand"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <div class="expand-panel">
              <el-table
                v-if="row.payments?.length"
                :data="row.payments"
                size="small"
                border
                class="line-table"
              >
                <el-table-column
                  label="付款时间"
                  min-width="170"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="scope">
                    {{ formatDateTime(scope.row.createdAt) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="付款金额"
                  min-width="110"
                  align="center"
                  class-name="col-money"
                  label-class-name="col-money"
                >
                  <template #default="scope">¥{{ money(scope.row.amountCents) }}</template>
                </el-table-column>
                <el-table-column
                  prop="notes"
                  label="备注"
                  min-width="180"
                  class-name="col-text"
                  label-class-name="col-text"
                />
              </el-table>
              <el-empty v-else description="暂无付款记录" :image-size="60" />
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="供应商"
          min-width="150"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">{{ row.supplierName }}</template>
        </el-table-column>
        <el-table-column
          label="关联采购单"
          min-width="110"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">
            <span class="cell-id">{{ row.purchaseOrderId }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="应付金额"
          min-width="110"
          align="center"
          class-name="col-money"
          label-class-name="col-money"
        >
          <template #default="{ row }">¥{{ money(row.amountCents) }}</template>
        </el-table-column>
        <el-table-column
          label="已付"
          min-width="100"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">¥{{ money(row.paidAmountCents) }}</template>
        </el-table-column>
        <el-table-column
          label="未付余额"
          min-width="110"
          align="center"
          class-name="col-money"
          label-class-name="col-money"
        >
          <template #default="{ row }">
            <span class="cell-id">{{ money(row.balanceCents) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="到期日"
          min-width="110"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ row.dueDate || '暂无' }}</template>
        </el-table-column>
        <el-table-column
          label="状态"
          min-width="100"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag :type="payableStatusType(String(row.status || ''))" size="small">
              {{ payableStatusText(String(row.status || '')) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="逾期"
          min-width="116"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-tag v-if="row.overdue" type="danger" size="small">
              逾期 {{ row.overdueDays }} 天
            </el-tag>
            <span v-else class="muted">未逾期</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="canEdit"
          label="操作"
          width="100"
          align="center"
          fixed="right"
          class-name="col-action"
          label-class-name="col-action"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="row.balanceCents <= 0 || ['PAID', 'CLOSED'].includes(row.status)"
              data-testid="pay-payable"
              @click="emit('pay', row)"
              >登记付款</el-button
            >
          </template>
        </el-table-column>
        <template #empty>
          <el-empty v-if="hydrated && !loading" description="暂无应付账款" />
        </template>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.payable-summary {
  margin-bottom: 12px;
}
</style>
