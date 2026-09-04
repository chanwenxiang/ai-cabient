<template>
  <div class="slot-grid">
    <div
      v-for="slot in slots"
      :key="slot.slotCode"
      class="slot-cell"
      :class="cellClass(slot)"
      @click="editable && emit('edit', slot)"
    >
      <div class="slot-code">{{ slot.slotCode }}</div>
      <div class="slot-sku">{{ slot.assignedSkuName || slot.assignedSkuId || '未配置' }}</div>
      <div class="slot-qty">账面 {{ slot.bookQty }} / {{ slot.maxLevel || slot.parLevel }}</div>
      <div v-if="slot.lastPhysicalQty != null" class="slot-physical">
        实盘 {{ slot.lastPhysicalQty }}
        <span v-if="slot.hasDiscrepancy" class="diff">差 {{ slot.qtyDiff }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { DeviceSlot } from '@aicabinet/shared-types';

defineProps<{ slots: DeviceSlot[]; editable?: boolean }>();
const emit = defineEmits<{ edit: [DeviceSlot] }>();

function cellClass(slot: DeviceSlot) {
  const st = (slot.stockStatus || '').toLowerCase();
  const cap = slot.maxLevel || slot.parLevel || 0;
  const over = cap > 0 && slot.bookQty > cap;
  return { [`status-${st}`]: !!st, over, mismatch: slot.hasDiscrepancy, clickable: true };
}
</script>

<style scoped>
.slot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 10px;
}
.slot-cell {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 8px;
  font-size: 0.78rem;
  background: var(--el-fill-color-blank);
}
.slot-cell.clickable {
  cursor: pointer;
}
.slot-cell.clickable:hover {
  border-color: var(--el-color-primary);
}
.slot-code {
  font-weight: 600;
}
.slot-sku {
  color: var(--layout-muted);
  margin: 4px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.slot-physical {
  margin-top: 2px;
  color: var(--layout-muted);
  font-size: 0.72rem;
}
.slot-physical .diff {
  color: #c2410c;
  margin-left: 4px;
}
.slot-cell.status-oos {
  border-color: #f87171;
  background: color-mix(in srgb, #ef4444 12%, var(--layout-card));
}
.slot-cell.status-low {
  border-color: #fbbf24;
  background: color-mix(in srgb, #f59e0b 10%, var(--layout-card));
}
.slot-cell.status-over,
.slot-cell.over {
  border-color: #f43f5e;
  background: color-mix(in srgb, #e11d48 14%, var(--layout-card));
}
.slot-cell.mismatch {
  box-shadow: inset 0 0 0 2px #f97316;
}
</style>
