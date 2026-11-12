<template>
  <div class="page-fill warehouse-page">
    <el-card class="page-card report-page warehouse-page-card" shadow="never">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title"
                >{{ pageTitle
                }}<span v-if="tabDescription" data-testid="warehouse-tab-desc"
                  ><HelpTip :content="tabDescription" aria-label="本页说明" /></span
              ></span>
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
            <!--
              V313 仓库侧报损：此前仓库里的破损/过期/丢失**没有核销入口**，
              只能走盘点改账、无法记录「为什么损」。
              🔴 刻意用danger 样式—— 报损不可逆（数量真扣），
              而「其他入库」是可逆的补录，两者心理预期不同。
            -->
            <el-button
              v-if="canReplenishEdit && tab === 'inventory'"
              type="danger"
              plain
              data-testid="warehouse-write-off"
              @click="openWriteOff()"
              >批次报损</el-button
            >
            <el-button
              v-if="canEdit && tab === 'outbounds'"
              :loading="cleanupStaleLoading"
              data-testid="cleanup-stale-outbounds"
              @click="cleanupStaleOutbounds"
              >清理空草稿/未结领货</el-button
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
            <el-button
              v-if="WAREHOUSE_EXPORTABLE_TABS.has(tab)"
              v-hasPermi="['ops:warehouse:export']"
              @click="onExport"
            >
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
            tab === 'monthly' ||
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
            :clearable="!warehouseFilterRequired"
            :placeholder="warehouseFilterRequired ? '请选择仓库' : '全部仓库'"
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
        <el-form-item v-if="tab === 'monthly'" label="月份">
          <input
            v-model="monthlyCloseYearMonth"
            class="native-date"
            type="month"
            @change="onWarehouseFilter"
          />
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
          <!-- P3-10（2026-11-12）：应付账款的 UNPAID 是「未付」，与账单族的「待付」
               语义不同（我方尚未付款 vs 等对方付款），不是文案漂移；但同族下拉统一改字典驱动防漂移。 -->
          <el-select
            v-model="payableStatusFilter"
            clearable
            placeholder="全部"
            style="width: 140px"
            @change="onPayableFilter"
          >
            <el-option
              v-for="item in payableStatusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
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

      <div v-if="tab === 'transit'" class="rule-help" data-testid="transit-flow-hint">
        <span>已领未上柜口径</span>
        <HelpTip
          content="已领未上柜 = 补货员已从分仓领出、尚未在柜确认上柜。到柜由补货员完成任务后自动入账并结清；本页不办理回仓入库。"
          aria-label="已领未上柜口径"
        />
      </div>

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
            ? `共 ${overdueTransitCount} 条到柜超时（领货超 ${TRANSIT_OVERDUE_HOURS} 小时未完成上柜）`
            : `共 ${overdueTransitCount} 条到柜超时，可勾选「仅超时未到柜」聚焦催办`
        "
      />

      <!-- D28-B7：请求失败 ≠ 没有数据。失败时下表的空态文案会把人骗成「这个仓是空的」，
           而真实原因是 5xx/超时（处置完全不同：一个去查服务，一个可以放一放）。 -->
      <el-alert
        v-if="failedTabs.has(tab)"
        type="error"
        :closable="false"
        show-icon
        class="tab-load-failed"
        data-testid="warehouse-tab-load-failed"
        title="这一页没加载成功——下方的空表不是「没有数据」，是请求失败了。"
      >
        <el-button link type="primary" :loading="isTabLoading(tab)" @click="reloadCurrent"
          >重试</el-button
        >
      </el-alert>

      <div
        v-if="!props.only && visibleTabGroups.length"
        class="warehouse-tab-groups"
        data-testid="warehouse-tab-groups"
      >
        <el-radio-group v-model="tabGroup" size="small" @change="onTabGroupChange">
          <el-radio-button v-for="g in visibleTabGroups" :key="g.id" :value="g.id">
            {{ g.label }}
            <span class="tab-group-count" :title="'本组含 ' + g.count + ' 个列表（非数据条数）'"
              >{{ g.count }}个列表</span
            >
          </el-radio-button>
        </el-radio-group>
        <span class="tab-group-hint"
          >当前「{{ currentTabGroupLabel }}」·
          {{ currentGroupTabCount }}个列表（角标不是库存/单据条数）</span
        >
      </div>

      <div
        v-if="props.only?.length && warehouseSiblings.length > 1"
        class="warehouse-siblings"
        data-testid="warehouse-sibling-nav"
      >
        <router-link
          v-for="n in warehouseSiblings"
          :key="n.path"
          :to="n.path"
          class="warehouse-siblings__item"
          :class="{ active: route.path === n.path }"
          >{{ n.title }}</router-link
        >
      </div>

      <el-empty
        v-if="onlyNoVisibleTabs"
        description="当前账号无本页内容的查看权限，请联系管理员开通"
        data-testid="warehouse-empty-perm"
      />

      <el-tabs v-if="!onlyNoVisibleTabs" v-model="tab" @tab-change="onTabChange">
        <el-tab-pane v-if="isPaneVisible('warehouses')" label="仓库概览" name="warehouses">
          <WarehouseOverviewTab
            :table="crud"
            :actions="warehouseRowActions"
            :manager-label="warehouseManagerLabel"
            @action="onWarehouseAction"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="isPaneVisible('transfers') && canWarehouseList"
          label="仓间调拨"
          name="transfers"
        >
          <WarehouseTransfersTab
            :loading="isTabLoading('transfers')"
            :hydrated="hydratedTabs.has('transfers')"
            :saving="saving"
            :rows="transfers"
            :can-warehouse-edit="canWarehouseEdit"
            :warehouse-name="warehouseName"
            :sku-name="skuName"
            :transfer-status-label="transferStatusLabel"
            @ship="shipTransfer"
            @receive="openTransferReceive"
            @cancel="cancelTransfer"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="isPaneVisible('suppliers') && canProcurementList"
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
          v-if="isPaneVisible('purchase') && canProcurementList"
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
            :can-review-purchase-row="canReviewPurchaseRow"
            @selection-change="onSelectionChange"
            @print="(row) => openPrint('purchase', { purchaseOrderId: row.purchaseOrderId })"
            @review-approve="(row) => reviewPurchase(row, true)"
            @review-reject="(row) => reviewPurchase(row, false)"
            @receive="openReceive"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="isPaneVisible('suggestions') && canProcurementList"
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
          v-if="isPaneVisible('returns') && canProcurementList"
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
          v-if="isPaneVisible('payables') && canProcurementList"
          label="应付账款"
          name="payables"
        >
          <WarehousePayablesTab
            :loading="isTabLoading('payables')"
            :hydrated="hydratedTabs.has('payables')"
            :rows="pagedPayables"
            :summary-text="payableSummaryText"
            :can-edit="canEdit"
            :payable-status-type="payableStatusType"
            :payable-status-text="payableStatusText"
            @selection-change="onSelectionChange"
            @pay="openPay"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="isPaneVisible('stocktakes') && canWarehouseList"
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

        <el-tab-pane v-if="isPaneVisible('bins') && canWarehouseList" label="货位" name="bins">
          <WarehouseBinsTab
            :loading="isTabLoading('bins')"
            :hydrated="hydratedTabs.has('bins')"
            :bins="bins"
            :bin-stock="pagedBinStock"
            :can-warehouse-edit="canWarehouseEdit"
            @edit="openBinDialog"
          />
        </el-tab-pane>

        <el-tab-pane v-if="isPaneVisible('outbounds')" label="出库单" name="outbounds">
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

        <el-tab-pane v-if="isPaneVisible('transit')" label="已领未上柜" name="transit">
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

        <el-tab-pane v-if="isPaneVisible('inventory')" label="批次库存" name="inventory">
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

        <el-tab-pane v-if="isPaneVisible('movements')" label="库存流水" name="movements">
          <WarehouseMovementsTab
            :loading="isTabLoading('movements')"
            :hydrated="hydratedTabs.has('movements')"
            :rows="pagedMovements"
            :sku-name="skuName"
            @selection-change="onSelectionChange"
          />
        </el-tab-pane>
        <el-tab-pane v-if="isPaneVisible('monthly')" label="月结" name="monthly">
          <WarehouseMonthlyCloseTab
            :loading="isTabLoading('monthly')"
            :hydrated="hydratedTabs.has('monthly')"
            :hint="monthlyCloseHint"
            :manager-name="monthlyCloseManager"
            :device-count="monthlyCloseDeviceCount"
            :rows="monthlyCloseLines"
            :load-restock-detail="loadMonthlyRestockDetail"
          />
        </el-tab-pane>
        <el-tab-pane
          v-if="isPaneVisible('channel-import') && canWarehouseList"
          label="渠道对账导入"
          name="channel-import"
        >
          <ExternalChannelImportTab />
        </el-tab-pane>
      </el-tabs>
      <!-- 仓库主 tab 的分页已内建 CrudTable；共享分页器仅服务其余 tab -->
      <PagePager
        v-if="tab !== 'warehouses' && tab !== 'monthly' && tab !== 'channel-import'"
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
        :warehouse-dialog="warehouseDialog"
        :supplier-dialog="supplierDialog"
        :payment-dialog="paymentDialog"
        :inbound-dialog="inboundDialog"
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
        :manager-options="managerOptions"
        :manager-loading="managerLoading"
        :manager-option-label="managerOptionLabel"
        :warehouse-manager-name="warehouseManagerName"
        @update:warehouse-dialog="setWarehouseDialog"
        @update:supplier-dialog="setSupplierDialog"
        @update:payment-dialog="setPaymentDialog"
        @update:inbound-dialog="setInboundDialog"
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
        @complete-and-adjust-stocktake="completeAndAdjustStocktakeAction"
        @cancel-stocktake="cancelStocktakeAction"
        @adjust-stocktake="adjustStocktakeAction"
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
        :active-warehouses="managedPurchaseWarehouses"
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

      <!--
        V313 仓库侧报损。`ref` 而非 `v-model`：它用`defineExpose({ open })`
        命令式打开（需要先拿到选中的批次行，见 openWriteOff）。
      -->
      <WarehouseWriteOffDialog ref="writeOffDialogRef" @done="onWriteOffDone" />

      <WarehouseTransferReceiveDialog ref="transferReceiveRef" @done="onTransferReceiveDone" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, Refresh, RefreshLeft } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { type CrudRowAction } from '@/components/CrudTable.vue';
import type { TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import HelpTip from '@/components/HelpTip.vue';
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
const WarehouseMonthlyCloseTab = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseMonthlyCloseTab.vue')
);
/** CB-036 渠道对账导入：独立异步组件（wizard 自取数，不走 view 级 loader），守住 route chunk 门禁 */
const ExternalChannelImportTab = defineAsyncComponent(
  () => import('@/components/warehouse/ExternalChannelImportTab.vue')
);
/**
 * V314 调拨收货损耗登记：独立异步组件（理由同V313 —— route chunk 门禁 ≤150KB）。
 */
const WarehouseTransferReceiveDialog = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseTransferReceiveDialog.vue')
);

/** V313 仓库侧报损：独立异步组件，守住 ≤150KB 的 route chunk 门禁 */
const WarehouseWriteOffDialog = defineAsyncComponent(
  () => import('@/components/warehouse/WarehouseWriteOffDialog.vue')
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
import { useDictOptions } from '@/composables/useDictOptions';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { useWarehouseBins } from '@/composables/warehouse/useWarehouseBins';
import {
  useWarehouseCsv,
  WAREHOUSE_EXPORTABLE_TABS
} from '@/composables/warehouse/useWarehouseCsv';
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
import { NAV_ITEMS } from '@/config/menu';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';
import { yuanText } from '@/utils/display';
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
/**
 * D28-B9：「批次报损」走 `POST /ops/admin/inventory/write-off`，后端要的是
 * `ops:replenishment:edit`（`OpsReplenishmentController:254`）——此前按 `ops:warehouse:edit` 显示，
 * 有仓权限没补货权限的人点进去必 403。判据按后端码配对，别按「在仓储页」猜。
 */
const canReplenishEdit = computed(() => auth.hasPerm('ops:replenishment:edit'));
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
/** V313 仓库侧报损对话框（命令式 open，见 openWriteOff）。 */
const writeOffDialogRef = ref<InstanceType<typeof WarehouseWriteOffDialog> | null>(null);
/** V314 调拨收货损耗登记对话框（命令式 open）。 */
const transferReceiveRef = ref<InstanceType<typeof WarehouseTransferReceiveDialog> | null>(null);

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

/**
 * V313 打开仓库侧报损对话框。
 *
 * <p>🔴 **一次只能报一个批次**（即使多选了也只取第一行）——
 * 报损要填归因（原因分类/责任方），一批货一个归因。
 * 多批次同因时让运营重复提交，比让一个对话框塞 N 行表单更不容易出错。
 */
function openWriteOff() {
  const rows = pickSelected(inventory.value);
  if (!rows.length) {
    ElMessage.warning('请先在「批次库存」里勾选要报损的批次');
    return;
  }
  if (rows.length > 1) {
    ElMessage.info(`已选 ${rows.length} 个批次，请只勾选一个（每个批次要分别填归因）`);
  }
  const row = rows[0];
  if (!row.warehouseId || !row.skuId) {
    ElMessage.warning('该行缺少仓库或商品信息，无法报损');
    return;
  }
  writeOffDialogRef.value?.open({
    warehouseId: String(row.warehouseId),
    skuId: String(row.skuId),
    batchNo: row.batchNo ? String(row.batchNo) : '',
    skuName: skuName(String(row.skuId)) || undefined,
    quantity: row.quantity != null ? Number(row.quantity) : undefined
  });
}

/** 报损后刷新批次库存 + 流水（报损会产生一条 WRITE_OFF 流水）。 */
function onWriteOffDone() {
  loadedTabs.value.delete('inventory');
  loadedTabs.value.delete('movements');
  void loadTab('inventory', true);
}

/**
 * V314 打开调拨收货登记弹窗。
 *
 * 🔴 不再「一点收货就按发运量全量入库」—— 那会让 B 仓虚增损耗件数。
 * 弹窗里逐行填实收，损耗自动推导。
 */
function openTransferReceive(row: Row) {
  const rawLines = (row.lines || []) as Array<{
    lineId?: string | number;
    skuId?: string;
    batchNo?: string;
    quantity?: number;
  }>;
  if (!Array.isArray(rawLines) || rawLines.length === 0) {
    ElMessage.warning('该调拨单没有明细行，无法登记收货');
    return;
  }
  transferReceiveRef.value?.open(
    row.transferId,
    row.transferNo || String(row.transferId ?? ''),
    rawLines.map((l) => ({
      lineId: l.lineId ?? '',
      skuId: l.skuId,
      batchNo: l.batchNo,
      quantity: Number(l.quantity) || 0
    }))
  );
}

function onTransferReceiveDone() {
  loadedTabs.value.delete('transfers');
  void loadTab('transfers', true);
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
/** D28-B7：加载失败的 tab（与「空数据」分离；见 useWarehouseTabLoader 的 markTabFailed）。 */
const failedTabs = ref(new Set<string>());
function isTabLoading(name: string) {
  return loadingTabs.value.has(name);
}
const saving = ref(false);
const tab = ref('warehouses');

/**
 * 拆分「仓储」顶级模块后，4 个子页复用本组件，各自只渲染自己的 tab 子集：
 * props.only = 允许的 tab 名；不传 = 全量 13 tab（旧 /warehouse 深链兼容）。
 * props.title/hint = 子页自己的标题与说明——🔴 别再用写死的「仓库」：子页各自的 tab
 * 不一样，标题与侧栏菜单（config/menu.ts）不一致时，运营在三处看到三种说法。
 */
const props = defineProps<{ only?: string[]; title?: string; hint?: string }>();

/**
 * 标题默认取侧栏菜单（`config/menu.ts`）里当前路径那一条——🔴 不在这里再抄一遍字面量：
 * 三处各写一次「仓库与库位」之类，改一处就漂一处。旧 /warehouse 整页不在菜单里，落回「仓库」。
 */
const pageTitle = computed(
  () => props.title || NAV_ITEMS.find((n) => n.path === route.path)?.title || '仓库'
);

/**
 * 子页模式下的同级页签：原来只有旧整页有分组切换器（见 warehouse-tab-groups），
 * 拆成 4 个子页后子页里既没有分组条也没有别页入口，运营只能回侧栏重找——
 * 这里按 `config/menu.ts` 的「仓储」分组生成同级导航，标题单一出处、权限同样过滤。
 */
const warehouseSiblings = computed(() =>
  NAV_ITEMS.filter(
    (n) =>
      n.group === '仓储' && n.path.startsWith('/warehouse/') && (!n.perm || auth.hasPerm(n.perm))
  )
);

/** 每个 tab 的一句用途说明（给不熟悉仓储域的运营照着找；改 tab 语义时同步改这里） */
const TAB_DESC: Record<string, string> = {
  warehouses: '仓库主数据：维护收发货仓与地址，补货员从这里领货带去各自看管的柜机',
  suppliers: '供应商档案：采购下单前先在这里维护供应商与联系方式',
  purchase: '采购单：向供应商下单，收货入库后形成批次与有效期',
  suggestions: '采购建议：系统按设备缺货与销量算出的补货清单，可一键转采购单',
  returns: '采购退货：把质量/临期问题的批次退回供应商并冲减应付',
  payables: '应付账款：按采购单汇总尚未结清的供应商货款',
  stocktakes: '盘点单：核对账面与实际数量，差异自动生成库存流水',
  bins: '货位：仓内位置的独立台账——只有「入库到货位」会同步批次账，收货/出库/调拨/盘点不记货位、移库也不写流水 ⇒ 本页合计 ≠ 批次库存，不能拿来对账',
  inventory: '批次库存：按批次查看各仓现存量与有效期，先进先出发货',
  movements: '库存流水：每笔入库/出库/调拨/盘点的明细账，可对账追溯',
  monthly:
    '月结一张表：本仓应有、实盘、领货件数（领货=从本仓带出的量，点数字下钻看上柜实收与领而未上），与结算金额无关',
  'channel-import':
    '渠道对账导入：大黄鹅/嗨便利等外部系统的补货与销售数据，解析→映射→提交为月结外调流水',
  transfers: '仓间调拨：库存在两个仓库间的调拨，生成调拨单与双向流水',
  outbounds:
    '出库单：按柜机建单 → 备货 → 领货（自提自送，无运输段）；领走但没上柜的差额在「已领未上柜」结清'
  // transit 不设通用描述：该 tab 有专属口径问号（transit-flow-hint，含签收/上架/不办回仓语义），避免同屏双提示
};
const tabDescription = computed(() => TAB_DESC[tab.value] || '');

/** only 子页里因权限被全部隐藏时的兜底提示（避免空白页）。判据与 `isWarehouseTabAllowed` 同源，别再抄一份。 */
const permGatedTabs = computed<Record<string, boolean>>(() => {
  const out: Record<string, boolean> = {};
  for (const name of Object.keys(TAB_GROUP_MAP)) out[name] = isWarehouseTabAllowed(name);
  return out;
});
const onlyNoVisibleTabs = computed(() => {
  if (!props.only?.length) return false;
  return !props.only.some((name) => permGatedTabs.value[name] !== false);
});

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
  monthly: 'inventory',
  'channel-import': 'inventory',
  transfers: 'fulfillment',
  outbounds: 'fulfillment',
  transit: 'fulfillment'
};

const TAB_GROUP_ORDER: Record<WarehouseTabGroup, string[]> = {
  overview: ['warehouses'],
  procurement: ['suppliers', 'purchase', 'suggestions', 'returns', 'payables'],
  inventory: ['stocktakes', 'bins', 'inventory', 'movements', 'monthly', 'channel-import'],
  fulfillment: ['transfers', 'outbounds', 'transit']
};

const tabGroup = ref<WarehouseTabGroup>('overview');

function tabGroupFor(name: string): WarehouseTabGroup {
  return TAB_GROUP_MAP[name] || 'overview';
}

/**
 * D28-B4：仓储这棵树上「读」与「写」的后端权限码并不一一对应，前端此前**只给 8 个 tab 装了门**，
 * `warehouses / inventory / movements / monthly / outbounds / transit` 直接 `return true` —— 没权限的人
 * 点进去必 403。判据一律照后端注解（`OpsWarehouseController` / `WarehouseTransferController`）：
 *   · `/warehouse/list`、`/inventory`、`/movements`、`/monthly-close`、`/outbounds`、`/in-transit`
 *     = OR(`ops:warehouse:list`, `ops:replenishment:list`) ⇒ 补货员只有补货读权限也必须看得见这几个 tab；
 *   · `/warehouse/transfers` GET = OR(`ops:warehouse:list`, `ops:warehouse:edit`) ⇒ 只有编辑权的人也能读；
 *   · `/stocktakes`、`/bins` = `ops:warehouse:list`（没有 OR，别顺手放宽）。
 */
const canWarehouseRead = computed(
  () => canWarehouseList.value || auth.hasPerm('ops:replenishment:list')
);
function isWarehouseTabAllowed(name: string): boolean {
  if (['suppliers', 'purchase', 'suggestions', 'returns', 'payables'].includes(name)) {
    return canProcurementList.value;
  }
  if (['stocktakes', 'bins', 'channel-import'].includes(name)) {
    return canWarehouseList.value;
  }
  if (name === 'transfers') return canWarehouseList.value || canWarehouseEdit.value;
  return canWarehouseRead.value;
}

/**
 * 权限全挡时别让页面落在一个看不见的 tab 上（深链 `?tab=suppliers` 而此人没有采购读权限）：
 * 按分组顺序取第一个「有权限且在子页子集内」的 tab；一个都没有就返回空串，调用方保持原值
 * （此时下面的 `onlyNoVisibleTabs` 兜底提示接管）。
 */
function firstAllowedTab(): string {
  const ordered = Object.values(TAB_GROUP_ORDER).flat();
  const candidates = props.only?.length
    ? ordered.filter((name) => props.only!.includes(name))
    : ordered;
  return candidates.find((name) => isWarehouseTabAllowed(name)) || '';
}

function isPaneVisible(name: string): boolean {
  // D28-B4：没权限的 tab 连页签都不该出现——此前只有 `isPaneVisible`（分组／子集）在管，
  // 点进去才被后端 403。
  if (!isWarehouseTabAllowed(name)) return false;
  if (props.only?.length) return props.only.includes(name);
  return tabGroup.value === tabGroupFor(name);
}

// only 模式：当前 tab 不在子集内（如深链带入别的 tab）时钳回子集第一个；
// D28-B4：当前 tab 没权限时同理（否则内容区空白，只剩一条空壳）。
watch(
  tab,
  (name) => {
    if (props.only?.length && !props.only.includes(name)) {
      tab.value = props.only[0];
      return;
    }
    if (!isWarehouseTabAllowed(name)) {
      const next = firstAllowedTab();
      if (next && next !== name) tab.value = next;
    }
  },
  { immediate: true }
);

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
  // 🔴 D28-B6：`monthly` 不在这里——月结接口一次返回整张表（`/warehouse/monthly-close` 不吃 page/size），
  // 分页器也刻意不显示。留在这张表里只会让 `tabTotal` 假装来自服务端分页，将来谁把分页器放开就变假分页。
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
const monthlyCloseLines = ref<Row[]>([]);
const monthlyCloseHint = ref('');
const monthlyCloseManager = ref('');
const monthlyCloseDeviceCount = ref(0);
const monthlyCloseYearMonth = ref(
  new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 7)
);
const suggestions = ref<Row[]>([]);
const suggestionLeadTimeDays = ref(2);
const suggestionCoverageDays = ref(14);
const payables = ref<Row[]>([]);
const payableSummary = ref<Row[]>([]);
const payableStatusFilter = ref('');
const payableStatusOptions = useDictOptions('supplier_payable_status');
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
    return `仓→柜自提自送：补货员领出后、未确认上柜的差额在此结清；时限 ${TRANSIT_OVERDUE_HOURS} 小时，超时标红`;
  }
  return props.hint || '仓库 / 供应商 / 库存 / 采购与退货';
});

const {
  showFilterBar,
  warehouseFilterRequired,
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
    case 'monthly':
      return monthlyCloseLines.value;
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
  return `共 ${rows.length} 家供应商有欠款，未付合计 ${yuanText(total)}，其中逾期 ${yuanText(overdue)}`;
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
  filterWarehouseId,
  filterOutboundStatus,
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
  loadMonthlyRestockDetail,
  loadTab: loadTabRaw
} = useWarehouseTabLoader({
  loadSeq,
  hasDeviceListPerm: () => auth.hasPerm('ops:device:list') || auth.hasPerm('ops:device:ref'),
  page,
  size,
  keyword,
  filterWarehouseId,
  filterOutboundStatus,
  overdueOnly,
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
  monthlyCloseLines,
  monthlyCloseHint,
  monthlyCloseManager,
  monthlyCloseDeviceCount,
  monthlyCloseYearMonth,
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
  hydratedTabs,
  failedTabs
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
  managedPurchaseWarehouses,
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
    isTabAllowed: isWarehouseTabAllowed,
    firstAllowedTab,
    loadTab,
    patchPurchaseOrderRow
  });
syncRouteQueryHolder.fn = syncRouteQuery;

const {
  stocktakeDialog,
  stocktakeDetailDialog,
  stocktakeForm,
  stocktakeDetail,
  openStocktakeCreate,
  saveStocktake,
  openStocktakeDetail,
  saveStocktakeLines,
  completeStocktakeAction,
  completeAndAdjustStocktakeAction,
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
  managerOptions,
  managerLoading,
  managerOptionLabel,
  warehouseManagerName,
  warehouseManagerLabel,
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

/** 显式 .value，避免模板 v-model 编译成 isRef?ref.value=v:null 在边界情况下空操作导致「取消」关不掉 */
function setWarehouseDialog(open: boolean) {
  warehouseDialog.value = open;
}
function setSupplierDialog(open: boolean) {
  supplierDialog.value = open;
}
function setPaymentDialog(open: boolean) {
  paymentDialog.value = open;
}
function setInboundDialog(open: boolean) {
  inboundDialog.value = open;
}
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
.warehouse-siblings {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 0 12px;
  flex-shrink: 0;
}
.warehouse-siblings__item {
  padding: 4px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-regular);
  text-decoration: none;
  line-height: 1.6;
}
.warehouse-siblings__item:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.warehouse-siblings__item.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}
.rule-help {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
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
.tab-load-failed {
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
