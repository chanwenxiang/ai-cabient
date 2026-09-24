<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import type { TempPlanEntryDraft } from '@/composables/device/useDeviceTempEnv';
import type { DeviceEnvReading } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

defineProps<{
  canEditTempPlan: boolean;
  tempPlanEnabled: boolean;
  tempPlanEntries: TempPlanEntryDraft[];
  tempPlanSaving: boolean;
  envRows: DeviceEnvReading[];
  envTypeLabel: (metricType: string) => string;
  envUnit: (metricType: string) => string;
}>();

const emit = defineEmits<{
  'update:tempPlanEnabled': [value: boolean];
  'add-entry': [];
  'remove-entry': [index: number];
  save: [];
  apply: [];
  'refresh-env': [];
}>();
</script>

<template>
  <div>
    <div class="temp-plan-box">
      <div class="pane-head">
        <h4>温控计划（分时目标温度）</h4>
        <el-switch
          :model-value="tempPlanEnabled"
          :disabled="!canEditTempPlan"
          aria-label="启用温控计划"
          @update:model-value="emit('update:tempPlanEnabled', $event)"
        />
      </div>
      <p class="muted">
        按当日分钟排程，调度器每分钟把当前时段目标温度下发到柜机；00:00 未设置时沿用前一日最后时段。
      </p>
      <div v-for="(e, i) in tempPlanEntries" :key="i" class="temp-plan-row">
        <el-time-select
          v-model="e.time"
          start="00:00"
          step="00:30"
          end="23:59"
          :disabled="!canEditTempPlan"
          placeholder="开始时间"
          style="width: 130px"
        />
        <el-input-number
          v-model="e.target"
          :min="-30"
          :max="30"
          :step="1"
          :disabled="!canEditTempPlan"
          size="small"
          controls-position="right"
        />
        <span class="muted">°C</span>
        <el-button
          v-if="canEditTempPlan"
          size="small"
          text
          type="danger"
          @click="emit('remove-entry', i)"
          >删除</el-button
        >
      </div>
      <div class="pane-actions">
        <el-button v-if="canEditTempPlan" size="small" @click="emit('add-entry')"
          >+ 添加时间点</el-button
        >
        <el-button
          v-if="canEditTempPlan"
          type="primary"
          size="small"
          :loading="tempPlanSaving"
          @click="emit('save')"
          >保存并应用</el-button
        >
        <el-button size="small" :loading="tempPlanSaving" @click="emit('apply')"
          >立即应用</el-button
        >
      </div>
    </div>

    <div class="env-box">
      <div class="pane-head">
        <h4>环境监控（近 24h）</h4>
        <el-button size="small" :icon="Refresh" @click="emit('refresh-env')">刷新</el-button>
      </div>
      <el-table :data="envRows" size="small" border stripe>
        <el-table-column label="指标" width="110">
          <template #default="{ row }">{{ envTypeLabel(String(row.metricType || '')) }}</template>
        </el-table-column>
        <el-table-column label="数值">
          <template #default="{ row }"
            >{{ row.value }}{{ envUnit(String(row.metricType || '')) }}</template
          >
        </el-table-column>
        <el-table-column label="上报时间" width="190">
          <template #default="{ row }">{{ formatDateTime(row.reportedAt) }}</template>
        </el-table-column>
      </el-table>
      <p v-if="!envRows.length" class="muted">暂无环境读数（设备心跳需携带湿度/电压/功耗字段）</p>
    </div>
  </div>
</template>

<style scoped>
.temp-plan-box,
.env-box {
  margin-bottom: 16px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.pane-head h4 {
  margin: 0;
  font-size: var(--admin-font-size-title);
}
.temp-plan-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.pane-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
</style>
