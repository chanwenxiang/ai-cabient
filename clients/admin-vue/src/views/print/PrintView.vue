<template>
  <div class="print-page">
    <div class="no-print toolbar">
      <span class="toolbar-title">{{ pageTitle }}</span>
      <span class="toolbar-actions">
        <button type="button" class="toolbar-btn primary" @click="doPrint">打印</button>
        <button type="button" class="toolbar-btn" @click="closeWindow">关闭</button>
      </span>
    </div>

    <div v-if="loading" class="no-print loading">单据加载中…</div>

    <div v-else class="print-sheet">
      <!-- 拣货单 -->
      <template v-if="mode === 'picking' && outbound">
        <div class="sheet-head">
          <div>
            <h1>拣货单</h1>
            <p>出库单 #{{ outbound.outboundId }} · {{ printTime }}</p>
          </div>
          <div class="sheet-meta">
            <p>仓库：{{ nameOf(warehouses, 'warehouseId', 'warehouseName', outbound.warehouseId) }}</p>
            <p>路线：{{ outbound.routeId || '—' }}</p>
            <p>状态：{{ outbound.status }}</p>
          </div>
        </div>
        <table class="print-table">
          <thead>
            <tr>
              <th>目标设备</th>
              <th>商品</th>
              <th>批次</th>
              <th>货道</th>
              <th>数量</th>
              <th>到期日</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(line, idx) in outbound.lines || []" :key="line.lineId || idx">
              <td>{{ nameOf(devices, 'deviceId', 'deviceName', line.deviceId) }}</td>
              <td>{{ nameOf(skus, 'skuId', 'skuName', line.skuId) }}</td>
              <td>{{ line.batchNo || '—' }}</td>
              <td>{{ line.slotId || '待分配' }}</td>
              <td class="num">{{ line.quantity }}</td>
              <td>{{ line.expiryDate || '—' }}</td>
            </tr>
          </tbody>
        </table>
        <p class="sheet-foot">打印时间：{{ printTime }} · 请在拣货/发运时按单核对批次与货道</p>
      </template>

      <!-- 采购收货单 -->
      <template v-else-if="mode === 'purchase' && purchase">
        <div class="sheet-head">
          <div>
            <h1>采购收货单</h1>
            <p>采购单 #{{ purchase.purchaseOrderId }} · {{ printTime }}</p>
          </div>
          <div class="sheet-meta">
            <p>供应商：{{ nameOf(suppliers, 'supplierId', 'supplierName', purchase.supplierId) }}</p>
            <p>入库仓库：{{ nameOf(warehouses, 'warehouseId', 'warehouseName', purchase.warehouseId) }}</p>
            <p v-if="purchase.refNo">外部单号：{{ purchase.refNo }}</p>
            <p>状态：{{ purchase.status }}</p>
          </div>
        </div>
        <table class="print-table">
          <thead>
            <tr>
              <th>商品</th>
              <th>批次</th>
              <th>采购数</th>
              <th>已收数</th>
              <th>单价(元)</th>
              <th>到期日</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(line, idx) in purchase.lines || []" :key="line.lineId || idx">
              <td>{{ nameOf(skus, 'skuId', 'skuName', line.skuId) }}</td>
              <td>{{ line.batchNo || '—' }}</td>
              <td class="num">{{ line.orderedQty }}</td>
              <td class="num">{{ line.receivedQty }}</td>
              <td class="num">{{ money(line.unitCostCents) }}</td>
              <td>{{ line.expiryDate || '—' }}</td>
            </tr>
          </tbody>
        </table>
        <p class="sheet-foot">打印时间：{{ printTime }} · 收货时请核对批次、效期与实收数量</p>
      </template>

      <!-- 商品标签 -->
      <template v-else-if="mode === 'labels' && labels.length">
        <div class="sheet-head">
          <div>
            <h1>商品标签</h1>
            <p>共 {{ labels.length }} 个商品 · {{ printTime }}</p>
          </div>
        </div>
        <div class="label-grid">
          <div v-for="sku in labels" :key="sku.skuId" class="label-card">
            <div class="label-name">{{ sku.skuName || sku.skuId }}</div>
            <div class="label-row">
              <span>SKU {{ sku.skuId }}</span>
              <span>¥{{ money(sku.priceCents) }}</span>
            </div>
            <div v-if="sku.barcode" class="label-barcode">{{ sku.barcode }}</div>
            <div v-else class="label-barcode muted">无条码</div>
          </div>
        </div>
      </template>

      <p v-else class="no-print empty-tip">暂无打印内容</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { api } from '@/api/client';

type Row = Record<string, any>;

const route = useRoute();
const loading = ref(true);
const outbound = ref<Row | null>(null);
const purchase = ref<Row | null>(null);
const labels = ref<Row[]>([]);
const warehouses = ref<Row[]>([]);
const devices = ref<Row[]>([]);
const skus = ref<Row[]>([]);
const suppliers = ref<Row[]>([]);

const mode = computed(() => String(route.query.type || ''));
const pageTitle = computed(() => {
  if (mode.value === 'picking') return '拣货单打印';
  if (mode.value === 'purchase') return '采购收货单打印';
  if (mode.value === 'labels') return '商品标签打印';
  return '打印单据';
});
const printTime = new Date().toLocaleString('zh-CN', { hour12: false });

function nameOf(rows: Row[], keyField: string, nameField: string, id?: string) {
  if (!id) return '—';
  return rows.find((r) => String(r[keyField]) === String(id))?.[nameField] || id;
}
function money(cents: number) {
  return ((Number(cents) || 0) / 100).toFixed(2);
}

async function load() {
  try {
    if (mode.value === 'picking') {
      const [ob, whs, devs, skuRows] = await Promise.all([
        api.request<Row>(`/api/v2/ops/admin/warehouse/outbounds/${route.query.outboundId}`, 'GET'),
        api.request<Row[]>('/api/v2/ops/admin/warehouse/list', 'GET').catch(() => []),
        api.request<Row[]>('/api/v2/ops/admin/devices/ref', 'GET').catch(() => []),
        api.request<Row[]>('/api/v2/ops/admin/skus', 'GET').catch(() => [])
      ]);
      outbound.value = ob;
      warehouses.value = whs;
      devices.value = devs;
      skus.value = skuRows;
    } else if (mode.value === 'purchase') {
      const [po, sups, whs, skuRows] = await Promise.all([
        api.request<Row>(`/api/v2/ops/admin/purchase-orders/${route.query.purchaseOrderId}`, 'GET'),
        api.request<Row[]>('/api/v2/ops/admin/suppliers', 'GET').catch(() => []),
        api.request<Row[]>('/api/v2/ops/admin/warehouse/list', 'GET').catch(() => []),
        api.request<Row[]>('/api/v2/ops/admin/skus', 'GET').catch(() => [])
      ]);
      purchase.value = po;
      suppliers.value = sups;
      warehouses.value = whs;
      skus.value = skuRows;
    } else if (mode.value === 'labels') {
      const ids = String(route.query.ids || '')
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
      const rows = await api.request<Row[]>('/api/v2/ops/admin/skus', 'GET').catch(() => []);
      labels.value = rows.filter((r) => ids.includes(String(r.skuId)));
    }
  } catch (e) {
    console.error('打印数据加载失败', e);
  } finally {
    loading.value = false;
    nextTick(() => {
      setTimeout(() => window.print(), 300);
    });
  }
}

function doPrint() {
  window.print();
}
function closeWindow() {
  window.close();
}

onMounted(load);
</script>

<style scoped>
.print-page {
  min-height: 100vh;
  background: #f5f7fa;
}
.toolbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.toolbar-title {
  font-size: 16px;
  font-weight: 600;
}
.toolbar-actions {
  display: flex;
  gap: 8px;
}
.toolbar-btn {
  padding: 6px 16px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  font-size: 14px;
}
.toolbar-btn.primary {
  background: #409eff;
  border-color: #409eff;
  color: #fff;
}
.loading,
.empty-tip {
  padding: 48px;
  text-align: center;
  color: #909399;
}
.print-sheet {
  width: 720px;
  margin: 24px auto;
  padding: 32px 40px;
  background: #fff;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}
.sheet-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
  padding-bottom: 12px;
  border-bottom: 2px solid #303133;
}
.sheet-head h1 {
  margin: 0 0 6px;
  font-size: 22px;
}
.sheet-head p {
  margin: 2px 0;
  color: #606266;
  font-size: 13px;
}
.sheet-meta {
  text-align: right;
}
.print-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.print-table th,
.print-table td {
  border: 1px solid #dcdfe6;
  padding: 7px 8px;
  text-align: left;
}
.print-table th {
  background: #f5f7fa;
  font-weight: 600;
}
.print-table .num {
  text-align: right;
}
.sheet-foot {
  margin-top: 16px;
  color: #909399;
  font-size: 12px;
}
.label-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
.label-card {
  padding: 12px;
  border: 1px dashed #909399;
  border-radius: 6px;
  page-break-inside: avoid;
}
.label-name {
  font-size: 15px;
  font-weight: 700;
  margin-bottom: 6px;
}
.label-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #606266;
}
.label-barcode {
  margin-top: 8px;
  padding: 6px 8px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-family: monospace;
  font-size: 16px;
  letter-spacing: 2px;
  text-align: center;
}
.muted {
  color: #c0c4cc;
}
@media print {
  body * {
    visibility: hidden;
  }
  .print-sheet,
  .print-sheet * {
    visibility: visible;
  }
  .print-sheet {
    position: absolute;
    left: 0;
    top: 0;
    width: 100%;
    margin: 0;
    box-shadow: none;
  }
  .no-print {
    display: none !important;
  }
}
</style>
