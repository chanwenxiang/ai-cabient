<template>
  <view class="page">
    <app-nav-bar title="采购入库" home-url="/pages/home/home" />
    <app-underline-tabs :items="modeTabs" :value="mode" @change="onModeTab" />
    <view class="page-body">
      <view v-if="mode === 'create'" class="panel">
        <view v-if="pageError" class="card">
          <error-state :message="pageError" retry-label="重试" @retry="loadCreate" />
        </view>
        <view v-else class="card">
          <text class="label">收货仓</text>
          <text class="warehouse">{{ warehouseName || '加载中…' }}</text>
          <text class="hint">只进您负责的分仓</text>
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
              <view class="recv-line-head">
                <text class="line-item"
                  >{{ line.skuId }} · 采购 {{ line.orderedQty
                  }}{{ line.alreadyReceived ? ` · 已收 ${line.alreadyReceived}` : '' }}</text
                >
                <view
                  v-if="canReceiveSatellitePurchase(order.status)"
                  role="button"
                  class="qty-box"
                  @click.stop
                >
                  <text role="button" class="qty-btn" @click="adjustReceivedQty(line, -1)">−</text>
                  <text class="qty-val">{{ line.receivedQty ?? line.orderedQty }}</text>
                  <text role="button" class="qty-btn" @click="adjustReceivedQty(line, 1)">+</text>
                </view>
              </view>
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
          <!-- 语义说明（2026-10-23 审计 M6）：全部行保持默认=整单收货；
               任一行调低=部分收货，只入库有增量的行，未调整的行不入库 -->
          <text v-if="canReceiveSatellitePurchase(order.status)" class="recv-hint"
            >全部行保持默认即整单收货；只到了部分货时，把实收数调低，未调整的行不入库</text
          >
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
import { requestActionErrorMessage } from '@/utils/self-replenish-submit';
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
  /** 服务端累计已收数（hydrate 时快照；后端对 ≤该值的目标静默无操作）。 */
  alreadyReceived?: number;
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

function eventInput(e: unknown) {
  return String((e as { detail?: { value?: string } })?.detail?.value || '');
}

function onExpiryPick(line: OrderLine, e: { detail?: { value?: string } }) {
  line.expiryDate = String(e.detail?.value || '');
}

/**
 * 收货行「字段原值快照」：hydrate 时写入，用于判断用户是否改过批次/效期。
 * 后端 P1-1 语义（ProcurementService.doReceive）：即使数量无增量，
 * 请求里带了 batchNo/expiryDate 也会覆盖订单行——所以这些行必须随提交发出。
 */
const receiveLineOriginals = new Map<string, { batchNo: string; expiryDate: string }>();

function receiveLineKey(line: OrderLine): string {
  return String(line.lineId ?? line.skuId);
}

function isReceiveLineBatchEdited(line: OrderLine): boolean {
  const orig = receiveLineOriginals.get(receiveLineKey(line));
  if (!orig) return false;
  return (
    String(line.batchNo || '') !== orig.batchNo || String(line.expiryDate || '') !== orig.expiryDate
  );
}

function hydrateReceiveLines(rows: OrderLine[] | undefined): OrderLine[] {
  const batch = defaultSatelliteReceiveBatch();
  const expiry = defaultSatelliteReceiveExpiry();
  return (rows || []).map((line) => {
    const hydrated: OrderLine = {
      ...line,
      batchNo: line.batchNo || batch,
      expiryDate: line.expiryDate || expiry,
      // 修复 2026-10-14：后端把提交值当「累计已收目标」（≤已收静默无操作）。
      // 保留服务端已收数供展示与下限约束，目标默认=采购数（补齐剩余）。
      alreadyReceived: Math.max(0, Number(line.receivedQty) || 0),
      receivedQty: Math.max(line.orderedQty, Number(line.receivedQty) || 0)
    };
    // 快照「用户可编辑字段」的初始值：之后凡与快照不同即视为已修正，提交时保留
    receiveLineOriginals.set(receiveLineKey(hydrated), {
      batchNo: String(hydrated.batchNo || ''),
      expiryDate: String(hydrated.expiryDate || '')
    });
    return hydrated;
  });
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

/**
 * 审计 P2-19 附加：实收数量可编辑（1..采购数，默认=采购数）。
 * 竞品（easygo）入库一次性全额、实际到货数不入账——账实漂移根源，不可学；
 * 后端分仓收货复用 processReceiveLine，本就支持部分收货（PARTIAL_RECEIVED）。
 * 供应商少发/运输破损时按实收入账，剩余量可后续再收。
 */
function adjustReceivedQty(line: OrderLine, delta: number) {
  // 下限=已收数（后端对 ≤已收 的目标静默无操作，UI 直接不允许调低到已收以下）。
  const max = Math.max(1, line.orderedQty, line.alreadyReceived ?? 0);
  const floor = Math.max(1, line.alreadyReceived ?? 0);
  const current = Math.max(floor, Number(line.receivedQty) || line.orderedQty);
  line.receivedQty = Math.min(max, Math.max(floor, current + delta));
}

async function receive(order: { purchaseOrderId: number; lines?: OrderLine[] }) {
  if (receivingId.value || !canConfirmSatelliteReceive(order.lines || [])) return;
  // 修复 2026-10-14：目标 ≤ 已收 时后端静默 return 0（无入库），此前却 toast 成功。
  const hasDelta = (order.lines || []).some(
    (line) =>
      (Number(line.receivedQty) || 0) > (line.alreadyReceived ?? 0) ||
      // 批次/效期修正也算「有变更」：后端对数量无增量的行也会落库批次修正（P1-1），
      // 此前只改批次号时本门闩直接报「数量未变化」，修正没有提交入口。
      isReceiveLineBatchEdited(line)
  );
  if (!hasDelta) {
    showError('收货未变化：没有可入库的增量，也没有批次/效期修正');
    return;
  }
  receivingId.value = order.purchaseOrderId;
  try {
    const lines = order.lines || [];
    // 语义收口（2026-10-23 审计 M6/M4）：
    // - 整单模式：所有行都保持默认（实收=剩余全量）→ 全量行提交，行为与旧版一致；
    // - 部分模式：任一行被调低（实收 < 默认全额）→ 只提交「有增量」的行。
    //   后端对请求里没有的行不收货（ProcurementService 只遍历请求 lines，@NotEmpty 仅要求 ≥1 行），
    //   此前 A 行改 3、未调整的 B 行也按 5 全额入库的写穿场景由此消除。
    // - 🔴 修正（第四轮回归）：有增量之外，「批次/效期被修正」的行也必须保留——
    //   后端对数量无增量的行同样会持久化 batchNo/expiryDate（P1-1），
    //   按纯数量过滤会把这类修正静默丢掉。
    const defaultFull = (l: OrderLine) => Math.max(l.orderedQty, l.alreadyReceived ?? 0);
    const partialMode = lines.some((l) => (Number(l.receivedQty) || 0) < defaultFull(l));
    const payloadLines = partialMode
      ? lines.filter(
          (l) =>
            (Number(l.receivedQty) || 0) > (l.alreadyReceived ?? 0) || isReceiveLineBatchEdited(l)
        )
      : lines;
    await merchantApi.receiveSatellitePurchaseOrder(order.purchaseOrderId, {
      lines: payloadLines.map((line) => ({
        lineId: line.lineId,
        skuId: line.skuId,
        batchNo: String(line.batchNo || '').trim(),
        expiryDate: String(line.expiryDate || '').trim(),
        receivedQty: Math.max(1, Number(line.receivedQty) || line.orderedQty)
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
