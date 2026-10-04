<template>
  <view class="page">
    <app-nav-bar title="采购入库" />
    <app-underline-tabs :items="modeTabs" :value="mode" @change="onModeTab" />
    <view class="page-body">
      <view v-if="mode === 'create'" class="panel">
        <view v-if="pageError" class="card">
          <error-state :message="pageError" retry-label="重试" @retry="loadCreate" />
        </view>
        <view v-else class="card">
          <text class="label">收货仓</text>
          <text class="warehouse">{{ warehouseName || '加载中…' }}</text>
          <text class="hint">只进您负责的分仓，不要用要货当日常采购</text>
        </view>

        <view class="card">
          <text class="label">供应商</text>
          <picker :range="supplierLabels" :value="supplierIndex" @change="onSupplierPick">
            <view class="picker">{{ supplierLabels[supplierIndex] || '请选择供应商' }}</view>
          </picker>
        </view>

        <view class="card">
          <text class="label">采购明细</text>
          <view v-if="createLoading" class="empty-inline">{{ loadingLabel('商品') }}</view>
          <view v-else-if="!skuLines.length" class="empty-inline">暂无可采购商品</view>
          <view
            v-for="line in skuLines"
            :key="line.skuId"
            role="button"
            class="line-row"
            @click="toggleLine(line)"
          >
            <view class="check" :class="{ on: line.selected }">{{ line.selected ? '✓' : '' }}</view>
            <view class="line-copy">
              <text class="sku-name">{{ line.skuName || line.skuId }}</text>
              <text class="sku-meta">{{ line.skuId }}</text>
            </view>
            <view role="button" class="qty-box" @click.stop>
              <text role="button" class="qty-btn" @click="adjustQty(line, -1)">−</text>
              <text class="qty-val">{{ line.qty }}</text>
              <text role="button" class="qty-btn" @click="adjustQty(line, 1)">+</text>
            </view>
          </view>
        </view>

        <app-button
          variant="primary"
          block
          :disabled="!canSubmit"
          :loading="submitting"
          label="提交采购申请"
          @click="submit"
        />
      </view>

      <view v-else class="panel">
        <view v-if="listError" class="card">
          <error-state :message="listError" retry-label="重试" @retry="loadOrders" />
        </view>
        <view v-else-if="listLoading && !orders.length" class="card empty-inline">{{
          loadingLabel('采购单')
        }}</view>
        <empty-state
          v-else-if="!orders.length"
          title="暂无采购单"
          desc="提交后在这里查看审核进度"
        />
        <view v-for="order in orders" :key="order.purchaseOrderId" class="card req-card">
          <view class="req-head">
            <text class="req-id">采购单 {{ order.purchaseOrderId }}</text>
            <text class="status" :class="statusClass(order.status)">{{
              satellitePurchaseStatusLabel(order.status)
            }}</text>
          </view>
          <text class="req-meta">供应商 {{ supplierName(order.supplierId) }}</text>
          <view v-if="order.lines?.length" class="line-list">
            <view
              v-for="line in order.lines"
              :key="line.skuId + String(line.lineId || '')"
              class="recv-line"
            >
              <text class="line-item">{{ line.skuId }} × {{ line.orderedQty }}</text>
              <template v-if="canReceiveSatellitePurchase(order.status)">
                <input
                  class="input"
                  :value="line.batchNo"
                  placeholder="批次"
                  @input="line.batchNo = eventInput($event)"
                />
                <picker mode="date" :value="line.expiryDate" @change="onExpiryPick(line, $event)">
                  <view class="picker">到期 {{ line.expiryDate || '请选择' }}</view>
                </picker>
              </template>
            </view>
          </view>
          <app-button
            v-if="canReceiveSatellitePurchase(order.status)"
            variant="primary"
            block
            :loading="receivingId === order.purchaseOrderId"
            :disabled="!canConfirmSatelliteReceive(order.lines || [])"
            label="货已到仓，确认收货"
            @click="receive(order)"
          />
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { showError, showSuccess } from '@/utils/notify';
import { hasPerm, isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import { requestActionErrorMessage } from '@/utils/request-submit';
import {
  canConfirmSatelliteReceive,
  canReceiveSatellitePurchase,
  canSubmitSatellitePurchase,
  defaultSatelliteReceiveBatch,
  defaultSatelliteReceiveExpiry,
  satellitePurchaseStatusLabel
} from '@/utils/purchase-display';

const modeTabs = [
  { value: 'create', label: '申请' },
  { value: 'list', label: '单据' }
];
const mode = ref<'create' | 'list'>('create');
const { me } = useMerchantMe();
const canView = computed(() => hasPerm(me.value, 'merchant:replenishment:view'));

const warehouseId = ref('');
const warehouseName = ref('');
const pageError = ref('');
const createLoading = ref(false);
const submitting = ref(false);

type SupplierRow = { supplierId: string; supplierName: string };
type SkuLine = {
  skuId: string;
  skuName: string;
  unitCostCents: number;
  selected: boolean;
  qty: number;
};

const suppliers = ref<SupplierRow[]>([]);
const supplierIndex = ref(0);
const skuLines = ref<SkuLine[]>([]);
const supplierLabels = computed(() => suppliers.value.map((s) => s.supplierName || s.supplierId));

type OrderLine = {
  lineId?: number;
  skuId: string;
  orderedQty: number;
  receivedQty?: number;
  batchNo?: string;
  expiryDate?: string;
};

const orders = ref<
  {
    purchaseOrderId: number;
    supplierId: string;
    status: string;
    lines?: OrderLine[];
  }[]
>([]);
const listLoading = ref(false);
const listError = ref('');
const receivingId = ref<number | null>(null);

const canSubmit = computed(() =>
  canSubmitSatellitePurchase({
    warehouseId: warehouseId.value,
    supplierId: suppliers.value[supplierIndex.value]?.supplierId || '',
    lines: skuLines.value
  })
);

function onModeTab(value: string) {
  mode.value = value === 'list' ? 'list' : 'create';
  if (mode.value === 'list') void loadOrders();
}

function onSupplierPick(e: { detail?: { value?: string | number } }) {
  supplierIndex.value = Number(e.detail?.value || 0);
}

function toggleLine(line: SkuLine) {
  line.selected = !line.selected;
  if (line.selected && line.qty < 1) line.qty = 1;
}

function adjustQty(line: SkuLine, delta: number) {
  const next = Math.max(0, line.qty + delta);
  line.qty = next;
  line.selected = next > 0;
}

function statusClass(status: string) {
  const key = String(status || '').toUpperCase();
  if (key === 'RECEIVED') return 'completed';
  if (key === 'REJECTED' || key === 'CANCELLED') return 'rejected';
  if (key === 'CREATED' || key === 'PARTIAL_RECEIVED') return 'accepted';
  return 'awaiting';
}

function eventInput(e: { detail?: { value?: string } }) {
  return String(e.detail?.value || '');
}

function onExpiryPick(line: OrderLine, e: { detail?: { value?: string } }) {
  line.expiryDate = String(e.detail?.value || '');
}

function hydrateReceiveLines(rows: OrderLine[] | undefined): OrderLine[] {
  const batch = defaultSatelliteReceiveBatch();
  const expiry = defaultSatelliteReceiveExpiry();
  return (rows || []).map((line) => ({
    ...line,
    batchNo: line.batchNo || batch,
    expiryDate: line.expiryDate || expiry,
    receivedQty: line.orderedQty
  }));
}

async function loadCreate() {
  if (!canView.value) return;
  createLoading.value = true;
  pageError.value = '';
  try {
    const [wh, sup, skus] = await Promise.all([
      merchantApi.satelliteWarehouse(),
      merchantApi.satellitePurchaseSuppliers(),
      merchantApi.satellitePurchaseSkus()
    ]);
    warehouseId.value = wh.warehouseId || '';
    warehouseName.value = wh.warehouseName || wh.warehouseId || '';
    suppliers.value = sup || [];
    if (supplierIndex.value >= suppliers.value.length) supplierIndex.value = 0;
    skuLines.value = (skus || []).map((sku) => ({
      skuId: sku.skuId,
      skuName: sku.skuName,
      unitCostCents: sku.unitCostCents,
      selected: false,
      qty: 0
    }));
  } catch (e) {
    pageError.value = requestActionErrorMessage(e, '加载失败');
  } finally {
    createLoading.value = false;
  }
}

async function loadOrders() {
  if (!canView.value) return;
  listLoading.value = true;
  listError.value = '';
  try {
    if (!suppliers.value.length) {
      suppliers.value = await merchantApi.satellitePurchaseSuppliers();
    }
    orders.value = (await merchantApi.satellitePurchaseOrders()).map((row) => ({
      ...row,
      lines: hydrateReceiveLines(row.lines)
    }));
  } catch (e) {
    listError.value = requestActionErrorMessage(e, '加载失败');
  } finally {
    listLoading.value = false;
  }
}

function supplierName(id: string) {
  return suppliers.value.find((s) => s.supplierId === id)?.supplierName || id;
}

async function receive(order: { purchaseOrderId: number; lines?: OrderLine[] }) {
  if (receivingId.value || !canConfirmSatelliteReceive(order.lines || [])) return;
  receivingId.value = order.purchaseOrderId;
  try {
    await merchantApi.receiveSatellitePurchaseOrder(order.purchaseOrderId, {
      lines: (order.lines || []).map((line) => ({
        lineId: line.lineId,
        skuId: line.skuId,
        batchNo: String(line.batchNo || '').trim(),
        expiryDate: String(line.expiryDate || '').trim(),
        receivedQty: Number(line.orderedQty) || 0
      }))
    });
    showSuccess(`已收货 采购单 ${order.purchaseOrderId}`);
    await loadOrders();
  } catch (e) {
    showError(requestActionErrorMessage(e, '收货失败'));
  } finally {
    receivingId.value = null;
  }
}

async function submit() {
  if (!canSubmit.value || submitting.value) return;
  const supplier = suppliers.value[supplierIndex.value];
  const lines = skuLines.value
    .filter((line) => line.selected && line.qty > 0)
    .map((line) => ({
      skuId: line.skuId,
      orderedQty: line.qty,
      unitCostCents: line.unitCostCents
    }));
  submitting.value = true;
  try {
    const created = await merchantApi.createSatellitePurchaseOrder({
      supplierId: supplier.supplierId,
      warehouseId: warehouseId.value,
      lines
    });
    showSuccess(`已提交采购单 ${created.purchaseOrderId}`);
    skuLines.value.forEach((line) => {
      line.selected = false;
      line.qty = 0;
    });
    mode.value = 'list';
    await loadOrders();
  } catch (e) {
    showError(requestActionErrorMessage(e, '提交失败'));
  } finally {
    submitting.value = false;
  }
}

onShow(() => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  if (mode.value === 'create') void loadCreate();
  else void loadOrders();
});

onPullDownRefresh(async () => {
  try {
    if (mode.value === 'create') await loadCreate();
    else await loadOrders();
  } finally {
    uni.stopPullDownRefresh();
  }
});
</script>

<style scoped src="./purchase.page.css"></style>
