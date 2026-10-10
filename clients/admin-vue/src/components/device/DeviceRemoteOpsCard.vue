<script setup lang="ts">
import type {
  DeviceRemoteOpsPolicy,
  DeviceRepairTicketRow
} from '@/composables/device/useDeviceRemoteOps';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

/** 父组件拥有 reactive 策略对象；开关编辑走 defineModel */
const policy = defineModel<DeviceRemoteOpsPolicy | null>('policy', { required: true });

defineProps<{
  canEditDevice: boolean;
  canAccessReplenishment: boolean;
  canAccessRepairTickets: boolean;
  cmdLoading: string;
  salesLocked: boolean;
  oosSlotCount: number;
  refundPolicyDraft: string;
  refundPolicySaving: boolean;
  globalRefundPolicy: string;
  effectiveRefundPolicy: string;
  refundDraftHint: string;
  refundPriorityHint: string;
  deviceRefundPolicy?: string | null;
  repairTickets: DeviceRepairTicketRow[];
  repairHydrated: boolean;
  policyLabel: (policyCode?: string | null) => string;
  repairStatusLabel: (status?: string) => string;
  priorityLabel: (priority?: string) => string;
}>();

const emit = defineEmits<{
  'send-command': [command: string];
  'go-replenish': [];
  'go-plan-replenish': [];
  'go-restock-tasks': [];
  'go-repair-list': [];
  'create-repair': [];
  'save-refund-policy': [];
  'save-policy': [];
  'update:refundPolicyDraft': [value: string];
}>();
</script>

<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">远程运维</span>
            <span class="hint">远程指令 / 补货入口 / 退款与锁机 / 维修工单</span>
          </div>
        </div>
      </div>
    </template>
    <!--
      远程运维两列：左＝「要下发的动作」（运维指令 / 补货入口 / 维修工单），右＝「柜机上的规则」
      （退款规则 / 策略锁）。维修工单归左列，避免左列只有几个按钮显得空、整卡高度失衡。
    -->
    <div class="ops-grid">
      <div class="ops-col">
        <div class="cmd-section-label">运维指令</div>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          class="open-door-alert"
          title="「运维远程开门」只用于应急检修：开门不结算、不绑补货任务。日常补货请走「补货调度 → 补货开门」或商户小程序（需先签到）。"
        />
        <div class="cmd-bar">
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="primary"
            :loading="cmdLoading === 'OPEN_DOOR'"
            @click="emit('send-command', 'OPEN_DOOR')"
            >运维远程开门</el-button
          >
          <el-button
            v-if="!salesLocked"
            v-hasPermi="['ops:device:edit']"
            type="warning"
            :loading="cmdLoading === 'LOCK'"
            @click="emit('send-command', 'LOCK')"
            >锁机停售</el-button
          >
          <el-button
            v-else
            v-hasPermi="['ops:device:edit']"
            type="success"
            :loading="cmdLoading === 'UNLOCK'"
            @click="emit('send-command', 'UNLOCK')"
            >解锁营业</el-button
          >
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="danger"
            plain
            :loading="cmdLoading === 'REBOOT'"
            @click="emit('send-command', 'REBOOT')"
            >重启设备</el-button
          >
        </div>

        <div class="cmd-section-label">补货入口</div>
        <div class="cmd-bar">
          <el-button v-if="canAccessReplenishment" @click="emit('go-replenish')"
            >缺货建议</el-button
          >
          <el-button
            v-if="canAccessReplenishment && oosSlotCount > 0"
            v-hasPermi="['ops:replenishment:edit']"
            type="primary"
            @click="emit('go-plan-replenish')"
          >
            一键规划补货
          </el-button>
          <el-button
            v-if="canAccessReplenishment"
            type="success"
            plain
            @click="emit('go-restock-tasks')"
          >
            补货调度 / 补货开门
          </el-button>
          <span v-else class="muted">无补货调度权限</span>
        </div>

        <div class="cmd-section-label repair-section">维修工单</div>
        <div class="cmd-bar">
          <el-button v-if="canAccessRepairTickets" @click="emit('go-repair-list')"
            >工单列表</el-button
          >
          <el-button
            v-hasPermi="['ops:repair:edit']"
            type="primary"
            plain
            @click="emit('create-repair')"
            >新建工单</el-button
          >
        </div>
        <el-table
          v-if="repairTickets.length"
          :data="repairTickets"
          size="small"
          class="repair-mini-table"
        >
          <el-table-column prop="ticketId" label="单号" width="70" class-name="col-text" />
          <el-table-column prop="title" label="标题" min-width="140" class-name="col-text" />
          <el-table-column
            prop="status"
            label="状态"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ repairStatusLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column
            label="优先级"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ priorityLabel(row.priority) }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建" width="150" class-name="col-text">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="更新" width="150" class-name="col-text">
            <template #default="{ row }">{{
              row.updatedAt ? formatDateTime(row.updatedAt) : '暂无'
            }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="muted">{{ repairHydrated ? '暂无最近工单' : UI_COPY.loading }}</div>
      </div>

      <div class="ops-col">
        <div class="cmd-section-label">退款规则</div>
        <el-form label-width="auto" class="policy-form" @submit.prevent>
          <el-form-item label="本柜策略">
            <el-select
              :model-value="refundPolicyDraft"
              :disabled="!canEditDevice"
              placeholder="请选择"
              style="width: 280px"
              @update:model-value="emit('update:refundPolicyDraft', $event)"
            >
              <el-option
                :label="`跟随全局默认（${policyLabel(globalRefundPolicy)}）`"
                value="INHERIT"
              />
              <el-option label="消费者可自助退款" value="AUTO_REFUND" />
              <el-option label="仅可申诉，运营审核后退款" value="DISPUTE_ONLY" />
            </el-select>
            <el-button
              v-hasPermi="['ops:device:edit']"
              type="primary"
              class="refund-save-btn"
              :disabled="!canEditDevice"
              :loading="refundPolicySaving"
              @click="emit('save-refund-policy')"
              >保存退款规则</el-button
            >
            <div class="field-hint">{{ refundDraftHint }}</div>
            <div class="field-hint">
              当前生效：
              <el-tag
                size="small"
                :type="effectiveRefundPolicy === 'DISPUTE_ONLY' ? 'warning' : 'success'"
              >
                {{ policyLabel(effectiveRefundPolicy) }}
              </el-tag>
              <span v-if="!deviceRefundPolicy" class="inherit-hint">（跟随全局）</span>
            </div>
          </el-form-item>
        </el-form>
        <div class="field-hint policy-priority-hint">{{ refundPriorityHint }}</div>

        <div class="cmd-section-label policy-lock-label">柜机策略锁</div>
        <el-form v-if="policy" label-width="auto" class="policy-form" @submit.prevent>
          <div class="policy-switch-grid">
            <el-form-item label="营业锁机">
              <el-switch
                v-model="policy.salesLocked"
                :disabled="!canEditDevice"
                @change="() => emit('save-policy')"
              />
            </el-form-item>
            <el-form-item label="价格锁定">
              <el-switch
                v-model="policy.priceLocked"
                :disabled="!canEditDevice"
                @change="() => emit('save-policy')"
              />
            </el-form-item>
            <el-form-item label="禁改商品">
              <el-switch
                v-model="policy.skuEditForbidden"
                :disabled="!canEditDevice"
                @change="() => emit('save-policy')"
              />
            </el-form-item>
            <el-form-item label="禁售">
              <el-switch
                v-model="policy.saleForbidden"
                :disabled="!canEditDevice"
                @change="() => emit('save-policy')"
              />
            </el-form-item>
          </div>
          <div class="field-hint">
            打开或关闭以上开关会同步到柜机立即生效；「禁售」会同时开启营业锁机。停售期间仍可签到后补货开门（不产生消费者账单）。
          </div>
        </el-form>

        <el-alert
          v-if="salesLocked"
          type="warning"
          :closable="false"
          show-icon
          class="lock-restock-hint"
          title="当前已锁机停售：消费者无法开门；补货请走「补货调度 → 签到 → 补货开门」，或使用左侧「运维远程开门」检修。"
        />
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: var(--admin-font-size-title);
}
.hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.ops-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 0 24px;
}
@media (max-width: 1200px) {
  .ops-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
.ops-col {
  min-width: 0;
}
.cmd-section-label {
  margin: 4px 0 8px;
  font-size: var(--admin-font-size-sm);
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.cmd-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}
.open-door-alert {
  margin-bottom: 14px;
}
.repair-section {
  margin-top: 16px;
}
/* 策略锁 4 个开关 2×2 排布：原纵向一行一个，右列被撑得过长 */
.policy-switch-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 24px;
}
@media (max-width: 640px) {
  .policy-switch-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
.policy-lock-label {
  margin-top: 16px;
}
.policy-priority-hint {
  margin: 0 0 4px;
}
.refund-save-btn {
  margin-left: 10px;
}
.inherit-hint {
  margin-left: 8px;
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
.field-hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 4px;
}
.lock-restock-hint {
  margin: 8px 0 12px;
}
.policy-form :deep(.field-hint) {
  flex-basis: 100%;
}
.muted {
  color: var(--el-text-color-secondary);
}
.repair-mini-table {
  width: 100%;
}
</style>
