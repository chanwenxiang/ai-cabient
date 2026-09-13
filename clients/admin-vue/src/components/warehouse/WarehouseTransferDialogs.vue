<template>
  <el-dialog
    :model-value="transferDialog"
    title="新建仓间调拨"
    destroy-on-close
    @update:model-value="emit('update:transferDialog', $event)"
  >
    <el-form label-width="auto">
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
      <el-form-item label="批次">
        <el-input v-model="transferForm.batchNo" placeholder="可空" />
      </el-form-item>
      <el-form-item label="数量" required>
        <el-input-number v-model="transferForm.quantity" :min="1" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="transferForm.notes" type="textarea" :rows="2" />
      </el-form-item>
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
  transferForm: WarehouseTransferRow;
  warehouses: WarehouseTransferRow[];
}>();

const emit = defineEmits<{
  'update:transferDialog': [value: boolean];
  saveTransfer: [];
}>();
</script>
