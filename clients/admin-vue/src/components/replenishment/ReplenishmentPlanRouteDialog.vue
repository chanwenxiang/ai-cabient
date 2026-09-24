<script setup lang="ts">
import type { ReplenishmentAssigneeOption } from '@/composables/replenishment/useReplenishmentRoutePlanning';
import type { ReplenishmentDeviceRef } from '@/composables/replenishment/useReplenishmentTaskActions';

defineProps<{
  modelValue: boolean;
  planForm: {
    routeName: string;
    plannedDate: string;
    assigneeUserId: number | undefined;
    deviceIds: string[];
  };
  planSaving: boolean;
  assigneeLoading: boolean;
  assigneeOptions: ReplenishmentAssigneeOption[];
  devices: ReplenishmentDeviceRef[];
  shortageDeviceIds: string[];
  selectedDevicesWithoutShortage: string[];
  assigneeOptionLabel: (op: ReplenishmentAssigneeOption) => string;
  planDeviceLabel: (device: ReplenishmentDeviceRef) => string;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  create: [];
  'go-shortage': [];
  'go-stock-health': [];
}>();
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="规划补货路线"
    class="dialog-wide"
    append-to-body
    destroy-on-close
    data-testid="plan-route-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-width="auto" class="plan-form">
      <el-form-item label="路线名称" required>
        <el-input
          v-model="planForm.routeName"
          maxlength="80"
          placeholder="例如：浦东早班补货路线"
          data-testid="plan-route-name"
        />
      </el-form-item>
      <el-form-item label="计划日期">
        <input
          v-model="planForm.plannedDate"
          class="native-date"
          type="date"
          data-testid="plan-route-date"
        />
      </el-form-item>
      <el-form-item label="负责人">
        <el-select
          v-model="planForm.assigneeUserId"
          filterable
          clearable
          placeholder="选择负责人"
          style="width: 100%"
          :loading="assigneeLoading"
          data-testid="plan-assignee-select"
        >
          <el-option
            v-for="op in assigneeOptions"
            :key="op.userId"
            :label="assigneeOptionLabel(op)"
            :value="op.userId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="目标设备" required>
        <!-- 勾选列表替代下拉：热区更大，Browser 不易难点选 -->
        <div class="plan-device-list" data-testid="plan-device-select">
          <el-checkbox-group v-model="planForm.deviceIds" class="plan-device-group">
            <!-- div 而非 label：避免外层 label 与 el-checkbox 内部 label 双绑导致偶发点选无效 -->
            <div
              v-for="device in devices"
              :key="device.deviceId"
              class="plan-device-option"
              :data-testid="`plan-device-option-${device.deviceId}`"
            >
              <el-checkbox :label="device.deviceId">
                {{ planDeviceLabel(device) }}
              </el-checkbox>
            </div>
          </el-checkbox-group>
        </div>
        <div v-if="!shortageDeviceIds.length" class="plan-hint">
          当前无缺货建议：满柜时无法规划。请先盘点/消费产生缺口，或
          <el-button link type="primary" native-type="button" @click="emit('go-shortage')"
            >查看缺货建议</el-button
          >、
          <el-button link type="primary" native-type="button" @click="emit('go-stock-health')"
            >库存健康</el-button
          >。
        </div>
        <div v-else-if="selectedDevicesWithoutShortage.length" class="plan-hint">
          所选设备中
          {{ selectedDevicesWithoutShortage.join('、') }} 不在缺货建议内，满柜可能无法生成出库单。
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="plan-dialog-footer">
        <el-button native-type="button" @click="emit('update:modelValue', false)">取消</el-button>
        <el-button
          type="primary"
          native-type="button"
          class="plan-create-btn"
          :loading="planSaving"
          :disabled="!planForm.deviceIds.length || planSaving"
          data-testid="plan-create-route"
          @click.stop="emit('create')"
        >
          创建路线
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.plan-hint {
  margin-top: 6px;
  font-size: var(--admin-font-size-sm);
  color: var(--el-color-warning);
  line-height: 1.4;
}
.plan-form {
  margin-top: 4px;
}
.plan-device-list {
  width: 100%;
  max-height: 220px;
  overflow: auto;
  border: 1px solid var(--layout-border);
  border-radius: 6px;
  padding: 4px 0;
  background: var(--layout-card);
}
.plan-device-group {
  display: flex;
  flex-direction: column;
  width: 100%;
}
.plan-device-option {
  display: flex;
  align-items: center;
  min-height: 40px;
  padding: 6px 12px;
  cursor: pointer;
  box-sizing: border-box;
}
.plan-device-option:hover {
  background: var(--el-fill-color-light);
}
.plan-device-option :deep(.el-checkbox) {
  width: 100%;
  height: auto;
  margin-right: 0;
}
.plan-device-option :deep(.el-checkbox__label) {
  white-space: normal;
  line-height: 1.35;
}
.plan-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  position: relative;
  z-index: 2;
}
.plan-create-btn {
  position: relative;
  z-index: 3;
  min-width: 96px;
  min-height: 36px;
  pointer-events: auto;
}
.native-date {
  width: 100%;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--layout-border);
  border-radius: 4px;
  color: var(--layout-text);
  background: var(--layout-card);
  box-sizing: border-box;
}
</style>
