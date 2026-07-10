<template>
  <div class="slot-grid">
    <div v-for="slot in slots" :key="slot.slotCode" class="slot-cell" :class="cellClass(slot)" @click="editable && emit('edit', slot)">
      <div class="slot-code">{{ slot.slotCode }}</div>
      <div class="slot-sku">{{ slot.assignedSkuName || slot.assignedSkuId || '未配置' }}</div>
      <div class="slot-qty">库存 {{ slot.bookQty }} / {{ slot.maxLevel || slot.parLevel }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { DeviceSlot } from '@aicabinet/shared-types';

defineProps<{ slots: DeviceSlot[]; editable?: boolean }>();
const emit = defineEmits<{ edit: [DeviceSlot] }>();

function cellClass(slot: DeviceSlot) {
  const st = (slot.stockStatus || '').toLowerCase();
  return { [`status-${st}`]: !!st, mismatch: slot.hasDiscrepancy, clickable: true };
}
</script>

<style scoped>
.slot-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 10px; }
.slot-cell {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 8px;
  font-size: 0.78rem;
  background: var(--el-fill-color-blank);
}
.slot-cell.clickable { cursor: pointer; }
.slot-cell.clickable:hover { border-color: var(--el-color-primary); }
.slot-code { font-weight: 600; }
.slot-sku { color: #64748b; margin: 4px 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.slot-cell.status-oos { border-color: #fca5a5; background: #fef2f2; }
.slot-cell.status-low { border-color: #fcd34d; }
.slot-cell.mismatch { box-shadow: inset 0 0 0 2px #f97316; }
</style>
