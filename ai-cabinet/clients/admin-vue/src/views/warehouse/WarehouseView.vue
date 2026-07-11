<template>
  <div class="warehouse-page">
    <section class="hero-card">
      <div>
        <p class="eyebrow">供应链控制台</p>
        <h2>采购与仓储</h2>
        <p class="hero-copy">从供应商、采购收货到出库在途，统一追踪批次、数量与责任人。</p>
      </div>
      <div class="hero-actions">
        <el-button v-if="tab === 'suppliers'" type="primary" @click="openSupplier()">新增供应商</el-button>
        <el-button v-if="tab === 'purchase'" type="primary" @click="openPurchase()">新建采购单</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </section>

    <el-card class="page-card" shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane label="仓库概览" name="warehouses">
          <el-table :data="warehouses" stripe>
            <el-table-column prop="warehouseName" label="仓库名称" min-width="180">
              <template #default="{ row }"><div class="master-data-cell"><strong>{{ row.warehouseName || row.warehouseId }}</strong><small>{{ row.warehouseId }}</small></div></template>
            </el-table-column>
            <el-table-column prop="address" label="地址" min-width="220" />
            <el-table-column prop="status" label="状态" width="110">
              <template #default="{ row }"><el-tag :type="dictTagType(row.status)">{{ dictLabel('warehouse_status', row.status || 'ACTIVE') }}</el-tag></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="供应商" name="suppliers">
          <el-table :data="suppliers" stripe>
            <el-table-column prop="supplierName" label="供应商" min-width="210">
              <template #default="{ row }"><div class="master-data-cell"><strong>{{ row.supplierName || row.supplierId }}</strong><small>{{ row.supplierId }}</small></div></template>
            </el-table-column>
            <el-table-column prop="contactName" label="联系人" width="120" />
            <el-table-column prop="contactPhone" label="联系电话" width="150" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="dictTagType(row.status)">{{ dictLabel('supplier_status', row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }"><el-button link type="primary" @click="openSupplier(row)">编辑</el-button></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="采购单" name="purchase">
          <el-table :data="purchaseOrders" stripe>
            <el-table-column type="expand">
              <template #default="{ row }">
                <el-table :data="row.lines" size="small" class="line-table">
                  <el-table-column label="商品" min-width="190"><template #default="scope"><div class="master-data-cell"><strong>{{ skuName(scope.row.skuId) }}</strong><small>{{ scope.row.skuId }}</small></div></template></el-table-column>
                  <el-table-column prop="batchNo" label="批次" min-width="150" />
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
            <el-table-column prop="refNo" label="外部单号" min-width="150" />
            <el-table-column label="供应商" min-width="190">
              <template #default="{ row }"><div class="master-data-cell"><strong>{{ supplierName(row.supplierId) }}</strong><small>{{ row.supplierId }}</small></div></template>
            </el-table-column>
            <el-table-column label="入库仓库" min-width="180">
              <template #default="{ row }"><div class="master-data-cell"><strong>{{ warehouseName(row.warehouseId) }}</strong><small>{{ row.warehouseId }}</small></div></template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="150">
              <template #default="{ row }"><el-tag :type="dictTagType(row.status)">{{ dictLabel('purchase_order_status', row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button v-if="['CREATED','PARTIAL_RECEIVED'].includes(row.status)" link type="primary" @click="openReceive(row)">采购收货</el-button>
                <span v-else class="muted">已完成</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="出库单" name="outbounds">
          <el-table :data="outbounds" stripe>
            <el-table-column type="expand">
              <template #default="{ row }">
                <el-table :data="row.lines" size="small" class="line-table">
                  <el-table-column label="目标柜机" min-width="190"><template #default="scope"><div class="master-data-cell"><strong>{{ deviceName(scope.row.deviceId) }}</strong><small>{{ scope.row.deviceId }}</small></div></template></el-table-column>
                  <el-table-column label="商品" min-width="190"><template #default="scope"><div class="master-data-cell"><strong>{{ skuName(scope.row.skuId) }}</strong><small>{{ scope.row.skuId }}</small></div></template></el-table-column>
                  <el-table-column prop="batchNo" label="批次" min-width="150" />
                  <el-table-column prop="quantity" label="数量" width="90" />
                  <el-table-column label="交接状态" width="120"><template #default="scope">{{ dictLabel('handover_status', scope.row.handoverStatus) }}</template></el-table-column>
                </el-table>
              </template>
            </el-table-column>
            <el-table-column prop="outboundId" label="出库单" width="100" />
            <el-table-column prop="routeId" label="路线" width="90" />
            <el-table-column label="出库仓库" min-width="180">
              <template #default="{ row }"><div class="master-data-cell"><strong>{{ warehouseName(row.warehouseId) }}</strong><small>{{ row.warehouseId }}</small></div></template>
            </el-table-column>
            <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="dictTagType(row.status)">{{ dictLabel('warehouse_outbound_status', row.status) }}</el-tag></template></el-table-column>
            <el-table-column prop="createdAt" label="创建时间" min-width="180" />
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="changeOutbound(row, 'pick')">确认拣货</el-button>
                <el-button v-if="row.status === 'PICKED'" link type="danger" @click="changeOutbound(row, 'ship')">确认发运</el-button>
                <span v-if="row.status === 'SHIPPED'" class="muted">已发运</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="在途" name="transit">
          <el-table :data="inTransit" stripe>
            <el-table-column prop="outboundId" label="出库单" width="100" />
            <el-table-column label="目标设备" min-width="190"><template #default="{ row }"><div class="master-data-cell"><strong>{{ deviceName(row.deviceId) }}</strong><small>{{ row.deviceId }}</small></div></template></el-table-column>
            <el-table-column label="商品" min-width="190"><template #default="{ row }"><div class="master-data-cell"><strong>{{ skuName(row.skuId) }}</strong><small>{{ row.skuId }}</small></div></template></el-table-column>
            <el-table-column prop="batchNo" label="批次" min-width="150" />
            <el-table-column prop="quantity" label="数量" width="90" />
            <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="dictTagType(row.status)">{{ dictLabel('in_transit_status', row.status) }}</el-tag></template></el-table-column>
            <el-table-column prop="createdAt" label="发运时间" min-width="180" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="批次库存" name="inventory">
          <el-table :data="inventory" stripe>
            <el-table-column label="商品" min-width="190"><template #default="{ row }"><div class="master-data-cell"><strong>{{ skuName(row.skuId) }}</strong><small>{{ row.skuId }}</small></div></template></el-table-column>
            <el-table-column prop="batchNo" label="批次" min-width="160" />
            <el-table-column prop="productionDate" label="生产日期" width="130" />
            <el-table-column prop="expiryDate" label="到期日期" width="130" />
            <el-table-column prop="quantity" label="库存" width="100" />
            <el-table-column label="效期状态" width="110">
              <template #default="{ row }"><el-tag :type="expiryType(row.expiryDate)">{{ expiryText(row.expiryDate) }}</el-tag></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="库存流水" name="movements">
          <el-table :data="movements" stripe>
            <el-table-column prop="movementId" label="流水" width="90" />
            <el-table-column label="类型" min-width="150"><template #default="{ row }">{{ dictLabel('warehouse_movement_type', row.movementType) }}</template></el-table-column>
            <el-table-column label="商品" min-width="190"><template #default="{ row }"><div class="master-data-cell"><strong>{{ skuName(row.skuId) }}</strong><small>{{ row.skuId }}</small></div></template></el-table-column>
            <el-table-column prop="batchNo" label="批次" min-width="150" />
            <el-table-column prop="deltaQty" label="变动" width="90">
              <template #default="{ row }"><span :class="row.deltaQty >= 0 ? 'positive' : 'negative'">{{ row.deltaQty > 0 ? '+' : '' }}{{ row.deltaQty }}</span></template>
            </el-table-column>
            <el-table-column label="关联业务" width="150"><template #default="{ row }">{{ dictLabel('business_reference_type', row.refType) }}</template></el-table-column>
            <el-table-column prop="refId" label="关联单号" width="120" />
            <el-table-column prop="createdAt" label="时间" min-width="180" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="supplierDialog" :title="supplierForm.editing ? '编辑供应商' : '新增供应商'" width="520px">
      <el-form label-width="92px">
        <el-form-item label="供应商 ID"><el-input v-model="supplierForm.supplierId" :disabled="supplierForm.editing" placeholder="例如 SUP-BEVERAGE-001" /></el-form-item>
        <el-form-item label="供应商名称"><el-input v-model="supplierForm.supplierName" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="supplierForm.contactName" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="supplierForm.contactPhone" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="supplierForm.status">
            <el-option v-for="item in dictOptions('supplier_status')" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="supplierDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveSupplier">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="purchaseDialog" title="新建采购单" width="760px">
      <el-form label-width="90px">
        <div class="form-grid">
          <el-form-item label="供应商"><el-select v-model="purchaseForm.supplierId" filterable><el-option v-for="item in activeSuppliers" :key="item.supplierId" :label="item.supplierName" :value="item.supplierId" /></el-select></el-form-item>
          <el-form-item label="入库仓库"><el-select v-model="purchaseForm.warehouseId"><el-option v-for="item in warehouses" :key="item.warehouseId" :label="item.warehouseName" :value="item.warehouseId" /></el-select></el-form-item>
          <el-form-item label="外部单号"><el-input v-model="purchaseForm.refNo" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="purchaseForm.notes" /></el-form-item>
        </div>
        <div class="section-title"><span>采购商品</span><el-button link type="primary" @click="addPurchaseLine">添加一行</el-button></div>
        <div v-for="(line,index) in purchaseForm.lines" :key="index" class="purchase-line-card">
          <div class="line-card-head">
            <strong>商品明细 {{ index + 1 }}</strong>
            <el-button link type="danger" :disabled="purchaseForm.lines.length===1" @click="purchaseForm.lines.splice(index,1)">删除本项</el-button>
          </div>
          <div class="line-grid">
            <label class="line-field"><span>采购商品</span><el-select v-model="line.skuId" data-testid="purchase-sku" filterable placeholder="选择商品"><el-option v-for="sku in skus" :key="sku.skuId" :label="`${sku.skuName || sku.skuId}（${sku.skuId}）`" :value="sku.skuId" /></el-select></label>
            <label class="line-field"><span>批次号</span><el-input v-model="line.batchNo" data-testid="purchase-batch" placeholder="例如 B-202607-001" /></label>
            <label class="line-field"><span>采购数量（件）</span><el-input-number v-model="line.orderedQty" data-testid="purchase-qty" :min="1" controls-position="right" /></label>
            <label class="line-field"><span>单位成本（分）</span><el-input-number v-model="line.unitCostCents" data-testid="purchase-cost" :min="1" controls-position="right" /></label>
            <label class="line-field"><span>生产日期</span><input v-model="line.productionDate" data-testid="purchase-production" class="native-date" type="date" /></label>
            <label class="line-field"><span>到期日期</span><input v-model="line.expiryDate" data-testid="purchase-expiry" class="native-date" type="date" /></label>
          </div>
        </div>
        <p class="field-hint">数量和成本单位分别为件、分；收货时再确认实际到货数量。</p>
      </el-form>
      <template #footer><el-button @click="purchaseDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="savePurchase">创建采购单</el-button></template>
    </el-dialog>

    <el-dialog v-model="receiveDialog" title="采购收货" width="700px">
      <el-alert type="info" :closable="false" title="请填写累计已收数量；系统只按本次新增数量入库，重复提交不会重复增加库存。" />
      <el-table :data="receiveForm.lines" class="receive-table">
        <el-table-column label="商品" min-width="190"><template #default="{ row }"><div class="master-data-cell"><strong>{{ skuName(row.skuId) }}</strong><small>{{ row.skuId }}</small></div></template></el-table-column>
        <el-table-column prop="batchNo" label="批次" min-width="150" />
        <el-table-column prop="orderedQty" label="采购数" width="90" />
        <el-table-column label="累计收货" width="150">
          <template #default="{ row }"><el-input-number v-model="row.receivedQty" :min="row.receivedQty" :max="row.orderedQty" controls-position="right" /></template>
        </el-table-column>
      </el-table>
      <el-input v-model="receiveForm.notes" type="textarea" placeholder="质检或收货备注" />
      <template #footer><el-button @click="receiveDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveReceive">确认收货入库</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';

type Row = Record<string, any>;
const loading = ref(false);
const saving = ref(false);
const tab = ref('warehouses');
const warehouses = ref<Row[]>([]);
const suppliers = ref<Row[]>([]);
const purchaseOrders = ref<Row[]>([]);
const outbounds = ref<Row[]>([]);
const inTransit = ref<Row[]>([]);
const inventory = ref<Row[]>([]);
const movements = ref<Row[]>([]);
const devices = ref<Row[]>([]);
const skus = ref<Row[]>([]);
const supplierDialog = ref(false);
const purchaseDialog = ref(false);
const receiveDialog = ref(false);
const supplierForm = reactive({ editing:false, supplierId:'', supplierName:'', contactName:'', contactPhone:'', status:'ACTIVE' });
const purchaseForm = reactive<Row>({ supplierId:'', warehouseId:'WH-DEMO-001', refNo:'', notes:'', lines:[] });
const receiveForm = reactive<Row>({ purchaseOrderId:null, notes:'', lines:[] });
const activeSuppliers = computed(() => suppliers.value.filter(item => item.status === 'ACTIVE'));

function supplierName(supplierId:string) { return suppliers.value.find(item => item.supplierId === supplierId)?.supplierName || supplierId || '-'; }
function warehouseName(warehouseId:string) { return warehouses.value.find(item => item.warehouseId === warehouseId)?.warehouseName || warehouseId || '-'; }
function deviceName(deviceId:string) { return devices.value.find(item => item.deviceId === deviceId)?.deviceName || deviceId || '-'; }
function skuName(skuId:string) { return skus.value.find(item => item.skuId === skuId)?.skuName || skuId || '-'; }

function localDate() { const now=new Date(); return new Date(now.getTime()-now.getTimezoneOffset()*60000).toISOString().slice(0,10); }
function newLine() { return { skuId:'SKU-DEMO-001', batchNo:'', productionDate:localDate(), expiryDate:'', orderedQty:1, receivedQty:0, unitCostCents:100 }; }
function money(cents:number) { return ((Number(cents)||0)/100).toFixed(2); }
function expiryDays(value:string) { return Math.ceil((new Date(value).getTime()-Date.now())/86400000); }
function expiryText(value:string) { const days=expiryDays(value); return days<0?'已过期':days<=7?'临期':`${days} 天`; }
function expiryType(value:string) { const days=expiryDays(value); return days<0?'danger':days<=7?'warning':'success'; }

async function load() {
  loading.value = true;
  try {
    const requests = await Promise.all([
      api.request<Row[]>('/api/v2/ops/admin/warehouse/list','GET'),
      api.request<Row[]>('/api/v2/ops/admin/suppliers','GET'),
      api.request<Row[]>('/api/v2/ops/admin/purchase-orders','GET'),
      api.request<Row[]>('/api/v2/ops/admin/warehouse/outbounds','GET').catch(()=>[]),
      api.request<Row[]>('/api/v2/ops/admin/warehouse/in-transit','GET').catch(()=>[]),
      api.request<Row[]>('/api/v2/ops/admin/warehouse/inventory','GET').catch(()=>[]),
      api.request<Row[]>('/api/v2/ops/admin/warehouse/movements','GET').catch(()=>[]),
      api.request<Row[]>('/api/v2/ops/admin/devices','GET').catch(()=>[]),
      api.request<Row[]>('/api/v2/ops/admin/skus','GET').catch(()=>[])
    ]);
    [warehouses.value,suppliers.value,purchaseOrders.value,outbounds.value,inTransit.value,inventory.value,movements.value,devices.value,skus.value] = requests.map(item=>item||[]);
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '仓储数据加载失败'); }
  finally { loading.value=false; }
}

function openSupplier(row?:Row) {
  Object.assign(supplierForm,{ editing:!!row, supplierId:row?.supplierId||'', supplierName:row?.supplierName||'', contactName:row?.contactName||'', contactPhone:row?.contactPhone||'', status:row?.status||'ACTIVE' });
  supplierDialog.value=true;
}
async function saveSupplier() {
  if(!supplierForm.supplierId.trim()||!supplierForm.supplierName.trim()) return ElMessage.warning('请填写供应商 ID 和名称');
  saving.value=true;
  try { await api.request(`/api/v2/ops/admin/suppliers/${encodeURIComponent(supplierForm.supplierId.trim())}`,'PUT',supplierForm); supplierDialog.value=false; ElMessage.success('供应商已保存'); await load(); }
  catch(error){ElMessage.error(error instanceof Error?error.message:'保存失败');} finally{saving.value=false;}
}
function openPurchase(){ Object.assign(purchaseForm,{supplierId:activeSuppliers.value[0]?.supplierId||'',warehouseId:warehouses.value[0]?.warehouseId||'WH-DEMO-001',refNo:'',notes:'',lines:[newLine()]}); purchaseDialog.value=true; }
function addPurchaseLine(){ purchaseForm.lines.push(newLine()); }
async function savePurchase(){
  if(!purchaseForm.supplierId||purchaseForm.lines.some((line:Row)=>!line.skuId||!line.batchNo||!line.expiryDate)) return ElMessage.warning('请完整填写供应商、SKU、批次和到期日期');
  saving.value=true;
  try{await api.request('/api/v2/ops/admin/purchase-orders','POST',purchaseForm);purchaseDialog.value=false;tab.value='purchase';ElMessage.success('采购单已创建');await load();}
  catch(error){ElMessage.error(error instanceof Error?error.message:'创建失败');}finally{saving.value=false;}
}
function openReceive(row:Row){Object.assign(receiveForm,{purchaseOrderId:row.purchaseOrderId,notes:'',lines:row.lines.map((line:Row)=>({...line}))});receiveDialog.value=true;}
async function saveReceive(){
  saving.value=true;
  try{await ElMessageBox.confirm('收货将增加仓库批次库存，请确认数量和批次无误。','确认采购收货',{type:'warning'});await api.request(`/api/v2/ops/admin/purchase-orders/${receiveForm.purchaseOrderId}/receive`,'POST',{lines:receiveForm.lines,notes:receiveForm.notes});receiveDialog.value=false;ElMessage.success('收货入库完成');await load();}
  catch(error:any){if(error!=='cancel'&&error!=='close')ElMessage.error(error instanceof Error?error.message:'收货失败');}finally{saving.value=false;}
}
async function changeOutbound(row:Row,action:'pick'|'ship'){
  const text=action==='pick'?'确认本单已完成拣货？':'发运后库存将转为在途，确认继续？';
  try{await ElMessageBox.confirm(text,action==='pick'?'确认拣货':'确认发运',{type:'warning'});await api.request(`/api/v2/ops/admin/warehouse/outbounds/${row.outboundId}/${action}`,'POST');ElMessage.success(action==='pick'?'拣货完成':'出库单已发运');await load();}
  catch(error:any){if(error!=='cancel'&&error!=='close')ElMessage.error(error instanceof Error?error.message:'操作失败');}
}
onMounted(load);
</script>

<style scoped>
.warehouse-page{display:grid;gap:16px}.hero-card{display:flex;justify-content:space-between;gap:24px;align-items:center;padding:24px 28px;border-radius:18px;color:#fff;background:linear-gradient(135deg,#172554 0%,#1d4ed8 58%,#0ea5e9 100%);box-shadow:0 18px 40px rgba(29,78,216,.2)}.eyebrow{margin:0 0 6px;font-size:11px;letter-spacing:.18em;opacity:.7}.hero-card h2{margin:0;font-size:26px}.hero-copy{margin:8px 0 0;opacity:.82}.hero-actions{display:flex;gap:10px;flex-wrap:wrap}.page-card{border:0;border-radius:16px}.line-table{margin:8px 44px;width:calc(100% - 88px)}.muted,.field-hint{color:#94a3b8;font-size:13px}.positive{color:#059669;font-weight:700}.negative{color:#dc2626;font-weight:700}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 16px}.section-title{display:flex;justify-content:space-between;align-items:center;margin:8px 0 12px;font-weight:700}.purchase-line-card{padding:16px;margin-bottom:14px;border:1px solid #e2e8f0;border-radius:12px;background:#f8fafc}.line-card-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px}.line-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.line-field{display:grid;gap:7px;color:#475569;font-size:13px}.line-field :deep(.el-input-number){width:100%}.native-date{width:100%;height:32px;padding:0 10px;border:1px solid #dcdfe6;border-radius:4px;color:#303133;background:#fff;box-sizing:border-box}.native-date:focus{outline:none;border-color:#409eff}.receive-table{margin:14px 0}.el-select{width:100%}@media(max-width:900px){.hero-card{align-items:flex-start;flex-direction:column}.form-grid{grid-template-columns:1fr}.line-grid{grid-template-columns:1fr 1fr}}@media(max-width:620px){.line-grid{grid-template-columns:1fr}.line-card-head{align-items:flex-start}.purchase-line-card{padding:14px}}
.master-data-cell{display:grid;gap:2px;line-height:1.35}.master-data-cell strong{color:#1e293b;font-weight:650}.master-data-cell small{color:#94a3b8;font-size:11px;letter-spacing:.02em}
</style>
