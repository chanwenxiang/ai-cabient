<template>
  <el-dialog
    :model-value="binDialog"
    :title="binForm.editing ? '编辑货位' : '新增货位'"
    destroy-on-close
    @update:model-value="emit('update:binDialog', $event)"
  >
    <el-form label-width="auto">
      <el-form-item label="仓库" required>
        <el-select
          v-model="binForm.warehouseId"
          filterable
          :disabled="binForm.editing"
          style="width: 100%"
        >
          <el-option
            v-for="w in activeWarehouses"
            :key="w.warehouseId"
            :label="w.warehouseName"
            :value="w.warehouseId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="货位编码" required
        ><el-input
          v-model="binForm.binCode"
          :disabled="binForm.editing"
          placeholder="如 A-01"
          maxlength="32"
      /></el-form-item>
      <el-form-item label="货位名称"
        ><el-input v-model="binForm.binName" maxlength="64"
      /></el-form-item>
      <el-form-item label="状态">
        <el-radio-group v-model="binForm.status">
          <el-radio value="ACTIVE">启用</el-radio>
          <el-radio value="INACTIVE">停用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:binDialog', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="emit('saveBin')">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog
    :model-value="binInboundDialog"
    title="入库到货位"
    class="dialog-wide"
    destroy-on-close
    @update:model-value="emit('update:binInboundDialog', $event)"
  >
    <el-form label-width="auto">
      <el-form-item label="仓库" required>
        <el-select
          v-model="binInboundForm.warehouseId"
          filterable
          style="width: 100%"
          @change="emit('binInboundWarehouseChange')"
        >
          <el-option
            v-for="w in activeWarehouses"
            :key="w.warehouseId"
            :label="w.warehouseName"
            :value="w.warehouseId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="货位" required>
        <el-select v-model="binInboundForm.binCode" filterable style="width: 100%">
          <el-option
            v-for="b in activeBinsFor(binInboundForm.warehouseId)"
            :key="b.binCode"
            :label="b.binCode"
            :value="b.binCode"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="商品" required>
        <el-select v-model="binInboundForm.skuId" filterable style="width: 100%">
          <el-option
            v-for="sku in skus"
            :key="sku.skuId"
            :label="`${sku.skuName || sku.skuId}`"
            :value="sku.skuId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="批次" required
        ><el-input v-model="binInboundForm.batchNo" maxlength="64"
      /></el-form-item>
      <el-form-item label="生产日期"
        ><input v-model="binInboundForm.productionDate" class="native-date" type="date"
      /></el-form-item>
      <el-form-item label="到期日" required
        ><input v-model="binInboundForm.expiryDate" class="native-date" type="date"
      /></el-form-item>
      <el-form-item label="数量" required>
        <el-input-number
          v-model="binInboundForm.quantity"
          :min="1"
          controls-position="right"
          style="width: 100%"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:binInboundDialog', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="emit('saveBinInbound')">确认入库</el-button>
    </template>
  </el-dialog>

  <el-dialog
    :model-value="binMoveDialog"
    title="货位移库"
    class="dialog-wide"
    destroy-on-close
    @update:model-value="emit('update:binMoveDialog', $event)"
  >
    <el-form label-width="auto">
      <el-form-item label="源货位" required>
        <el-select
          v-model="binMoveForm.fromBinId"
          filterable
          style="width: 100%"
          @change="emit('binMoveSourceChange')"
        >
          <el-option
            v-for="b in allBins"
            :key="b.binId"
            :label="binLabel(b)"
            :value="b.binId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="目标货位" required>
        <el-select v-model="binMoveForm.toBinId" filterable style="width: 100%">
          <el-option
            v-for="b in allBins"
            :key="b.binId"
            :label="binLabel(b)"
            :value="b.binId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="商品" required>
        <el-select v-model="binMoveForm.skuId" filterable style="width: 100%">
          <el-option
            v-for="s in sourceBinSkus"
            :key="s.skuId"
            :label="s.skuName"
            :value="s.skuId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="批次" required
        ><el-input v-model="binMoveForm.batchNo" maxlength="64"
      /></el-form-item>
      <el-form-item label="数量" required>
        <el-input-number
          v-model="binMoveForm.quantity"
          :min="1"
          :max="sourceBinMaxQty"
          controls-position="right"
          style="width: 100%"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:binMoveDialog', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="emit('saveBinMove')">确认移库</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { WarehouseBinRow } from '@/composables/warehouse/useWarehouseBins';

defineProps<{
  binDialog: boolean;
  binInboundDialog: boolean;
  binMoveDialog: boolean;
  saving: boolean;
  binForm: WarehouseBinRow;
  binInboundForm: WarehouseBinRow;
  binMoveForm: WarehouseBinRow;
  activeWarehouses: WarehouseBinRow[];
  skus: WarehouseBinRow[];
  allBins: WarehouseBinRow[];
  sourceBinSkus: WarehouseBinRow[];
  sourceBinMaxQty: number;
  activeBinsFor: (warehouseId: string) => WarehouseBinRow[];
  binLabel: (b: WarehouseBinRow) => string;
}>();

const emit = defineEmits<{
  'update:binDialog': [value: boolean];
  'update:binInboundDialog': [value: boolean];
  'update:binMoveDialog': [value: boolean];
  saveBin: [];
  saveBinInbound: [];
  saveBinMove: [];
  binInboundWarehouseChange: [];
  binMoveSourceChange: [];
}>();
</script>

<style scoped>
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
