<template>
  <el-dialog
    v-model="warehouseDialog"
    :title="warehouseForm.editing ? '编辑仓库' : '新增仓库'"
    destroy-on-close
  >
    <el-form label-width="auto">
      <el-form-item label="仓库 ID" required>
        <el-input
          v-model="warehouseForm.warehouseId"
          :disabled="warehouseForm.editing"
          placeholder="如 WH-SH-001"
        />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="warehouseForm.warehouseName" maxlength="64" />
      </el-form-item>
      <el-form-item label="地址">
        <!-- 用省/市/区级联而不是自由文本：地址要能对齐行政区，后续才能按区县归集与解析坐标 -->
        <AddressPicker v-model="warehouseForm.address" :maxlength="255" />
      </el-form-item>
      <el-form-item label="状态">
        <el-radio-group v-model="warehouseForm.status">
          <el-radio value="ACTIVE">正常</el-radio>
          <el-radio value="INACTIVE">停用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button data-testid="warehouse-dialog-cancel" @click.stop="warehouseDialog = false"
        >取消</el-button
      >
      <el-button type="primary" :loading="saving" @click="emit('saveWarehouse')">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="supplierDialog"
    :title="supplierForm.editing ? '编辑供应商' : '新增供应商'"
    destroy-on-close
  >
    <el-form label-width="auto">
      <el-form-item label="供应商 ID"
        ><el-input v-model="supplierForm.supplierId" :disabled="supplierForm.editing"
      /></el-form-item>
      <el-form-item label="供应商名称"
        ><el-input v-model="supplierForm.supplierName"
      /></el-form-item>
      <el-form-item label="联系人"><el-input v-model="supplierForm.contactName" /></el-form-item>
      <el-form-item label="联系电话"><el-input v-model="supplierForm.contactPhone" /></el-form-item>
      <el-form-item label="账期(天)"
        ><el-input-number
          v-model="supplierForm.paymentTermsDays"
          :min="0"
          :max="365"
          style="width: 100%"
      /></el-form-item>
      <el-form-item label="信用额度(元)"
        ><el-input-number
          v-model="supplierForm.creditLimitYuan"
          :min="0"
          :step="100"
          :precision="2"
          style="width: 100%"
      /></el-form-item>
      <el-form-item label="状态">
        <el-select v-model="supplierForm.status" style="width: 100%">
          <el-option
            v-for="item in dictOptions('supplier_status')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button data-testid="supplier-dialog-cancel" @click.stop="supplierDialog = false"
        >取消</el-button
      >
      <el-button type="primary" :loading="saving" @click="emit('saveSupplier')">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="paymentDialog" title="登记付款" destroy-on-close>
    <el-form label-width="auto">
      <el-form-item label="供应商">{{ payTarget.supplierName }}</el-form-item>
      <el-form-item label="关联采购单">
        <span class="cell-id">{{ payTarget.purchaseOrderId }}</span>
      </el-form-item>
      <el-form-item label="未付余额">¥{{ money(payTarget.balanceCents) }}</el-form-item>
      <el-form-item label="付款金额(元)" required>
        <el-input-number
          v-model="paymentForm.amountYuan"
          :min="0.01"
          :max="payMaxYuan"
          :precision="2"
          :step="100"
          controls-position="right"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="备注"
        ><el-input v-model="paymentForm.notes" maxlength="200"
      /></el-form-item>
    </el-form>
    <template #footer>
      <el-button data-testid="payment-dialog-cancel" @click.stop="paymentDialog = false"
        >取消</el-button
      >
      <el-button type="primary" :loading="saving" @click="emit('savePayment')">确认付款</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="inboundDialog" title="其他入库" class="dialog-wide" destroy-on-close>
    <el-form v-loading="dialogBootLoading" label-width="auto">
      <div class="form-grid">
        <el-form-item label="仓库" required>
          <el-select v-model="inboundForm.warehouseId" style="width: 100%">
            <el-option
              v-for="w in activeWarehouses"
              :key="w.warehouseId"
              :label="w.warehouseName"
              :value="w.warehouseId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="参考单号"><el-input v-model="inboundForm.refNo" /></el-form-item>
      </div>
      <el-form-item label="备注"><el-input v-model="inboundForm.notes" /></el-form-item>
      <div class="section-title">
        <span>入库明细</span>
        <el-button link type="primary" @click="emit('addInboundLine')">添加一行</el-button>
      </div>
      <div v-for="(line, index) in inboundForm.lines" :key="index" class="purchase-line-card">
        <div class="line-card-head">
          <strong>明细 {{ index + 1 }}</strong>
          <el-button
            link
            type="danger"
            :disabled="inboundForm.lines.length === 1"
            @click="emit('removeInboundLine', index)"
            >删除</el-button
          >
        </div>
        <div class="line-grid">
          <div class="line-field">
            <span>商品</span>
            <el-select v-model="line.skuId" filterable>
              <el-option
                v-for="sku in skus"
                :key="sku.skuId"
                :label="sku.skuName || sku.skuId"
                :value="sku.skuId"
              />
            </el-select>
          </div>
          <div class="line-field"><span>批次</span><el-input v-model="line.batchNo" /></div>
          <div class="line-field">
            <span>数量</span
            ><el-input-number v-model="line.quantity" :min="1" controls-position="right" />
          </div>
          <label class="line-field"
            ><span>生产日期</span
            ><input v-model="line.productionDate" class="native-date" type="date"
          /></label>
          <label class="line-field"
            ><span>到期日期</span><input v-model="line.expiryDate" class="native-date" type="date"
          /></label>
        </div>
      </div>
    </el-form>
    <template #footer>
      <el-button data-testid="inbound-dialog-cancel" @click.stop="inboundDialog = false"
        >取消</el-button
      >
      <el-button
        type="primary"
        :loading="saving"
        :disabled="dialogBootLoading"
        @click="emit('saveInbound')"
        >确认入库</el-button
      >
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { dictOptions } from '@aicabinet/shared-dict';
import AddressPicker from '@/components/AddressPicker.vue';
import type { WarehouseEntityRow } from '@/composables/warehouse/useWarehouseEntityDialogs';

/** 可见性走 defineModel，避免 props+emit 在 footer「取消」上不同步关闭 */
const warehouseDialog = defineModel<boolean>('warehouseDialog', { required: true });
const supplierDialog = defineModel<boolean>('supplierDialog', { required: true });
const paymentDialog = defineModel<boolean>('paymentDialog', { required: true });
const inboundDialog = defineModel<boolean>('inboundDialog', { required: true });

defineProps<{
  saving: boolean;
  dialogBootLoading: boolean;
  payTarget: WarehouseEntityRow;
  payMaxYuan: number;
  activeWarehouses: WarehouseEntityRow[];
  skus: WarehouseEntityRow[];
}>();

const warehouseForm = defineModel<WarehouseEntityRow>('warehouseForm', { required: true });
const supplierForm = defineModel<WarehouseEntityRow>('supplierForm', { required: true });
const paymentForm = defineModel<WarehouseEntityRow>('paymentForm', { required: true });
const inboundForm = defineModel<WarehouseEntityRow>('inboundForm', { required: true });
const emit = defineEmits<{
  saveWarehouse: [];
  saveSupplier: [];
  savePayment: [];
  saveInbound: [];
  addInboundLine: [];
  removeInboundLine: [index: number];
}>();

function money(cents: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}
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

@media (max-width: 900px) {
  .form-grid,
  .line-grid {
    grid-template-columns: 1fr;
  }
}
</style>
