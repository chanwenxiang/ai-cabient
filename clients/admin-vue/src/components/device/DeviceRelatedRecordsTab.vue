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
  /** 段级总数（列表只取最近 8 条，总数用于「共 N 条 · 查看全部」，MA-009） */
  sessionTotal: number;
  orderTotal: number;
  canAccessSessions: boolean;
  canAccessOrders: boolean;
}>();

const emit = defineEmits<{
  'open-sessions': [payload: { deviceId: string; sessionId?: string }];
  'open-orders': [payload: { deviceId: string }];
}>();

function sessionKindLabel(kind?: string | null) {
  // 与 SessionListView 同源走 session_kind 字典（STOCKTAKE=盘点此前漏分支显示成「消费」，2026-10-30 修复）
  return displayLabel('session_kind', kind, '消费');
}
</script>

<template>
  <div>
    <h4 class="section-title">
      最近开门记录
      <el-button
        v-if="sessionTotal > sessions.length"
        link
        type="primary"
        class="section-more"
        @click="emit('open-sessions', { deviceId })"
        >共 {{ sessionTotal }} 条 · 查看全部</el-button
      >
    </h4>
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
            ><el-empty v-if="hydrated" description="暂无开门记录" :image-size="48"
          /></template>
          <el-table-column label="开门编号" min-width="160" class-name="col-text">
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
          <!-- 「录像」列已下线：判据是旧边缘链路的 shopping_session.video_uri/upload_status，
               将邑柜机从不写这两列 ⇒ 恒显「无」，属误导性死信息。取货视频改在「订单 →
               柜机取货视频」复核（CB-029，按订单 = sessionId 直查将邑台账）。 -->
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

    <h4 class="section-title">
      最近订单
      <el-button
        v-if="orderTotal > orders.length"
        link
        type="primary"
        class="section-more"
        @click="emit('open-orders', { deviceId })"
        >共 {{ orderTotal }} 条 · 查看全部</el-button
      >
    </h4>
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
                  Number(row.couponDiscountCents || 0) +
                    Number(row.memberDiscountCents || 0) +
                    Number(row.activityDiscountCents || 0) >
                  0
                "
              >
                -¥{{
                  (
                    (Number(row.couponDiscountCents || 0) +
                      Number(row.memberDiscountCents || 0) +
                      Number(row.activityDiscountCents || 0)) /
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
/* MA-009：段级「共 N 条 · 查看全部」跟标题同行，字号降一级、不加粗，别抢标题权重 */
.section-more {
  margin-left: 8px;
  font-size: var(--admin-font-size-body);
  font-weight: 400;
}
.section-title + .table-scroll {
  margin-bottom: 18px;
}
.muted {
  color: var(--el-text-color-secondary);
}
</style>
