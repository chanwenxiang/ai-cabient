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
          <el-select
            v-model="purchaseForm.warehouseId"
            style="width: 100%"
            placeholder="仅已指定负责人的分仓"
          >
            <el-option
              v-for="item in activeWarehouses"
              :key="item.warehouseId"
              :label="item.warehouseName"
              :value="item.warehouseId"
            />
          </el-select>
          <div class="form-hint">没有可选仓时，先到仓库概览绑定负责人。</div>
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
            <span>批次号（选填，收货时录入）</span
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
            ><input
              v-model="line.productionDate"
              class="native-date"
              type="date"
              @change="onProductionDateChange(line)"
          /></label>
          <label
            class="line-field"
            :class="{ 'field-invalid': purchaseFieldErrors.lineErrors[index]?.expiryDate }"
            ><span>到期日期（按保质期自动估算，可修改）</span
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
          label="批次（收货必填）"
          min-width="150"
          class-name="col-text"
          label-class-name="col-text"
        >
          <template #default="{ row }">
            <el-input
              v-model="row.batchNo"
              size="small"
              :placeholder="row.batchNo ? '' : '下单未填，收货必填'"
            />
          </template>
        </el-table-column>
        <el-table-column
          prop="expiryDate"
          label="到期日（收货必填）"
          width="160"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <input v-model="row.expiryDate" class="native-date" type="date" />
          </template>
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
      <el-button type="primary" :loading="saving" @click="emit('receivePurchase')"
        >确认收货</el-button
      >
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
        <!--
          V318：退货原因分类 / 责任方 / 残次品。
          🔴 三者都**允许留空** —— 「未分类/未认定」是可治理的状态，
             强制填写会让人为过校验随便选一个，假分类会让「退得最多的是谁」失去意义。
        -->
        <el-form-item label="退货原因">
          <el-select
            v-model="returnForm.reasonCategory"
            clearable
            placeholder="未分类（待补）"
            style="width: 100%"
            data-testid="return-reason-category"
          >
            <el-option
              v-for="(label, value) in RETURN_CATEGORY_LABELS"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="责任方">
          <el-select
            v-model="returnForm.responsibleParty"
            clearable
            placeholder="尚未认定"
            style="width: 100%"
            data-testid="return-responsible-party"
          >
            <el-option
              v-for="(label, value) in RETURN_PARTY_LABELS"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="是否残次品">
          <el-select
            v-model="returnForm.defective"
            clearable
            placeholder="未标记"
            style="width: 100%"
            data-testid="return-defective"
          >
            <el-option :value="true" label="是（商品本身有问题，需供应商理赔）" />
            <el-option :value="false" label="否（我方不要，如滞销/买多）" />
          </el-select>
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

const props = defineProps<{
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

/**
 * CB-026：选了生产日期后按商品保质期自动预填到期日期（可手工修改）。
 * 生产日期是批次属性，归采购入库/库存批次管，商品档案不放（每次到货日期不同）。
 * 口径：到期日 = 生产日期 + 保质期天数（与手工录入惯例一致，估算值允许改）。
 */
function onProductionDateChange(line: {
  skuId: string;
  productionDate: string;
  expiryDate: string;
}) {
  if (!line.productionDate) return;
  const days = props.skus.find((s) => s.skuId === line.skuId)?.shelfLifeDays;
  if (!days || days <= 0) return;
  const d = new Date(line.productionDate);
  d.setDate(d.getDate() + days);
  line.expiryDate = d.toISOString().slice(0, 10);
}

/**
 * V318：退货原因分类标签（枚举语义与后端 `WriteOffReasonCategory` **刻意对齐**，
 * 这样「报废原因」在报损链路与退货链路上可合并统计）。
 */
const RETURN_CATEGORY_LABELS: Record<string, string> = {
  EXPIRED: '过期/临期',
  DAMAGED: '外力损坏',
  LOST: '运输丢失',
  SHORT_SUPPLIED: '错发/少发（供方责任）',
  DAMAGED_IN_TRANSIT: '运输中破损',
  OTHER: '其他'
};

/** V318：责任方。区分「尚未认定」与「认定无责任方」—— 追责只能对前者发起。 */
const RETURN_PARTY_LABELS: Record<string, string> = {
  SUPPLIER: '供应商',
  LOGISTICS: '物流',
  MERCHANT: '商户',
  NONE: '无责任方（滞销/买多）',
  UNDETERMINED: '待判定'
};
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
.form-hint {
  margin-top: 6px;
  font-size: var(--admin-font-size-caption, 12px);
  color: var(--layout-muted);
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
