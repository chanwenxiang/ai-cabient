<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">仓库</span>
            <span class="hint">{{ pageHint }}</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-if="canEdit && tab === 'warehouses'" type="primary" @click="openWarehouse()"
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
          <el-button :icon="Refresh" :loading="isTabLoading(tab)" @click="reloadCurrent"
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
      <el-form-item v-if="tab === 'transit'" label="签收">
        <el-checkbox
          v-model="overdueOnly"
          data-testid="transit-overdue-only"
          @change="onOverdueToggle"
          >仅超时</el-checkbox
        >
      </el-form-item>
    </el-form>

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
          ? `共 ${overdueTransitCount} 条签收超时（发运超 ${TRANSIT_OVERDUE_HOURS} 小时未签收）`
          : `共 ${overdueTransitCount} 条签收超时，可勾选「仅超时」聚焦处理`
      "
    />

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="仓库概览" name="warehouses">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="isTabLoading('warehouses')"
              :data="pagedWarehouses"
              :default-sort="warehouseIdDefaultSort"
              @sort-change="onWarehouseIdSortChange"
              stripe
              border
              class="report-table"
              row-key="warehouseId"
              @selection-change="onSelectionChange"
              empty-text=" "
            >
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('warehouses') && !isTabLoading('warehouses')"
                  description="暂无仓库"
              /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column
                prop="warehouseId"
                label="仓库编号"
                min-width="120"
                align="center"
                class-name="col-text"
                show-overflow-tooltip
                sortable="custom"
              >
                <template #default="{ row }">
                  <span class="cell-id">{{ row.warehouseId }}</span>
                </template>
              </el-table-column>
              <el-table-column
                label="仓库"
                min-width="140"
                align="center"
                class-name="col-text"
                show-overflow-tooltip
              >
                <template #default="{ row }">{{ row.warehouseName || '无' }}</template>
              </el-table-column>
              <el-table-column
                prop="address"
                label="地址"
                min-width="220"
                show-overflow-tooltip
                align="center"
                class-name="col-text"
              />
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="dictTagType(row.status)" size="small">
                    {{ dictLabel('warehouse_status', row.status || 'ACTIVE') }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column
                v-if="canEdit"
                label="操作"
                width="88"
                class-name="col-action"
                align="center"
              >
                <template #default="{ row }">
                  <TableActions
                    :actions="[{ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' }]"
                    @action="() => openWarehouse(row)"
                  />
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canWarehouseList" label="仓间调拨" name="transfers">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="isTabLoading('transfers')"
              :data="transfers"
              stripe
              border
              class="report-table"
              empty-text=" "
            >
              <template #empty>
                <el-empty
                  v-if="hydratedTabs.has('transfers') && !isTabLoading('transfers')"
                  description="暂无调拨单"
                />
              </template>
              <el-table-column
                prop="transferNo"
                label="调拨单号"
                min-width="160"
                show-overflow-tooltip
              />
              <el-table-column label="调出仓" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">{{
                  warehouseName(row.fromWarehouseId) || row.fromWarehouseId
                }}</template>
              </el-table-column>
              <el-table-column label="调入仓" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">{{
                  warehouseName(row.toWarehouseId) || row.toWarehouseId
                }}</template>
              </el-table-column>
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain">{{ transferStatusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="明细" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">
                  {{
                    (row.lines || [])
                      .map(
                        (l: any) =>
                          `${skuName(l.skuId) || l.skuId}×${l.quantity}${l.batchNo ? '(' + l.batchNo + ')' : ''}`
                      )
                      .join(' · ') || ''
                  }}
                </template>
              </el-table-column>
              <el-table-column label="发运" width="150" align="center">
                <template #default="{ row }">
                  <span class="cell-datetime">{{
                    row.shippedAt ? formatDateTime(row.shippedAt) : ''
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column label="收货" width="150" align="center">
                <template #default="{ row }">
                  <span class="cell-datetime">{{
                    row.receivedAt ? formatDateTime(row.receivedAt) : ''
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column label="备注" min-width="100" show-overflow-tooltip>
                <template #default="{ row }">{{ row.notes || '' }}</template>
              </el-table-column>
              <el-table-column v-if="canWarehouseEdit" label="操作" width="200" align="center">
                <template #default="{ row }">
                  <el-button
                    v-if="row.status === 'DRAFT'"
                    link
                    type="primary"
                    @click="shipTransfer(row)"
                    >发运</el-button
                  >
                  <el-button
                    v-if="row.status === 'SHIPPED'"
                    link
                    type="success"
                    @click="receiveTransfer(row)"
                    >收货</el-button
                  >
                  <el-button
                    v-if="row.status === 'DRAFT'"
                    link
                    type="danger"
                    @click="cancelTransfer(row)"
                    >取消</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canProcurementList" label="供应商" name="suppliers">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="isTabLoading('suppliers')"
              :data="pagedSuppliers"
              :default-sort="supplierIdDefaultSort"
              @sort-change="onSupplierIdSortChange"
              stripe
              border
              row-key="supplierId"
              @selection-change="onSelectionChange"
              empty-text=" "
            >
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column
                prop="supplierId"
                label="供应商编号"
                min-width="120"
                align="center"
                class-name="col-text"
                show-overflow-tooltip
                sortable="custom"
              >
                <template #default="{ row }">
                  <span class="cell-id">{{ row.supplierId }}</span>
                </template>
              </el-table-column>
              <el-table-column
                label="供应商"
                min-width="140"
                align="center"
                class-name="col-text"
                show-overflow-tooltip
              >
                <template #default="{ row }">{{ row.supplierName || '无' }}</template>
              </el-table-column>
              <el-table-column prop="contactName" label="联系人" min-width="120" align="center" />
              <el-table-column
                prop="contactPhone"
                label="联系电话"
                min-width="150"
                align="center"
              />
              <el-table-column
                prop="paymentTermsDays"
                label="账期(天)"
                min-width="96"
                align="center"
              />
              <el-table-column label="状态" min-width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="dictTagType(row.status)" size="small">{{
                    dictLabel('supplier_status', row.status)
                  }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column
                v-if="canEdit"
                label="操作"
                width="88"
                class-name="col-action"
                align="center"
              >
                <template #default="{ row }">
                  <TableActions
                    :actions="[{ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' }]"
                    @action="() => openSupplier(row)"
                  />
                </template>
              </el-table-column>
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('suppliers') && !isTabLoading('suppliers')"
                  description="暂无供应商"
              /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canProcurementList" label="采购单" name="purchase">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="isTabLoading('purchase')"
              :data="pagedPurchaseOrders"
              stripe
              border
              row-key="purchaseOrderId"
              @selection-change="onSelectionChange"
              empty-text=" "
            >
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column type="expand" align="center">
                <template #default="{ row }">
                  <div class="expand-panel">
                    <el-table :data="row.lines || []" size="small" border class="line-table">
                      <el-table-column label="商品" min-width="180" align="center">
                        <template #default="scope">
                          {{ skuName(scope.row.skuId) }}
                        </template>
                      </el-table-column>
                      <el-table-column prop="batchNo" label="批次" min-width="140" align="center" />
                      <el-table-column
                        prop="orderedQty"
                        label="采购数"
                        min-width="88"
                        align="center"
                      />
                      <el-table-column
                        prop="receivedQty"
                        label="已收数"
                        min-width="88"
                        align="center"
                      />
                      <el-table-column
                        prop="returnedQty"
                        label="已退数"
                        min-width="88"
                        align="center"
                      />
                      <el-table-column label="成本" min-width="96" align="center">
                        <template #default="scope">¥{{ money(scope.row.unitCostCents) }}</template>
                      </el-table-column>
                      <el-table-column
                        prop="expiryDate"
                        label="到期日期"
                        min-width="120"
                        align="center"
                      />
                    </el-table>
                  </div>
                </template>
              </el-table-column>
              <el-table-column
                prop="purchaseOrderId"
                label="采购单"
                min-width="96"
                align="center"
              />
              <el-table-column
                prop="refNo"
                label="外部单号"
                min-width="140"
                show-overflow-tooltip
                align="center"
              >
                <template #default="{ row }">
                  <span v-if="row.refNo">{{ row.refNo }}</span>
                  <span v-else class="muted">未填写</span>
                </template>
              </el-table-column>
              <el-table-column label="供应商" min-width="160" align="center">
                <template #default="{ row }">
                  {{ supplierName(row.supplierId) }}
                </template>
              </el-table-column>
              <el-table-column label="入库仓库" min-width="160" align="center">
                <template #default="{ row }">
                  {{ warehouseName(row.warehouseId) }}
                </template>
              </el-table-column>
              <el-table-column label="状态" min-width="120" align="center">
                <template #default="{ row }">
                  <el-tag :type="dictTagType(row.status)" size="small">{{
                    dictLabel('purchase_order_status', row.status)
                  }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column
                v-if="canEdit"
                label="操作"
                min-width="220"
                class-name="col-action"
                align="center"
              >
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    class="print-btn"
                    @click="openPrint('purchase', { purchaseOrderId: row.purchaseOrderId })"
                    >打印收货单</el-button
                  >
                  <el-button
                    v-if="row.status === 'PENDING_APPROVAL' && canReviewPurchase"
                    link
                    type="success"
                    @click="reviewPurchase(row, true)"
                    >通过</el-button
                  >
                  <el-button
                    v-if="row.status === 'PENDING_APPROVAL' && canReviewPurchase"
                    link
                    type="danger"
                    @click="reviewPurchase(row, false)"
                    >驳回</el-button
                  >
                  <el-button
                    v-if="['CREATED', 'PARTIAL_RECEIVED'].includes(row.status)"
                    link
                    type="primary"
                    @click="openReceive(row)"
                    >采购收货</el-button
                  >
                </template>
              </el-table-column>
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('purchase') && !isTabLoading('purchase')"
                  description="暂无采购单"
              /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canProcurementList" label="采购建议" name="suggestions">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="isTabLoading('suggestions')"
              :data="pagedSuggestions"
              stripe
              border
              row-key="skuId"
              @selection-change="onSelectionChange"
              empty-text=" "
            >
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="商品" min-width="170" align="center" show-overflow-tooltip>
                <template #default="{ row }">{{ skuName(row.skuId) }}</template>
              </el-table-column>
              <el-table-column label="近7日销量" prop="soldQty7d" min-width="96" align="center" />
              <el-table-column
                label="近14日销量"
                prop="soldQty14d"
                min-width="104"
                align="center"
              />
              <el-table-column label="日均销量" min-width="88" align="center">
                <template #default="{ row }">
                  {{ Number(row.avgDailySales ?? 0).toFixed(2) }}
                </template>
              </el-table-column>
              <el-table-column label="预测日均" min-width="88" align="center">
                <template #default="{ row }">
                  {{ Number(row.forecastDailySales ?? row.avgDailySales ?? 0).toFixed(2) }}
                </template>
              </el-table-column>
              <el-table-column label="日均趋势" min-width="88" align="center">
                <template #default="{ row }">
                  <span v-if="Number(row.trendPerDay ?? 0) > 0" class="trend-up"
                    >+{{ Number(row.trendPerDay).toFixed(2) }}</span
                  >
                  <span v-else-if="Number(row.trendPerDay ?? 0) < 0" class="trend-down">{{
                    Number(row.trendPerDay).toFixed(2)
                  }}</span>
                  <span v-else>暂无</span>
                </template>
              </el-table-column>
              <el-table-column label="仓库库存" prop="onHandQty" min-width="88" align="center" />
              <el-table-column label="待收采购" prop="pendingPoQty" min-width="88" align="center" />
              <el-table-column label="覆盖天数" prop="coverageDays" min-width="88" align="center" />
              <el-table-column label="建议采购量" min-width="104" align="center">
                <template #default="{ row }">
                  <span class="cell-id">{{ row.suggestQty }}</span>
                </template>
              </el-table-column>
              <el-table-column label="安全库存" min-width="88" align="center">
                <template #default="{ row }">
                  {{ row.safetyStockQty ?? 0 }}
                </template>
              </el-table-column>
              <el-table-column label="建议理由" min-width="110" align="center">
                <template #default="{ row }">
                  <el-tag size="small" type="warning">
                    {{ suggestionReasonText(row.suggestReason) }}
                  </el-tag>
                </template>
              </el-table-column>
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('suggestions') && !isTabLoading('suggestions')"
                  description="暂无采购建议（近 14 日有动销且库存不足的商品才会出现）"
              /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canProcurementList" label="采购退货" name="returns">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="isTabLoading('returns')"
              :data="pagedPurchaseReturns"
              stripe
              border
              row-key="returnId"
              @selection-change="onSelectionChange"
              empty-text=" "
            >
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column type="expand" align="center">
                <template #default="{ row }">
                  <div class="expand-panel">
                    <el-table :data="row.lines || []" size="small" border class="line-table">
                      <el-table-column label="商品" min-width="180" align="center">
                        <template #default="scope">
                          {{ skuName(scope.row.skuId) }}
                        </template>
                      </el-table-column>
                      <el-table-column prop="batchNo" label="批次" min-width="140" align="center" />
                      <el-table-column
                        prop="quantity"
                        label="退货数"
                        min-width="88"
                        align="center"
                      />
                    </el-table>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="returnId" label="退货单" min-width="96" align="center" />
              <el-table-column
                prop="purchaseOrderId"
                label="采购单"
                min-width="96"
                align="center"
              />
              <el-table-column label="供应商" min-width="160" align="center">
                <template #default="{ row }">
                  {{ supplierName(row.supplierId) }}
                </template>
              </el-table-column>
              <el-table-column label="仓库" min-width="160" align="center">
                <template #default="{ row }">
                  {{ warehouseName(row.warehouseId) }}
                </template>
              </el-table-column>
              <el-table-column label="状态" min-width="100" align="center">
                <template #default="{ row }">
                  <el-tag type="success" size="small">{{ returnStatusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="创建时间" min-width="170" align="center">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </el-table-column>
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('returns') && !isTabLoading('returns')"
                  description="暂无采购退货"
              /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canProcurementList" label="应付账款" name="payables">
        <el-alert
          v-if="hydratedTabs.has('payables') && !isTabLoading('payables')"
          :closable="false"
          show-icon
          type="info"
          class="payable-summary"
          :title="payableSummaryText"
        />
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="isTabLoading('payables')"
              :data="pagedPayables"
              stripe
              border
              row-key="payableId"
              @selection-change="onSelectionChange"
              empty-text=" "
            >
              <el-table-column type="expand" align="center">
                <template #default="{ row }">
                  <div class="expand-panel">
                    <el-table
                      v-if="row.payments?.length"
                      :data="row.payments"
                      size="small"
                      border
                      class="line-table"
                    >
                      <el-table-column label="付款时间" min-width="170" align="center">
                        <template #default="scope">
                          {{ formatDateTime(scope.row.createdAt) }}
                        </template>
                      </el-table-column>
                      <el-table-column label="付款金额" min-width="110" align="center">
                        <template #default="scope">¥{{ money(scope.row.amountCents) }}</template>
                      </el-table-column>
                      <el-table-column prop="notes" label="备注" min-width="180" align="center" />
                    </el-table>
                    <el-empty v-else description="暂无付款记录" :image-size="60" />
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="供应商" min-width="150" align="center" show-overflow-tooltip>
                <template #default="{ row }">{{ row.supplierName }}</template>
              </el-table-column>
              <el-table-column label="关联采购单" min-width="110" align="center">
                <template #default="{ row }">
                  <span class="cell-id">{{ row.purchaseOrderId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="应付金额" min-width="110" align="center">
                <template #default="{ row }">¥{{ money(row.amountCents) }}</template>
              </el-table-column>
              <el-table-column label="已付" min-width="100" align="center">
                <template #default="{ row }">¥{{ money(row.paidAmountCents) }}</template>
              </el-table-column>
              <el-table-column label="未付余额" min-width="110" align="center">
                <template #default="{ row }">
                  <span class="cell-id">{{ money(row.balanceCents) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="到期日" min-width="110" align="center">
                <template #default="{ row }">{{ row.dueDate || '暂无' }}</template>
              </el-table-column>
              <el-table-column label="状态" min-width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="payableStatusType(row.status)" size="small">
                    {{ payableStatusText(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="逾期" min-width="116" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.overdue" type="danger" size="small">
                    逾期 {{ row.overdueDays }} 天
                  </el-tag>
                  <span v-else class="muted">未逾期</span>
                </template>
              </el-table-column>
              <el-table-column v-if="canEdit" label="操作" width="100" align="center">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    :disabled="row.balanceCents <= 0 || ['PAID', 'CLOSED'].includes(row.status)"
                    data-testid="pay-payable"
                    @click="openPay(row)"
                    >登记付款</el-button
                  >
                </template>
              </el-table-column>
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('payables') && !isTabLoading('payables')"
                  description="暂无应付账款"
              /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canWarehouseList" label="盘点单" name="stocktakes">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="isTabLoading('stocktakes')"
              :data="pagedStocktakes"
              stripe
              border
              row-key="stocktakeId"
              @selection-change="onSelectionChange"
              empty-text=" "
            >
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="盘点单号" min-width="160" align="center">
                <template #default="{ row }">
                  <span class="cell-id">{{ row.stocktakeNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="仓库" min-width="140" align="center">
                <template #default="{ row }">{{ row.warehouseName }}</template>
              </el-table-column>
              <el-table-column label="模式" min-width="80" align="center">
                <template #default="{ row }">{{ stocktakeModeText(row.mode) }}</template>
              </el-table-column>
              <el-table-column label="状态" min-width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="stocktakeStatusType(row.status)" size="small">
                    {{ stocktakeStatusText(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="账面件数" prop="bookQty" min-width="90" align="center" />
              <el-table-column label="实盘件数" prop="countedQty" min-width="90" align="center" />
              <el-table-column label="差异件数" min-width="90" align="center">
                <template #default="{ row }">{{ row.diffQty }}</template>
              </el-table-column>
              <el-table-column
                label="差异行数"
                prop="diffLineCount"
                min-width="90"
                align="center"
              />
              <el-table-column label="创建时间" min-width="160" align="center">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column v-if="canWarehouseEdit" label="操作" width="110" align="center">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openStocktakeDetail(row)">
                    {{ ['DRAFT', 'IN_PROGRESS'].includes(row.status) ? '盘点' : '查看/调整' }}
                  </el-button>
                </template>
              </el-table-column>
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('stocktakes') && !isTabLoading('stocktakes')"
                  description="暂无盘点单"
              /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canWarehouseList" label="货位" name="bins">
        <div class="section-title">货位档案</div>
        <div class="table-scroll compact">
          <el-table
            v-loading="isTabLoading('bins')"
            :data="bins"
            stripe
            border
            size="small"
            empty-text=" "
          >
            <el-table-column label="货位编码" min-width="110" align="center">
              <template #default="{ row }">
                <span class="cell-id">{{ row.binCode }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="binName" label="货位名称" min-width="140" align="center">
              <template #default="{ row }">{{ row.binName || '暂无' }}</template>
            </el-table-column>
            <el-table-column label="仓库" min-width="140" align="center">
              <template #default="{ row }">{{ row.warehouseName }}</template>
            </el-table-column>
            <el-table-column label="状态" min-width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                  {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column v-if="canWarehouseEdit" label="操作" width="80" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openBinDialog(row)">编辑</el-button>
              </template>
            </el-table-column>
            <template #empty
              ><el-empty
                v-if="hydratedTabs.has('bins') && !isTabLoading('bins')"
                description="暂无货位，请先新增货位"
                :image-size="60"
            /></template>
          </el-table>
        </div>
        <div class="section-title">货位库存</div>
        <div class="table-scroll">
          <el-table
            v-loading="isTabLoading('bins')"
            :data="pagedBinStock"
            stripe
            border
            empty-text=" "
          >
            <el-table-column label="货位" min-width="100" align="center">
              <template #default="{ row }">
                <span class="cell-id">{{ row.binCode }}</span>
              </template>
            </el-table-column>
            <el-table-column label="商品" min-width="170" align="center" show-overflow-tooltip>
              <template #default="{ row }">{{ row.skuName }}</template>
            </el-table-column>
            <el-table-column prop="batchNo" label="批次" min-width="130" align="center" />
            <el-table-column
              prop="productionDate"
              label="生产日期"
              min-width="110"
              align="center"
            />
            <el-table-column prop="expiryDate" label="到期日" min-width="110" align="center" />
            <el-table-column prop="quantity" label="数量" min-width="80" align="center" />
            <template #empty
              ><el-empty
                v-if="hydratedTabs.has('bins') && !isTabLoading('bins')"
                description="暂无货位库存"
                :image-size="60"
            /></template>
          </el-table>
        </div>
      </el-tab-pane>

      <el-tab-pane label="出库单" name="outbounds">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="isTabLoading('outbounds')"
              :data="pagedOutbounds"
              stripe
              border
              row-key="outboundId"
              :row-class-name="outboundRowClassName"
              data-testid="outbound-table"
              @selection-change="onSelectionChange"
              empty-text=" "
            >
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column type="expand" align="center">
                <template #default="{ row }">
                  <div class="expand-panel" :data-testid="`outbound-expand-${row.outboundId}`">
                    <el-table :data="row.lines || []" size="small" border class="line-table">
                      <el-table-column label="目标设备" min-width="180" align="center">
                        <template #default="scope">
                          {{ deviceName(scope.row.deviceId) }}
                        </template>
                      </el-table-column>
                      <el-table-column label="商品" min-width="180" align="center">
                        <template #default="scope">
                          {{ skuName(scope.row.skuId) }}
                        </template>
                      </el-table-column>
                      <el-table-column label="货道" min-width="88" align="center">
                        <template #default="scope">{{ scope.row.slotId || '无' }}</template>
                      </el-table-column>
                      <el-table-column prop="batchNo" label="批次" min-width="140" align="center" />
                      <el-table-column prop="quantity" label="数量" min-width="88" align="center" />
                      <el-table-column label="交接状态" min-width="110" align="center">
                        <template #default="scope">{{
                          dictLabel('handover_status', scope.row.handoverStatus || 'PENDING')
                        }}</template>
                      </el-table-column>
                    </el-table>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="出库单" min-width="110" align="center">
                <template #default="{ row }">
                  <span :data-testid="`outbound-id-${row.outboundId}`" class="outbound-id-cell">{{
                    row.outboundId
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="routeId" label="路线" min-width="88" align="center" />
              <el-table-column label="出库仓库" min-width="160" align="center">
                <template #default="{ row }">
                  {{ warehouseName(row.warehouseId) }}
                </template>
              </el-table-column>
              <el-table-column label="状态" min-width="110" align="center">
                <template #default="{ row }">
                  <el-tag :type="dictTagType(row.status)" size="small">{{
                    dictLabel('warehouse_outbound_status', row.status)
                  }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="创建时间" min-width="170" align="center">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column
                v-if="canEdit"
                label="操作"
                width="180"
                class-name="col-action"
                align="center"
              >
                <template #default="{ row }">
                  <div :data-testid="`outbound-row-${row.outboundId}`">
                    <el-button
                      v-if="row.lines?.length"
                      link
                      type="primary"
                      class="print-btn"
                      @click="openPrint('picking', { outboundId: row.outboundId })"
                      >打印拣货单</el-button
                    >
                    <TableActions
                      v-if="outboundActions(row).length"
                      :actions="outboundActions(row)"
                      :test-id-prefix="`outbound-${row.outboundId}`"
                      @action="
                        (k) =>
                          changeOutbound(row, String(k) as 'pick' | 'ship' | 'cancel-unreceived')
                      "
                    />
                    <span v-else-if="!row.lines?.length && row.status !== 'SHIPPED'" class="muted"
                      >无明细</span
                    >
                    <span v-else-if="row.status === 'SHIPPED'" class="muted">已发运</span>
                  </div>
                </template>
              </el-table-column>
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('outbounds') && !isTabLoading('outbounds')"
                  description="暂无出库单"
              /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="在途" name="transit">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="isTabLoading('transit')"
              :data="pagedInTransit"
              stripe
              border
              :row-key="transitRowKey"
              :row-class-name="transitRowClassName"
              @selection-change="onSelectionChange"
              empty-text=" "
            >
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('transit') && !isTabLoading('transit')"
                  :description="transitEmptyHint"
              /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column prop="outboundId" label="出库单" min-width="96" align="center" />
              <el-table-column label="目标设备" min-width="180" align="center">
                <template #default="{ row }">
                  {{ deviceName(row.deviceId) }}
                </template>
              </el-table-column>
              <el-table-column label="商品" min-width="180" align="center">
                <template #default="{ row }">
                  {{ skuName(row.skuId) }}
                </template>
              </el-table-column>
              <el-table-column prop="batchNo" label="批次" min-width="140" align="center" />
              <el-table-column prop="quantity" label="数量" min-width="88" align="center" />
              <el-table-column label="状态" min-width="110" align="center">
                <template #default="{ row }">
                  <el-tag :type="dictTagType(row.status)" size="small">{{
                    dictLabel('in_transit_status', row.status)
                  }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column
                label="在途 / 时限"
                min-width="160"
                align="center"
                class-name="col-text"
              >
                <template #default="{ row }">
                  <div class="sla-cell">
                    <template v-if="isTransitOverdue(row)">
                      <el-tag type="danger" size="small">签收超时</el-tag>
                      <small class="sla-meta danger"
                        >超 {{ formatAge(transitOverdueMs(row)) }}</small
                      >
                    </template>
                    <template v-else-if="isTransitDueSoon(row)">
                      <el-tag type="warning" size="small">临近超时</el-tag>
                      <small class="sla-meta"
                        >已运 {{ formatAge(transitAgeMs(row)) }} · 剩
                        {{ formatAge(transitRemainMs(row)) }}</small
                      >
                    </template>
                    <template v-else>
                      <span class="cell-datetime">已运 {{ formatAge(transitAgeMs(row)) }}</span>
                      <small class="sla-meta">时限 {{ TRANSIT_OVERDUE_HOURS }} 小时</small>
                    </template>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="发运时间" min-width="170" align="center">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="批次库存" name="inventory">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="isTabLoading('inventory')"
              :data="pagedInventory"
              stripe
              border
              :row-key="inventoryRowKey"
              @selection-change="onSelectionChange"
              empty-text=" "
            >
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="仓库" min-width="140" align="center">
                <template #default="{ row }">{{ warehouseName(row.warehouseId) }}</template>
              </el-table-column>
              <el-table-column label="商品" min-width="180" align="center">
                <template #default="{ row }">
                  {{ skuName(row.skuId) }}
                </template>
              </el-table-column>
              <el-table-column prop="batchNo" label="批次" min-width="150" align="center" />
              <el-table-column
                prop="productionDate"
                label="生产日期"
                min-width="120"
                align="center"
              />
              <el-table-column prop="expiryDate" label="到期日期" min-width="120" align="center" />
              <el-table-column prop="quantity" label="库存" min-width="88" align="center" />
              <el-table-column label="效期" min-width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="expiryType(row.expiryDate)" size="small">{{
                    expiryText(row.expiryDate)
                  }}</el-tag>
                </template>
              </el-table-column>
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('inventory') && !isTabLoading('inventory')"
                  description="暂无库存"
              /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="库存流水" name="movements">
        <p class="muted tip">仅显示最近 100 条</p>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="isTabLoading('movements')"
              :data="pagedMovements"
              stripe
              border
              row-key="movementId"
              @selection-change="onSelectionChange"
              empty-text=" "
            >
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column prop="movementId" label="流水" min-width="90" align="center" />
              <el-table-column label="类型" min-width="130" align="center">
                <template #default="{ row }">{{
                  dictLabel('warehouse_movement_type', row.movementType)
                }}</template>
              </el-table-column>
              <el-table-column label="商品" min-width="180" align="center">
                <template #default="{ row }">
                  {{ skuName(row.skuId) }}
                </template>
              </el-table-column>
              <el-table-column prop="batchNo" label="批次" min-width="140" align="center" />
              <el-table-column prop="deltaQty" label="变动" min-width="88" align="center">
                <template #default="{ row }">
                  <span :class="row.deltaQty >= 0 ? 'positive' : 'negative'"
                    >{{ row.deltaQty > 0 ? '+' : '' }}{{ row.deltaQty }}</span
                  >
                </template>
              </el-table-column>
              <el-table-column label="关联业务" min-width="140" align="center">
                <template #default="{ row }">{{
                  dictLabel('business_reference_type', row.refType)
                }}</template>
              </el-table-column>
              <el-table-column label="关联单号" min-width="120" align="center">
                <template #default="{ row }">{{ displayBizNo(row.refId, '无') }}</template>
              </el-table-column>
              <el-table-column label="时间" min-width="170" align="center">
                <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
              </el-table-column>
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('movements') && !isTabLoading('movements')"
                  description="暂无流水"
              /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
    <PagePager
      :hydrated="hydratedTabs.has(tab)"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="tabTotal"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @current-change="onPagerChange"
      @size-change="onPagerSizeChange"
    />

    <el-dialog
      v-model="warehouseDialog"
      :title="warehouseForm.editing ? '编辑仓库' : '新增仓库'"
      width="480px"
      destroy-on-close
    >
      <el-form label-width="88px">
        <el-form-item label="仓库 ID" required>
          <el-input
            v-model="warehouseForm.warehouseId"
            :disabled="warehouseForm.editing"
            placeholder="如 WH-SH-001"
          />
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

    <el-dialog
      v-model="supplierDialog"
      :title="supplierForm.editing ? '编辑供应商' : '新增供应商'"
      width="520px"
      destroy-on-close
    >
      <el-form label-width="92px">
        <el-form-item label="供应商 ID"
          ><el-input v-model="supplierForm.supplierId" :disabled="supplierForm.editing"
        /></el-form-item>
        <el-form-item label="供应商名称"
          ><el-input v-model="supplierForm.supplierName"
        /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="supplierForm.contactName" /></el-form-item>
        <el-form-item label="联系电话"
          ><el-input v-model="supplierForm.contactPhone"
        /></el-form-item>
        <el-form-item label="账期(天)"
          ><el-input-number
            v-model="supplierForm.paymentTermsDays"
            :min="0"
            :max="365"
            style="width: 100%"
        /></el-form-item>
        <el-form-item label="信用额度(元)"
          ><el-input-number
            v-model="supplierForm.creditLimitYuan"
            :min="0"
            :step="100"
            :precision="2"
            style="width: 100%"
        /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="supplierForm.status" style="width: 100%">
            <el-option
              v-for="item in dictOptions('supplier_status')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="supplierDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSupplier">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="paymentDialog" title="登记付款" width="480px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="供应商">{{ payTarget.supplierName }}</el-form-item>
        <el-form-item label="关联采购单">
          <span class="cell-id">{{ payTarget.purchaseOrderId }}</span>
        </el-form-item>
        <el-form-item label="未付余额">¥{{ money(payTarget.balanceCents) }}</el-form-item>
        <el-form-item label="付款金额(元)" required>
          <el-input-number
            v-model="paymentForm.amountYuan"
            :min="0.01"
            :max="payMaxYuan"
            :precision="2"
            :step="100"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注"
          ><el-input v-model="paymentForm.notes" maxlength="200"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="paymentDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePayment">确认付款</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="stocktakeDialog" title="新建盘点" width="480px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="仓库" required>
          <el-select v-model="stocktakeForm.warehouseId" filterable style="width: 100%">
            <el-option
              v-for="w in activeWarehouses"
              :key="w.warehouseId"
              :label="w.warehouseName"
              :value="w.warehouseId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="盘点模式">
          <el-radio-group v-model="stocktakeForm.mode">
            <el-radio value="OPEN">明盘（预填账面数）</el-radio>
            <el-radio value="BLIND">盲盘（实盘留空）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注"
          ><el-input v-model="stocktakeForm.notes" maxlength="200"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stocktakeDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveStocktake">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="stocktakeDetailDialog"
      :title="`盘点单 ${stocktakeDetail.stocktakeNo || ''}`"
      width="980px"
      class="dialog-wide"
      destroy-on-close
    >
      <el-form inline class="filter-bar filter-bar--compact" @submit.prevent>
        <el-form-item label="仓库">{{ stocktakeDetail.warehouseName }}</el-form-item>
        <el-form-item label="模式">{{ stocktakeModeText(stocktakeDetail.mode) }}</el-form-item>
        <el-form-item label="状态">
          <el-tag :type="stocktakeStatusType(stocktakeDetail.status)" size="small">
            {{ stocktakeStatusText(stocktakeDetail.status) }}
          </el-tag>
        </el-form-item>
        <el-form-item label="账面件数">{{ stocktakeDetail.bookQty ?? 0 }}</el-form-item>
        <el-form-item label="实盘件数">{{ stocktakeDetail.countedQty ?? 0 }}</el-form-item>
        <el-form-item label="差异件数">
          <b>{{ stocktakeDetail.diffQty ?? 0 }}</b>
        </el-form-item>
        <el-form-item label="差异行数">{{ stocktakeDetail.diffLineCount ?? 0 }}</el-form-item>
      </el-form>
      <el-table
        :data="stocktakeDetail.lines || []"
        size="small"
        border
        max-height="420"
        class="line-table"
      >
        <el-table-column label="商品" min-width="170">
          <template #default="{ row }">{{ row.skuName }}</template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批次" min-width="130" />
        <el-table-column prop="productionDate" label="生产日期" min-width="110" />
        <el-table-column prop="expiryDate" label="到期日" min-width="110" />
        <el-table-column prop="bookQty" label="账面" min-width="70" align="center" />
        <el-table-column label="实盘" min-width="130" align="center">
          <template #default="{ row }">
            <el-input-number
              v-if="['DRAFT', 'IN_PROGRESS'].includes(stocktakeDetail.status)"
              v-model="row.countedQty"
              :min="0"
              size="small"
              controls-position="right"
            />
            <span v-else>{{ row.countedQty ?? '暂无' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="差异" min-width="80" align="center">
          <template #default="{ row }">{{ row.diffQty }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="stocktakeLineStatusType(row.status)" size="small">
              {{ stocktakeLineStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="stocktakeDetailDialog = false">关闭</el-button>
        <template v-if="['DRAFT', 'IN_PROGRESS'].includes(stocktakeDetail.status)">
          <el-button
            type="primary"
            plain
            :loading="scanningPhoto"
            @click="triggerStocktakePhotoScan"
            >拍照识别</el-button
          >
          <el-button type="primary" :loading="saving" @click="saveStocktakeLines"
            >保存实盘</el-button
          >
          <el-button type="success" :loading="saving" @click="completeStocktakeAction"
            >完成盘点</el-button
          >
          <el-button
            v-if="stocktakeDetail.status === 'DRAFT'"
            :loading="saving"
            @click="cancelStocktakeAction"
            >取消盘点</el-button
          >
        </template>
        <el-button
          v-if="stocktakeDetail.status === 'COMPLETED' && (stocktakeDetail.diffLineCount ?? 0) > 0"
          type="warning"
          :loading="saving"
          @click="adjustStocktakeAction"
          >复盘调整</el-button
        >
      </template>
      <input
        ref="stocktakePhotoInput"
        type="file"
        accept="image/*"
        class="hidden-input"
        @change="onStocktakePhoto"
      />
    </el-dialog>

    <el-dialog
      v-model="binDialog"
      :title="binForm.editing ? '编辑货位' : '新增货位'"
      width="480px"
      destroy-on-close
    >
      <el-form label-width="92px">
        <el-form-item label="仓库" required>
          <el-select
            v-model="binForm.warehouseId"
            filterable
            :disabled="binForm.editing"
            style="width: 100%"
          >
            <el-option
              v-for="w in activeWarehouses"
              :key="w.warehouseId"
              :label="w.warehouseName"
              :value="w.warehouseId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="货位编码" required
          ><el-input
            v-model="binForm.binCode"
            :disabled="binForm.editing"
            placeholder="如 A-01"
            maxlength="32"
        /></el-form-item>
        <el-form-item label="货位名称"
          ><el-input v-model="binForm.binName" maxlength="64"
        /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="binForm.status">
            <el-radio value="ACTIVE">启用</el-radio>
            <el-radio value="INACTIVE">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="binDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBin">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="binInboundDialog" title="入库到货位" width="560px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="仓库" required>
          <el-select
            v-model="binInboundForm.warehouseId"
            filterable
            style="width: 100%"
            @change="onBinInboundWarehouse"
          >
            <el-option
              v-for="w in activeWarehouses"
              :key="w.warehouseId"
              :label="w.warehouseName"
              :value="w.warehouseId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="货位" required>
          <el-select v-model="binInboundForm.binCode" filterable style="width: 100%">
            <el-option
              v-for="b in activeBinsFor(binInboundForm.warehouseId)"
              :key="b.binCode"
              :label="b.binCode"
              :value="b.binCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="商品" required>
          <el-select v-model="binInboundForm.skuId" filterable style="width: 100%">
            <el-option
              v-for="sku in skus"
              :key="sku.skuId"
              :label="`${sku.skuName || sku.skuId}`"
              :value="sku.skuId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="批次" required
          ><el-input v-model="binInboundForm.batchNo" maxlength="64"
        /></el-form-item>
        <el-form-item label="生产日期"
          ><input v-model="binInboundForm.productionDate" class="native-date" type="date"
        /></el-form-item>
        <el-form-item label="到期日" required
          ><input v-model="binInboundForm.expiryDate" class="native-date" type="date"
        /></el-form-item>
        <el-form-item label="数量" required>
          <el-input-number
            v-model="binInboundForm.quantity"
            :min="1"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="binInboundDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBinInbound">确认入库</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="binMoveDialog" title="货位移库" width="560px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="源货位" required>
          <el-select
            v-model="binMoveForm.fromBinId"
            filterable
            style="width: 100%"
            @change="onBinMoveSource"
          >
            <el-option v-for="b in allBins" :key="b.binId" :label="binLabel(b)" :value="b.binId" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标货位" required>
          <el-select v-model="binMoveForm.toBinId" filterable style="width: 100%">
            <el-option v-for="b in allBins" :key="b.binId" :label="binLabel(b)" :value="b.binId" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品" required>
          <el-select v-model="binMoveForm.skuId" filterable style="width: 100%">
            <el-option
              v-for="s in sourceBinSkus"
              :key="s.skuId"
              :label="s.skuName"
              :value="s.skuId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="批次" required
          ><el-input v-model="binMoveForm.batchNo" maxlength="64"
        /></el-form-item>
        <el-form-item label="数量" required>
          <el-input-number
            v-model="binMoveForm.quantity"
            :min="1"
            :max="sourceBinMaxQty"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="binMoveDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBinMove">确认移库</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="purchaseDialog"
      title="新建采购单"
      width="760px"
      class="dialog-wide"
      destroy-on-close
    >
      <el-form v-loading="dialogBootLoading" label-width="90px">
        <div class="form-grid">
          <el-form-item label="供应商">
            <el-select v-model="purchaseForm.supplierId" filterable style="width: 100%">
              <el-option
                v-for="item in activeSuppliers"
                :key="item.supplierId"
                :label="item.supplierName"
                :value="item.supplierId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="入库仓库">
            <el-select v-model="purchaseForm.warehouseId" style="width: 100%">
              <el-option
                v-for="item in activeWarehouses"
                :key="item.warehouseId"
                :label="item.warehouseName"
                :value="item.warehouseId"
              />
            </el-select>
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
          <el-button link type="primary" @click="addPurchaseLine">添加一行</el-button>
        </div>
        <div v-for="(line, index) in purchaseForm.lines" :key="index" class="purchase-line-card">
          <div class="line-card-head">
            <strong>明细 {{ index + 1 }}</strong>
            <el-button
              link
              type="danger"
              :disabled="purchaseForm.lines.length === 1"
              @click="removePurchaseLine(index)"
              >删除</el-button
            >
          </div>
          <div class="line-grid">
            <label class="line-field">
              <span>商品</span>
              <el-select v-model="line.skuId" filterable placeholder="选择商品">
                <el-option
                  v-for="sku in skus"
                  :key="sku.skuId"
                  :label="`${sku.skuName || sku.skuId}`"
                  :value="sku.skuId"
                />
              </el-select>
            </label>
            <label class="line-field"><span>批次号</span><el-input v-model="line.batchNo" /></label>
            <label class="line-field"
              ><span>数量（件）</span
              ><el-input-number v-model="line.orderedQty" :min="1" controls-position="right"
            /></label>
            <label class="line-field"
              ><span>单价（元）</span
              ><el-input-number
                v-model="line.unitCostYuan"
                :min="0.01"
                :step="0.01"
                :precision="2"
                controls-position="right"
            /></label>
            <label class="line-field"
              ><span>生产日期</span
              ><input v-model="line.productionDate" class="native-date" type="date"
            /></label>
            <label class="line-field"
              ><span>到期日期</span
              ><input v-model="line.expiryDate" class="native-date" type="date"
            /></label>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="purchaseDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="dialogBootLoading"
          @click="savePurchase"
          >创建</el-button
        >
      </template>
    </el-dialog>

    <el-dialog
      v-model="receiveDialog"
      title="采购收货"
      width="700px"
      class="dialog-wide"
      destroy-on-close
    >
      <el-form label-width="100px" style="margin-bottom: 8px">
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
          <el-table-column label="商品" min-width="160" align="center">
            <template #default="{ row }">
              <div>{{ skuName(row.skuId) }}</div>
              <small class="muted">{{ row.skuId }}</small>
            </template>
          </el-table-column>
          <el-table-column prop="batchNo" label="批次" min-width="120" align="center" />
          <el-table-column prop="expiryDate" label="到期日" width="110" align="center">
            <template #default="{ row }">{{ row.expiryDate || '暂无' }}</template>
          </el-table-column>
          <el-table-column prop="orderedQty" label="采购数" width="90" align="center" />
          <el-table-column label="待收" width="80" align="center">
            <template #default="{ row }">
              {{ Math.max(0, Number(row.orderedQty || 0) - Number(row.receivedQty || 0)) }}
            </template>
          </el-table-column>
          <el-table-column label="累计收货" width="150" align="center">
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
        <el-button @click="receiveDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveReceive">确认收货</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transferDialog" title="新建仓间调拨" width="520px" destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="调出仓" required>
          <el-select v-model="transferForm.fromWarehouseId" filterable style="width: 100%">
            <el-option
              v-for="w in warehouses"
              :key="w.warehouseId"
              :label="w.warehouseName || w.warehouseId"
              :value="w.warehouseId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="调入仓" required>
          <el-select v-model="transferForm.toWarehouseId" filterable style="width: 100%">
            <el-option
              v-for="w in warehouses"
              :key="'to-' + w.warehouseId"
              :label="w.warehouseName || w.warehouseId"
              :value="w.warehouseId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="SKU" required>
          <el-input v-model="transferForm.skuId" placeholder="商品 SKU" />
        </el-form-item>
        <el-form-item label="批次">
          <el-input v-model="transferForm.batchNo" placeholder="可空" />
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number v-model="transferForm.quantity" :min="1" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="transferForm.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveTransfer">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="returnDialog"
      title="采购退货"
      width="760px"
      class="dialog-wide"
      destroy-on-close
    >
      <div v-loading="dialogBootLoading">
        <el-form label-width="90px">
          <el-form-item label="采购单" required>
            <el-select
              v-model="returnForm.purchaseOrderId"
              filterable
              placeholder="选择已收货采购单"
              style="width: 100%"
              @change="onReturnPoChange"
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
        </el-form>
        <div class="table-scroll">
          <el-table :data="returnForm.lines" class="receive-table">
            <el-table-column label="商品" min-width="160" align="center">
              <template #default="{ row }">
                <div>{{ skuName(row.skuId) }}</div>
                <small class="muted">{{ row.skuId }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="batchNo" label="批次" min-width="120" align="center" />
            <el-table-column prop="expiryDate" label="到期日" width="110" align="center">
              <template #default="{ row }">{{ row.expiryDate || '暂无' }}</template>
            </el-table-column>
            <el-table-column prop="receivedQty" label="已收" width="80" align="center" />
            <el-table-column prop="returnedQty" label="已退" width="80" align="center" />
            <el-table-column label="可退" width="72" align="center">
              <template #default="{ row }">{{ row.maxQty ?? '暂无' }}</template>
            </el-table-column>
            <el-table-column label="本次退货" width="150" align="center">
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
        <el-button @click="returnDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="dialogBootLoading"
          @click="saveReturn"
          >确认退货</el-button
        >
      </template>
    </el-dialog>

    <el-dialog
      v-model="inboundDialog"
      title="其他入库"
      width="720px"
      class="dialog-wide"
      destroy-on-close
    >
      <el-form v-loading="dialogBootLoading" label-width="88px">
        <div class="form-grid">
          <el-form-item label="仓库" required>
            <el-select v-model="inboundForm.warehouseId" style="width: 100%">
              <el-option
                v-for="w in activeWarehouses"
                :key="w.warehouseId"
                :label="w.warehouseName"
                :value="w.warehouseId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="参考单号"><el-input v-model="inboundForm.refNo" /></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="inboundForm.notes" /></el-form-item>
        <div class="section-title">
          <span>入库明细</span>
          <el-button link type="primary" @click="inboundForm.lines.push(newInboundLine())"
            >添加一行</el-button
          >
        </div>
        <div v-for="(line, index) in inboundForm.lines" :key="index" class="purchase-line-card">
          <div class="line-card-head">
            <strong>明细 {{ index + 1 }}</strong>
            <el-button
              link
              type="danger"
              :disabled="inboundForm.lines.length === 1"
              @click="removeInboundLine(index)"
              >删除</el-button
            >
          </div>
          <div class="line-grid">
            <label class="line-field">
              <span>商品</span>
              <el-select v-model="line.skuId" filterable>
                <el-option
                  v-for="sku in skus"
                  :key="sku.skuId"
                  :label="sku.skuName || sku.skuId"
                  :value="sku.skuId"
                />
              </el-select>
            </label>
            <label class="line-field"><span>批次</span><el-input v-model="line.batchNo" /></label>
            <label class="line-field"
              ><span>数量</span
              ><el-input-number v-model="line.quantity" :min="1" controls-position="right"
            /></label>
            <label class="line-field"
              ><span>生产日期</span
              ><input v-model="line.productionDate" class="native-date" type="date"
            /></label>
            <label class="line-field"
              ><span>到期日期</span
              ><input v-model="line.expiryDate" class="native-date" type="date"
            /></label>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="inboundDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="dialogBootLoading"
          @click="saveInbound"
          >确认入库</el-button
        >
      </template>
    </el-dialog>

    <!-- 拣货/发运：用 el-dialog 替代 MessageBox，避免自动化偶发点不到确认钮 -->
    <el-dialog
      v-model="outboundConfirm.visible"
      :title="outboundConfirm.title"
      width="420px"
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
      data-testid="outbound-confirm-dialog"
      @closed="onOutboundConfirmClosed"
    >
      <p class="outbound-confirm-body">
        <span class="outbound-confirm-id" data-testid="outbound-confirm-id"
          >出库单 {{ outboundConfirm.outboundId }}</span
        >
        <br />
        {{ outboundConfirm.message }}
      </p>
      <template #footer>
        <el-button data-testid="outbound-confirm-cancel" @click="cancelOutboundConfirm"
          >取消</el-button
        >
        <el-button
          type="primary"
          :loading="outboundConfirm.saving"
          data-testid="outbound-confirm-ok"
          @click="submitOutboundConfirm"
          >确定</el-button
        >
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Box, EditPen, Refresh, RefreshLeft, Van } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, authFetch, downloadAuthFile } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { useAuthStore } from '@/stores/auth';
import { csvFileName } from '@/utils/csv';
import { dictLabel, dictOptions, dictTagType, displayLabel } from '@aicabinet/shared-dict';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
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
  if (v === '启用' || v === '正常') return 'ACTIVE';
  if (v === '停用' || v === '禁用') return 'INACTIVE';
  return fallback;
}

const loadingTabs = ref(new Set<string>());
const hydratedTabs = ref(new Set<string>());
function isTabLoading(name: string) {
  return loadingTabs.value.has(name);
}
const saving = ref(false);
const cleanupStaleLoading = ref(false);
const tab = ref('warehouses');
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
const filterWarehouseId = ref('');
/** 默认「待处理」：有明细的 DRAFT + PICKED，避免历史草稿淹没操作列 */
const filterOutboundStatus = ref<string>('actionable');
/** Matches AdminDashboardService.IN_TRANSIT_OVERDUE_HOURS */
const TRANSIT_OVERDUE_HOURS = 24;
const TRANSIT_OVERDUE_MS = TRANSIT_OVERDUE_HOURS * 3600 * 1000;
const TRANSIT_DUE_SOON_MS = 4 * 3600 * 1000;
const overdueOnly = ref(false);
const focusDeviceId = ref('');
const warehouses = ref<Row[]>([]);
const suppliers = ref<Row[]>([]);
const {
  defaultSort: warehouseIdDefaultSort,
  onSortChange: onWarehouseIdSortChange,
  sortById: sortWarehousesById
} = useIdColumnSort<Row>('warehouseId');
const {
  defaultSort: supplierIdDefaultSort,
  onSortChange: onSupplierIdSortChange,
  sortById: sortSuppliersById
} = useIdColumnSort<Row>('supplierId');

const purchaseOrders = ref<Row[]>([]);
const purchaseReturns = ref<Row[]>([]);
const transfers = ref<Row[]>([]);
const transferDialog = ref(false);
const transferForm = reactive({
  fromWarehouseId: '',
  toWarehouseId: '',
  notes: '',
  skuId: '',
  batchNo: '',
  quantity: 1
});
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
const paymentDialog = ref(false);
const paymentForm = reactive<Row>({ payableId: null, amountYuan: 0, notes: '' });
const payTarget = ref<Row>({});
const stocktakes = ref<Row[]>([]);
const stocktakeStatusFilter = ref('');
const stocktakeDialog = ref(false);
const stocktakeDetailDialog = ref(false);
const scanningPhoto = ref(false);
const stocktakePhotoInput = ref<HTMLInputElement | null>(null);
const stocktakeForm = reactive<Row>({ warehouseId: '', mode: 'OPEN', notes: '' });
const stocktakeDetail = ref<Row>({});
const bins = ref<Row[]>([]);
const binStock = ref<Row[]>([]);
const filterBinId = ref<number | null>(null);
const binDialog = ref(false);
const binInboundDialog = ref(false);
const binMoveDialog = ref(false);
const binForm = reactive<Row>({
  editing: false,
  warehouseId: '',
  binCode: '',
  binName: '',
  status: 'ACTIVE'
});
const binInboundForm = reactive<Row>({
  warehouseId: '',
  binCode: '',
  skuId: '',
  batchNo: '',
  productionDate: localDate(),
  expiryDate: '',
  quantity: 1
});
const binMoveForm = reactive<Row>({
  fromBinId: null,
  toBinId: null,
  skuId: '',
  batchNo: '',
  quantity: 1
});
const devices = ref<Row[]>([]);
const skus = ref<Row[]>([]);
const loadedTabs = ref(new Set<string>(['warehouses']));

const warehouseDialog = ref(false);
const supplierDialog = ref(false);
const purchaseDialog = ref(false);
const receiveDialog = ref(false);
const returnDialog = ref(false);
const inboundDialog = ref(false);
const dialogBootLoading = ref(false);

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
  paymentTermsDays: 30,
  creditLimitYuan: 0,
  status: 'ACTIVE'
});
const purchaseForm = reactive<Row>({
  supplierId: '',
  warehouseId: '',
  refNo: '',
  notes: '',
  lines: []
});
const receiveForm = reactive<Row>({
  purchaseOrderId: null,
  notes: '',
  receiveWarehouseId: '',
  lines: []
});
const returnForm = reactive<Row>({ purchaseOrderId: null, notes: '', lines: [] });
const inboundForm = reactive<Row>({ warehouseId: '', refNo: '', notes: '', lines: [] });

const pageHint = computed(() => {
  if (tab.value === 'transit') {
    return `在途签收时限 ${TRANSIT_OVERDUE_HOURS} 小时；超时标红，可勾选「仅超时」`;
  }
  return '仓库 / 供应商 / 库存 / 采购与退货';
});

const showFilterBar = computed(() =>
  [
    'warehouses',
    'suppliers',
    'purchase',
    'returns',
    'suggestions',
    'payables',
    'stocktakes',
    'bins',
    'inventory',
    'movements',
    'outbounds',
    'transit'
  ].includes(tab.value)
);
const activeSuppliers = computed(() => suppliers.value.filter((s) => s.status === 'ACTIVE'));
const activeWarehouses = computed(() =>
  warehouses.value.filter((w) => (w.status || 'ACTIVE') === 'ACTIVE')
);
const filteredSuppliers = computed(() => suppliers.value);
const filteredPurchaseOrders = computed(() => purchaseOrders.value);
const filteredPurchaseReturns = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  let list = purchaseReturns.value;
  if (filterWarehouseId.value) list = list.filter((r) => r.warehouseId === filterWarehouseId.value);
  if (!q) return list;
  return list.filter((r) =>
    [r.returnId, r.purchaseOrderId, r.supplierId, supplierName(r.supplierId)]
      .join(' ')
      .toLowerCase()
      .includes(q)
  );
});
const returnablePurchaseOrders = ref<Row[]>([]);
const OUTBOUND_STATUS_RANK: Record<string, number> = {
  PICKED: 0,
  DRAFT: 1,
  SHIPPED: 2,
  CANCELLED: 3
};

function isOutboundActionable(row: Row) {
  const hasLines = (row.lines?.length || 0) > 0;
  return (row.status === 'DRAFT' && hasLines) || (row.status === 'PICKED' && hasLines);
}

const filteredOutbounds = computed(() => {
  let list = outbounds.value;
  const st = filterOutboundStatus.value;
  if (st === 'actionable') {
    list = list.filter((o) => isOutboundActionable(o));
  } else if (st) {
    list = list.filter((o) => o.status === st);
  }
  return [...list].sort((a, b) => {
    const ra = OUTBOUND_STATUS_RANK[String(a.status)] ?? 9;
    const rb = OUTBOUND_STATUS_RANK[String(b.status)] ?? 9;
    if (ra !== rb) return ra - rb;
    return Number(b.outboundId) - Number(a.outboundId);
  });
});

function outboundRowClassName({ row }: { row: Row }) {
  const parts = [`outbound-tr-${row.outboundId}`];
  if (isOutboundActionable(row)) parts.push('outbound-row--actionable');
  return parts.join(' ');
}

function onOutboundStatusFilter() {
  page.value = 1;
  selectedKeys.value = [];
}

function parseTs(value: unknown) {
  if (value == null || value === '') return Number.NaN;
  if (typeof value === 'number') return value;
  const t = Date.parse(String(value));
  return Number.isNaN(t) ? Number.NaN : t;
}

function transitCreatedMs(row: Row) {
  return parseTs(row.createdAt);
}

function transitAgeMs(row: Row) {
  const t = transitCreatedMs(row);
  return Number.isNaN(t) ? 0 : Math.max(0, Date.now() - t);
}

function transitRemainMs(row: Row) {
  const t = transitCreatedMs(row);
  if (Number.isNaN(t)) return TRANSIT_OVERDUE_MS;
  return Math.max(0, t + TRANSIT_OVERDUE_MS - Date.now());
}

function transitOverdueMs(row: Row) {
  const t = transitCreatedMs(row);
  if (Number.isNaN(t)) return 0;
  return Math.max(0, Date.now() - (t + TRANSIT_OVERDUE_MS));
}

function isTransitOverdue(row: Row) {
  const t = transitCreatedMs(row);
  if (Number.isNaN(t)) return false;
  return Date.now() - t >= TRANSIT_OVERDUE_MS;
}

function isTransitDueSoon(row: Row) {
  if (isTransitOverdue(row)) return false;
  const left = transitRemainMs(row);
  return left > 0 && left <= TRANSIT_DUE_SOON_MS;
}

function formatAge(ms: number) {
  const abs = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(abs / 3600);
  const m = Math.floor((abs % 3600) / 60);
  if (h >= 48) return `${Math.floor(h / 24)} 天`;
  if (h > 0) return `${h} 小时 ${m} 分`;
  if (m > 0) return `${m} 分钟`;
  return '不到 1 分钟';
}

function transitRowClassName({ row }: { row: Row }) {
  const classes: string[] = [];
  if (isTransitOverdue(row)) classes.push('is-overdue');
  else if (isTransitDueSoon(row)) classes.push('is-due-soon');
  if (focusDeviceId.value && row.deviceId === focusDeviceId.value) classes.push('is-focus');
  return classes.join(' ');
}

const filteredInTransit = computed(() => {
  let list = [...inTransit.value];
  if (focusDeviceId.value) {
    list = list.filter((r) => r.deviceId === focusDeviceId.value);
  }
  if (overdueOnly.value) {
    list = list.filter((r) => isTransitOverdue(r));
  }
  return list.sort((a, b) => {
    const ao = isTransitOverdue(a) ? 0 : 1;
    const bo = isTransitOverdue(b) ? 0 : 1;
    if (ao !== bo) return ao - bo;
    const at = transitCreatedMs(a);
    const bt = transitCreatedMs(b);
    if (Number.isNaN(at) && Number.isNaN(bt)) return 0;
    if (Number.isNaN(at)) return 1;
    if (Number.isNaN(bt)) return -1;
    return at - bt;
  });
});

const overdueTransitCount = computed(() => {
  let list = inTransit.value;
  if (focusDeviceId.value) {
    list = list.filter((r) => r.deviceId === focusDeviceId.value);
  }
  return list.filter((r) => isTransitOverdue(r)).length;
});

const transitEmptyHint = computed(() => {
  if (overdueOnly.value) {
    return focusDeviceId.value
      ? `设备 ${focusDeviceId.value} 无超过 ${TRANSIT_OVERDUE_HOURS} 小时的签收超时`
      : `当前无超过 ${TRANSIT_OVERDUE_HOURS} 小时的签收超时`;
  }
  if (focusDeviceId.value) return `设备 ${focusDeviceId.value} 暂无在途`;
  return '暂无在途';
});

function onOverdueToggle() {
  page.value = 1;
  selectedKeys.value = [];
  syncRouteQuery();
}

function clearFocusDevice() {
  focusDeviceId.value = '';
  page.value = 1;
  syncRouteQuery();
}

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
const pagedWarehouses = computed(() => sortWarehousesById(warehouses.value));
const pagedSuppliers = computed(() => sortSuppliersById(suppliers.value));
const pagedPurchaseOrders = computed(() => purchaseOrders.value);
const pagedPurchaseReturns = computed(() => purchaseReturns.value);
const pagedSuggestions = computed(() => suggestions.value);
const pagedPayables = computed(() => payables.value);
const pagedStocktakes = computed(() => stocktakes.value);
const pagedBinStock = computed(() => binStock.value);
const pagedOutbounds = computed(() => filteredOutbounds.value);
const pagedInTransit = computed(() => filteredInTransit.value);
const pagedInventory = computed(() => inventory.value);
const pagedMovements = computed(() => movements.value);
const payableSummaryText = computed(() => {
  const rows = payableSummary.value;
  if (!rows.length) return '暂无未结清应付账款';
  const total = rows.reduce((s, r) => s + (Number(r.totalBalanceCents) || 0), 0);
  const overdue = rows.reduce((s, r) => s + (Number(r.overdueBalanceCents) || 0), 0);
  return `共 ${rows.length} 家供应商有欠款，未付合计 ¥${money(total)}，其中逾期 ¥${money(overdue)}`;
});
const payMaxYuan = computed(() => Number((payTarget.value.balanceCents || 0) / 100));
const allBins = computed(() => bins.value);
const sourceBinSkus = computed(() => {
  const rows = binStock.value.filter((r) => r.binId === binMoveForm.fromBinId);
  const map = new Map<string, Row>();
  for (const r of rows) {
    if (!map.has(r.skuId)) {
      map.set(r.skuId, { skuId: r.skuId, skuName: r.skuName });
    }
  }
  return [...map.values()];
});
const sourceBinMaxQty = computed(() =>
  binStock.value
    .filter((r) => r.binId === binMoveForm.fromBinId && r.skuId === binMoveForm.skuId)
    .reduce((s, r) => s + (Number(r.quantity) || 0), 0)
);

watch(tab, () => {
  page.value = 1;
  selectedKeys.value = [];
});
watch([keyword, filterWarehouseId, focusDeviceId], () => {
  page.value = 1;
  if (SERVER_PAGINATED_TABS.has(tab.value)) {
    loadedTabs.value.delete(tab.value);
    loadTab(tab.value, true);
  }
});
watch(overdueOnly, () => {
  page.value = 1;
});

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
    pickSelected(warehouses.value).map((row) => [
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
      await api.request(`/api/v2/ops/admin/warehouse/${encodeURIComponent(warehouseId)}`, 'PUT', {
        warehouseName,
        address: (row['地址'] || row.address || '').trim(),
        status: statusCode(row['状态'] || row.status)
      });
      ok++;
    }
    loadedTabs.value.delete('warehouses');
    await loadTab('warehouses', true);
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
    pickSelected(filteredSuppliers.value).map((row) => [
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
      await api.request(`/api/v2/ops/admin/suppliers/${encodeURIComponent(supplierId)}`, 'PUT', {
        supplierId,
        supplierName,
        contactName: (row['联系人'] || row.contactName || '').trim(),
        contactPhone: (row['联系电话'] || row.contactPhone || '').trim(),
        status: statusCode(row['状态'] || row.status)
      });
      ok++;
    }
    loadedTabs.value.delete('suppliers');
    await loadTab('suppliers', true);
    return ok;
  }
});

const importing = computed(() => importingWarehouses.value || importingSuppliers.value);

function onDownloadImportTemplate() {
  if (tab.value === 'warehouses') {
    downloadWarehouseTemplate(['演示中心仓', 'WH-DEMO-001', '上海市示例路 1 号', '启用']);
  } else if (tab.value === 'suppliers') {
    downloadSupplierTemplate([
      'Demo Beverage Supplier',
      'SUP-DEMO-001',
      '张三',
      '13800000000',
      '启用'
    ]);
  }
}

function triggerImport() {
  if (tab.value === 'warehouses') triggerWarehouseImport();
  else if (tab.value === 'suppliers') triggerSupplierImport();
}

const { onExport: exportPurchase } = useListCsv({
  filePrefix: '采购单',
  headers: ['采购单', '外部单号', '供应商', '入库仓库', '状态'],
  toRows: () =>
    pickSelected(filteredPurchaseOrders.value).map((row) => [
      row.purchaseOrderId,
      row.refNo || '未填写',
      supplierName(row.supplierId),
      warehouseName(row.warehouseId),
      dictLabel('purchase_order_status', row.status)
    ])
});

const { onExport: exportReturns } = useListCsv({
  filePrefix: '采购退货',
  headers: ['退货单', '采购单', '供应商', '仓库', '状态', '创建时间'],
  toRows: () =>
    pickSelected(filteredPurchaseReturns.value).map((row) => [
      row.returnId,
      row.purchaseOrderId,
      supplierName(row.supplierId),
      warehouseName(row.warehouseId),
      returnStatusLabel(row.status),
      formatDateTime(row.createdAt)
    ])
});

const { onExport: exportOutbounds } = useListCsv({
  filePrefix: '出库单',
  headers: ['出库单', '路线', '出库仓库', '状态', '创建时间'],
  toRows: () =>
    pickSelected(filteredOutbounds.value).map((row) => [
      row.outboundId,
      row.routeId || '',
      warehouseName(row.warehouseId),
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
    pickSelected(filteredInTransit.value).map((row) => [
      row.outboundId,
      deviceName(row.deviceId),
      skuName(row.skuId),
      row.batchNo || '',
      row.quantity,
      dictLabel('in_transit_status', row.status),
      formatAge(transitAgeMs(row)),
      isTransitOverdue(row) ? '是' : '否',
      formatDateTime(row.createdAt)
    ])
});

const { onExport: exportInventory } = useListCsv({
  filePrefix: '批次库存',
  headers: ['仓库', '商品', '批次', '生产日期', '到期日期', '库存', '效期'],
  toRows: () =>
    pickSelected(inventory.value).map((row) => [
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
    pickSelected(movements.value).map((row) => [
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
    switch (tab.value) {
      case 'warehouses':
        return warehouses.value;
      case 'suppliers':
        return suppliers.value;
      case 'purchase':
        return purchaseOrders.value;
      case 'returns':
        return purchaseReturns.value;
      case 'inventory':
        return inventory.value;
      case 'outbounds':
        return outbounds.value;
      default:
        return [];
    }
  })();
  const partial = selectedKeys.value.length > 0 && selectedKeys.value.length < currentRows.length;
  if (partial || !serverTabs.has(tab.value)) {
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
    exporters[tab.value]?.();
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
      `/api/v2/ops/admin/warehouse/export?tab=${encodeURIComponent(tab.value)}`,
      csvFileName(labels[tab.value] || '仓库')
    );
    ElMessage.success('已导出');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

function returnStatusLabel(status?: string) {
  const code = (status || 'COMPLETED').toUpperCase();
  if (code === 'COMPLETED') return '已完成';
  if (code === 'CANCELLED') return '已取消';
  return status || '已完成';
}
function supplierName(id: string) {
  return suppliers.value.find((s) => s.supplierId === id)?.supplierName || id || '无';
}
function warehouseName(id: string) {
  return warehouses.value.find((w) => w.warehouseId === id)?.warehouseName || id || '无';
}
function transferStatusLabel(status?: string) {
  if (!status) return '';
  return (
    (
      {
        DRAFT: '草稿',
        SHIPPED: '已发运',
        RECEIVED: '已收货',
        CANCELLED: '已取消'
      } as Record<string, string>
    )[String(status).toUpperCase()] || status
  );
}
function deviceName(id: string) {
  return devices.value.find((d) => d.deviceId === id)?.deviceName || id || '无';
}
function skuName(id: string) {
  return skus.value.find((s) => s.skuId === id)?.skuName || id || '无';
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
function localDate() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

/** 未手填外部单号时，创建采购单用时间戳单号。 */
function defaultPurchaseRefNo() {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `PO-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
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
function openPrint(type: string, query: Record<string, string | number>) {
  const url = router.resolve({ name: 'print', query: { type, ...query } }).href;
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

function outboundActions(row: Row): TableAction[] {
  const acts: TableAction[] = [];
  const hasLines = (row.lines?.length || 0) > 0;
  if (row.status === 'DRAFT' && hasLines) {
    acts.push({ key: 'pick', label: '确认拣货', icon: Box, type: 'primary' });
  }
  if (row.status === 'PICKED' && hasLines) {
    acts.push({ key: 'ship', label: '确认发运', icon: Van, type: 'danger' });
  }
  if (['DRAFT', 'PICKED', 'SHIPPED'].includes(String(row.status || '')) && hasLines) {
    const handed = (row.lines || []).some((l: Row) =>
      ['RECEIVED', 'PARTIAL'].includes(String(l.handoverStatus || ''))
    );
    if (!handed) {
      acts.push({
        key: 'cancel-unreceived',
        label: row.status === 'SHIPPED' ? '作废回仓' : '作废出库',
        icon: RefreshLeft,
        type: 'warning',
        overflow: acts.length >= 2
      });
    }
  }
  return acts;
}

async function ensureMeta() {
  if (!devices.value.length) {
    if (!auth.hasPerm('ops:device:list') && !auth.hasPerm('ops:device:ref')) {
      devices.value = [];
    } else {
      devices.value = await api
        .request<Row[]>('/api/v2/ops/admin/devices/ref', 'GET')
        .catch(() => []);
    }
  }
  if (!skus.value.length) {
    skus.value =
      (
        await api
          .request<{ items: Row[] }>('/api/v2/ops/admin/skus?page=0&size=500', 'GET')
          .catch(() => ({ items: [] as Row[] }))
      ).items || [];
  }
}

async function loadWarehouses() {
  const q = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (keyword.value.trim()) q.set('q', keyword.value.trim());
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/warehouse/list?${q}`,
    'GET'
  );
  warehouses.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, warehouses: Number(data.total) || 0 };
}
async function loadWarehousesSoft() {
  try {
    const data = await api.request<{ items: Row[] }>(
      '/api/v2/ops/admin/warehouse/list?page=0&size=500',
      'GET'
    );
    warehouses.value = data.items || [];
  } catch {
    /* 筛选用元数据失败时保留旧列表，不拖垮库存/出库主数据 */
  }
}
async function loadSuppliers() {
  const q = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (keyword.value.trim()) q.set('q', keyword.value.trim());
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/suppliers?${q}`,
    'GET'
  );
  suppliers.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, suppliers: Number(data.total) || 0 };
}
async function loadSuppliersSoft() {
  try {
    const data = await api.request<{ items: Row[] }>(
      '/api/v2/ops/admin/suppliers?page=0&size=500',
      'GET'
    );
    suppliers.value = data.items || [];
  } catch {
    /* 采购/退货筛选项可选 */
  }
}
async function loadPurchase() {
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/purchase-orders?${warehouseListParams()}`,
    'GET'
  );
  purchaseOrders.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, purchase: Number(data.total) || 0 };
}
async function loadReturnablePurchaseOrders() {
  try {
    returnablePurchaseOrders.value =
      (
        await api.request<{ items: Row[] }>(
          '/api/v2/ops/admin/purchase-orders?returnableOnly=true&page=0&size=500',
          'GET'
        )
      ).items || [];
  } catch {
    returnablePurchaseOrders.value = [];
  }
}
async function loadReturns() {
  const q = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (keyword.value.trim()) q.set('q', keyword.value.trim());
  if (filterWarehouseId.value) q.set('warehouseId', filterWarehouseId.value);
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/purchase-returns?${q}`,
    'GET'
  );
  purchaseReturns.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, returns: Number(data.total) || 0 };
}
async function loadOutbounds() {
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/warehouse/outbounds?${warehouseListParams()}`,
    'GET'
  );
  outbounds.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, outbounds: Number(data.total) || 0 };
}
async function loadTransit() {
  const q = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (focusDeviceId.value) q.set('deviceId', focusDeviceId.value);
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/warehouse/in-transit?${q}`,
    'GET'
  );
  inTransit.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, transit: Number(data.total) || 0 };
}
async function loadInventory() {
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/warehouse/inventory?${warehouseListParams()}`,
    'GET'
  );
  inventory.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, inventory: Number(data.total) || 0 };
}
async function loadMovements() {
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/warehouse/movements?${warehouseListParams()}`,
    'GET'
  );
  movements.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, movements: Number(data.total) || 0 };
}

function warehouseListParams() {
  const q = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (keyword.value.trim()) q.set('q', keyword.value.trim());
  if (filterWarehouseId.value) q.set('warehouseId', filterWarehouseId.value);
  return q;
}

function onPagerChange() {
  if (SERVER_PAGINATED_TABS.has(tab.value)) {
    loadTab(tab.value, true);
  }
}

function onPagerSizeChange() {
  page.value = 1;
  if (SERVER_PAGINATED_TABS.has(tab.value)) {
    loadTab(tab.value, true);
  }
}
async function loadSuggestions() {
  const params = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (suggestionLeadTimeDays.value > 0) {
    params.set('leadTimeDays', String(suggestionLeadTimeDays.value));
  }
  if (suggestionCoverageDays.value > 0) {
    params.set('coverageDays', String(suggestionCoverageDays.value));
  }
  if (filterWarehouseId.value) {
    params.set('warehouseId', filterWarehouseId.value);
  }
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/procurement/suggestions?${params}`,
    'GET'
  );
  suggestions.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, suggestions: Number(data.total) || 0 };
}
async function loadPayables() {
  const params = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (payableStatusFilter.value) params.set('status', payableStatusFilter.value);
  if (payableOverdueOnly.value) params.set('overdueOnly', 'true');
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/suppliers/payables?${params}`,
    'GET'
  );
  payables.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, payables: Number(data.total) || 0 };
}
async function loadPayableSummary() {
  payableSummary.value = await api
    .request<Row[]>('/api/v2/ops/admin/suppliers/payables/summary', 'GET')
    .catch(() => []);
}
async function loadStocktakes() {
  const params = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (stocktakeStatusFilter.value) params.set('status', stocktakeStatusFilter.value);
  if (filterWarehouseId.value) params.set('warehouseId', filterWarehouseId.value);
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/warehouse/stocktakes?${params}`,
    'GET'
  );
  stocktakes.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, stocktakes: Number(data.total) || 0 };
}
async function loadBins() {
  bins.value = await api.request<Row[]>('/api/v2/ops/admin/warehouse/bins', 'GET');
}
async function loadBinStock() {
  const params = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (filterWarehouseId.value) params.set('warehouseId', filterWarehouseId.value);
  if (filterBinId.value != null) params.set('binId', String(filterBinId.value));
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/warehouse/bins/stock?${params}`,
    'GET'
  );
  binStock.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, bins: Number(data.total) || 0 };
}
async function loadTransfers() {
  const q = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  const data = await api.request<{ items: Row[]; total: number }>(
    `/api/v2/ops/admin/warehouse/transfers?${q}`,
    'GET'
  );
  transfers.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, transfers: Number(data.total) || 0 };
}
async function openTransferCreate() {
  Object.assign(transferForm, {
    fromWarehouseId: warehouses.value[0]?.warehouseId || '',
    toWarehouseId: warehouses.value[1]?.warehouseId || '',
    notes: '',
    skuId: '',
    batchNo: '',
    quantity: 1
  });
  await loadWarehousesSoft();
  transferDialog.value = true;
}
async function saveTransfer() {
  if (!transferForm.fromWarehouseId || !transferForm.toWarehouseId || !transferForm.skuId.trim()) {
    ElMessage.warning('请填写调出/调入仓与 SKU');
    return;
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/warehouse/transfers', 'POST', {
      fromWarehouseId: transferForm.fromWarehouseId,
      toWarehouseId: transferForm.toWarehouseId,
      notes: transferForm.notes,
      lines: [
        {
          skuId: transferForm.skuId.trim(),
          batchNo: transferForm.batchNo || '',
          quantity: transferForm.quantity
        }
      ]
    });
    transferDialog.value = false;
    ElMessage.success('调拨单已创建');
    loadedTabs.value.delete('transfers');
    await loadTab('transfers', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  } finally {
    saving.value = false;
  }
}
async function shipTransfer(row: Row) {
  await api.request(`/api/v2/ops/admin/warehouse/transfers/${row.transferId}/ship`, 'POST');
  ElMessage.success('已发运');
  loadedTabs.value.delete('transfers');
  await loadTab('transfers', true);
}
async function receiveTransfer(row: Row) {
  await api.request(`/api/v2/ops/admin/warehouse/transfers/${row.transferId}/receive`, 'POST');
  ElMessage.success('已收货入库');
  loadedTabs.value.delete('transfers');
  await loadTab('transfers', true);
}
async function cancelTransfer(row: Row) {
  await api.request(`/api/v2/ops/admin/warehouse/transfers/${row.transferId}/cancel`, 'POST');
  ElMessage.success('已取消');
  loadedTabs.value.delete('transfers');
  await loadTab('transfers', true);
}

async function loadTab(name: string, force = false) {
  if (!force && loadedTabs.value.has(name) && name !== 'inventory' && name !== 'movements') return;
  const nextLoading = new Set(loadingTabs.value);
  nextLoading.add(name);
  loadingTabs.value = nextLoading;
  try {
    await ensureMeta();
    if (name === 'warehouses') await loadWarehouses();
    else if (name === 'suppliers') await loadSuppliers();
    else if (name === 'purchase') {
      await Promise.all([loadPurchase(), loadSuppliersSoft(), loadWarehousesSoft()]);
    } else if (name === 'returns') {
      await Promise.all([
        loadReturns(),
        loadReturnablePurchaseOrders(),
        loadPurchase().catch(() => {}),
        loadSuppliersSoft(),
        loadWarehousesSoft()
      ]);
    } else if (name === 'suggestions') {
      await Promise.all([loadSuggestions(), loadWarehousesSoft(), loadSuppliersSoft()]);
    } else if (name === 'payables') {
      await Promise.all([loadPayables(), loadPayableSummary(), loadSuppliersSoft()]);
    } else if (name === 'stocktakes') {
      await Promise.all([loadStocktakes(), loadWarehousesSoft()]);
    } else if (name === 'bins') {
      await Promise.all([loadBins(), loadBinStock(), loadWarehousesSoft()]);
    } else if (name === 'outbounds') {
      await Promise.all([loadOutbounds(), loadWarehousesSoft()]);
    } else if (name === 'transit') await loadTransit();
    else if (name === 'transfers') {
      await Promise.all([loadTransfers(), loadWarehousesSoft()]);
    } else if (name === 'inventory') {
      await Promise.all([loadInventory(), loadWarehousesSoft()]);
    } else if (name === 'movements') {
      await Promise.all([loadMovements(), loadWarehousesSoft()]);
    }
    loadedTabs.value.add(name);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    const next = new Set(hydratedTabs.value);
    next.add(name);
    hydratedTabs.value = next;
    const doneLoading = new Set(loadingTabs.value);
    doneLoading.delete(name);
    loadingTabs.value = doneLoading;
  }
}

function onTabChange(name: string | number) {
  page.value = 1;
  const next = String(name);
  if (next !== 'transit') {
    overdueOnly.value = false;
    focusDeviceId.value = '';
  }
  syncRouteQuery(next);
  loadTab(next);
}
function reloadCurrent() {
  loadedTabs.value.delete(tab.value);
  loadTab(tab.value, true);
}
function onWarehouseFilter() {
  page.value = 1;
  if (SERVER_PAGINATED_TABS.has(tab.value)) {
    loadedTabs.value.delete(tab.value);
    loadTab(tab.value, true);
  }
}
function onSuggestionParamsChange() {
  page.value = 1;
  loadedTabs.value.delete('suggestions');
  loadTab('suggestions', true);
}
function onPayableFilter() {
  page.value = 1;
  loadedTabs.value.delete('payables');
  loadTab('payables', true);
}
function onStocktakeFilter() {
  page.value = 1;
  loadedTabs.value.delete('stocktakes');
  loadTab('stocktakes', true);
}
function onBinFilter() {
  page.value = 1;
  loadedTabs.value.delete('bins');
  loadTab('bins', true);
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
    await api.request(
      `/api/v2/ops/admin/warehouse/${encodeURIComponent(warehouseForm.warehouseId.trim())}`,
      'PUT',
      {
        warehouseName: warehouseForm.warehouseName.trim(),
        address: warehouseForm.address,
        status: warehouseForm.status
      }
    );
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
    paymentTermsDays: row?.paymentTermsDays || 30,
    creditLimitYuan: row?.creditLimitCents != null ? Number(row.creditLimitCents) / 100 : 0,
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
    await api.request(
      `/api/v2/ops/admin/suppliers/${encodeURIComponent(supplierForm.supplierId.trim())}`,
      'PUT',
      {
        supplierId: supplierForm.supplierId.trim(),
        supplierName: supplierForm.supplierName.trim(),
        contactName: supplierForm.contactName,
        contactPhone: supplierForm.contactPhone,
        paymentTermsDays: Number(supplierForm.paymentTermsDays) || 30,
        creditLimitCents: Math.round((Number(supplierForm.creditLimitYuan) || 0) * 100),
        status: supplierForm.status
      }
    );
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
  Object.assign(purchaseForm, {
    supplierId: '',
    warehouseId: '',
    refNo: defaultPurchaseRefNo(),
    notes: '',
    lines: [newLine()]
  });
  purchaseDialog.value = true;
  dialogBootLoading.value = true;
  try {
    await Promise.all([loadSuppliersSoft(), loadWarehousesSoft(), ensureMeta()]);
    purchaseForm.supplierId = activeSuppliers.value[0]?.supplierId || '';
    purchaseForm.warehouseId = activeWarehouses.value[0]?.warehouseId || '';
  } finally {
    dialogBootLoading.value = false;
  }
}
async function openPurchaseFromSuggestions() {
  const rows = pickSelected(suggestions.value);
  if (!rows.length) {
    return ElMessage.warning('当前没有可用的采购建议');
  }
  Object.assign(purchaseForm, {
    supplierId: '',
    warehouseId: filterWarehouseId.value || '',
    refNo: defaultPurchaseRefNo(),
    notes: `由采购建议生成（覆盖 ${suggestionCoverageDays.value} 天）`,
    lines: rows.map((r: Row) => ({
      skuId: r.skuId,
      batchNo: '',
      productionDate: localDate(),
      expiryDate: '',
      orderedQty: r.suggestQty || 1,
      receivedQty: 0,
      unitCostYuan: 1
    }))
  });
  purchaseDialog.value = true;
  dialogBootLoading.value = true;
  try {
    await Promise.all([loadSuppliersSoft(), loadWarehousesSoft(), ensureMeta()]);
    purchaseForm.supplierId = activeSuppliers.value[0]?.supplierId || '';
    if (!purchaseForm.warehouseId) {
      purchaseForm.warehouseId = activeWarehouses.value[0]?.warehouseId || '';
    }
  } finally {
    dialogBootLoading.value = false;
  }
}
function openPay(row: Row) {
  Object.assign(payTarget.value, row);
  paymentForm.payableId = row.payableId;
  paymentForm.amountYuan = Number((Number(row.balanceCents) || 0) / 100);
  paymentForm.notes = '';
  paymentDialog.value = true;
}
async function savePayment() {
  if (!paymentForm.payableId) return;
  const amountCents = Math.round((Number(paymentForm.amountYuan) || 0) * 100);
  if (amountCents <= 0) return ElMessage.warning('请输入付款金额');
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/suppliers/payables/${paymentForm.payableId}/pay`, 'POST', {
      amountCents,
      notes: paymentForm.notes
    });
    paymentDialog.value = false;
    ElMessage.success('付款登记成功');
    loadedTabs.value.delete('payables');
    await loadTab('payables', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '付款登记失败');
  } finally {
    saving.value = false;
  }
}
async function openStocktakeCreate() {
  Object.assign(stocktakeForm, { warehouseId: '', mode: 'OPEN', notes: '' });
  stocktakeDialog.value = true;
  try {
    await loadWarehousesSoft();
    stocktakeForm.warehouseId = activeWarehouses.value[0]?.warehouseId || '';
  } catch {
    /* 保留空值由用户选择 */
  }
}
async function saveStocktake() {
  if (!stocktakeForm.warehouseId) return ElMessage.warning('请选择仓库');
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/warehouse/stocktakes', 'POST', {
      warehouseId: stocktakeForm.warehouseId,
      mode: stocktakeForm.mode,
      notes: stocktakeForm.notes
    });
    stocktakeDialog.value = false;
    ElMessage.success('盘点单已创建');
    loadedTabs.value.delete('stocktakes');
    await loadTab('stocktakes', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  } finally {
    saving.value = false;
  }
}
async function openStocktakeDetail(row: Row) {
  try {
    stocktakeDetail.value = await api.request<Row>(
      `/api/v2/ops/admin/warehouse/stocktakes/${row.stocktakeId}`,
      'GET'
    );
    stocktakeDetailDialog.value = true;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  }
}
function triggerStocktakePhotoScan() {
  stocktakePhotoInput.value?.click();
}
async function onStocktakePhoto(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  if (file.size > 8 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 8MB');
    return;
  }
  scanningPhoto.value = true;
  try {
    const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
    const form = new FormData();
    form.append('file', file);
    const res = await authFetch(
      `${base}/api/v2/ops/admin/warehouse/stocktakes/${stocktakeDetail.value.stocktakeId}/scan-photo`,
      {
        method: 'POST',
        body: form
      }
    );
    const json = await res.json().catch(() => ({}));
    if (!res.ok || json.code !== 0) {
      throw new Error(json.message || `识别失败 (${res.status})`);
    }
    stocktakeDetail.value = json.data;
    ElMessage.success('识别完成，已自动填入实盘数，请核对后保存');
    loadedTabs.value.delete('stocktakes');
    await loadTab('stocktakes', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '识别失败');
  } finally {
    scanningPhoto.value = false;
  }
}
async function reloadStocktakeDetail() {
  const id = stocktakeDetail.value.stocktakeId;
  if (!id) return;
  stocktakeDetail.value = await api.request<Row>(
    `/api/v2/ops/admin/warehouse/stocktakes/${id}`,
    'GET'
  );
  loadedTabs.value.delete('stocktakes');
  await loadTab('stocktakes', true);
}
async function saveStocktakeLines() {
  const id = stocktakeDetail.value.stocktakeId;
  const lines: Row[] = stocktakeDetail.value.lines || [];
  saving.value = true;
  try {
    await Promise.all(
      lines
        .filter((l: Row) => l.countedQty != null)
        .map((l: Row) =>
          api.request(`/api/v2/ops/admin/warehouse/stocktakes/${id}/lines/${l.lineId}`, 'PUT', {
            countedQty: l.countedQty,
            notes: l.notes
          })
        )
    );
    ElMessage.success('实盘数据已保存');
    await reloadStocktakeDetail();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}
async function completeStocktakeAction() {
  const id = stocktakeDetail.value.stocktakeId;
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/warehouse/stocktakes/${id}/complete`, 'POST');
    ElMessage.success('盘点已完成');
    await reloadStocktakeDetail();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '完成失败');
  } finally {
    saving.value = false;
  }
}
async function adjustStocktakeAction() {
  const id = stocktakeDetail.value.stocktakeId;
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/warehouse/stocktakes/${id}/adjust`, 'POST', {});
    ElMessage.success('差异已调整入库');
    await reloadStocktakeDetail();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '调整失败');
  } finally {
    saving.value = false;
  }
}
async function cancelStocktakeAction() {
  const id = stocktakeDetail.value.stocktakeId;
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/warehouse/stocktakes/${id}/cancel`, 'POST');
    ElMessage.success('盘点单已取消');
    await reloadStocktakeDetail();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '取消失败');
  } finally {
    saving.value = false;
  }
}
function openBinDialog(row?: Row) {
  Object.assign(binForm, {
    editing: !!row,
    warehouseId: row?.warehouseId || activeWarehouses.value[0]?.warehouseId || '',
    binCode: row?.binCode || '',
    binName: row?.binName || '',
    status: row?.status || 'ACTIVE'
  });
  binDialog.value = true;
}
async function saveBin() {
  if (!binForm.warehouseId || !binForm.binCode.trim()) {
    return ElMessage.warning('请填写仓库和货位编码');
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/warehouse/bins', 'PUT', {
      warehouseId: binForm.warehouseId,
      binCode: binForm.binCode.trim(),
      binName: binForm.binName,
      status: binForm.status
    });
    binDialog.value = false;
    ElMessage.success('货位已保存');
    loadedTabs.value.delete('bins');
    await loadTab('bins', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}
function activeBinsFor(warehouseId: string) {
  return bins.value.filter((b) => b.warehouseId === warehouseId && b.status === 'ACTIVE');
}
function binLabel(b: Row) {
  return b.binName ? `${b.binCode} · ${b.binName}` : b.binCode;
}
function onBinInboundWarehouse() {
  const first = activeBinsFor(binInboundForm.warehouseId)[0];
  binInboundForm.binCode = first?.binCode || '';
}
async function openBinInbound() {
  Object.assign(binInboundForm, {
    warehouseId: filterWarehouseId.value || activeWarehouses.value[0]?.warehouseId || '',
    binCode: '',
    skuId: skus.value[0]?.skuId || '',
    batchNo: '',
    productionDate: localDate(),
    expiryDate: '',
    quantity: 1
  });
  binInboundDialog.value = true;
  try {
    await Promise.all([loadWarehousesSoft(), ensureMeta()]);
  } catch {
    /* 保留旧值 */
  }
  onBinInboundWarehouse();
}
async function saveBinInbound() {
  if (
    !binInboundForm.warehouseId ||
    !binInboundForm.binCode ||
    !binInboundForm.skuId ||
    !binInboundForm.batchNo.trim() ||
    !binInboundForm.expiryDate
  ) {
    return ElMessage.warning('请完整填写仓库、货位、商品、批次和到期日');
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/warehouse/bins/stock/inbound', 'POST', {
      warehouseId: binInboundForm.warehouseId,
      binCode: binInboundForm.binCode,
      skuId: binInboundForm.skuId,
      batchNo: binInboundForm.batchNo.trim(),
      productionDate: binInboundForm.productionDate,
      expiryDate: binInboundForm.expiryDate,
      quantity: Number(binInboundForm.quantity) || 0
    });
    binInboundDialog.value = false;
    ElMessage.success('已入库到货位');
    loadedTabs.value.delete('bins');
    await loadTab('bins', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '入库失败');
  } finally {
    saving.value = false;
  }
}
function onBinMoveSource() {
  const first = sourceBinSkus.value[0];
  binMoveForm.skuId = first?.skuId || '';
  binMoveForm.batchNo = '';
  binMoveForm.quantity = 1;
}
async function openBinMove() {
  Object.assign(binMoveForm, {
    fromBinId: allBins.value[0]?.binId ?? null,
    toBinId: null,
    skuId: '',
    batchNo: '',
    quantity: 1
  });
  binMoveDialog.value = true;
  onBinMoveSource();
}
async function saveBinMove() {
  if (
    binMoveForm.fromBinId == null ||
    binMoveForm.toBinId == null ||
    !binMoveForm.skuId ||
    !binMoveForm.batchNo.trim()
  ) {
    return ElMessage.warning('请完整填写源/目标货位、商品和批次');
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/warehouse/bins/stock/move', 'POST', {
      fromBinId: binMoveForm.fromBinId,
      toBinId: binMoveForm.toBinId,
      skuId: binMoveForm.skuId,
      batchNo: binMoveForm.batchNo.trim(),
      quantity: Number(binMoveForm.quantity) || 0
    });
    binMoveDialog.value = false;
    ElMessage.success('移库完成');
    loadedTabs.value.delete('bins');
    await loadTab('bins', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '移库失败');
  } finally {
    saving.value = false;
  }
}
function addPurchaseLine() {
  purchaseForm.lines.push(newLine());
}
async function removePurchaseLine(index: number) {
  if (purchaseForm.lines.length <= 1) return;
  try {
    await ElMessageBox.confirm('确定删除该采购行吗？', '删除行', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  purchaseForm.lines.splice(index, 1);
}
async function removeInboundLine(index: number) {
  if (inboundForm.lines.length <= 1) return;
  try {
    await ElMessageBox.confirm('确定删除该入库行吗？', '删除行', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  inboundForm.lines.splice(index, 1);
}
async function savePurchase() {
  if (
    !purchaseForm.supplierId ||
    purchaseForm.lines.some((l: Row) => !l.skuId || !l.batchNo || !l.expiryDate)
  ) {
    return ElMessage.warning('请完整填写供应商、商品、批次和到期日期');
  }
  saving.value = true;
  try {
    const refNo = String(purchaseForm.refNo || '').trim() || defaultPurchaseRefNo();
    const body = {
      supplierId: purchaseForm.supplierId,
      warehouseId: purchaseForm.warehouseId,
      refNo,
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
    ElMessage.success('采购单已提交审批');
    loadedTabs.value.delete('purchase');
    await loadTab('purchase', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  } finally {
    saving.value = false;
  }
}
async function reviewPurchase(row: Row, approve: boolean) {
  try {
    await ElMessageBox.confirm(
      approve ? `确认通过采购单 ${row.refNo || row.purchaseOrderId}？` : `确认驳回该采购单？`,
      approve ? '审批通过' : '审批驳回',
      { type: approve ? 'info' : 'warning' }
    );
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/purchase-orders/${row.purchaseOrderId}/review`, 'POST', {
      approve,
      remark: approve ? '审批通过' : '审批驳回'
    });
    ElMessage.success(approve ? '已通过' : '已驳回');
    loadedTabs.value.delete('purchase');
    await loadTab('purchase', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '审批失败');
  }
}

function openReceive(row: Row) {
  Object.assign(receiveForm, {
    purchaseOrderId: row.purchaseOrderId,
    notes: '',
    receiveWarehouseId: row.warehouseId || '',
    lines: (row.lines || []).map((line: Row) => ({
      ...line,
      minReceived: line.receivedQty || 0,
      receivedQty: line.receivedQty || 0
    }))
  });
  receiveDialog.value = true;
  loadWarehousesSoft();
}
async function saveReceive() {
  saving.value = true;
  try {
    await ElMessageBox.confirm('确认按累计收货数量入库？', '采购收货', { type: 'warning' });
    await api.request(
      `/api/v2/ops/admin/purchase-orders/${receiveForm.purchaseOrderId}/receive`,
      'POST',
      {
        lines: receiveForm.lines,
        notes: receiveForm.notes,
        receiveWarehouseId: receiveForm.receiveWarehouseId || undefined
      }
    );
    receiveDialog.value = false;
    ElMessage.success('收货完成');
    loadedTabs.value.delete('purchase');
    loadedTabs.value.delete('inventory');
    await loadTab('purchase', true);
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close')
      ElMessage.error(e instanceof Error ? e.message : '收货失败');
  } finally {
    saving.value = false;
  }
}

async function openReturn() {
  Object.assign(returnForm, {
    purchaseOrderId: null,
    notes: '',
    lines: []
  });
  returnDialog.value = true;
  dialogBootLoading.value = true;
  try {
    await Promise.all([
      loadPurchase().catch(() => {}),
      loadSuppliersSoft(),
      loadWarehousesSoft(),
      ensureMeta()
    ]);
    const first = returnablePurchaseOrders.value[0];
    returnForm.purchaseOrderId = first?.purchaseOrderId || null;
    returnForm.notes = '';
    returnForm.lines = [];
    if (first) onReturnPoChange(first.purchaseOrderId);
  } finally {
    dialogBootLoading.value = false;
  }
}
function onReturnPoChange(purchaseOrderId: number | string | null) {
  const po = purchaseOrders.value.find((p) => p.purchaseOrderId === purchaseOrderId);
  returnForm.lines = (po?.lines || [])
    .map((line: Row) => {
      const maxQty = Math.max(0, (line.receivedQty || 0) - (line.returnedQty || 0));
      return {
        purchaseLineId: line.lineId,
        skuId: line.skuId,
        batchNo: line.batchNo,
        receivedQty: line.receivedQty || 0,
        returnedQty: line.returnedQty || 0,
        maxQty,
        quantity: maxQty > 0 ? 1 : 0
      };
    })
    .filter((l: Row) => l.maxQty > 0);
}
async function saveReturn() {
  if (!returnForm.purchaseOrderId) {
    return ElMessage.warning('请选择采购单');
  }
  const lines = (returnForm.lines || []).filter((l: Row) => (l.quantity || 0) > 0);
  if (!lines.length) {
    return ElMessage.warning('请填写退货数量');
  }
  saving.value = true;
  try {
    await ElMessageBox.confirm('确认退货并扣减仓库库存？', '采购退货', { type: 'warning' });
    await api.request('/api/v2/ops/admin/purchase-returns', 'POST', {
      purchaseOrderId: returnForm.purchaseOrderId,
      notes: returnForm.notes,
      lines: lines.map((l: Row) => ({
        purchaseLineId: l.purchaseLineId,
        quantity: l.quantity
      }))
    });
    returnDialog.value = false;
    ElMessage.success('退货完成');
    loadedTabs.value.delete('returns');
    loadedTabs.value.delete('purchase');
    loadedTabs.value.delete('inventory');
    loadedTabs.value.delete('movements');
    tab.value = 'returns';
    await loadTab('returns', true);
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close')
      ElMessage.error(e instanceof Error ? e.message : '退货失败');
  } finally {
    saving.value = false;
  }
}

const outboundConfirm = reactive({
  visible: false,
  saving: false,
  title: '',
  message: '',
  action: 'pick' as 'pick' | 'ship' | 'cancel-unreceived',
  outboundId: null as number | string | null
});

function changeOutbound(row: Row, action: 'pick' | 'ship' | 'cancel-unreceived') {
  outboundConfirm.action = action;
  outboundConfirm.outboundId = row.outboundId;
  if (action === 'pick') {
    outboundConfirm.title = '确认拣货';
    outboundConfirm.message = `确认出库单 ${row.outboundId} 已完成拣货？`;
  } else if (action === 'ship') {
    outboundConfirm.title = '确认发运';
    outboundConfirm.message = `确认发运出库单 ${row.outboundId}？发运后库存将转为在途。`;
  } else {
    outboundConfirm.title = row.status === 'SHIPPED' ? '作废回仓' : '作废出库';
    outboundConfirm.message =
      row.status === 'SHIPPED'
        ? `确认作废出库单 ${row.outboundId}？将回仓并取消在途（仅未签收）。`
        : `确认作废出库单 ${row.outboundId}？未发运单据将直接取消。`;
  }
  outboundConfirm.saving = false;
  outboundConfirm.visible = true;
}

function cancelOutboundConfirm() {
  outboundConfirm.visible = false;
}

async function cleanupStaleOutbounds() {
  try {
    await ElMessageBox.confirm(
      '将安全作废：空草稿/已拣货、终态路线上的未发运草稿、终态路线上未签收且无已完成任务的发运单（回仓并取消在途）。不硬删业务行；已签收或任务已完成的单据跳过。',
      '清理空草稿/脏在途',
      { type: 'warning', confirmButtonText: '确认清理' }
    );
  } catch {
    return;
  }
  cleanupStaleLoading.value = true;
  try {
    const result = await api.request<{
      cancelledEmptyDrafts?: number;
      cancelledTerminalDrafts?: number;
      cancelledOrphanShipped?: number;
      skipped?: number;
      cancelledOutboundIds?: number[];
    }>('/api/v2/ops/admin/warehouse/outbounds/cleanup-stale', 'POST');
    const total =
      (result?.cancelledEmptyDrafts || 0) +
      (result?.cancelledTerminalDrafts || 0) +
      (result?.cancelledOrphanShipped || 0);
    ElMessage.success({
      message: total
        ? `已清理 ${total} 单（空草稿 ${result?.cancelledEmptyDrafts || 0} / 终态草稿 ${result?.cancelledTerminalDrafts || 0} / 孤儿发运 ${result?.cancelledOrphanShipped || 0}），跳过 ${result?.skipped || 0}`
        : `无可清理单据（跳过 ${result?.skipped || 0}）`,
      duration: 5000
    });
    loadedTabs.value.delete('outbounds');
    loadedTabs.value.delete('transit');
    loadedTabs.value.delete('inventory');
    await loadTab('outbounds', true);
  } catch (e: any) {
    ElMessage.error(e instanceof Error ? e.message : '清理失败');
  } finally {
    cleanupStaleLoading.value = false;
  }
}

function onOutboundConfirmClosed() {
  if (!outboundConfirm.saving) {
    outboundConfirm.outboundId = null;
  }
}

async function submitOutboundConfirm() {
  const action = outboundConfirm.action;
  const outboundId = outboundConfirm.outboundId;
  if (outboundId == null) {
    outboundConfirm.visible = false;
    return;
  }
  outboundConfirm.saving = true;
  try {
    await api.request(`/api/v2/ops/admin/warehouse/outbounds/${outboundId}/${action}`, 'POST');
    outboundConfirm.visible = false;
    let okMsg: string;
    if (action === 'pick') okMsg = '拣货完成';
    else if (action === 'ship') okMsg = '已发运';
    else okMsg = '出库单已作废';
    ElMessage.success(okMsg);
    loadedTabs.value.delete('outbounds');
    loadedTabs.value.delete('transit');
    loadedTabs.value.delete('inventory');
    await loadTab('outbounds', true);
  } catch (e: any) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  } finally {
    outboundConfirm.saving = false;
    outboundConfirm.outboundId = null;
  }
}

async function openInbound() {
  Object.assign(inboundForm, {
    warehouseId: filterWarehouseId.value || '',
    refNo: '',
    notes: '',
    lines: [newInboundLine()]
  });
  inboundDialog.value = true;
  dialogBootLoading.value = true;
  try {
    await Promise.all([loadWarehousesSoft(), ensureMeta()]);
    if (!inboundForm.warehouseId) {
      inboundForm.warehouseId = activeWarehouses.value[0]?.warehouseId || '';
    }
  } finally {
    dialogBootLoading.value = false;
  }
}
async function saveInbound() {
  if (
    !inboundForm.warehouseId ||
    inboundForm.lines.some((l: Row) => !l.skuId || !l.batchNo || !l.expiryDate || !l.quantity)
  ) {
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

function syncRouteQuery(nextTab = tab.value) {
  const query: Record<string, string> = {
    ...Object.fromEntries(
      Object.entries(route.query)
        .filter((entry): entry is [string, string] => typeof entry[1] === 'string')
        .filter(([k]) => !['tab', 'overdue', 'deviceId'].includes(k))
    )
  };
  if (nextTab && nextTab !== 'warehouses') query.tab = nextTab;
  if (nextTab === 'transit') {
    if (overdueOnly.value) query.overdue = '1';
    if (focusDeviceId.value) query.deviceId = focusDeviceId.value;
  }
  const same =
    String(route.query.tab || '') === String(query.tab || '') &&
    String(route.query.overdue || '') === String(query.overdue || '') &&
    String(route.query.deviceId || '') === String(query.deviceId || '');
  if (!same) {
    router.replace({ query });
  }
}

function applyQueryFilters() {
  const qOverdue = route.query.overdue === '1' || route.query.overdue === 'true';
  if (qOverdue !== overdueOnly.value) {
    overdueOnly.value = qOverdue;
  }
  const qDevice = typeof route.query.deviceId === 'string' ? route.query.deviceId : '';
  if (qDevice !== focusDeviceId.value) {
    focusDeviceId.value = qDevice;
  }
}

function applyTabFromQuery() {
  const qTab = typeof route.query.tab === 'string' ? route.query.tab : '';
  const qDevice = typeof route.query.deviceId === 'string' ? route.query.deviceId : '';
  const allowed = [
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
    'inventory',
    'movements'
  ];
  if (allowed.includes(qTab) && tab.value !== qTab) {
    tab.value = qTab;
  } else if (!qTab && qDevice) {
    // deviceId deep-link without tab → in-transit (replenishment / dashboard)
    if (tab.value !== 'transit') tab.value = 'transit';
  } else if (!qTab && tab.value !== 'warehouses' && !qDevice) {
    // keep current tab when user switched locally; only reset when query fully cleared
  }
  if (tab.value === 'transit') {
    applyQueryFilters();
  } else {
    overdueOnly.value = false;
    focusDeviceId.value = '';
  }
}

onMounted(async () => {
  applyTabFromQuery();
  await loadTab(tab.value, true);
});

onActivated(() => {
  applyTabFromQuery();
  loadTab(tab.value, true);
});

watch(
  () => [route.query.tab, route.query.overdue, route.query.deviceId] as const,
  () => {
    applyTabFromQuery();
    loadTab(tab.value, true);
  }
);
</script>

<style scoped>
.trend-up {
  color: var(--el-color-danger);
  font-weight: 600;
}

.trend-down {
  color: var(--el-color-success);
  font-weight: 600;
}

.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
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
  font-size: 15px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
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
.outbound-confirm-body {
  margin: 0;
  color: var(--layout-text);
  line-height: 1.6;
  font-size: 14px;
}
.outbound-confirm-id {
  display: inline-block;
  margin-bottom: 6px;
  font-weight: 700;
  font-size: 15px;
  color: var(--app-primary, #0f766e);
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
  font-size: 11px;
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
  font-size: 13px;
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
  gap: 7px;
  color: var(--layout-muted);
  font-size: 13px;
}
.line-field :deep(.el-input-number),
.line-field :deep(.el-select) {
  width: 100%;
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
