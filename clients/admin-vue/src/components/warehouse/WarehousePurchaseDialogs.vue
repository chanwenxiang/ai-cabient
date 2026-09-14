<template>
  <el-dialog
    :model-value="purchaseDialog"
    title="新建采购单"
    class="dialog-wide"
    destroy-on-close
    @update:model-value="emit('update:purchaseDialog', $event)"
  >
    <el-form v-loading="dialogBootLoading" label-width="auto">
      <div class="form-grid">
        <el-form-item label="供应商" :class="{ 'field-invalid': purchaseFieldErrors.supplierId }">
          <el-select
            v-model="purchaseForm.supplierId"
            filterable
            style="width: 100%"
            @change="purchaseFieldErrors.supplierId = false"
          >
            <el-option
              v-for="item in activeSuppliers"
              :key="item.supplierId"
              :label="item.supplierName"
              :value="item.supplierId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="入库仓库">
          <el-select v-model="purchaseForm.warehouseId" style="width: 100%">
            <el-option
              v-for="item in activeWarehouses"
              :key="item.warehouseId"
              :label="item.warehouseName"
              :value="item.warehouseId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="外部单号">
          <el-input
            v-model="purchaseForm.refNo"
            placeholder="选填：供应商合同号 / ERP 单号，留空则自动生成"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="purchaseForm.notes" /></el-form-item>
      </div>
      <div class="section-title">
        <span>采购商品</span>
        <el-button link type="primary" @click="emit('addPurchaseLine')">添加一行</el-button>
      </div>
      <div v-for="(line, index) in purchaseForm.lines" :key="index" class="purchase-line-card">
        <div class="line-card-head">
          <strong>明细 {{ index + 1 }}</strong>
          <el-button
            link
            type="danger"
            :disabled="purchaseForm.lines.length === 1"
            @click="emit('removePurchaseLine', index)"
            >删除</el-button
          >
        </div>
        <div class="line-grid">
          <div
            class="line-field"
            :class="{ 'field-invalid': purchaseFieldErrors.lineErrors[index]?.skuId }"
          >
            <span>商品</span>
            <el-select
              v-model="line.skuId"
              filterable
              placeholder="选择商品"
              @change="emit('clearPurchaseLineError', index, 'skuId')"
            >
              <el-option
                v-for="sku in skus"
                :key="sku.skuId"
                :label="`${sku.skuName || sku.skuId}`"
                :value="sku.skuId"
              />
            </el-select>
          </div>
          <div
            class="line-field"
            :class="{ 'field-invalid': purchaseFieldErrors.lineErrors[index]?.batchNo }"
          >
            <span>批次号</span
            ><el-input
              v-model="line.batchNo"
              @input="emit('clearPurchaseLineError', index, 'batchNo')"
            />
          </div>
          <div class="line-field">
            <span>数量（件）</span
            ><el-input-number v-model="line.orderedQty" :min="1" controls-position="right" />
          </div>
          <div class="line-field">
            <span>单价（元）</span
            ><el-input-number
              v-model="line.unitCostYuan"
              :min="0.01"
              :step="0.01"
              :precision="2"
              controls-position="right"
            />
          </div>
          <label class="line-field"
            ><span>生产日期</span
            ><input v-model="line.productionDate" class="native-date" type="date"
          /></label>
          <label
            class="line-field"
            :class="{ 'field-invalid': purchaseFieldErrors.lineErrors[index]?.expiryDate }"
            ><span>到期日期</span
            ><input
              v-model="line.expiryDate"
              class="native-date"
              type="date"
              @change="emit('clearPurchaseLineError', index, 'expiryDate')"
          /></label>
        </div>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:purchaseDialog', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="dialogBootLoading"
        @click="emit('savePurchase')"
        >创建</el-button
      >
    </template>
  </el-dialog>

  <el-dialog
    :model-value="receiveDialog"
    title="采购收货"
    class="dialog-wide"
    destroy-on-close
    @update:model-value="emit('update:receiveDialog', $event)"
  >
    <el-form label-width="auto" style="margin-bottom: 8px">
      <el-form-item label="收货仓库">
        <el-select v-model="receiveForm.receiveWarehouseId" filterable style="width: 100%">
          <el-option
            v-for="w in warehouses"
            :key="w.warehouseId"
            :label="`${w.warehouseName || w.warehouseId}（${w.warehouseId}）`"
            :value="w.warehouseId"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <div class="table-scroll">
      <el-table :data="receiveForm.lines" class="receive-table">
        <el-table-column
          label="商品"
          min-width="160"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">
            <div>{{ skuName(row.skuId) }}</div>
            <small class="muted">{{ row.skuId }}</small>
          </template>
        </el-table-column>
        <el-table-column
          prop="batchNo"
          label="批次"
          min-width="120"
          class-name="col-text"
          label-class-name="col-text"
        />
        <el-table-column
          prop="expiryDate"
          label="到期日"
          width="110"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ row.expiryDate || '暂无' }}</template>
        </el-table-column>
        <el-table-column
          prop="orderedQty"
          label="采购数"
          width="90"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        />
        <el-table-column
          label="待收"
          width="80"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            {{ Math.max(0, Number(row.orderedQty || 0) - Number(row.receivedQty || 0)) }}
          </template>
        </el-table-column>
        <el-table-column
          label="累计收货"
          width="150"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <el-input-number
              v-model="row.receivedQty"
              :min="row.minReceived"
              :max="row.orderedQty"
              controls-position="right"
            />
          </template>
        </el-table-column>
      </el-table>
    </div>
    <el-input
      v-model="receiveForm.notes"
      type="textarea"
      placeholder="收货备注"
      style="margin-top: 12px"
    />
    <template #footer>
      <el-button @click="emit('update:receiveDialog', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="emit('receivePurchase')">确认收货</el-button>
    </template>
  </el-dialog>

  <el-dialog
    :model-value="returnDialog"
    title="采购退货"
    class="dialog-wide"
    destroy-on-close
    @update:model-value="emit('update:returnDialog', $event)"
  >
    <div v-loading="dialogBootLoading">
      <el-form label-width="auto">
        <el-form-item label="采购单" required>
          <el-select
            v-model="returnForm.purchaseOrderId"
            filterable
            placeholder="选择已收货采购单"
            style="width: 100%"
            @change="emit('returnPoChange', $event)"
          >
            <el-option
              v-for="po in returnablePurchaseOrders"
              :key="po.purchaseOrderId"
              :label="`${po.purchaseOrderId} · ${supplierName(po.supplierId)} · ${warehouseName(po.warehouseId)}`"
              :value="po.purchaseOrderId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="returnForm.notes" type="textarea" placeholder="退货备注" />
        </el-form-item>
      </el-form>
      <div class="table-scroll">
        <el-table :data="returnForm.lines" class="receive-table">
          <el-table-column
            label="商品"
            min-width="160"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">
              <div>{{ skuName(row.skuId) }}</div>
              <small class="muted">{{ row.skuId }}</small>
            </template>
          </el-table-column>
          <el-table-column
            prop="batchNo"
            label="批次"
            min-width="120"
            class-name="col-text"
            label-class-name="col-text"
          />
          <el-table-column
            prop="expiryDate"
            label="到期日"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.expiryDate || '暂无' }}</template>
          </el-table-column>
          <el-table-column
            prop="receivedQty"
            label="已收"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            prop="returnedQty"
            label="已退"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            label="可退"
            width="72"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.maxQty ?? '暂无' }}</template>
          </el-table-column>
          <el-table-column
            label="本次退货"
            width="150"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-input-number
                v-model="row.quantity"
                :min="0"
                :max="row.maxQty"
                controls-position="right"
              />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    <template #footer>
      <el-button @click="emit('update:returnDialog', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="dialogBootLoading"
        @click="emit('returnPurchase')"
        >确认退货</el-button
      >
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { WarehousePurchaseRow } from '@/composables/warehouse/useWarehousePurchaseOrders';

defineProps<{
  purchaseDialog: boolean;
  receiveDialog: boolean;
  returnDialog: boolean;
  dialogBootLoading: boolean;
  saving: boolean;
  activeSuppliers: WarehousePurchaseRow[];
  activeWarehouses: WarehousePurchaseRow[];
  warehouses: WarehousePurchaseRow[];
  skus: WarehousePurchaseRow[];
  returnablePurchaseOrders: WarehousePurchaseRow[];
  supplierName: (id: string) => string;
  warehouseName: (id: string) => string;
  skuName: (id?: string) => string;
}>();


const purchaseForm = defineModel<WarehousePurchaseRow>('purchaseForm', { required: true });
const purchaseFieldErrors = defineModel<{
    supplierId: boolean;
    lineErrors: Array<{ skuId?: boolean; batchNo?: boolean; expiryDate?: boolean }>;
  }>('purchaseFieldErrors', { required: true });
const receiveForm = defineModel<WarehousePurchaseRow>('receiveForm', { required: true });
const returnForm = defineModel<WarehousePurchaseRow>('returnForm', { required: true });
const emit = defineEmits<{
  'update:purchaseDialog': [value: boolean];
  'update:receiveDialog': [value: boolean];
  'update:returnDialog': [value: boolean];
  addPurchaseLine: [];
  removePurchaseLine: [index: number];
  clearPurchaseLineError: [index: number, field: 'skuId' | 'batchNo' | 'expiryDate'];
  savePurchase: [];
  receivePurchase: [];
  returnPurchase: [];
  returnPoChange: [purchaseOrderId: number | string | null];
}>();
</script>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.section-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 8px 0 12px;
  font-weight: 700;
}
.purchase-line-card {
  padding: 16px;
  margin-bottom: 14px;
  border: 1px solid var(--layout-border);
  border-radius: 12px;
  background: var(--el-fill-color-light);
}
.line-card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.line-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.line-field {
  display: grid;
  gap: 6px;
  font-size: var(--admin-font-size-table);
  color: var(--layout-muted);
}
.line-field :deep(.el-select),
.line-field :deep(.el-input),
.line-field :deep(.el-input-number) {
  width: 100%;
}
.field-invalid :deep(.el-input__wrapper),
.field-invalid :deep(.el-select__wrapper),
.field-invalid .native-date {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset !important;
  border-color: var(--el-color-danger);
}
.field-invalid > span:first-child,
.field-invalid :deep(.el-form-item__label) {
  color: var(--el-color-danger);
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
.receive-table {
  margin-bottom: 12px;
}
.muted {
  color: var(--layout-muted);
}

@media (max-width: 900px) {
  .form-grid,
  .line-grid {
    grid-template-columns: 1fr;
  }
}
</style>
