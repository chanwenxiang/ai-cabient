import { type Ref } from 'vue';
import type { Router } from 'vue-router';
import { displayLabel } from '@aicabinet/shared-dict';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';

/** 仓储动态行（D15：禁止散落 Record<string, any>） */
export type WarehouseLabelRow = AdminDynamicRow;

export type UseWarehouseLabelsDeps = {
  suppliers: Ref<WarehouseLabelRow[]>;
  warehouses: Ref<WarehouseLabelRow[]>;
  devices: Ref<WarehouseLabelRow[]>;
  skus: Ref<WarehouseLabelRow[]>;
  router: Router;
};

/**
 * 仓储列表展示文案：供应商/仓库/设备/SKU 名、状态标签、效期与金额。
 */
export function useWarehouseLabels(deps: UseWarehouseLabelsDeps) {
  function returnStatusLabel(status?: string) {
    const code = (status || 'COMPLETED').toUpperCase();
    if (code === 'COMPLETED') return displayLabel('order_status', 'COMPLETED');
    if (code === 'CANCELLED') return displayLabel('order_status', 'CANCELLED');
    return status || displayLabel('order_status', 'COMPLETED');
  }

  function supplierName(id: string) {
    return deps.suppliers.value.find((s) => s.supplierId === id)?.supplierName || id || '无';
  }

  function warehouseName(id: string) {
    return deps.warehouses.value.find((w) => w.warehouseId === id)?.warehouseName || id || '无';
  }

  function transferStatusLabel(status?: string) {
    if (!status) return '';
    const code = String(status).toUpperCase();
    if (code === 'DRAFT') return displayLabel('stocktake_status', 'DRAFT');
    if (code === 'SHIPPED') return displayLabel('warehouse_outbound_status', 'SHIPPED');
    if (code === 'RECEIVED') return displayLabel('purchase_order_status', 'RECEIVED');
    if (code === 'CANCELLED') return displayLabel('order_status', 'CANCELLED');
    return status;
  }

  function deviceName(id?: string, snapshot?: string | null) {
    const snap = snapshot != null ? String(snapshot).trim() : '';
    if (snap) return snap;
    const deviceId = id != null ? String(id) : '';
    if (!deviceId) return '无';
    return deps.devices.value.find((d) => d.deviceId === deviceId)?.deviceName || deviceId;
  }

  function skuName(id?: string) {
    const skuId = id != null ? String(id) : '';
    if (!skuId) return '无';
    return deps.skus.value.find((s) => s.skuId === skuId)?.skuName || skuId;
  }

  function suggestionReasonText(code: string) {
    return displayLabel('purchase_suggestion_reason', code, '暂无');
  }

  function payableStatusText(code: string) {
    return displayLabel('supplier_payable_status', code, '暂无');
  }

  function payableStatusType(code: string) {
    const map: Record<string, string> = {
      UNPAID: 'warning',
      PARTIAL: 'primary',
      PAID: 'success',
      CLOSED: 'info'
    };
    return map[code] || 'info';
  }

  function stocktakeModeText(mode: string) {
    return displayLabel('stocktake_mode', mode, '未知');
  }

  function stocktakeStatusText(code: string) {
    return displayLabel('stocktake_status', code, '暂无');
  }

  function stocktakeStatusType(code: string) {
    const map: Record<string, string> = {
      DRAFT: 'info',
      IN_PROGRESS: 'warning',
      COMPLETED: 'success',
      ADJUSTED: 'primary',
      CANCELLED: 'info'
    };
    return map[code] || 'info';
  }

  function stocktakeLineStatusText(code: string) {
    return displayLabel('stocktake_line_status', code, '暂无');
  }

  function stocktakeLineStatusType(code: string) {
    const map: Record<string, string> = {
      PENDING: 'info',
      MATCHED: 'success',
      DIFF: 'danger',
      ADJUSTED: 'primary'
    };
    return map[code] || 'info';
  }

  function money(cents: number) {
    return ((Number(cents) || 0) / 100).toFixed(2);
  }

  function openPrint(type: string, query: Record<string, string | number>) {
    const url = deps.router.resolve({ name: 'print', query: { type, ...query } }).href;
    globalThis.open(url, '_blank');
  }

  function expiryDays(value: string) {
    return Math.ceil((new Date(value).getTime() - Date.now()) / 86400000);
  }

  function expiryText(value: string) {
    const days = expiryDays(value);
    if (days < 0) return '已过期';
    if (days <= 7) return '临期';
    return `${days} 天`;
  }

  function expiryType(value: string) {
    const days = expiryDays(value);
    if (days < 0) return 'danger';
    if (days <= 7) return 'warning';
    return 'success';
  }

  return {
    returnStatusLabel,
    supplierName,
    warehouseName,
    transferStatusLabel,
    deviceName,
    skuName,
    suggestionReasonText,
    payableStatusText,
    payableStatusType,
    stocktakeModeText,
    stocktakeStatusText,
    stocktakeStatusType,
    stocktakeLineStatusText,
    stocktakeLineStatusType,
    money,
    openPrint,
    expiryDays,
    expiryText,
    expiryType
  };
}
