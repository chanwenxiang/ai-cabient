import { computed, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api, downloadAuthFile } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { useListCsv } from '@/composables/useListCsv';
import { csvFileName } from '@/utils/csv';
import { errorMessage } from '@/utils/error-message';
import { dictLabel, displayLabel } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import type { WarehouseFilterRow } from './useWarehouseListFilters';

export type UseWarehouseCsvDeps = {
  tab: Ref<string>;
  selectedKeys: Ref<Array<string | number>>;
  loadedTabs: Ref<Set<string>>;
  loadTab: (name: string, force?: boolean) => Promise<void>;
  warehouses: Ref<WarehouseFilterRow[]>;
  suppliers: Ref<WarehouseFilterRow[]>;
  purchaseOrders: Ref<WarehouseFilterRow[]>;
  purchaseReturns: Ref<WarehouseFilterRow[]>;
  outbounds: Ref<WarehouseFilterRow[]>;
  inventory: Ref<WarehouseFilterRow[]>;
  movements: Ref<WarehouseFilterRow[]>;
  filteredSuppliers: Ref<WarehouseFilterRow[]>;
  filteredPurchaseOrders: Ref<WarehouseFilterRow[]>;
  filteredPurchaseReturns: Ref<WarehouseFilterRow[]>;
  filteredOutbounds: Ref<WarehouseFilterRow[]>;
  filteredInTransit: Ref<WarehouseFilterRow[]>;
  pickSelected: <T extends WarehouseFilterRow>(all: T[]) => T[];
  statusCode: (raw: string | undefined, fallback?: string) => string;
  supplierName: (id: string) => string;
  warehouseName: (id: string) => string;
  deviceName: (id?: string, snapshot?: string | null) => string;
  skuName: (id?: string) => string;
  returnStatusLabel: (status?: string) => string;
  expiryText: (value: string) => string;
  formatAge: (ms: number) => string;
  transitAgeMs: (row: WarehouseFilterRow) => number;
  isTransitOverdue: (row: WarehouseFilterRow) => boolean;
};

/**
 * 仓储 CSV：主数据导入模板、各 Tab 客户端导出、服务端全量导出分流。
 */
export function useWarehouseCsv(deps: UseWarehouseCsvDeps) {
  const {
    onExport: exportWarehouses,
    importing: importingWarehouses,
    importInput: warehouseImportInput,
    onDownloadTemplate: downloadWarehouseTemplate,
    triggerImport: triggerWarehouseImport,
    onImportFile: onWarehouseImportFile
  } = useListCsv({
    filePrefix: '仓库概览',
    headers: ['仓库名称', '仓库编号', '地址', '状态'],
    toRows: () =>
      deps
        .pickSelected(deps.warehouses.value)
        .map((row) => [
          row.warehouseName || row.warehouseId,
          row.warehouseId,
          row.address || '',
          dictLabel('warehouse_status', row.status || 'ACTIVE')
        ]),
    onImportRows: async (rows) => {
      let ok = 0;
      for (const row of rows) {
        const warehouseId = (row['仓库编号'] || row.warehouseId || '').trim();
        const warehouseName = (row['仓库名称'] || row.warehouseName || '').trim();
        if (!warehouseId || !warehouseName) continue;
        await api.request(AdminEndpoints.warehouseItem(warehouseId), 'PUT', {
          warehouseName,
          address: (row['地址'] || row.address || '').trim(),
          status: deps.statusCode(row['状态'] || row.status)
        });
        ok++;
      }
      deps.loadedTabs.value.delete('warehouses');
      await deps.loadTab('warehouses', true);
      return ok;
    }
  });

  const {
    onExport: exportSuppliers,
    importing: importingSuppliers,
    importInput: supplierImportInput,
    onDownloadTemplate: downloadSupplierTemplate,
    triggerImport: triggerSupplierImport,
    onImportFile: onSupplierImportFile
  } = useListCsv({
    filePrefix: '供应商',
    headers: ['供应商', '供应商编号', '联系人', '联系电话', '状态'],
    toRows: () =>
      deps
        .pickSelected(deps.filteredSuppliers.value)
        .map((row) => [
          row.supplierName || row.supplierId,
          row.supplierId,
          row.contactName || '',
          row.contactPhone || '',
          dictLabel('supplier_status', row.status)
        ]),
    onImportRows: async (rows) => {
      let ok = 0;
      for (const row of rows) {
        const supplierId = (row['供应商编号'] || row.supplierId || '').trim();
        const supplierName = (row['供应商'] || row.supplierName || '').trim();
        if (!supplierId || !supplierName) continue;
        await api.request(AdminEndpoints.supplier(supplierId), 'PUT', {
          supplierId,
          supplierName,
          contactName: (row['联系人'] || row.contactName || '').trim(),
          contactPhone: (row['联系电话'] || row.contactPhone || '').trim(),
          status: deps.statusCode(row['状态'] || row.status)
        });
        ok++;
      }
      deps.loadedTabs.value.delete('suppliers');
      await deps.loadTab('suppliers', true);
      return ok;
    }
  });

  const importing = computed(() => importingWarehouses.value || importingSuppliers.value);

  function onDownloadImportTemplate() {
    if (deps.tab.value === 'warehouses') {
      downloadWarehouseTemplate([
        '示例中心仓',
        'WH-DEMO-001',
        '上海市示例路 1 号',
        displayLabel('warehouse_status', 'ACTIVE')
      ]);
    } else if (deps.tab.value === 'suppliers') {
      downloadSupplierTemplate([
        '示例饮品供应商',
        'SUP-DEMO-001',
        '张三',
        '13800000000',
        displayLabel('supplier_status', 'ACTIVE')
      ]);
    }
  }

  function triggerImport() {
    if (deps.tab.value === 'warehouses') triggerWarehouseImport();
    else if (deps.tab.value === 'suppliers') triggerSupplierImport();
  }

  const { onExport: exportPurchase } = useListCsv({
    filePrefix: '采购单',
    headers: ['采购单', '外部单号', '供应商', '入库仓库', '状态'],
    toRows: () =>
      deps
        .pickSelected(deps.filteredPurchaseOrders.value)
        .map((row) => [
          row.purchaseOrderId,
          row.refNo || '未填写',
          deps.supplierName(row.supplierId),
          deps.warehouseName(row.warehouseId),
          dictLabel('purchase_order_status', row.status)
        ])
  });

  const { onExport: exportReturns } = useListCsv({
    filePrefix: '采购退货',
    headers: ['退货单', '采购单', '供应商', '仓库', '状态', '创建时间'],
    toRows: () =>
      deps
        .pickSelected(deps.filteredPurchaseReturns.value)
        .map((row) => [
          row.returnId,
          row.purchaseOrderId,
          deps.supplierName(row.supplierId),
          deps.warehouseName(row.warehouseId),
          deps.returnStatusLabel(row.status),
          formatDateTime(row.createdAt)
        ])
  });

  const { onExport: exportOutbounds } = useListCsv({
    filePrefix: '出库单',
    headers: ['出库单', '路线', '出库仓库', '状态', '创建时间'],
    toRows: () =>
      deps
        .pickSelected(deps.filteredOutbounds.value)
        .map((row) => [
          row.outboundId,
          row.routeId || '',
          deps.warehouseName(row.warehouseId),
          dictLabel('warehouse_outbound_status', row.status),
          formatDateTime(row.createdAt)
        ])
  });

  const { onExport: exportTransit } = useListCsv({
    filePrefix: '在途',
    headers: [
      '出库单',
      '目标设备',
      '商品',
      '批次',
      '数量',
      '状态',
      '在途时长',
      '是否超时',
      '发运时间'
    ],
    toRows: () =>
      deps
        .pickSelected(deps.filteredInTransit.value)
        .map((row) => [
          row.outboundId,
          deps.deviceName(row.deviceId, row.deviceName),
          deps.skuName(row.skuId),
          row.batchNo || '',
          row.quantity,
          dictLabel('in_transit_status', row.status),
          deps.formatAge(deps.transitAgeMs(row)),
          deps.isTransitOverdue(row) ? '是' : '否',
          formatDateTime(row.createdAt)
        ])
  });

  const { onExport: exportInventory } = useListCsv({
    filePrefix: '批次库存',
    headers: ['仓库', '商品', '批次', '生产日期', '到期日期', '库存', '效期'],
    toRows: () =>
      deps
        .pickSelected(deps.inventory.value)
        .map((row) => [
          deps.warehouseName(row.warehouseId),
          deps.skuName(row.skuId),
          row.batchNo || '',
          row.productionDate || '',
          row.expiryDate || '',
          row.quantity,
          deps.expiryText(row.expiryDate)
        ])
  });

  const { onExport: exportMovements } = useListCsv({
    filePrefix: '库存流水',
    headers: ['流水', '类型', '商品', '批次', '变动', '关联业务', '关联单号', '时间'],
    toRows: () =>
      deps
        .pickSelected(deps.movements.value)
        .map((row) => [
          row.movementId,
          dictLabel('warehouse_movement_type', row.movementType),
          deps.skuName(row.skuId),
          row.batchNo || '',
          row.deltaQty,
          dictLabel('business_reference_type', row.refType),
          row.refId || '',
          formatDateTime(row.createdAt)
        ])
  });

  async function onExport() {
    const serverTabs = new Set([
      'warehouses',
      'suppliers',
      'purchase',
      'returns',
      'inventory',
      'outbounds',
      'movements'
    ]);
    const currentRows = (() => {
      switch (deps.tab.value) {
        case 'warehouses':
          return deps.warehouses.value;
        case 'suppliers':
          return deps.suppliers.value;
        case 'purchase':
          return deps.purchaseOrders.value;
        case 'returns':
          return deps.purchaseReturns.value;
        case 'inventory':
          return deps.inventory.value;
        case 'outbounds':
          return deps.outbounds.value;
        default:
          return [];
      }
    })();
    const partial =
      deps.selectedKeys.value.length > 0 && deps.selectedKeys.value.length < currentRows.length;
    if (partial || !serverTabs.has(deps.tab.value)) {
      const exporters: Record<string, () => void> = {
        warehouses: exportWarehouses,
        suppliers: exportSuppliers,
        purchase: exportPurchase,
        returns: exportReturns,
        outbounds: exportOutbounds,
        transit: exportTransit,
        inventory: exportInventory,
        movements: exportMovements
      };
      exporters[deps.tab.value]?.();
      return;
    }
    const labels: Record<string, string> = {
      warehouses: '仓库',
      suppliers: '供应商',
      purchase: '采购单',
      returns: '采购退货',
      inventory: '仓库库存',
      outbounds: '出库单',
      movements: '库存流水'
    };
    try {
      await downloadAuthFile(
        AdminEndpoints.warehouseExport(deps.tab.value),
        csvFileName(labels[deps.tab.value] || '仓库')
      );
      ElMessage.success('已导出');
    } catch (e) {
      ElMessage.error(errorMessage(e, '导出失败'));
    }
  }

  return {
    importing,
    warehouseImportInput,
    supplierImportInput,
    onWarehouseImportFile,
    onSupplierImportFile,
    onDownloadImportTemplate,
    triggerImport,
    onExport
  };
}
