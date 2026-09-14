<template>
  <el-dialog
    :model-value="stocktakeDialog"
    title="新建盘点"
    destroy-on-close
    @update:model-value="emit('update:stocktakeDialog', $event)"
  >
    <el-form label-width="auto">
      <el-form-item label="仓库" required>
        <el-select v-model="stocktakeForm.warehouseId" filterable style="width: 100%">
          <el-option
            v-for="w in activeWarehouses"
            :key="w.warehouseId"
            :label="w.warehouseName"
            :value="w.warehouseId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="盘点模式">
        <el-radio-group v-model="stocktakeForm.mode">
          <el-radio value="OPEN">明盘（预填账面数）</el-radio>
          <el-radio value="BLIND">盲盘（实盘留空）</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="备注"
        ><el-input v-model="stocktakeForm.notes" maxlength="200"
      /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:stocktakeDialog', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="emit('saveStocktake')">创建</el-button>
    </template>
  </el-dialog>

  <el-dialog
    :model-value="stocktakeDetailDialog"
    :title="`盘点单 ${stocktakeDetail.stocktakeNo || ''}`"
    class="dialog-wide"
    destroy-on-close
    @update:model-value="emit('update:stocktakeDetailDialog', $event)"
  >
    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent>
      <el-form-item label="仓库">{{ stocktakeDetail.warehouseName }}</el-form-item>
      <el-form-item label="模式">{{ stocktakeModeText(stocktakeDetail.mode) }}</el-form-item>
      <el-form-item label="状态">
        <el-tag :type="stocktakeStatusType(stocktakeDetail.status)" size="small">
          {{ stocktakeStatusText(stocktakeDetail.status) }}
        </el-tag>
      </el-form-item>
      <el-form-item label="账面件数">{{ stocktakeDetail.bookQty ?? 0 }}</el-form-item>
      <el-form-item label="实盘件数">{{ stocktakeDetail.countedQty ?? 0 }}</el-form-item>
      <el-form-item label="差异件数">
        <b>{{ stocktakeDetail.diffQty ?? 0 }}</b>
      </el-form-item>
      <el-form-item label="差异行数">{{ stocktakeDetail.diffLineCount ?? 0 }}</el-form-item>
    </el-form>
    <el-table
      :data="stocktakeDetail.lines || []"
      size="small"
      border
      max-height="420"
      class="line-table"
    >
      <el-table-column label="商品" min-width="170">
        <template #default="{ row }">{{ row.skuName }}</template>
      </el-table-column>
      <el-table-column prop="batchNo" label="批次" min-width="130" />
      <el-table-column prop="productionDate" label="生产日期" min-width="110" />
      <el-table-column prop="expiryDate" label="到期日" min-width="110" />
      <el-table-column
        prop="bookQty"
        label="账面"
        min-width="70"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
      <el-table-column
        label="实盘"
        min-width="130"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">
          <el-input-number
            v-if="['DRAFT', 'IN_PROGRESS'].includes(stocktakeDetail.status)"
            v-model="row.countedQty"
            :min="0"
            size="small"
            controls-position="right"
          />
          <span v-else>{{ row.countedQty ?? '暂无' }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="差异"
        min-width="80"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">{{ row.diffQty }}</template>
      </el-table-column>
      <el-table-column
        label="状态"
        min-width="100"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">
          <el-tag :type="stocktakeLineStatusType(row.status)" size="small">
            {{ stocktakeLineStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="emit('update:stocktakeDetailDialog', false)">关闭</el-button>
      <template v-if="['DRAFT', 'IN_PROGRESS'].includes(stocktakeDetail.status)">
        <el-button type="primary" plain :loading="scanningPhoto" @click="triggerStocktakePhotoScan"
          >拍照识别</el-button
        >
        <el-button type="primary" :loading="saving" @click="emit('saveStocktakeLines')"
          >保存实盘</el-button
        >
        <el-button type="success" :loading="saving" @click="emit('completeStocktake')"
          >完成盘点</el-button
        >
        <el-button
          v-if="stocktakeDetail.status === 'DRAFT'"
          :loading="saving"
          @click="emit('cancelStocktake')"
          >取消盘点</el-button
        >
      </template>
      <el-button
        v-if="stocktakeDetail.status === 'COMPLETED' && (stocktakeDetail.diffLineCount ?? 0) > 0"
        type="warning"
        :loading="saving"
        @click="emit('adjustStocktake')"
        >复盘调整</el-button
      >
    </template>
    <input
      ref="stocktakePhotoInput"
      type="file"
      accept="image/*"
      class="hidden-input"
      @change="emit('stocktakePhoto', $event)"
    />
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import type { WarehouseStocktakeRow } from '@/composables/warehouse/useWarehouseStocktakes';

defineProps<{
  stocktakeDialog: boolean;
  stocktakeDetailDialog: boolean;
  saving: boolean;
  scanningPhoto: boolean;
  stocktakeDetail: WarehouseStocktakeRow;
  activeWarehouses: WarehouseStocktakeRow[];
  stocktakeModeText: (mode: string) => string;
  stocktakeStatusText: (code: string) => string;
  stocktakeStatusType: (code: string) => string;
  stocktakeLineStatusText: (code: string) => string;
  stocktakeLineStatusType: (code: string) => string;
}>();

const stocktakeForm = defineModel<WarehouseStocktakeRow>('stocktakeForm', { required: true });
const emit = defineEmits<{
  'update:stocktakeDialog': [value: boolean];
  'update:stocktakeDetailDialog': [value: boolean];
  saveStocktake: [];
  saveStocktakeLines: [];
  completeStocktake: [];
  cancelStocktake: [];
  adjustStocktake: [];
  stocktakePhoto: [event: Event];
}>();

const stocktakePhotoInput = ref<HTMLInputElement | null>(null);

function triggerStocktakePhotoScan() {
  stocktakePhotoInput.value?.click();
}
</script>

<style scoped>
.hidden-input {
  display: none;
}
.filter-bar--compact {
  margin-bottom: 12px;
}
.line-table {
  width: 100%;
}
</style>
