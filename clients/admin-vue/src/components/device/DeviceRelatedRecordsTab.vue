<script setup lang="ts">
import { View } from '@element-plus/icons-vue';
import TableActions from '@/components/TableActions.vue';
import type { OrderReadModel, SessionDto } from '@aicabinet/shared-types';
import { dictLabel, displayLabel } from '@aicabinet/shared-dict';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';

defineProps<{
  deviceId: string;
  hydrated: boolean;
  sessions: SessionDto[];
  orders: OrderReadModel[];
  canAccessSessions: boolean;
  canAccessOrders: boolean;
}>();

const emit = defineEmits<{
  'open-sessions': [payload: { deviceId: string; sessionId?: string }];
  'open-orders': [payload: { deviceId: string }];
}>();

function sessionKindLabel(kind?: string | null) {
  if (kind === 'RESTOCK') return '补货';
  if (kind === 'OPS') return '运维';
  return '消费';
}
</script>

<template>
  <div>
    <h4 class="section-title">最近开门记录</h4>
    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="!hydrated"
          :data="sessions"
          stripe
          border
          size="small"
          class="report-table"
          empty-text=" "
        >
          <template #empty
            ><el-empty v-if="hydrated" description="暂无会话" :image-size="48"
          /></template>
          <el-table-column label="会话" min-width="160" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ displayBizNo(row.sessionId) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{
                dictLabel('session_state', row.state)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="类型"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ sessionKindLabel(row.sessionKind) }}</template>
          </el-table-column>
          <el-table-column
            label="入口"
            width="88"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">
              {{ displayLabel('pay_channel', row.entryChannel || row.payChannel, '暂无') }}
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="录像"
            width="72"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              {{ row.videoUri || row.uploadStatus === 'UPLOADED' ? '有' : '无' }}
            </template>
          </el-table-column>
          <el-table-column label="订单" min-width="120" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ displayBizNo(row.orderId, '无') }}</span>
            </template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="120" class-name="col-text">
            <template #default="{ row }">
              {{ row.failReason || row.failureReason || '暂无' }}
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
          <el-table-column
            label="操作"
            width="88"
            class-name="col-action"
            align="center"
            fixed="right"
          >
            <template #default="{ row }">
              <TableActions
                v-if="canAccessSessions"
                :actions="[{ key: 'sessions', label: '查看', icon: View, type: 'primary' }]"
                @action="
                  emit('open-sessions', {
                    deviceId,
                    sessionId: row.sessionId || undefined
                  })
                "
              />
              <span v-else class="muted">暂无</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <h4 class="section-title">最近订单</h4>
    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="!hydrated"
          :data="orders"
          stripe
          border
          size="small"
          class="report-table"
          empty-text=" "
        >
          <template #empty
            ><el-empty v-if="hydrated" description="暂无订单" :image-size="48"
          /></template>
          <el-table-column label="订单" min-width="160" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ displayBizNo(row.orderId) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{
                dictLabel('order_status', row.status)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="渠道"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              {{ displayLabel('pay_channel', row.payChannel, '暂无') }}
            </template>
          </el-table-column>
          <el-table-column label="金额" width="100" class-name="col-money">
            <template #default="{ row }"
              >¥{{ ((row.totalAmountCents || 0) / 100).toFixed(2) }}</template
            >
          </el-table-column>
          <el-table-column label="优惠" width="88" class-name="col-money">
            <template #default="{ row }">
              <span
                v-if="
                  Number(row.couponDiscountCents || 0) + Number(row.memberDiscountCents || 0) > 0
                "
              >
                -¥{{
                  (
                    (Number(row.couponDiscountCents || 0) + Number(row.memberDiscountCents || 0)) /
                    100
                  ).toFixed(2)
                }}
              </span>
              <span v-else class="muted">暂无</span>
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
          <el-table-column
            label="操作"
            width="88"
            class-name="col-action"
            align="center"
            fixed="right"
          >
            <template #default>
              <TableActions
                v-if="canAccessOrders"
                :actions="[{ key: 'orders', label: '查看', icon: View, type: 'primary' }]"
                @action="emit('open-orders', { deviceId })"
              />
              <span v-else class="muted">暂无</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.section-title {
  margin: 0 0 10px;
  font-size: var(--admin-font-size-title);
  font-weight: 600;
}
.section-title + .table-scroll {
  margin-bottom: 18px;
}
.muted {
  color: var(--el-text-color-secondary);
}
</style>
