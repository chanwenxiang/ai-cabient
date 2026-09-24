<template>
  <div class="page-fill warehouse-page">
    <el-card class="page-card report-page warehouse-page-card" shadow="never">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title">仓库</span>
              <span class="hint">{{ pageHint }}</span>
            </div>
          </div>
          <div class="page-card-head__actions">
            <el-button
              v-if="canEdit && tab === 'warehouses'"
              type="primary"
              @click="openWarehouse()"
              >新增仓库</el-button
            >
            <el-button
              v-if="canWarehouseEdit && tab === 'transfers'"
              type="primary"
              @click="openTransferCreate"
              >新建调拨</el-button
            >
            <el-button v-if="canEdit && tab === 'suppliers'" type="primary" @click="openSupplier()"
              >新增供应商</el-button
            >
            <el-button v-if="canEdit && tab === 'purchase'" type="primary" @click="openPurchase()"
              >新建采购单</el-button
            >
            <el-button
              v-if="canEdit && tab === 'suggestions'"
              type="primary"
              data-testid="create-purchase-from-suggestions"
              @click="openPurchaseFromSuggestions"
              >按建议生成采购单</el-button
            >
            <el-button
              v-if="canWarehouseEdit && tab === 'stocktakes'"
              type="primary"
              data-testid="create-stocktake"
              @click="openStocktakeCreate"
              >新建盘点</el-button
            >
            <el-button
              v-if="canWarehouseEdit && tab === 'bins'"
              type="primary"
              @click="openBinDialog()"
              >新增货位</el-button
            >
            <el-button
              v-if="canWarehouseEdit && tab === 'bins'"
              type="primary"
              plain
              @click="openBinInbound"
              >入库到货位</el-button
            >
            <el-button
              v-if="canWarehouseEdit && tab === 'bins'"
              type="primary"
              plain
              @click="openBinMove"
              >货位移库</el-button
            >
            <el-button v-if="canEdit && tab === 'returns'" type="primary" @click="openReturn()"
              >新建退货</el-button
            >
            <el-button
              v-if="canEdit && (tab === 'inventory' || tab === 'movements')"
              type="primary"
              @click="openInbound()"
              >其他入库</el-button
            >
            <el-button
              v-if="canEdit && tab === 'outbounds'"
              :loading="cleanupStaleLoading"
              data-testid="cleanup-stale-outbounds"
              @click="cleanupStaleOutbounds"
              >清理空草稿/脏在途</el-button
            >
            <el-button
              v-if="canImportMaster"
              v-hasPermi="['ops:warehouse:import']"
              @click="onDownloadImportTemplate"
              >导入模板</el-button
            >
            <el-button
              v-if="canImportMaster"
              v-hasPermi="['ops:warehouse:import']"
              :loading="importing"
              @click="triggerImport"
              >导入</el-button
            >
            <input
              ref="warehouseImportInput"
              type="file"
              accept=".csv,text/csv"
              class="hidden-input"
              @change="onWarehouseImportFile"
            />
            <input
              ref="supplierImportInput"
              type="file"
              accept=".csv,text/csv"
              class="hidden-input"
              @change="onSupplierImportFile"
            />
            <el-button v-hasPermi="['ops:warehouse:export']" @click="onExport">
              {{ selectedKeys.length ? `导出选中 (${selectedKeys.length})` : '导出' }}
            </el-button>
            <!-- 仓库主 tab 的刷新已内建 CrudTable 工具条；页头刷新仅服务其余 tab -->
            <el-button
              v-if="tab !== 'warehouses'"
              :icon="Refresh"
              :loading="isTabLoading(tab)"
              @click="reloadCurrent"
              >刷新</el-button
            >
          </div>
        </div>
      </template>

      <el-form v-if="showFilterBar" inline class="filter-bar filter-bar--compact" @submit.prevent>
        <el-form-item
          v-if="
            tab === 'inventory' ||
            tab === 'movements' ||
            tab === 'outbounds' ||
            tab === 'purchase' ||
            tab === 'suggestions' ||
            tab === 'bins' ||
            tab === 'returns'
          "
          label="仓库"
        >
          <el-select
            v-model="filterWarehouseId"
            clearable
            placeholder="全部仓库"
            style="width: 220px"
            @change="onWarehouseFilter"
          >
            <el-option
              v-for="w in warehouses"
              :key="w.warehouseId"
              :label="w.warehouseName"
              :value="w.warehouseId"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="tab === 'suppliers' || tab === 'purchase' || tab === 'returns'"
          label="关键词"
        >
          <el-input v-model="keyword" clearable placeholder="搜索关键词" style="width: 200px" />
        </el-form-item>
        <el-form-item v-if="tab === 'purchase'" label="列表">
          <el-checkbox
            v-model="hideTestPurchaseOrders"
            data-testid="purchase-hide-test-ref"
            @change="onPurchaseFilterChange"
            >隐藏 E2E/冒烟测试单</el-checkbox
          >
        </el-form-item>
        <el-form-item v-if="tab === 'suggestions'" label="采购前置期(天)">
          <el-input-number
            v-model="suggestionLeadTimeDays"
            :min="1"
            :max="60"
            size="small"
            controls-position="right"
            @change="onSuggestionParamsChange"
          />
        </el-form-item>
        <el-form-item v-if="tab === 'suggestions'" label="覆盖天数">
          <el-input-number
            v-model="suggestionCoverageDays"
            :min="1"
            :max="365"
            size="small"
            controls-position="right"
            @change="onSuggestionParamsChange"
          />
        </el-form-item>
        <el-form-item v-if="tab === 'payables'" label="状态">
          <el-select
            v-model="payableStatusFilter"
            clearable
            placeholder="全部"
            style="width: 140px"
            @change="onPayableFilter"
          >
            <el-option label="未付" value="UNPAID" />
            <el-option label="部分付款" value="PARTIAL" />
            <el-option label="已付" value="PAID" />
            <el-option label="已关闭" value="CLOSED" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="tab === 'payables'" label="逾期">
          <el-checkbox
            v-model="payableOverdueOnly"
            data-testid="payable-overdue-only"
            @change="onPayableFilter"
            >仅看逾期</el-checkbox
          >
        </el-form-item>
        <el-form-item v-if="tab === 'stocktakes'" label="状态">
          <el-select
            v-model="stocktakeStatusFilter"
            clearable
            placeholder="全部"
            style="width: 140px"
            @change="onStocktakeFilter"
          >
            <el-option label="草稿" value="DRAFT" />
            <el-option label="盘点中" value="IN_PROGRESS" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="已调整" value="ADJUSTED" />
            <el-option label="已取消" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="tab === 'bins'" label="货位">
          <el-select
            v-model="filterBinId"
            clearable
            placeholder="全部货位"
            style="width: 160px"
            @change="onBinFilter"
          >
            <el-option v-for="b in bins" :key="b.binId" :label="b.binCode" :value="b.binId" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="tab === 'outbounds'" label="状态">
          <el-select
            v-model="filterOutboundStatus"
            style="width: 160px"
            data-testid="outbound-status-filter"
            @change="onOutboundStatusFilter"
          >
            <el-option label="待处理" value="actionable" />
            <el-option label="全部" value="" />
            <el-option
              v-for="item in dictOptions('warehouse_outbound_status').filter((o) =>
                ['DRAFT', 'PICKED', 'SHIPPED'].includes(o.value)
              )"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="tab === 'transit' && focusDeviceId" label="设备">
          <el-tag closable type="info" @close="clearFocusDevice">{{ focusDeviceId }}</el-tag>
        </el-form-item>
        <el-form-item v-if="tab === 'transit'" label="超时筛选">
          <el-checkbox
            v-model="overdueOnly"
            data-testid="transit-overdue-only"
            @change="onOverdueToggle"
            >仅超时未到柜</el-checkbox
          >
        </el-form-item>
      </el-form>

      <el-alert
        v-if="tab === 'transit'"
        type="info"
        :closable="false"
        show-icon
        class="transit-flow-hint"
        data-testid="transit-flow-hint"
        title="在途 = 已发往柜机、尚未在柜完成补货。到柜由补货员完成任务后自动签收并上架；本页不办理回仓入库。"
      />

      <el-alert
        v-if="
          tab === 'transit' &&
          hydratedTabs.has('transit') &&
          !isTabLoading('transit') &&
          overdueTransitCount > 0
        "
        type="error"
        :closable="false"
        show-icon
        class="sla-banner"
        data-testid="transit-overdue-banner"
        :title="
          overdueOnly
            ? `共 ${overdueTransitCount} 条到柜超时（发运超 ${TRANSIT_OVERDUE_HOURS} 小时未完成补货签收）`
            : `共 ${overdueTransitCount} 条到柜超时，可勾选「仅超时未到柜」聚焦催办`
        "
      />

      <div
        v-if="visibleTabGroups.length"
        class="warehouse-tab-groups"
        data-testid="warehouse-tab-groups"
      >
        <el-radio-group v-model="tabGroup" size="small" @change="onTabGroupChange">
          <el-radio-button v-for="g in visibleTabGroups" :key="g.id" :value="g.id">
            {{ g.label }}
            <span class="tab-group-count">{{ g.count }}</span>
          </el-radio-button>
        </el-radio-group>
        <span class="tab-group-hint"
          >当前「{{ currentTabGroupLabel }}」· 共 {{ currentGroupTabCount }} 个列表</span
        >
      </div>

      <el-tabs v-model="tab" @tab-change="onTabChange">
        <el-tab-pane v-if="tabGroup === 'overview'" label="仓库概览" name="warehouses">
          <WarehouseOverviewTab
            :table="crud"
            :actions="warehouseRowActions"
            @action="onWarehouseAction"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="tabGroup === 'fulfillment' && canWarehouseList"
          label="仓间调拨"
          name="transfers"
        >
          <WarehouseTransfersTab
            :loading="isTabLoading('transfers')"
            :hydrated="hydratedTabs.has('transfers')"
            :rows="transfers"
            :can-warehouse-edit="canWarehouseEdit"
            :warehouse-name="warehouseName"
            :sku-name="skuName"
            :transfer-status-label="transferStatusLabel"
            @ship="shipTransfer"
            @receive="receiveTransfer"
            @cancel="cancelTransfer"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="tabGroup === 'procurement' && canProcurementList"
          label="供应商"
          name="suppliers"
        >
          <WarehouseSuppliersTab
            :loading="isTabLoading('suppliers')"
            :hydrated="hydratedTabs.has('suppliers')"
            :rows="pagedSuppliers"
            :can-edit="canEdit"
            :default-sort="supplierIdDefaultSort"
            @selection-change="onSelectionChange"
            @sort-change="onSupplierIdSortChange"
            @edit="openSupplier"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="tabGroup === 'procurement' && canProcurementList"
          label="采购单"
          name="purchase"
        >
          <WarehousePurchaseOrdersTab
            :loading="isTabLoading('purchase')"
            :hydrated="hydratedTabs.has('purchase')"
            :rows="pagedPurchaseOrders"
            :can-edit="canEdit"
            :can-procurement-list="canProcurementList"
            :sku-name="skuName"
            :supplier-name="supplierName"
            :warehouse-name="warehouseName"
            :money="money"
            :can-review-purchase-row="canReviewPurchaseRow"
            @selection-change="onSelectionChange"
            @print="(row) => openPrint('purchase', { purchaseOrderId: row.purchaseOrderId })"
            @review-approve="(row) => reviewPurchase(row, true)"
            @review-reject="(row) => reviewPurchase(row, false)"
            @receive="openReceive"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="tabGroup === 'procurement' && canProcurementList"
          label="采购建议"
          name="suggestions"
        >
          <WarehouseSuggestionsTab
            :loading="isTabLoading('suggestions')"
            :hydrated="hydratedTabs.has('suggestions')"
            :rows="pagedSuggestions"
            :sku-name="skuName"
            :suggestion-reason-text="suggestionReasonText"
            @selection-change="onSelectionChange"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="tabGroup === 'procurement' && canProcurementList"
          label="采购退货"
          name="returns"
        >
          <WarehousePurchaseReturnsTab
            :loading="isTabLoading('returns')"
            :hydrated="hydratedTabs.has('returns')"
            :rows="pagedPurchaseReturns"
            :sku-name="skuName"
            :supplier-name="supplierName"
            :warehouse-name="warehouseName"
            :return-status-label="returnStatusLabel"
            @selection-change="onSelectionChange"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="tabGroup === 'procurement' && canProcurementList"
          label="应付账款"
          name="payables"
        >
          <WarehousePayablesTab
            :loading="isTabLoading('payables')"
            :hydrated="hydratedTabs.has('payables')"
            :rows="pagedPayables"
            :summary-text="payableSummaryText"
            :can-edit="canEdit"
            :money="money"
            :payable-status-type="payableStatusType"
            :payable-status-text="payableStatusText"
            @selection-change="onSelectionChange"
            @pay="openPay"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="tabGroup === 'inventory' && canWarehouseList"
          label="盘点单"
          name="stocktakes"
        >
          <WarehouseStocktakesTab
            :loading="isTabLoading('stocktakes')"
            :hydrated="hydratedTabs.has('stocktakes')"
            :rows="pagedStocktakes"
            :can-warehouse-edit="canWarehouseEdit"
            :stocktake-mode-text="stocktakeModeText"
            :stocktake-status-type="stocktakeStatusType"
            :stocktake-status-text="stocktakeStatusText"
            @selection-change="onSelectionChange"
            @detail="openStocktakeDetail"
          />
        </el-tab-pane>

        <el-tab-pane v-if="tabGroup === 'inventory' && canWarehouseList" label="货位" name="bins">
          <WarehouseBinsTab
            :loading="isTabLoading('bins')"
            :hydrated="hydratedTabs.has('bins')"
            :bins="bins"
            :bin-stock="pagedBinStock"
            :can-warehouse-edit="canWarehouseEdit"
            @edit="openBinDialog"
          />
        </el-tab-pane>

        <el-tab-pane v-if="tabGroup === 'fulfillment'" label="出库单" name="outbounds">
          <WarehouseOutboundsTab
            :loading="isTabLoading('outbounds')"
            :hydrated="hydratedTabs.has('outbounds')"
            :rows="pagedOutbounds"
            :can-edit="canEdit"
            :sku-name="skuName"
            :warehouse-name="warehouseName"
            :device-name="deviceName"
            :outbound-row-class-name="outboundRowClassName"
            :outbound-secondary-actions="outboundSecondaryActions"
            @selection-change="onSelectionChange"
            @print="(row) => openPrint('picking', { outboundId: row.outboundId })"
            @pick="(row) => changeOutbound(row, 'pick')"
            @ship="(row) => changeOutbound(row, 'ship')"
            @cancel-unreceived="(row) => changeOutbound(row, 'cancel-unreceived')"
          />
        </el-tab-pane>

        <el-tab-pane v-if="tabGroup === 'fulfillment'" label="在途" name="transit">
          <WarehouseTransitTab
            :loading="isTabLoading('transit')"
            :hydrated="hydratedTabs.has('transit')"
            :rows="pagedInTransit"
            :empty-hint="transitEmptyHint"
            :row-key="transitRowKey"
            :row-class-name="transitRowClassName"
            :device-name="deviceName"
            :sku-name="skuName"
            :is-transit-overdue="isTransitOverdue"
            :is-transit-due-soon="isTransitDueSoon"
            :format-age="formatAge"
            :transit-age-ms="transitAgeMs"
            :transit-remain-ms="transitRemainMs"
            :transit-overdue-ms="transitOverdueMs"
            @selection-change="onSelectionChange"
          />
        </el-tab-pane>

        <el-tab-pane v-if="tabGroup === 'inventory'" label="批次库存" name="inventory">
          <WarehouseInventoryTab
            :loading="isTabLoading('inventory')"
            :hydrated="hydratedTabs.has('inventory')"
            :rows="pagedInventory"
            :row-key="inventoryRowKey"
            :warehouse-name="warehouseName"
            :sku-name="skuName"
            :expiry-type="expiryType"
            :expiry-text="expiryText"
            @selection-change="onSelectionChange"
          />
        </el-tab-pane>

        <el-tab-pane v-if="tabGroup === 'inventory'" label="库存流水" name="movements">
          <WarehouseMovementsTab
            :loading="isTabLoading('movements')"
            :hydrated="hydratedTabs.has('movements')"
            :rows="pagedMovements"
            :sku-name="skuName"
            @selection-change="onSelectionChange"
          />
        </el-tab-pane>
      </el-tabs>
      <!-- 仓库主 tab 的分页已内建 CrudTable；共享分页器仅服务其余 tab -->
      <PagePager
        v-if="tab !== 'warehouses'"
        :hydrated="hydratedTabs.has(tab)"
        v-model:current-page="page"
        v-model:page-size="size"
        :total="tabTotal"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="onPagerChange"
        @size-change="onPagerSizeChange"
      />

      <WarehouseEntityDialogs
        v-model:warehouse-dialog="warehouseDialog"
        v-model:supplier-dialog="supplierDialog"
        v-model:payment-dialog="paymentDialog"
        v-model:inbound-dialog="inboundDialog"
        :saving="saving"
        :dialog-boot-loading="dialogBootLoading"
        v-model:warehouse-form="warehouseForm"
        v-model:supplier-form="supplierForm"
        v-model:payment-form="paymentForm"
        :pay-target="payTarget"
        v-model:inbound-form="inboundForm"
        :pay-max-yuan="payMaxYuan"
        :active-warehouses="activeWarehouses"
        :skus="skus"
        @save-warehouse="saveWarehouse"
        @save-supplier="saveSupplier"
        @save-payment="savePayment"
        @save-inbound="saveInbound"
        @add-inbound-line="addInboundLine"
        @remove-inbound-line="removeInboundLine"
      />

      <WarehouseStocktakeDialogs
        v-model:stocktake-dialog="stocktakeDialog"
        v-model:stocktake-detail-dialog="stocktakeDetailDialog"
        :saving="saving"
        :scanning-photo="scanningPhoto"
        v-model:stocktake-form="stocktakeForm"
        :stocktake-detail="stocktakeDetail"
        :active-warehouses="activeWarehouses"
        :stocktake-mode-text="stocktakeModeText"
        :stocktake-status-text="stocktakeStatusText"
        :stocktake-status-type="stocktakeStatusType"
        :stocktake-line-status-text="stocktakeLineStatusText"
        :stocktake-line-status-type="stocktakeLineStatusType"
        @save-stocktake="saveStocktake"
        @save-stocktake-lines="saveStocktakeLines"
        @complete-stocktake="completeStocktakeAction"
        @cancel-stocktake="cancelStocktakeAction"
        @adjust-stocktake="adjustStocktakeAction"
        @stocktake-photo="onStocktakePhoto"
      />

      <WarehouseBinDialogs
        v-model:bin-dialog="binDialog"
        v-model:bin-inbound-dialog="binInboundDialog"
        v-model:bin-move-dialog="binMoveDialog"
        :saving="saving"
        v-model:bin-form="binForm"
        v-model:bin-inbound-form="binInboundForm"
        v-model:bin-move-form="binMoveForm"
        :active-warehouses="activeWarehouses"
        :skus="skus"
        :all-bins="allBins"
        :source-bin-skus="sourceBinSkus"
        :source-bin-max-qty="sourceBinMaxQty"
        :active-bins-for="activeBinsFor"
        :bin-label="binLabel"
        @save-bin="saveBin"
        @save-bin-inbound="saveBinInbound"
        @save-bin-move="saveBinMove"
        @bin-inbound-warehouse-change="onBinInboundWarehouse"
        @bin-move-source-change="onBinMoveSource"
      />

      <WarehousePurchaseDialogs
        v-model:purchase-dialog="purchaseDialog"
        v-model:receive-dialog="receiveDialog"
        v-model:return-dialog="returnDialog"
        :dialog-boot-loading="dialogBootLoading"
        :saving="saving"
        v-model:purchase-form="purchaseForm"
        v-model:purchase-field-errors="purchaseFieldErrors"
        v-model:receive-form="receiveForm"
        v-model:return-form="returnForm"
        :active-suppliers="activeSuppliers"
        :active-warehouses="activeWarehouses"
        :warehouses="warehouses"
        :skus="skus"
        :returnable-purchase-orders="returnablePurchaseOrders"
        :supplier-name="supplierName"
        :warehouse-name="warehouseName"
        :sku-name="skuName"
        @add-purchase-line="addPurchaseLine"
        @remove-purchase-line="removePurchaseLine"
        @clear-purchase-line-error="clearPurchaseLineError"
        @save-purchase="savePurchase"
        @receive-purchase="receivePurchase"
        @return-purchase="returnPurchase"
        @return-po-change="onReturnPoChange"
      />

      <WarehouseTransferDialogs
        v-model:transfer-dialog="transferDialog"
        :saving="saving"
        v-model:transfer-form="transferForm"
        :warehouses="warehouses"
        @save-transfer="saveTransfer"
      />

      <WarehouseOutboundDialogs
        :outbound-confirm="outboundConfirm"
        @update:visible="(v) => (outboundConfirm.visible = v)"
        @cancel="cancelOutboundConfirm"
        @submit="submitOutboundConfirm"
        @closed="onOutboundConfirmClosed"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, Refresh, RefreshLeft } from '@element-plus/icons-vue';
import { type CrudRowAction } from '@/components/CrudTable.vue';
import type { TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import WarehouseBinDialogs from '@/components/warehouse/WarehouseBinDialogs.vue';
import WarehouseEntityDialogs from '@/components/warehouse/WarehouseEntityDialogs.vue';
import WarehouseOutboundDialogs from '@/components/warehouse/WarehouseOutboundDialogs.vue';
import WarehousePurchaseDialogs from '@/components/warehouse/WarehousePurchaseDialogs.vue';
import WarehouseStocktakeDialogs from '@/components/warehouse/WarehouseStocktakeDialogs.vue';
import WarehouseTransferDialogs from '@/components/warehouse/WarehouseTransferDialogs.vue';

/** Tab 懒加载：压 WarehouseView 路由 chunk 进 ≤150KB 门禁（sync 时 151.7KB OVER） */
const WarehouseBinsTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseBinsTab.vue')
);
const WarehouseInventoryTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseInventoryTab.vue')
);
const WarehouseMovementsTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseMovementsTab.vue')
);
const WarehouseOutboundsTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseOutboundsTab.vue')
);
const WarehouseOverviewTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseOverviewTab.vue')
);
const WarehousePayablesTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehousePayablesTab.vue')
);
const WarehousePurchaseOrdersTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehousePurchaseOrdersTab.vue')
);
const WarehousePurchaseReturnsTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehousePurchaseReturnsTab.vue')
);
const WarehouseStocktakesTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseStocktakesTab.vue')
);
const WarehouseSuggestionsTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseSuggestionsTab.vue')
);
const WarehouseSuppliersTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseSuppliersTab.vue')
);
const WarehouseTransfersTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseTransfersTab.vue')
);
const WarehouseTransitTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseTransitTab.vue')
);
import { createLoadSeq } from '@/composables/createLoadSeq';
import { useCrudTable } from '@/composables/useCrudTable';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { useWarehouseBins } from '@/composables/warehouse/useWarehouseBins';
import { useWarehouseCsv } from '@/composables/warehouse/useWarehouseCsv';
import { useWarehouseEntityDialogs } from '@/composables/warehouse/useWarehouseEntityDialogs';
import { useWarehouseLabels } from '@/composables/warehouse/useWarehouseLabels';
import {
  TRANSIT_OVERDUE_HOURS,
  useWarehouseListFilters
} from '@/composables/warehouse/useWarehouseListFilters';
import { useWarehouseOutbounds } from '@/composables/warehouse/useWarehouseOutbounds';
import { useWarehousePurchaseOrders } from '@/composables/warehouse/useWarehousePurchaseOrders';
import {
  useWarehouseRouteLifecycle,
  type WarehouseTabGroup
} from '@/composables/warehouse/useWarehouseRouteLifecycle';
import { useWarehouseStocktakes } from '@/composables/warehouse/useWarehouseStocktakes';
import { useWarehouseTabLoader } from '@/composables/warehouse/useWarehouseTabLoader';
import { useWarehouseTransfers } from '@/composables/warehouse/useWarehouseTransfers';
import { useAuthStore } from '@/stores/auth';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';

const loadSeq = createLoadSeq();

/** 仓储动态行（D15：禁止散落 Record<string, any>） */
type Row = AdminDynamicRow;

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const canWarehouseEdit = computed(() => auth.hasPerm('ops:warehouse:edit'));
const canWarehouseList = computed(() => auth.hasPerm('ops:warehouse:list'));
const canProcurementEdit = computed(() => auth.hasPerm('ops:procurement:edit'));
const canProcurementList = computed(() => auth.hasPerm('ops:procurement:list'));
const canReviewPurchase = computed(
  () => canProcurementEdit.value || auth.hasPerm('ops:finance:view')
);
function canReviewPurchaseRow(row: Row) {
  if (!canReviewPurchase.value || row.status !== 'PENDING_APPROVAL') return false;
  // 仅当前节点处理人可见通过/驳回，避免超管误点触发 403 且无 toast（IMP-016）
  return row.approvalPendingForMe === true;
}
const canEdit = computed(() => {
  if (['suppliers', 'purchase', 'returns', 'suggestions'].includes(tab.value))
    return canProcurementEdit.value;
  return canWarehouseEdit.value;
});
const canImportMaster = computed(() => tab.value === 'warehouses' || tab.value === 'suppliers');
const selectedKeys = ref<Array<string | number>>([]);

function rowKeyOf(row: Row): string | number {
  switch (tab.value) {
    case 'warehouses':
      return row.warehouseId;
    case 'suppliers':
      return row.supplierId;
    case 'purchase':
      return row.purchaseOrderId;
    case 'returns':
      return row.returnId;
    case 'suggestions':
      return row.skuId;
    case 'payables':
      return row.payableId;
    case 'stocktakes':
      return row.stocktakeId;
    case 'bins':
      return row.id ?? row.binId;
    case 'outbounds':
      return row.outboundId;
    case 'transit':
      return transitRowKey(row);
    case 'inventory':
      return inventoryRowKey(row);
    case 'movements':
      return row.movementId;
    default:
      return row.warehouseId;
  }
}
function transitRowKey(row: Row) {
  return `${row.outboundId || ''}|${row.deviceId || ''}|${row.skuId || ''}|${row.batchNo || ''}`;
}
function inventoryRowKey(row: Row) {
  return `${row.warehouseId || ''}|${row.skuId || ''}|${row.batchNo || ''}|${row.expiryDate || ''}`;
}
function onSelectionChange(rows: Row[]) {
  selectedKeys.value = rows.map((r) => rowKeyOf(r)).filter((k) => k != null && k !== '');
}
function pickSelected<T extends Row>(all: T[]): T[] {
  if (!selectedKeys.value.length) return all;
  const set = new Set(selectedKeys.value.map(String));
  return all.filter((r) => set.has(String(rowKeyOf(r))));
}
function statusCode(raw: string | undefined, fallback = 'ACTIVE') {
  const v = (raw || '').trim();
  if (!v) return fallback;
  const upper = v.toUpperCase();
  if (['ACTIVE', 'INACTIVE', 'ENABLED', 'DISABLED'].includes(upper)) {
    if (upper === 'ENABLED') return 'ACTIVE';
    if (upper === 'DISABLED') return 'INACTIVE';
    return upper;
  }
  if (
    v === displayLabel('enable_status', 'ACTIVE') ||
    v === displayLabel('warehouse_status', 'ACTIVE')
  ) {
    return 'ACTIVE';
  }
  if (
    v === displayLabel('enable_status', 'INACTIVE') ||
    v === displayLabel('warehouse_status', 'INACTIVE') ||
    v === '禁用'
  ) {
    return 'INACTIVE';
  }
  return fallback;
}

const loadingTabs = ref(new Set<string>());
const hydratedTabs = ref(new Set<string>());
function isTabLoading(name: string) {
  return loadingTabs.value.has(name);
}
const saving = ref(false);
const tab = ref('warehouses');

/** loadTab / syncRouteQuery 在 loader、route lifecycle 之后赋值 */
const loadTabHolder: { fn: (name: string, force?: boolean) => Promise<void> } = {
  fn: async () => undefined
};
const syncRouteQueryHolder: { fn: (nextTab?: string) => void } = {
  fn: () => undefined
};

const TAB_GROUP_MAP: Record<string, WarehouseTabGroup> = {
  warehouses: 'overview',
  suppliers: 'procurement',
  purchase: 'procurement',
  suggestions: 'procurement',
  returns: 'procurement',
  payables: 'procurement',
  stocktakes: 'inventory',
  bins: 'inventory',
  inventory: 'inventory',
  movements: 'inventory',
  transfers: 'fulfillment',
  outbounds: 'fulfillment',
  transit: 'fulfillment'
};

const TAB_GROUP_ORDER: Record<WarehouseTabGroup, string[]> = {
  overview: ['warehouses'],
  procurement: ['suppliers', 'purchase', 'suggestions', 'returns', 'payables'],
  inventory: ['stocktakes', 'bins', 'inventory', 'movements'],
  fulfillment: ['transfers', 'outbounds', 'transit']
};

const tabGroup = ref<WarehouseTabGroup>('overview');

function tabGroupFor(name: string): WarehouseTabGroup {
  return TAB_GROUP_MAP[name] || 'overview';
}

function isWarehouseTabAllowed(name: string): boolean {
  if (['suppliers', 'purchase', 'suggestions', 'returns', 'payables'].includes(name)) {
    return canProcurementList.value;
  }
  if (['transfers', 'stocktakes', 'bins'].includes(name)) {
    return canWarehouseList.value;
  }
  return true;
}

function firstTabInGroup(group: WarehouseTabGroup): string {
  for (const name of TAB_GROUP_ORDER[group]) {
    if (isWarehouseTabAllowed(name)) return name;
  }
  return 'warehouses';
}

const TAB_GROUP_LABELS: Record<WarehouseTabGroup, string> = {
  overview: '基础',
  procurement: '采购',
  inventory: '库存',
  fulfillment: '履约'
};

function allowedTabsInGroup(group: WarehouseTabGroup): string[] {
  return TAB_GROUP_ORDER[group].filter((name) => isWarehouseTabAllowed(name));
}

const visibleTabGroups = computed(() => {
  const groups: WarehouseTabGroup[] = ['overview', 'procurement', 'inventory', 'fulfillment'];
  return groups
    .map((id) => {
      const tabs = allowedTabsInGroup(id);
      return { id, label: TAB_GROUP_LABELS[id], count: tabs.length };
    })
    .filter((g) => g.count > 0);
});

const currentTabGroupLabel = computed(
  () => TAB_GROUP_LABELS[tabGroup.value] || TAB_GROUP_LABELS.overview
);
const currentGroupTabCount = computed(() => allowedTabsInGroup(tabGroup.value).length);

function syncTabGroupFromTab(name = tab.value) {
  tabGroup.value = tabGroupFor(name);
}

function onTabGroupChange(group: WarehouseTabGroup) {
  if (tabGroupFor(tab.value) === group) return;
  const next = firstTabInGroup(group);
  tab.value = next;
  page.value = 1;
  if (next !== 'transit') {
    overdueOnly.value = false;
    focusDeviceId.value = '';
  }
  syncRouteQueryHolder.fn(next);
  loadTabHolder.fn(next);
}
const SERVER_PAGINATED_TABS = new Set([
  'warehouses',
  'suppliers',
  'purchase',
  'returns',
  'suggestions',
  'payables',
  'stocktakes',
  'bins',
  'outbounds',
  'transit',
  'transfers',
  'inventory',
  'movements'
]);
const tabTotals = ref<Record<string, number>>({});
const page = ref(1);
const size = ref(20);
const keyword = ref('');
const HIDE_TEST_PO_KEY = 'admin_warehouse_hide_test_po';
const hideTestPurchaseOrders = ref(localStorage.getItem(HIDE_TEST_PO_KEY) !== '0');
const filterWarehouseId = ref('');
/** 默认「待处理」：有明细的 DRAFT + PICKED，避免历史草稿淹没操作列 */
const filterOutboundStatus = ref<string>('actionable');
const overdueOnly = ref(false);
const focusDeviceId = ref('');
const warehouses = ref<Row[]>([]);
const suppliers = ref<Row[]>([]);
/** loadWarehouses 由 useWarehouseTabLoader 提供（setup 后半段赋值）；crud.fetchPage 委托它，避免重复拼装查询 */
const loadWarehousesHolder: { fn: () => Promise<void> } = { fn: async () => undefined };

// 仓库主列表状态机：分页/排序/多选/竞态收口到 CrudTable。
// autoLoad:false —— 首查由路由生命周期 loadTab('warehouses', true) 触发（经 loadTab 桥接转发 crud.load），
// 与深链 / keep-alive 激活刷新共用同一入口，避免双重首查。
const crud = useCrudTable<Row>({
  rowKey: (r) => r.warehouseId,
  autoLoad: false,
  sort: { prop: 'warehouseId', mode: 'local' },
  errorMessage: '加载失败',
  fetchPage: async (params) => {
    page.value = params.page + 1; // 与多 tab 共用的 page/size 保持同步（其余 tab 与页头按钮仍读这两个 ref）
    size.value = params.size;
    await loadWarehousesHolder.fn();
    return { items: warehouses.value, total: tabTotals.value.warehouses || 0 };
  }
});

// 仓库主列表勾选已迁 CrudTable：同步回页面级 selectedKeys，页头「导出选中 (N)」与 useWarehouseCsv 继续复用
watch(
  () => [...crud.selectedKeys],
  (keys) => {
    if (tab.value === 'warehouses') selectedKeys.value = keys;
  }
);
const {
  defaultSort: supplierIdDefaultSort,
  onSortChange: onSupplierIdSortChange,
  sortById: sortSuppliersById
} = useIdColumnSort<Row>('supplierId');

const purchaseOrders = ref<Row[]>([]);
const purchaseReturns = ref<Row[]>([]);
const transfers = ref<Row[]>([]);
const outbounds = ref<Row[]>([]);
const inTransit = ref<Row[]>([]);
const inventory = ref<Row[]>([]);
const movements = ref<Row[]>([]);
const suggestions = ref<Row[]>([]);
const suggestionLeadTimeDays = ref(2);
const suggestionCoverageDays = ref(14);
const payables = ref<Row[]>([]);
const payableSummary = ref<Row[]>([]);
const payableStatusFilter = ref('');
const payableOverdueOnly = ref(false);
const stocktakes = ref<Row[]>([]);
const stocktakeStatusFilter = ref('');
const bins = ref<Row[]>([]);
const binStock = ref<Row[]>([]);
const filterBinId = ref<number | null>(null);
const devices = ref<Row[]>([]);
const skus = ref<Row[]>([]);
const loadedTabs = ref(new Set<string>(['warehouses']));

const dialogBootLoading = ref(false);

const {
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
  expiryText,
  expiryType
} = useWarehouseLabels({
  suppliers,
  warehouses,
  devices,
  skus,
  router
});

const pageHint = computed(() => {
  if (tab.value === 'transit') {
    return `仓→柜在途：补货完成才从本列表消失；时限 ${TRANSIT_OVERDUE_HOURS} 小时，超时标红`;
  }
  return '仓库 / 供应商 / 库存 / 采购与退货';
});

const {
  showFilterBar,
  activeSuppliers,
  activeWarehouses,
  filteredSuppliers,
  filteredPurchaseOrders,
  filteredPurchaseReturns,
  filteredOutbounds,
  outboundRowClassName,
  onOutboundStatusFilter,
  transitAgeMs,
  transitRemainMs,
  transitOverdueMs,
  isTransitOverdue,
  isTransitDueSoon,
  formatAge,
  transitRowClassName,
  filteredInTransit,
  overdueTransitCount,
  transitEmptyHint,
  onOverdueToggle,
  clearFocusDevice,
  pagedOutbounds,
  pagedInTransit,
  onWarehouseFilter,
  onPurchaseFilterChange,
  onSuggestionParamsChange,
  onPayableFilter,
  onStocktakeFilter,
  onBinFilter
} = useWarehouseListFilters({
  tab,
  page,
  selectedKeys,
  keyword,
  filterWarehouseId,
  filterOutboundStatus,
  overdueOnly,
  focusDeviceId,
  suppliers,
  warehouses,
  purchaseOrders,
  purchaseReturns,
  outbounds,
  inTransit,
  hideTestPurchaseOrders,
  hideTestPoKey: HIDE_TEST_PO_KEY,
  syncRouteQuery: (next) => syncRouteQueryHolder.fn(next),
  loadTab: (name, force) => loadTabHolder.fn(name, force),
  loadedTabs,
  serverPaginatedTabs: SERVER_PAGINATED_TABS,
  supplierName
});

const returnablePurchaseOrders = ref<Row[]>([]);

const tabSource = computed(() => {
  switch (tab.value) {
    case 'warehouses':
      return warehouses.value;
    case 'suppliers':
      return suppliers.value;
    case 'purchase':
      return filteredPurchaseOrders.value;
    case 'returns':
      return filteredPurchaseReturns.value;
    case 'suggestions':
      return suggestions.value;
    case 'payables':
      return payables.value;
    case 'stocktakes':
      return stocktakes.value;
    case 'bins':
      return binStock.value;
    case 'outbounds':
      return filteredOutbounds.value;
    case 'transit':
      return filteredInTransit.value;
    case 'transfers':
      return transfers.value;
    case 'inventory':
      return inventory.value;
    case 'movements':
      return movements.value;
    default:
      return warehouses.value;
  }
});
const tabTotal = computed(() => {
  if (SERVER_PAGINATED_TABS.has(tab.value)) {
    return tabTotals.value[tab.value] || 0;
  }
  return tabSource.value.length;
});
const pagedSuppliers = computed(() => sortSuppliersById(suppliers.value));
const pagedPurchaseOrders = computed(() => purchaseOrders.value);
const pagedPurchaseReturns = computed(() => purchaseReturns.value);
const pagedSuggestions = computed(() => suggestions.value);
const pagedPayables = computed(() => payables.value);
const pagedStocktakes = computed(() => stocktakes.value);
const pagedBinStock = computed(() => binStock.value);
const pagedInventory = computed(() => inventory.value);
const pagedMovements = computed(() => movements.value);
const payableSummaryText = computed(() => {
  const rows = payableSummary.value;
  if (!rows.length) return '暂无未结清应付账款';
  const total = rows.reduce((s, r) => s + (Number(r.totalBalanceCents) || 0), 0);
  const overdue = rows.reduce((s, r) => s + (Number(r.overdueBalanceCents) || 0), 0);
  return `共 ${rows.length} 家供应商有欠款，未付合计 ¥${money(total)}，其中逾期 ¥${money(overdue)}`;
});

watch(tab, () => {
  page.value = 1;
  selectedKeys.value = [];
  crud.clearSelection(); // 仓库主列表勾选随切 tab 清空（与原共用勾选行为一致）
});
watch([keyword, filterWarehouseId, focusDeviceId], () => {
  page.value = 1;
  if (SERVER_PAGINATED_TABS.has(tab.value)) {
    loadedTabs.value.delete(tab.value);
    loadTabHolder.fn(tab.value, true);
  }
});
watch(overdueOnly, () => {
  page.value = 1;
});

const {
  importing,
  warehouseImportInput,
  supplierImportInput,
  onWarehouseImportFile,
  onSupplierImportFile,
  onDownloadImportTemplate,
  triggerImport,
  onExport
} = useWarehouseCsv({
  tab,
  selectedKeys,
  loadedTabs,
  loadTab: (name, force) => loadTabHolder.fn(name, force),
  warehouses,
  suppliers,
  purchaseOrders,
  purchaseReturns,
  outbounds,
  inventory,
  movements,
  filteredSuppliers,
  filteredPurchaseOrders,
  filteredPurchaseReturns,
  filteredOutbounds,
  filteredInTransit,
  pickSelected,
  statusCode,
  supplierName,
  warehouseName,
  deviceName,
  skuName,
  returnStatusLabel,
  expiryText,
  formatAge,
  transitAgeMs,
  isTransitOverdue
});

/** 仓库主列表行操作（CrudTable rowActions）：perm 与原 canEdit（warehouses tab）等价 */
function warehouseRowActions(_row: Row): CrudRowAction[] {
  return [
    { key: 'edit', label: '编辑', icon: EditPen, type: 'primary', perm: 'ops:warehouse:edit' }
  ];
}

function onWarehouseAction({ key, row }: { key: string; row: Row }) {
  if (key === 'edit') openWarehouse(row);
}

function outboundSecondaryActions(row: Row): TableAction[] {
  const acts: TableAction[] = [];
  const hasLines = (row.lines?.length || 0) > 0;
  if (['DRAFT', 'PICKED', 'SHIPPED'].includes(String(row.status || '')) && hasLines) {
    const handed = (row.lines || []).some((l: Row) =>
      ['RECEIVED', 'PARTIAL'].includes(String(l.handoverStatus || ''))
    );
    if (!handed) {
      acts.push({
        key: 'cancel-unreceived',
        label: row.status === 'SHIPPED' ? '作废回仓' : '作废出库',
        icon: RefreshLeft,
        type: 'warning'
      });
    }
  }
  return acts;
}

const {
  ensureMeta,
  loadWarehouses,
  loadWarehousesSoft,
  loadSuppliersSoft,
  loadPurchase,
  loadTab: loadTabRaw
} = useWarehouseTabLoader({
  loadSeq,
  hasDeviceListPerm: () => auth.hasPerm('ops:device:list') || auth.hasPerm('ops:device:ref'),
  page,
  size,
  keyword,
  filterWarehouseId,
  hideTestPurchaseOrders,
  focusDeviceId,
  suggestionLeadTimeDays,
  suggestionCoverageDays,
  payableStatusFilter,
  payableOverdueOnly,
  stocktakeStatusFilter,
  filterBinId,
  warehouses,
  suppliers,
  purchaseOrders,
  returnablePurchaseOrders,
  purchaseReturns,
  outbounds,
  inTransit,
  inventory,
  movements,
  suggestions,
  payables,
  payableSummary,
  stocktakes,
  bins,
  binStock,
  transfers,
  devices,
  skus,
  tabTotals,
  loadedTabs,
  loadingTabs,
  hydratedTabs
});
loadWarehousesHolder.fn = loadWarehouses;

/**
 * 仓库主列表已迁 CrudTable：warehouses tab 的加载改走 crud 状态机（fetchPage 委托 loadWarehouses），
 * 其余 tab 原样走 useWarehouseTabLoader。缓存语义与原 loadTab 一致：非 force 且已加载则跳过。
 */
async function loadTab(name: string, force = false): Promise<void> {
  if (name !== 'warehouses') return loadTabRaw(name, force);
  if (!force && loadedTabs.value.has('warehouses')) return;
  await crud.load();
  loadedTabs.value.add('warehouses');
}
loadTabHolder.fn = loadTab;

const {
  purchaseDialog,
  receiveDialog,
  returnDialog,
  purchaseForm,
  purchaseFieldErrors,
  receiveForm,
  returnForm,
  clearPurchaseLineError,
  patchPurchaseOrderRow,
  openPurchase,
  openPurchaseFromSuggestions,
  addPurchaseLine,
  removePurchaseLine,
  savePurchase,
  reviewPurchase,
  openReceive,
  receivePurchase,
  openReturn,
  onReturnPoChange,
  returnPurchase
} = useWarehousePurchaseOrders({
  saving,
  dialogBootLoading,
  tab,
  loadedTabs,
  loadTab,
  purchaseOrders,
  returnablePurchaseOrders,
  suggestions,
  skus,
  filterWarehouseId,
  suggestionCoverageDays,
  activeSuppliers,
  activeWarehouses,
  pickSelected,
  loadSuppliersSoft,
  loadWarehousesSoft,
  loadPurchase,
  ensureMeta
});

const { syncRouteQuery, onPagerChange, onPagerSizeChange, onTabChange, reloadCurrent } =
  useWarehouseRouteLifecycle({
    route,
    router,
    tab,
    tabGroup,
    page,
    overdueOnly,
    focusDeviceId,
    loadedTabs,
    serverPaginatedTabs: SERVER_PAGINATED_TABS,
    syncTabGroupFromTab,
    tabGroupFor,
    loadTab,
    patchPurchaseOrderRow
  });
syncRouteQueryHolder.fn = syncRouteQuery;

const {
  stocktakeDialog,
  stocktakeDetailDialog,
  scanningPhoto,
  stocktakeForm,
  stocktakeDetail,
  openStocktakeCreate,
  saveStocktake,
  openStocktakeDetail,
  onStocktakePhoto,
  saveStocktakeLines,
  completeStocktakeAction,
  adjustStocktakeAction,
  cancelStocktakeAction
} = useWarehouseStocktakes({
  saving,
  loadedTabs,
  loadTab,
  activeWarehouses,
  loadWarehousesSoft
});

const {
  binDialog,
  binInboundDialog,
  binMoveDialog,
  binForm,
  binInboundForm,
  binMoveForm,
  allBins,
  sourceBinSkus,
  sourceBinMaxQty,
  activeBinsFor,
  binLabel,
  openBinDialog,
  saveBin,
  onBinInboundWarehouse,
  openBinInbound,
  saveBinInbound,
  onBinMoveSource,
  openBinMove,
  saveBinMove
} = useWarehouseBins({
  saving,
  loadedTabs,
  loadTab,
  bins,
  binStock,
  skus,
  filterWarehouseId,
  activeWarehouses,
  loadWarehousesSoft,
  ensureMeta
});

const {
  transferDialog,
  transferForm,
  openTransferCreate,
  saveTransfer,
  shipTransfer,
  receiveTransfer,
  cancelTransfer
} = useWarehouseTransfers({
  saving,
  loadedTabs,
  loadTab,
  warehouses,
  loadWarehousesSoft
});

const {
  cleanupStaleLoading,
  outboundConfirm,
  changeOutbound,
  cancelOutboundConfirm,
  onOutboundConfirmClosed,
  submitOutboundConfirm,
  cleanupStaleOutbounds
} = useWarehouseOutbounds({
  loadedTabs,
  loadTab
});

const {
  warehouseDialog,
  supplierDialog,
  paymentDialog,
  inboundDialog,
  warehouseForm,
  supplierForm,
  paymentForm,
  payTarget,
  inboundForm,
  payMaxYuan,
  openWarehouse,
  saveWarehouse,
  openSupplier,
  saveSupplier,
  openPay,
  savePayment,
  addInboundLine,
  removeInboundLine,
  openInbound,
  saveInbound
} = useWarehouseEntityDialogs({
  saving,
  dialogBootLoading,
  tab,
  loadedTabs,
  loadTab,
  filterWarehouseId,
  activeWarehouses,
  skus,
  loadWarehousesSoft,
  ensureMeta
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.warehouse-tab-groups {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 16px;
  margin: 0 0 12px;
  flex-shrink: 0;
}
.transit-flow-hint {
  margin-bottom: 12px;
  flex-shrink: 0;
}
.tab-group-count {
  margin-left: 4px;
  font-size: var(--admin-font-size-sm);
  opacity: 0.72;
}
.tab-group-hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.warehouse-page-card {
  min-height: 0;
}
.warehouse-page :deep(.el-tabs) {
  display: flex;
  flex-direction: column;
  /* 折叠侧栏/窄视口时保证 Tab 内容区仍占可视高度，避免「空页」感（IMP-002） */
  min-height: min(560px, calc(100svh - 240px));
}
.warehouse-page :deep(.el-tabs__content) {
  flex: 1 1 auto;
  min-height: 0;
}
.warehouse-page :deep(.el-tab-pane) {
  min-height: 200px;
}
.warehouse-tab-groups :deep(.el-radio-button__inner) {
  padding: 8px 14px;
}
.page-card-head__meta {
  min-width: 0;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.title {
  font-weight: 600;
  font-size: var(--admin-font-size-title);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}

.line-table {
  margin: 0;
  width: 100% !important;
}
.expand-panel {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  padding: 10px 12px 12px;
  overflow-x: auto;
}
.expand-panel .line-table {
  width: 100% !important;
  min-width: 100%;
}
.hidden-input {
  display: none;
}
.outbound-id-cell {
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
:deep(.outbound-row--actionable) > td {
  background: color-mix(in srgb, var(--app-primary, #0f766e) 8%, transparent);
}
:deep(.el-table .is-overdue > td.el-table__cell) {
  background: color-mix(
    in srgb,
    var(--el-color-danger) 6%,
    var(--el-table-bg-color, #fff)
  ) !important;
}
:deep(.el-table .is-due-soon > td.el-table__cell) {
  background: color-mix(
    in srgb,
    var(--el-color-warning) 7%,
    var(--el-table-bg-color, #fff)
  ) !important;
}
:deep(.el-table .is-focus > td.el-table__cell) {
  outline: 1px solid color-mix(in srgb, var(--app-primary, #0f766e) 35%, transparent);
}
.sla-banner {
  margin: 0 0 12px;
}
.sla-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
}
.sla-meta {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-xs);
}
.sla-meta.danger {
  color: var(--el-color-danger);
}
.cell-datetime {
  font-variant-numeric: tabular-nums;
}
.muted,
.tip {
  color: var(--layout-muted);
  font-size: var(--admin-font-size-table);
}
.tip {
  margin: 0 0 8px;
}
.positive {
  color: #059669;
  font-weight: 700;
}
.negative {
  color: #dc2626;
  font-weight: 700;
}
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

@media (max-width: 900px) {
  .form-grid,
  .line-grid {
    grid-template-columns: 1fr;
  }
}
</style>
