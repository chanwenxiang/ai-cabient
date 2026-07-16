<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">仓库</span>
        <div class="actions">
          <el-button
            v-if="canEdit && tab === 'warehouses'"
            type="primary"
            @click="openWarehouse()"
          >新增仓库</el-button>
          <el-button
            v-if="canEdit && tab === 'suppliers'"
            type="primary"
            @click="openSupplier()"
          >新增供应商</el-button>
          <el-button
            v-if="canEdit && tab === 'purchase'"
            type="primary"
            @click="openPurchase()"
          >新建采购单</el-button>
          <el-button
            v-if="canEdit && (tab === 'inventory' || tab === 'movements')"
            type="primary"
            @click="openInbound()"
          >其他入库</el-button>
          <el-button @click="onExport">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="reloadCurrent">刷新</el-button>
        </div>
      </div>
    </template>

    <div v-if="showFilterBar" class="filter-bar">
      <el-select
        v-if="tab === 'inventory' || tab === 'movements' || tab === 'outbounds' || tab === 'purchase'"
        v-model="filterWarehouseId"
        clearable
        placeholder="全部仓库"
        style="width: 220px"
        @change="onWarehouseFilter"
      >
        <el-option v-for="w in warehouses" :key="w.warehouseId" :label="w.warehouseName" :value="w.warehouseId" />
      </el-select>
      <el-input
        v-if="tab === 'suppliers' || tab === 'purchase'"
        v-model="keyword"
        clearable
        placeholder="搜索关键词"
        style="width: 200px"
      />
    </div>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="仓库概览" name="warehouses">
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 900px">
            <el-table v-loading="loading" :data="warehouses" stripe border>
          <el-table-column prop="warehouseName" label="仓库名称" min-width="180">
            <template #default="{ row }">
              <div class="master-data-cell"><strong>{{ row.warehouseName || row.warehouseId }}</strong><small>{{ row.warehouseId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column prop="address" label="地址" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">{{ dictLabel('warehouse_status', row.status || 'ACTIVE') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="88" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                :actions="[{ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' }]"
                @action="() => openWarehouse(row)"
              />
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无仓库" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="供应商" name="suppliers">
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 900px">
            <el-table v-loading="loading" :data="filteredSuppliers" stripe border>
          <el-table-column prop="supplierName" label="供应商" min-width="200">
            <template #default="{ row }">
              <div class="master-data-cell"><strong>{{ row.supplierName || row.supplierId }}</strong><small>{{ row.supplierId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column prop="contactName" label="联系人" width="120" />
          <el-table-column prop="contactPhone" label="联系电话" width="150" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">{{ dictLabel('supplier_status', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="88" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                :actions="[{ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' }]"
                @action="() => openSupplier(row)"
              />
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无供应商" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="采购单" name="purchase">
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 1100px">
            <el-table v-loading="loading" :data="filteredPurchaseOrders" stripe border>
          <el-table-column type="expand">
            <template #default="{ row }">
              <el-table :data="row.lines || []" size="small" class="line-table">
                <el-table-column label="商品" min-width="180">
                  <template #default="scope">
                    <div class="master-data-cell"><strong>{{ skuName(scope.row.skuId) }}</strong><small>{{ scope.row.skuId }}</small></div>
                  </template>
                </el-table-column>
                <el-table-column prop="batchNo" label="批次" min-width="140" />
                <el-table-column prop="orderedQty" label="采购数" width="90" />
                <el-table-column prop="receivedQty" label="已收数" width="90" />
                <el-table-column label="成本" width="100">
                  <template #default="scope">¥{{ money(scope.row.unitCostCents) }}</template>
                </el-table-column>
                <el-table-column prop="expiryDate" label="到期日期" width="130" />
              </el-table>
            </template>
          </el-table-column>
          <el-table-column prop="purchaseOrderId" label="采购单" width="100" />
          <el-table-column prop="refNo" label="外部单号" min-width="140" show-overflow-tooltip />
          <el-table-column label="供应商" min-width="160">
            <template #default="{ row }">
              <div class="master-data-cell"><strong>{{ supplierName(row.supplierId) }}</strong><small>{{ row.supplierId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="入库仓库" min-width="160">
            <template #default="{ row }">
              <div class="master-data-cell"><strong>{{ warehouseName(row.warehouseId) }}</strong><small>{{ row.warehouseId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">{{ dictLabel('purchase_order_status', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="100" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                v-if="['CREATED', 'PARTIAL_RECEIVED'].includes(row.status)"
                :actions="[{ key: 'receive', label: '采购收货', icon: Box, type: 'primary' }]"
                @action="() => openReceive(row)"
              />
              <span v-else class="muted">已完成</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无采购单" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="出库单" name="outbounds">
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 1100px">
            <el-table v-loading="loading" :data="filteredOutbounds" stripe border>
          <el-table-column type="expand">
            <template #default="{ row }">
              <el-table :data="row.lines || []" size="small" class="line-table">
                <el-table-column label="目标柜机" min-width="180">
                  <template #default="scope">
                    <div class="master-data-cell"><strong>{{ deviceName(scope.row.deviceId) }}</strong><small>{{ scope.row.deviceId }}</small></div>
                  </template>
                </el-table-column>
                <el-table-column label="商品" min-width="180">
                  <template #default="scope">
                    <div class="master-data-cell"><strong>{{ skuName(scope.row.skuId) }}</strong><small>{{ scope.row.skuId }}</small></div>
                  </template>
                </el-table-column>
                <el-table-column prop="batchNo" label="批次" min-width="140" />
                <el-table-column prop="quantity" label="数量" width="90" />
                <el-table-column label="交接状态" width="120">
                  <template #default="scope">{{ dictLabel('handover_status', scope.row.handoverStatus || 'PENDING') }}</template>
                </el-table-column>
              </el-table>
            </template>
          </el-table-column>
          <el-table-column prop="outboundId" label="出库单" width="100" />
          <el-table-column prop="routeId" label="路线" width="90" />
          <el-table-column label="出库仓库" min-width="160">
            <template #default="{ row }">
              <div class="master-data-cell"><strong>{{ warehouseName(row.warehouseId) }}</strong><small>{{ row.warehouseId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">{{ dictLabel('warehouse_outbound_status', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="100" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                v-if="outboundActions(row).length"
                :actions="outboundActions(row)"
                :max-primary="1"
                @action="(k) => changeOutbound(row, String(k) as 'pick' | 'ship')"
              />
              <span v-else-if="!(row.lines?.length) && row.status !== 'SHIPPED'" class="muted">无明细</span>
              <span v-else-if="row.status === 'SHIPPED'" class="muted">已发运</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无出库单" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="在途" name="transit">
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 980px">
            <el-table v-loading="loading" :data="inTransit" stripe border>
          <el-table-column prop="outboundId" label="出库单" width="100" />
          <el-table-column label="目标设备" min-width="180">
            <template #default="{ row }">
              <div class="master-data-cell"><strong>{{ deviceName(row.deviceId) }}</strong><small>{{ row.deviceId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="商品" min-width="180">
            <template #default="{ row }">
              <div class="master-data-cell"><strong>{{ skuName(row.skuId) }}</strong><small>{{ row.skuId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column prop="batchNo" label="批次" min-width="140" />
          <el-table-column prop="quantity" label="数量" width="90" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">{{ dictLabel('in_transit_status', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发运时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty><el-empty description="暂无在途" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="批次库存" name="inventory">
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 900px">
            <el-table v-loading="loading" :data="inventory" stripe border>
          <el-table-column label="仓库" min-width="140">
            <template #default="{ row }">{{ warehouseName(row.warehouseId) }}</template>
          </el-table-column>
          <el-table-column label="商品" min-width="180">
            <template #default="{ row }">
              <div class="master-data-cell"><strong>{{ skuName(row.skuId) }}</strong><small>{{ row.skuId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column prop="batchNo" label="批次" min-width="150" />
          <el-table-column prop="productionDate" label="生产日期" width="120" />
          <el-table-column prop="expiryDate" label="到期日期" width="120" />
          <el-table-column prop="quantity" label="库存" width="90" />
          <el-table-column label="效期" width="100">
            <template #default="{ row }">
              <el-tag :type="expiryType(row.expiryDate)" size="small">{{ expiryText(row.expiryDate) }}</el-tag>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无库存" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="库存流水" name="movements">
        <p class="muted tip">仅显示最近 100 条</p>
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 1060px">
            <el-table v-loading="loading" :data="movements" stripe border>
          <el-table-column prop="movementId" label="流水" width="90" />
          <el-table-column label="类型" min-width="130">
            <template #default="{ row }">{{ dictLabel('warehouse_movement_type', row.movementType) }}</template>
          </el-table-column>
          <el-table-column label="商品" min-width="180">
            <template #default="{ row }">
              <div class="master-data-cell"><strong>{{ skuName(row.skuId) }}</strong><small>{{ row.skuId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column prop="batchNo" label="批次" min-width="140" />
          <el-table-column prop="deltaQty" label="变动" width="90">
            <template #default="{ row }">
              <span :class="row.deltaQty >= 0 ? 'positive' : 'negative'">{{ row.deltaQty > 0 ? '+' : '' }}{{ row.deltaQty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="关联业务" width="140">
            <template #default="{ row }">{{ dictLabel('business_reference_type', row.refType) }}</template>
          </el-table-column>
          <el-table-column prop="refId" label="关联单号" width="120" />
          <el-table-column label="时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty><el-empty description="暂无流水" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="warehouseDialog" :title="warehouseForm.editing ? '编辑仓库' : '新增仓库'" width="480px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="仓库 ID" required>
          <el-input v-model="warehouseForm.warehouseId" :disabled="warehouseForm.editing" placeholder="如 WH-SH-001" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="warehouseForm.warehouseName" maxlength="64" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="warehouseForm.address" maxlength="255" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="warehouseForm.status">
            <el-radio value="ACTIVE">正常</el-radio>
            <el-radio value="INACTIVE">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="warehouseDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveWarehouse">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="supplierDialog" :title="supplierForm.editing ? '编辑供应商' : '新增供应商'" width="520px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="供应商 ID"><el-input v-model="supplierForm.supplierId" :disabled="supplierForm.editing" /></el-form-item>
        <el-form-item label="供应商名称"><el-input v-model="supplierForm.supplierName" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="supplierForm.contactName" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="supplierForm.contactPhone" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="supplierForm.status" style="width: 100%">
            <el-option v-for="item in dictOptions('supplier_status')" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="supplierDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSupplier">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="purchaseDialog" title="新建采购单" width="760px" destroy-on-close>
      <el-form label-width="90px">
        <div class="form-grid">
          <el-form-item label="供应商">
            <el-select v-model="purchaseForm.supplierId" filterable style="width: 100%">
              <el-option v-for="item in activeSuppliers" :key="item.supplierId" :label="item.supplierName" :value="item.supplierId" />
            </el-select>
          </el-form-item>
          <el-form-item label="入库仓库">
            <el-select v-model="purchaseForm.warehouseId" style="width: 100%">
              <el-option v-for="item in activeWarehouses" :key="item.warehouseId" :label="item.warehouseName" :value="item.warehouseId" />
            </el-select>
          </el-form-item>
          <el-form-item label="外部单号"><el-input v-model="purchaseForm.refNo" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="purchaseForm.notes" /></el-form-item>
        </div>
        <div class="section-title">
          <span>采购商品</span>
          <el-button link type="primary" @click="addPurchaseLine">添加一行</el-button>
        </div>
        <div v-for="(line, index) in purchaseForm.lines" :key="index" class="purchase-line-card">
          <div class="line-card-head">
            <strong>明细 {{ index + 1 }}</strong>
            <el-button link type="danger" :disabled="purchaseForm.lines.length === 1" @click="purchaseForm.lines.splice(index, 1)">删除</el-button>
          </div>
          <div class="line-grid">
            <label class="line-field">
              <span>商品</span>
              <el-select v-model="line.skuId" filterable placeholder="选择商品">
                <el-option v-for="sku in skus" :key="sku.skuId" :label="`${sku.skuName || sku.skuId}`" :value="sku.skuId" />
              </el-select>
            </label>
            <label class="line-field"><span>批次号</span><el-input v-model="line.batchNo" /></label>
            <label class="line-field"><span>数量（件）</span><el-input-number v-model="line.orderedQty" :min="1" controls-position="right" /></label>
            <label class="line-field"><span>单价（元）</span><el-input-number v-model="line.unitCostYuan" :min="0.01" :step="0.01" :precision="2" controls-position="right" /></label>
            <label class="line-field"><span>生产日期</span><input v-model="line.productionDate" class="native-date" type="date" /></label>
            <label class="line-field"><span>到期日期</span><input v-model="line.expiryDate" class="native-date" type="date" /></label>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="purchaseDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePurchase">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="receiveDialog" title="采购收货" width="700px" destroy-on-close>
      <el-table :data="receiveForm.lines" class="receive-table">
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">
            <div class="master-data-cell"><strong>{{ skuName(row.skuId) }}</strong><small>{{ row.skuId }}</small></div>
          </template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批次" min-width="140" />
        <el-table-column prop="orderedQty" label="采购数" width="90" />
        <el-table-column label="累计收货" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.receivedQty" :min="row.minReceived" :max="row.orderedQty" controls-position="right" />
          </template>
        </el-table-column>
      </el-table>
      <el-input v-model="receiveForm.notes" type="textarea" placeholder="收货备注" style="margin-top: 12px" />
      <template #footer>
        <el-button @click="receiveDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveReceive">确认收货</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="inboundDialog" title="其他入库" width="720px" destroy-on-close>
      <el-form label-width="88px">
        <div class="form-grid">
          <el-form-item label="仓库" required>
            <el-select v-model="inboundForm.warehouseId" style="width: 100%">
              <el-option v-for="w in activeWarehouses" :key="w.warehouseId" :label="w.warehouseName" :value="w.warehouseId" />
            </el-select>
          </el-form-item>
          <el-form-item label="参考单号"><el-input v-model="inboundForm.refNo" /></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="inboundForm.notes" /></el-form-item>
        <div class="section-title">
          <span>入库明细</span>
          <el-button link type="primary" @click="inboundForm.lines.push(newInboundLine())">添加一行</el-button>
        </div>
        <div v-for="(line, index) in inboundForm.lines" :key="index" class="purchase-line-card">
          <div class="line-card-head">
            <strong>明细 {{ index + 1 }}</strong>
            <el-button link type="danger" :disabled="inboundForm.lines.length === 1" @click="inboundForm.lines.splice(index, 1)">删除</el-button>
          </div>
          <div class="line-grid">
            <label class="line-field">
              <span>商品</span>
              <el-select v-model="line.skuId" filterable>
                <el-option v-for="sku in skus" :key="sku.skuId" :label="sku.skuName || sku.skuId" :value="sku.skuId" />
              </el-select>
            </label>
            <label class="line-field"><span>批次</span><el-input v-model="line.batchNo" /></label>
            <label class="line-field"><span>数量</span><el-input-number v-model="line.quantity" :min="1" controls-position="right" /></label>
            <label class="line-field"><span>生产日期</span><input v-model="line.productionDate" class="native-date" type="date" /></label>
            <label class="line-field"><span>到期日期</span><input v-model="line.expiryDate" class="native-date" type="date" /></label>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="inboundDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveInbound">确认入库</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Box, EditPen, Refresh, Van } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
const auth = useAuthStore();
const canEdit = computed(() => auth.hasPerm('ops:warehouse:edit') || auth.hasPerm('ops:replenishment:edit'));

const loading = ref(false);
const saving = ref(false);
const tab = ref('warehouses');
const keyword = ref('');
const filterWarehouseId = ref('');
const warehouses = ref<Row[]>([]);
const suppliers = ref<Row[]>([]);
const purchaseOrders = ref<Row[]>([]);
const outbounds = ref<Row[]>([]);
const inTransit = ref<Row[]>([]);
const inventory = ref<Row[]>([]);
const movements = ref<Row[]>([]);
const devices = ref<Row[]>([]);
const skus = ref<Row[]>([]);
const loadedTabs = ref(new Set<string>(['warehouses']));

const warehouseDialog = ref(false);
const supplierDialog = ref(false);
const purchaseDialog = ref(false);
const receiveDialog = ref(false);
const inboundDialog = ref(false);

const warehouseForm = reactive({
  editing: false,
  warehouseId: '',
  warehouseName: '',
  address: '',
  status: 'ACTIVE'
});
const supplierForm = reactive({
  editing: false,
  supplierId: '',
  supplierName: '',
  contactName: '',
  contactPhone: '',
  status: 'ACTIVE'
});
const purchaseForm = reactive<Row>({ supplierId: '', warehouseId: '', refNo: '', notes: '', lines: [] });
const receiveForm = reactive<Row>({ purchaseOrderId: null, notes: '', lines: [] });
const inboundForm = reactive<Row>({ warehouseId: '', refNo: '', notes: '', lines: [] });

const showFilterBar = computed(() =>
  ['suppliers', 'purchase', 'inventory', 'movements', 'outbounds'].includes(tab.value)
);
const activeSuppliers = computed(() => suppliers.value.filter((s) => s.status === 'ACTIVE'));
const activeWarehouses = computed(() => warehouses.value.filter((w) => (w.status || 'ACTIVE') === 'ACTIVE'));
const filteredSuppliers = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q || tab.value !== 'suppliers') return suppliers.value;
  return suppliers.value.filter((s) =>
    [s.supplierId, s.supplierName, s.contactName, s.contactPhone].join(' ').toLowerCase().includes(q)
  );
});
const filteredPurchaseOrders = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  let list = purchaseOrders.value;
  if (filterWarehouseId.value) list = list.filter((p) => p.warehouseId === filterWarehouseId.value);
  if (!q) return list;
  return list.filter((p) =>
    [p.purchaseOrderId, p.refNo, p.supplierId, supplierName(p.supplierId)].join(' ').toLowerCase().includes(q)
  );
});
const filteredOutbounds = computed(() => {
  if (!filterWarehouseId.value) return outbounds.value;
  return outbounds.value.filter((o) => o.warehouseId === filterWarehouseId.value);
});

const { onExport: exportWarehouses } = useListCsv({
  filePrefix: '仓库概览',
  headers: ['仓库名称', '仓库编号', '地址', '状态'],
  toRows: () =>
    warehouses.value.map((row) => [
      row.warehouseName || row.warehouseId,
      row.warehouseId,
      row.address || '',
      dictLabel('warehouse_status', row.status || 'ACTIVE')
    ])
});

const { onExport: exportSuppliers } = useListCsv({
  filePrefix: '供应商',
  headers: ['供应商', '供应商编号', '联系人', '联系电话', '状态'],
  toRows: () =>
    filteredSuppliers.value.map((row) => [
      row.supplierName || row.supplierId,
      row.supplierId,
      row.contactName || '',
      row.contactPhone || '',
      dictLabel('supplier_status', row.status)
    ])
});

const { onExport: exportPurchase } = useListCsv({
  filePrefix: '采购单',
  headers: ['采购单', '外部单号', '供应商', '入库仓库', '状态'],
  toRows: () =>
    filteredPurchaseOrders.value.map((row) => [
      row.purchaseOrderId,
      row.refNo || '',
      supplierName(row.supplierId),
      warehouseName(row.warehouseId),
      dictLabel('purchase_order_status', row.status)
    ])
});

const { onExport: exportOutbounds } = useListCsv({
  filePrefix: '出库单',
  headers: ['出库单', '路线', '出库仓库', '状态', '创建时间'],
  toRows: () =>
    filteredOutbounds.value.map((row) => [
      row.outboundId,
      row.routeId || '',
      warehouseName(row.warehouseId),
      dictLabel('warehouse_outbound_status', row.status),
      formatDateTime(row.createdAt)
    ])
});

const { onExport: exportTransit } = useListCsv({
  filePrefix: '在途',
  headers: ['出库单', '目标设备', '商品', '批次', '数量', '状态', '发运时间'],
  toRows: () =>
    inTransit.value.map((row) => [
      row.outboundId,
      deviceName(row.deviceId),
      skuName(row.skuId),
      row.batchNo || '',
      row.quantity,
      dictLabel('in_transit_status', row.status),
      formatDateTime(row.createdAt)
    ])
});

const { onExport: exportInventory } = useListCsv({
  filePrefix: '批次库存',
  headers: ['仓库', '商品', '批次', '生产日期', '到期日期', '库存', '效期'],
  toRows: () =>
    inventory.value.map((row) => [
      warehouseName(row.warehouseId),
      skuName(row.skuId),
      row.batchNo || '',
      row.productionDate || '',
      row.expiryDate || '',
      row.quantity,
      expiryText(row.expiryDate)
    ])
});

const { onExport: exportMovements } = useListCsv({
  filePrefix: '库存流水',
  headers: ['流水', '类型', '商品', '批次', '变动', '关联业务', '关联单号', '时间'],
  toRows: () =>
    movements.value.map((row) => [
      row.movementId,
      dictLabel('warehouse_movement_type', row.movementType),
      skuName(row.skuId),
      row.batchNo || '',
      row.deltaQty,
      dictLabel('business_reference_type', row.refType),
      row.refId || '',
      formatDateTime(row.createdAt)
    ])
});

function onExport() {
  const exporters: Record<string, () => void> = {
    warehouses: exportWarehouses,
    suppliers: exportSuppliers,
    purchase: exportPurchase,
    outbounds: exportOutbounds,
    transit: exportTransit,
    inventory: exportInventory,
    movements: exportMovements
  };
  exporters[tab.value]?.();
}

function supplierName(id: string) {
  return suppliers.value.find((s) => s.supplierId === id)?.supplierName || id || '-';
}
function warehouseName(id: string) {
  return warehouses.value.find((w) => w.warehouseId === id)?.warehouseName || id || '-';
}
function deviceName(id: string) {
  return devices.value.find((d) => d.deviceId === id)?.deviceName || id || '-';
}
function skuName(id: string) {
  return skus.value.find((s) => s.skuId === id)?.skuName || id || '-';
}
function localDate() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}
function newLine() {
  return {
    skuId: skus.value[0]?.skuId || '',
    batchNo: '',
    productionDate: localDate(),
    expiryDate: '',
    orderedQty: 1,
    receivedQty: 0,
    unitCostYuan: 1
  };
}
function newInboundLine() {
  return {
    skuId: skus.value[0]?.skuId || '',
    batchNo: '',
    productionDate: localDate(),
    expiryDate: '',
    quantity: 1
  };
}
function money(cents: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}
function expiryDays(value: string) {
  return Math.ceil((new Date(value).getTime() - Date.now()) / 86400000);
}
function expiryText(value: string) {
  const days = expiryDays(value);
  return days < 0 ? '已过期' : days <= 7 ? '临期' : `${days} 天`;
}
function expiryType(value: string) {
  const days = expiryDays(value);
  return days < 0 ? 'danger' : days <= 7 ? 'warning' : 'success';
}

function outboundActions(row: Row): TableAction[] {
  const acts: TableAction[] = [];
  const hasLines = (row.lines?.length || 0) > 0;
  if (row.status === 'DRAFT' && hasLines) {
    acts.push({ key: 'pick', label: '确认拣货', icon: Box, type: 'primary' });
  }
  if (row.status === 'PICKED' && hasLines) {
    acts.push({ key: 'ship', label: '确认发运', icon: Van, type: 'danger' });
  }
  return acts;
}

async function ensureMeta() {
  if (!devices.value.length) {
    devices.value = await api.request<Row[]>('/api/v2/ops/admin/devices', 'GET').catch(() => []);
  }
  if (!skus.value.length) {
    skus.value = await api.request<Row[]>('/api/v2/ops/admin/skus', 'GET').catch(() => []);
  }
}

async function loadWarehouses() {
  warehouses.value = await api.request<Row[]>('/api/v2/ops/admin/warehouse/list', 'GET');
}
async function loadSuppliers() {
  suppliers.value = await api.request<Row[]>('/api/v2/ops/admin/suppliers', 'GET');
}
async function loadPurchase() {
  purchaseOrders.value = await api.request<Row[]>('/api/v2/ops/admin/purchase-orders', 'GET');
}
async function loadOutbounds() {
  outbounds.value = await api.request<Row[]>('/api/v2/ops/admin/warehouse/outbounds', 'GET');
}
async function loadTransit() {
  inTransit.value = await api.request<Row[]>('/api/v2/ops/admin/warehouse/in-transit', 'GET');
}
async function loadInventory() {
  const q = filterWarehouseId.value ? `?warehouseId=${encodeURIComponent(filterWarehouseId.value)}` : '';
  inventory.value = await api.request<Row[]>(`/api/v2/ops/admin/warehouse/inventory${q}`, 'GET');
}
async function loadMovements() {
  const q = filterWarehouseId.value ? `?warehouseId=${encodeURIComponent(filterWarehouseId.value)}` : '';
  movements.value = await api.request<Row[]>(`/api/v2/ops/admin/warehouse/movements${q}`, 'GET');
}

async function loadTab(name: string, force = false) {
  if (!force && loadedTabs.value.has(name) && name !== 'inventory' && name !== 'movements') return;
  loading.value = true;
  try {
    await ensureMeta();
    if (name === 'warehouses') await loadWarehouses();
    else if (name === 'suppliers') await loadSuppliers();
    else if (name === 'purchase') {
      await Promise.all([loadPurchase(), loadSuppliers(), loadWarehouses()]);
    } else if (name === 'outbounds') {
      await Promise.all([loadOutbounds(), loadWarehouses()]);
    } else if (name === 'transit') await loadTransit();
    else if (name === 'inventory') {
      await Promise.all([loadInventory(), loadWarehouses()]);
    } else if (name === 'movements') {
      await Promise.all([loadMovements(), loadWarehouses()]);
    }
    loadedTabs.value.add(name);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function onTabChange(name: string | number) {
  loadTab(String(name));
}
function reloadCurrent() {
  loadedTabs.value.delete(tab.value);
  loadTab(tab.value, true);
}
function onWarehouseFilter() {
  if (tab.value === 'inventory' || tab.value === 'movements') {
    loadedTabs.value.delete(tab.value);
    loadTab(tab.value, true);
  }
}

function openWarehouse(row?: Row) {
  Object.assign(warehouseForm, {
    editing: !!row,
    warehouseId: row?.warehouseId || '',
    warehouseName: row?.warehouseName || '',
    address: row?.address || '',
    status: row?.status || 'ACTIVE'
  });
  warehouseDialog.value = true;
}
async function saveWarehouse() {
  if (!warehouseForm.warehouseId.trim() || !warehouseForm.warehouseName.trim()) {
    return ElMessage.warning('请填写仓库 ID 和名称');
  }
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/warehouse/${encodeURIComponent(warehouseForm.warehouseId.trim())}`, 'PUT', {
      warehouseName: warehouseForm.warehouseName.trim(),
      address: warehouseForm.address,
      status: warehouseForm.status
    });
    warehouseDialog.value = false;
    ElMessage.success('仓库已保存');
    loadedTabs.value.delete('warehouses');
    await loadTab('warehouses', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function openSupplier(row?: Row) {
  Object.assign(supplierForm, {
    editing: !!row,
    supplierId: row?.supplierId || '',
    supplierName: row?.supplierName || '',
    contactName: row?.contactName || '',
    contactPhone: row?.contactPhone || '',
    status: row?.status || 'ACTIVE'
  });
  supplierDialog.value = true;
}
async function saveSupplier() {
  if (!supplierForm.supplierId.trim() || !supplierForm.supplierName.trim()) {
    return ElMessage.warning('请填写供应商 ID 和名称');
  }
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/suppliers/${encodeURIComponent(supplierForm.supplierId.trim())}`, 'PUT', {
      supplierId: supplierForm.supplierId.trim(),
      supplierName: supplierForm.supplierName.trim(),
      contactName: supplierForm.contactName,
      contactPhone: supplierForm.contactPhone,
      status: supplierForm.status
    });
    supplierDialog.value = false;
    ElMessage.success('供应商已保存');
    loadedTabs.value.delete('suppliers');
    await loadTab('suppliers', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function openPurchase() {
  await Promise.all([loadSuppliers(), loadWarehouses(), ensureMeta()]);
  Object.assign(purchaseForm, {
    supplierId: activeSuppliers.value[0]?.supplierId || '',
    warehouseId: activeWarehouses.value[0]?.warehouseId || '',
    refNo: '',
    notes: '',
    lines: [newLine()]
  });
  purchaseDialog.value = true;
}
function addPurchaseLine() {
  purchaseForm.lines.push(newLine());
}
async function savePurchase() {
  if (!purchaseForm.supplierId || purchaseForm.lines.some((l: Row) => !l.skuId || !l.batchNo || !l.expiryDate)) {
    return ElMessage.warning('请完整填写供应商、商品、批次和到期日期');
  }
  saving.value = true;
  try {
    const body = {
      supplierId: purchaseForm.supplierId,
      warehouseId: purchaseForm.warehouseId,
      refNo: purchaseForm.refNo,
      notes: purchaseForm.notes,
      lines: purchaseForm.lines.map((l: Row) => ({
        skuId: l.skuId,
        batchNo: l.batchNo,
        productionDate: l.productionDate,
        expiryDate: l.expiryDate,
        orderedQty: l.orderedQty,
        receivedQty: 0,
        unitCostCents: Math.round((Number(l.unitCostYuan) || 0) * 100)
      }))
    };
    await api.request('/api/v2/ops/admin/purchase-orders', 'POST', body);
    purchaseDialog.value = false;
    tab.value = 'purchase';
    ElMessage.success('采购单已创建');
    loadedTabs.value.delete('purchase');
    await loadTab('purchase', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  } finally {
    saving.value = false;
  }
}

function openReceive(row: Row) {
  Object.assign(receiveForm, {
    purchaseOrderId: row.purchaseOrderId,
    notes: '',
    lines: (row.lines || []).map((line: Row) => ({
      ...line,
      minReceived: line.receivedQty || 0,
      receivedQty: line.receivedQty || 0
    }))
  });
  receiveDialog.value = true;
}
async function saveReceive() {
  saving.value = true;
  try {
    await ElMessageBox.confirm('确认按累计收货数量入库？', '采购收货', { type: 'warning' });
    await api.request(`/api/v2/ops/admin/purchase-orders/${receiveForm.purchaseOrderId}/receive`, 'POST', {
      lines: receiveForm.lines,
      notes: receiveForm.notes
    });
    receiveDialog.value = false;
    ElMessage.success('收货完成');
    loadedTabs.value.delete('purchase');
    loadedTabs.value.delete('inventory');
    await loadTab('purchase', true);
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e instanceof Error ? e.message : '收货失败');
  } finally {
    saving.value = false;
  }
}

async function changeOutbound(row: Row, action: 'pick' | 'ship') {
  const text = action === 'pick' ? '确认本单已完成拣货？' : '发运后库存将转为在途，确认继续？';
  try {
    await ElMessageBox.confirm(text, action === 'pick' ? '确认拣货' : '确认发运', { type: 'warning' });
    await api.request(`/api/v2/ops/admin/warehouse/outbounds/${row.outboundId}/${action}`, 'POST');
    ElMessage.success(action === 'pick' ? '拣货完成' : '已发运');
    loadedTabs.value.delete('outbounds');
    loadedTabs.value.delete('transit');
    loadedTabs.value.delete('inventory');
    await loadTab('outbounds', true);
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

async function openInbound() {
  await Promise.all([loadWarehouses(), ensureMeta()]);
  Object.assign(inboundForm, {
    warehouseId: filterWarehouseId.value || activeWarehouses.value[0]?.warehouseId || '',
    refNo: '',
    notes: '',
    lines: [newInboundLine()]
  });
  inboundDialog.value = true;
}
async function saveInbound() {
  if (!inboundForm.warehouseId || inboundForm.lines.some((l: Row) => !l.skuId || !l.batchNo || !l.expiryDate || !l.quantity)) {
    return ElMessage.warning('请完整填写仓库、商品、批次、到期日和数量');
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/warehouse/inbound', 'POST', {
      warehouseId: inboundForm.warehouseId,
      refNo: inboundForm.refNo,
      notes: inboundForm.notes,
      lines: inboundForm.lines
    });
    inboundDialog.value = false;
    ElMessage.success('入库完成');
    loadedTabs.value.delete('inventory');
    loadedTabs.value.delete('movements');
    tab.value = 'inventory';
    await loadTab('inventory', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '入库失败');
  } finally {
    saving.value = false;
  }
}

onMounted(async () => {
  await loadTab('warehouses', true);
});
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.title { font-weight: 600; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
.filter-bar { display: flex; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.line-table { margin: 8px 44px; width: calc(100% - 88px); }
.muted, .tip { color: var(--layout-muted); font-size: 13px; }
.tip { margin: 0 0 8px; }
.positive { color: #059669; font-weight: 700; }
.negative { color: #dc2626; font-weight: 700; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.section-title { display: flex; justify-content: space-between; align-items: center; margin: 8px 0 12px; font-weight: 700; }
.purchase-line-card { padding: 16px; margin-bottom: 14px; border: 1px solid var(--layout-border); border-radius: 12px; background: var(--el-fill-color-light); }
.line-card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.line-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.line-field { display: grid; gap: 7px; color: var(--layout-muted); font-size: 13px; }
.line-field :deep(.el-input-number), .line-field :deep(.el-select) { width: 100%; }
.native-date { width: 100%; height: 32px; padding: 0 10px; border: 1px solid var(--layout-border); border-radius: 4px; color: var(--layout-text); background: var(--layout-card); box-sizing: border-box; }
.receive-table { margin-bottom: 12px; }
.master-data-cell { display: grid; gap: 2px; line-height: 1.35; }
.master-data-cell strong { color: var(--layout-text); font-weight: 650; }
.master-data-cell small { color: var(--layout-muted); font-size: 11px; }
@media (max-width: 900px) {
  .form-grid, .line-grid { grid-template-columns: 1fr; }
}
</style>
