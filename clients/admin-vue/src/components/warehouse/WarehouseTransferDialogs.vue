<template>
  <el-dialog
    :model-value="transferDialog"
    title="新建仓间调拨"
    class="dialog-wide"
    destroy-on-close
    @update:model-value="emit('update:transferDialog', $event)"
  >
    <el-form label-width="auto">
      <div class="form-grid">
        <el-form-item label="调出仓" required>
          <el-select v-model="transferForm.fromWarehouseId" filterable style="width: 100%">
            <el-option
              v-for="w in warehouses"
              :key="w.warehouseId"
              :label="w.warehouseName || w.warehouseId"
              :value="w.warehouseId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="调入仓" required>
          <el-select v-model="transferForm.toWarehouseId" filterable style="width: 100%">
            <el-option
              v-for="w in warehouses"
              :key="'to-' + w.warehouseId"
              :label="w.warehouseName || w.warehouseId"
              :value="w.warehouseId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="SKU" required>
          <el-input v-model="transferForm.skuId" placeholder="商品 SKU" />
        </el-form-item>
        <el-form-item label="批次" required>
          <el-input v-model="transferForm.batchNo" placeholder="收货按 SKU+批次入库" />
        </el-form-item>
        <el-form-item label="有效期" required>
          <el-date-picker
            v-model="transferForm.expiryDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择有效期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number v-model="transferForm.quantity" :min="1" />
        </el-form-item>
        <el-form-item label="备注" class="span-2">
          <el-input v-model="transferForm.notes" type="textarea" :rows="2" />
        </el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:transferDialog', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="emit('saveTransfer')">创建</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { WarehouseTransferRow } from '@/composables/warehouse/useWarehouseTransfers';

defineProps<{
  transferDialog: boolean;
  saving: boolean;
  warehouses: WarehouseTransferRow[];
}>();

const transferForm = defineModel<WarehouseTransferRow>('transferForm', { required: true });
const emit = defineEmits<{
  'update:transferDialog': [value: boolean];
  saveTransfer: [];
}>();
</script>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.span-2 {
  grid-column: 1 / -1;
}
@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
