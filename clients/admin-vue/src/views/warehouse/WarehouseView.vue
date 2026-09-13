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
                <el-table-column
                  type="selection"
                  width="48"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  prop="warehouseId"
                  label="仓库编号"
                  min-width="120"
                  class-name="col-text"
                  sortable="custom"
                >
                  <template #default="{ row }">
                    <span class="cell-id">{{ row.warehouseId }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="仓库" min-width="140" class-name="col-text">
                  <template #default="{ row }">{{ row.warehouseName || '无' }}</template>
                </el-table-column>
                <el-table-column
                  prop="address"
                  label="地址"
                  min-width="220"
                  class-name="col-text"
                />
                <el-table-column
                  label="状态"
                  width="100"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
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
                  fixed="right"
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

        <el-tab-pane
          v-if="tabGroup === 'fulfillment' && canWarehouseList"
          label="仓间调拨"
          name="transfers"
        >
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
                <el-table-column prop="transferNo" label="调拨单号" min-width="160" />
                <el-table-column label="调出仓" min-width="120">
                  <template #default="{ row }">{{
                    warehouseName(row.fromWarehouseId) || row.fromWarehouseId
                  }}</template>
                </el-table-column>
                <el-table-column label="调入仓" min-width="120">
                  <template #default="{ row }">{{
                    warehouseName(row.toWarehouseId) || row.toWarehouseId
                  }}</template>
                </el-table-column>
                <el-table-column
                  label="状态"
                  width="100"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <el-tag size="small" effect="plain">{{
                      transferStatusLabel(row.status)
                    }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="明细" min-width="180" class-name="col-text">
                  <template #default="{ row }">
                    <span
                      class="cell-ellipsis"
                      :title="
                        (row.lines || [])
                          .map(
                            (l: WarehouseLine) =>
                              `${skuName(l.skuId) || l.skuId}×${l.quantity}${l.batchNo ? '(' + l.batchNo + ')' : ''}`
                          )
                          .join(' · ') || ''
                      "
                      >{{
                        (row.lines || [])
                          .map(
                            (l: WarehouseLine) =>
                              `${skuName(l.skuId) || l.skuId}×${l.quantity}${l.batchNo ? '(' + l.batchNo + ')' : ''}`
                          )
                          .join(' · ') || ''
                      }}</span
                    >
                  </template>
                </el-table-column>
                <el-table-column
                  label="发运"
                  width="150"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <span class="cell-datetime">{{
                      row.shippedAt ? formatDateTime(row.shippedAt) : ''
                    }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="收货"
                  width="150"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <span class="cell-datetime">{{
                      row.receivedAt ? formatDateTime(row.receivedAt) : ''
                    }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="备注" min-width="100" class-name="col-text">
                  <template #default="{ row }">
                    <span class="cell-ellipsis" :title="row.notes || ''">{{
                      row.notes || ''
                    }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  v-if="canWarehouseEdit"
                  label="操作"
                  width="200"
                  align="center"
                  fixed="right"
                  class-name="col-action"
                  label-class-name="col-action"
                >
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

        <el-tab-pane
          v-if="tabGroup === 'procurement' && canProcurementList"
          label="供应商"
          name="suppliers"
        >
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
                <el-table-column
                  type="selection"
                  width="48"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  prop="supplierId"
                  label="供应商编号"
                  min-width="120"
                  class-name="col-text"
                  sortable="custom"
                >
                  <template #default="{ row }">
                    <span class="cell-id">{{ row.supplierId }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="供应商" min-width="140" class-name="col-text">
                  <template #default="{ row }">{{ row.supplierName || '无' }}</template>
                </el-table-column>
                <el-table-column
                  prop="contactName"
                  label="联系人"
                  min-width="120"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  prop="contactPhone"
                  label="联系电话"
                  min-width="150"
                  class-name="col-text"
                  label-class-name="col-text"
                />
                <el-table-column
                  prop="paymentTermsDays"
                  label="账期(天)"
                  min-width="96"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="状态"
                  min-width="100"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
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
                  fixed="right"
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

        <el-tab-pane
          v-if="tabGroup === 'procurement' && canProcurementList"
          label="采购单"
          name="purchase"
        >
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
                <el-table-column
                  type="selection"
                  width="48"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  type="expand"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <div class="expand-panel">
                      <el-table :data="row.lines || []" size="small" border class="line-table">
                        <el-table-column
                          label="商品"
                          min-width="180"
                          class-name="col-text"
                          label-class-name="col-text"
                        >
                          <template #default="scope">
                            {{ skuName(scope.row.skuId) }}
                          </template>
                        </el-table-column>
                        <el-table-column
                          prop="batchNo"
                          label="批次"
                          min-width="140"
                          class-name="col-text"
                          label-class-name="col-text"
                        />
                        <el-table-column
                          prop="orderedQty"
                          label="采购数"
                          min-width="88"
                          align="center"
                          class-name="col-status"
                          label-class-name="col-status"
                        />
                        <el-table-column
                          prop="receivedQty"
                          label="已收数"
                          min-width="88"
                          align="center"
                          class-name="col-status"
                          label-class-name="col-status"
                        />
                        <el-table-column
                          prop="returnedQty"
                          label="已退数"
                          min-width="88"
                          align="center"
                          class-name="col-status"
                          label-class-name="col-status"
                        />
                        <el-table-column
                          label="成本"
                          min-width="96"
                          align="center"
                          class-name="col-money"
                          label-class-name="col-money"
                        >
                          <template #default="scope"
                            >¥{{ money(scope.row.unitCostCents) }}</template
                          >
                        </el-table-column>
                        <el-table-column
                          prop="expiryDate"
                          label="到期日期"
                          min-width="120"
                          align="center"
                          class-name="col-status"
                          label-class-name="col-status"
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
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  prop="refNo"
                  label="外部单号"
                  min-width="140"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <span v-if="row.refNo">{{ row.refNo }}</span>
                    <span v-else class="muted">未填写</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="供应商"
                  min-width="160"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">
                    {{ supplierName(row.supplierId) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="入库仓库"
                  min-width="160"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    {{ warehouseName(row.warehouseId) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="状态"
                  min-width="120"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <el-tag :type="dictTagType(row.status)" size="small">{{
                      dictLabel('purchase_order_status', row.status)
                    }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column
                  v-if="canProcurementList"
                  label="审批节点"
                  min-width="140"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <template
                      v-if="row.status === 'PENDING_APPROVAL' && row.approvalCurrentNodeName"
                    >
                      <span>{{ row.approvalCurrentNodeName }}</span>
                      <span v-if="row.approvalPendingForMe === false" class="muted">
                        （待他人处理）
                      </span>
                    </template>
                    <span v-else-if="row.status === 'PENDING_APPROVAL'" class="muted">待审批</span>
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
                <el-table-column
                  v-if="canEdit"
                  label="操作"
                  min-width="220"
                  class-name="col-action"
                  align="center"
                  fixed="right"
                >
                  <template #default="{ row }">
                    <el-button
                      v-if="row.status !== 'PENDING_APPROVAL'"
                      link
                      type="primary"
                      class="print-btn"
                      @click="openPrint('purchase', { purchaseOrderId: row.purchaseOrderId })"
                      >打印收货单</el-button
                    >
                    <el-button
                      v-if="row.status === 'PENDING_APPROVAL' && canReviewPurchaseRow(row)"
                      link
                      type="success"
                      @click="reviewPurchase(row, true)"
                      >通过</el-button
                    >
                    <el-button
                      v-if="row.status === 'PENDING_APPROVAL' && canReviewPurchaseRow(row)"
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

        <el-tab-pane
          v-if="tabGroup === 'procurement' && canProcurementList"
          label="采购建议"
          name="suggestions"
        >
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
                <el-table-column
                  type="selection"
                  width="48"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="商品"
                  min-width="170"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">{{ skuName(row.skuId) }}</template>
                </el-table-column>
                <el-table-column
                  label="近7日销量"
                  prop="soldQty7d"
                  min-width="96"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="近14日销量"
                  prop="soldQty14d"
                  min-width="104"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="日均销量"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    {{ Number(row.avgDailySales ?? 0).toFixed(2) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="预测日均"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    {{ Number(row.forecastDailySales ?? row.avgDailySales ?? 0).toFixed(2) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="日均趋势"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
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
                <el-table-column
                  label="仓库库存"
                  prop="onHandQty"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="待收采购"
                  prop="pendingPoQty"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="覆盖天数"
                  prop="coverageDays"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="建议采购量"
                  min-width="104"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">
                    <span class="cell-id">{{ row.suggestQty }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="安全库存"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    {{ row.safetyStockQty ?? 0 }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="建议理由"
                  min-width="110"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
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

        <el-tab-pane
          v-if="tabGroup === 'procurement' && canProcurementList"
          label="采购退货"
          name="returns"
        >
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
                <el-table-column
                  type="selection"
                  width="48"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  type="expand"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <div class="expand-panel">
                      <el-table :data="row.lines || []" size="small" border class="line-table">
                        <el-table-column
                          label="商品"
                          min-width="180"
                          class-name="col-text"
                          label-class-name="col-text"
                        >
                          <template #default="scope">
                            {{ skuName(scope.row.skuId) }}
                          </template>
                        </el-table-column>
                        <el-table-column
                          prop="batchNo"
                          label="批次"
                          min-width="140"
                          class-name="col-text"
                          label-class-name="col-text"
                        />
                        <el-table-column
                          prop="quantity"
                          label="退货数"
                          min-width="88"
                          align="center"
                          class-name="col-status"
                          label-class-name="col-status"
                        />
                      </el-table>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column
                  prop="returnId"
                  label="退货单"
                  min-width="96"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  prop="purchaseOrderId"
                  label="采购单"
                  min-width="96"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="供应商"
                  min-width="160"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">
                    {{ supplierName(row.supplierId) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="仓库"
                  min-width="160"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">
                    {{ warehouseName(row.warehouseId) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="状态"
                  min-width="100"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <el-tag type="success" size="small">{{ returnStatusLabel(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column
                  label="创建时间"
                  min-width="170"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
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

        <el-tab-pane
          v-if="tabGroup === 'procurement' && canProcurementList"
          label="应付账款"
          name="payables"
        >
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
                <el-table-column
                  type="expand"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <div class="expand-panel">
                      <el-table
                        v-if="row.payments?.length"
                        :data="row.payments"
                        size="small"
                        border
                        class="line-table"
                      >
                        <el-table-column
                          label="付款时间"
                          min-width="170"
                          align="center"
                          class-name="col-status"
                          label-class-name="col-status"
                        >
                          <template #default="scope">
                            {{ formatDateTime(scope.row.createdAt) }}
                          </template>
                        </el-table-column>
                        <el-table-column
                          label="付款金额"
                          min-width="110"
                          align="center"
                          class-name="col-money"
                          label-class-name="col-money"
                        >
                          <template #default="scope">¥{{ money(scope.row.amountCents) }}</template>
                        </el-table-column>
                        <el-table-column
                          prop="notes"
                          label="备注"
                          min-width="180"
                          class-name="col-text"
                          label-class-name="col-text"
                        />
                      </el-table>
                      <el-empty v-else description="暂无付款记录" :image-size="60" />
                    </div>
                  </template>
                </el-table-column>
                <el-table-column
                  label="供应商"
                  min-width="150"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">{{ row.supplierName }}</template>
                </el-table-column>
                <el-table-column
                  label="关联采购单"
                  min-width="110"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">
                    <span class="cell-id">{{ row.purchaseOrderId }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="应付金额"
                  min-width="110"
                  align="center"
                  class-name="col-money"
                  label-class-name="col-money"
                >
                  <template #default="{ row }">¥{{ money(row.amountCents) }}</template>
                </el-table-column>
                <el-table-column
                  label="已付"
                  min-width="100"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">¥{{ money(row.paidAmountCents) }}</template>
                </el-table-column>
                <el-table-column
                  label="未付余额"
                  min-width="110"
                  align="center"
                  class-name="col-money"
                  label-class-name="col-money"
                >
                  <template #default="{ row }">
                    <span class="cell-id">{{ money(row.balanceCents) }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="到期日"
                  min-width="110"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">{{ row.dueDate || '暂无' }}</template>
                </el-table-column>
                <el-table-column
                  label="状态"
                  min-width="100"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <el-tag :type="payableStatusType(row.status)" size="small">
                      {{ payableStatusText(row.status) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column
                  label="逾期"
                  min-width="116"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <el-tag v-if="row.overdue" type="danger" size="small">
                      逾期 {{ row.overdueDays }} 天
                    </el-tag>
                    <span v-else class="muted">未逾期</span>
                  </template>
                </el-table-column>
                <el-table-column
                  v-if="canEdit"
                  label="操作"
                  width="100"
                  align="center"
                  fixed="right"
                  class-name="col-action"
                  label-class-name="col-action"
                >
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

        <el-tab-pane
          v-if="tabGroup === 'inventory' && canWarehouseList"
          label="盘点单"
          name="stocktakes"
        >
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
                <el-table-column
                  type="selection"
                  width="48"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="盘点单号"
                  min-width="160"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <span class="cell-id">{{ row.stocktakeNo }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="仓库"
                  min-width="140"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">{{ row.warehouseName }}</template>
                </el-table-column>
                <el-table-column
                  label="模式"
                  min-width="80"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">{{ stocktakeModeText(row.mode) }}</template>
                </el-table-column>
                <el-table-column
                  label="状态"
                  min-width="100"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <el-tag :type="stocktakeStatusType(row.status)" size="small">
                      {{ stocktakeStatusText(row.status) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column
                  label="账面件数"
                  prop="bookQty"
                  min-width="90"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="实盘件数"
                  prop="countedQty"
                  min-width="90"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="差异件数"
                  min-width="90"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">{{ row.diffQty }}</template>
                </el-table-column>
                <el-table-column
                  label="差异行数"
                  prop="diffLineCount"
                  min-width="90"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="创建时间"
                  min-width="160"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
                </el-table-column>
                <el-table-column
                  v-if="canWarehouseEdit"
                  label="操作"
                  width="110"
                  align="center"
                  fixed="right"
                  class-name="col-action"
                  label-class-name="col-action"
                >
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

        <el-tab-pane v-if="tabGroup === 'inventory' && canWarehouseList" label="货位" name="bins">
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
              <el-table-column
                label="货位编码"
                min-width="110"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <span class="cell-id">{{ row.binCode }}</span>
                </template>
              </el-table-column>
              <el-table-column
                prop="binName"
                label="货位名称"
                min-width="140"
                class-name="col-text"
                label-class-name="col-text"
              >
                <template #default="{ row }">{{ row.binName || '暂无' }}</template>
              </el-table-column>
              <el-table-column
                label="仓库"
                min-width="140"
                class-name="col-text"
                label-class-name="col-text"
              >
                <template #default="{ row }">{{ row.warehouseName }}</template>
              </el-table-column>
              <el-table-column
                label="状态"
                min-width="90"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                    {{ displayLabel('enable_status', row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column
                v-if="canWarehouseEdit"
                label="操作"
                width="80"
                align="center"
                fixed="right"
                class-name="col-action"
                label-class-name="col-action"
              >
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
              <el-table-column
                label="货位"
                min-width="100"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <span class="cell-id">{{ row.binCode }}</span>
                </template>
              </el-table-column>
              <el-table-column
                label="商品"
                min-width="170"
                class-name="col-text"
                label-class-name="col-text"
              >
                <template #default="{ row }">{{ row.skuName }}</template>
              </el-table-column>
              <el-table-column
                prop="batchNo"
                label="批次"
                min-width="130"
                class-name="col-text"
                label-class-name="col-text"
              />
              <el-table-column
                prop="productionDate"
                label="生产日期"
                min-width="110"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              />
              <el-table-column
                prop="expiryDate"
                label="到期日"
                min-width="110"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              />
              <el-table-column
                prop="quantity"
                label="数量"
                min-width="80"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              />
              <template #empty
                ><el-empty
                  v-if="hydratedTabs.has('bins') && !isTabLoading('bins')"
                  description="暂无货位库存"
                  :image-size="60"
              /></template>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="tabGroup === 'fulfillment'" label="出库单" name="outbounds">
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
                <el-table-column
                  type="selection"
                  width="48"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  type="expand"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <div class="expand-panel" :data-testid="`outbound-expand-${row.outboundId}`">
                      <el-table :data="row.lines || []" size="small" border class="line-table">
                        <el-table-column
                          label="目标设备"
                          min-width="180"
                          align="center"
                          class-name="col-status"
                          label-class-name="col-status"
                        >
                          <template #default="scope">
                            {{ deviceName(scope.row.deviceId, scope.row.deviceName) }}
                          </template>
                        </el-table-column>
                        <el-table-column
                          label="商品"
                          min-width="180"
                          class-name="col-text"
                          label-class-name="col-text"
                        >
                          <template #default="scope">
                            {{ skuName(scope.row.skuId) }}
                          </template>
                        </el-table-column>
                        <el-table-column
                          label="货道"
                          min-width="88"
                          class-name="col-text"
                          label-class-name="col-text"
                        >
                          <template #default="scope">{{ scope.row.slotId || '无' }}</template>
                        </el-table-column>
                        <el-table-column
                          prop="batchNo"
                          label="批次"
                          min-width="140"
                          class-name="col-text"
                          label-class-name="col-text"
                        />
                        <el-table-column
                          prop="quantity"
                          label="数量"
                          min-width="88"
                          align="center"
                          class-name="col-status"
                          label-class-name="col-status"
                        />
                        <el-table-column
                          label="交接状态"
                          min-width="110"
                          align="center"
                          class-name="col-status"
                          label-class-name="col-status"
                        >
                          <template #default="scope">{{
                            dictLabel('handover_status', scope.row.handoverStatus || 'PENDING')
                          }}</template>
                        </el-table-column>
                      </el-table>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column
                  label="出库单"
                  min-width="110"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">
                    <span :data-testid="`outbound-id-${row.outboundId}`" class="outbound-id-cell">{{
                      row.outboundId
                    }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  prop="routeId"
                  label="路线"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="出库仓库"
                  min-width="160"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    {{ warehouseName(row.warehouseId) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="状态"
                  min-width="110"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <el-tag :type="dictTagType(row.status)" size="small">{{
                      dictLabel('warehouse_outbound_status', row.status)
                    }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column
                  label="创建时间"
                  min-width="170"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
                </el-table-column>
                <el-table-column
                  v-if="canEdit"
                  label="操作"
                  min-width="240"
                  class-name="col-action"
                  align="center"
                  fixed="right"
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
                      <el-button
                        v-if="row.status === 'DRAFT' && row.lines?.length"
                        link
                        type="primary"
                        class="print-btn"
                        :data-testid="`outbound-${row.outboundId}-pick`"
                        @click="changeOutbound(row, 'pick')"
                        >确认拣货</el-button
                      >
                      <el-button
                        v-if="row.status === 'PICKED' && row.lines?.length"
                        link
                        type="danger"
                        class="print-btn"
                        :data-testid="`outbound-${row.outboundId}-ship`"
                        @click="changeOutbound(row, 'ship')"
                        >确认发运</el-button
                      >
                      <TableActions
                        v-if="outboundSecondaryActions(row).length"
                        :actions="outboundSecondaryActions(row)"
                        :test-id-prefix="`outbound-${row.outboundId}`"
                        @action="(k) => changeOutbound(row, String(k) as 'cancel-unreceived')"
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

        <el-tab-pane v-if="tabGroup === 'fulfillment'" label="在途" name="transit">
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
                <el-table-column
                  type="selection"
                  width="48"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  prop="outboundId"
                  label="出库单"
                  min-width="96"
                  class-name="col-text"
                  label-class-name="col-text"
                />
                <el-table-column
                  label="目标设备"
                  min-width="180"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    {{ deviceName(row.deviceId, row.deviceName) }}
                  </template>
                </el-table-column>
                <el-table-column
                  label="商品"
                  min-width="180"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">
                    {{ skuName(row.skuId) }}
                  </template>
                </el-table-column>
                <el-table-column
                  prop="batchNo"
                  label="批次"
                  min-width="140"
                  class-name="col-text"
                  label-class-name="col-text"
                />
                <el-table-column
                  prop="quantity"
                  label="数量"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="状态"
                  min-width="110"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <el-tag :type="dictTagType(row.status)" size="small">{{
                      dictLabel('in_transit_status', row.status)
                    }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column
                  align="center"
                  label="在途 / 时限"
                  min-width="160"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">
                    <div class="sla-cell">
                      <template v-if="isTransitOverdue(row)">
                        <el-tag type="danger" size="small">到柜超时</el-tag>
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
                        <small class="sla-meta">待补货员到柜完成</small>
                      </template>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column
                  label="发运时间"
                  min-width="170"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="tabGroup === 'inventory'" label="批次库存" name="inventory">
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
                <el-table-column
                  type="selection"
                  width="48"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="仓库"
                  min-width="140"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">{{ warehouseName(row.warehouseId) }}</template>
                </el-table-column>
                <el-table-column
                  label="商品"
                  min-width="180"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">
                    {{ skuName(row.skuId) }}
                  </template>
                </el-table-column>
                <el-table-column
                  prop="batchNo"
                  label="批次"
                  min-width="150"
                  class-name="col-text"
                  label-class-name="col-text"
                />
                <el-table-column
                  prop="productionDate"
                  label="生产日期"
                  min-width="120"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  prop="expiryDate"
                  label="到期日期"
                  min-width="120"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  prop="quantity"
                  label="库存"
                  min-width="88"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="效期"
                  min-width="100"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
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

        <el-tab-pane v-if="tabGroup === 'inventory'" label="库存流水" name="movements">
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
                <el-table-column
                  type="selection"
                  width="48"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  prop="movementId"
                  label="流水"
                  min-width="90"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                />
                <el-table-column
                  label="类型"
                  min-width="130"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">{{
                    dictLabel('warehouse_movement_type', row.movementType)
                  }}</template>
                </el-table-column>
                <el-table-column
                  label="商品"
                  min-width="180"
                  class-name="col-text"
                  label-class-name="col-text"
                >
                  <template #default="{ row }">
                    {{ skuName(row.skuId) }}
                  </template>
                </el-table-column>
                <el-table-column
                  prop="batchNo"
                  label="批次"
                  min-width="140"
                  class-name="col-text"
                  label-class-name="col-text"
                />
                <el-table-column
                  prop="deltaQty"
                  label="变动"
                  min-width="88"
                  align="center"
                  class-name="col-money"
                  label-class-name="col-money"
                >
                  <template #default="{ row }">
                    <span :class="row.deltaQty >= 0 ? 'positive' : 'negative'"
                      >{{ row.deltaQty > 0 ? '+' : '' }}{{ row.deltaQty }}</span
                    >
                  </template>
                </el-table-column>
                <el-table-column
                  label="关联业务"
                  min-width="140"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">{{
                    dictLabel('business_reference_type', row.refType)
                  }}</template>
                </el-table-column>
                <el-table-column
                  label="关联单号"
                  min-width="120"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
                  <template #default="{ row }">{{ displayBizNo(row.refId, '无') }}</template>
                </el-table-column>
                <el-table-column
                  label="时间"
                  min-width="170"
                  align="center"
                  class-name="col-status"
                  label-class-name="col-status"
                >
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
        :warehouse-form="warehouseForm"
        :supplier-form="supplierForm"
        :payment-form="paymentForm"
        :pay-target="payTarget"
        :inbound-form="inboundForm"
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
        :stocktake-form="stocktakeForm"
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
        :bin-form="binForm"
        :bin-inbound-form="binInboundForm"
        :bin-move-form="binMoveForm"
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
        :purchase-form="purchaseForm"
        :purchase-field-errors="purchaseFieldErrors"
        :receive-form="receiveForm"
        :return-form="returnForm"
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
        :transfer-form="transferForm"
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
import { computed, onActivated, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, Refresh, RefreshLeft } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { errorMessage } from '@/utils/error-message';
import { api, downloadAuthFile } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import WarehouseBinDialogs from '@/components/warehouse/WarehouseBinDialogs.vue';
import WarehouseEntityDialogs from '@/components/warehouse/WarehouseEntityDialogs.vue';
import WarehouseOutboundDialogs from '@/components/warehouse/WarehouseOutboundDialogs.vue';
import WarehousePurchaseDialogs from '@/components/warehouse/WarehousePurchaseDialogs.vue';
import WarehouseStocktakeDialogs from '@/components/warehouse/WarehouseStocktakeDialogs.vue';
import WarehouseTransferDialogs from '@/components/warehouse/WarehouseTransferDialogs.vue';
import { useListCsv } from '@/composables/useListCsv';
import { createLoadSeq } from '@/composables/createLoadSeq';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { useWarehouseBins } from '@/composables/warehouse/useWarehouseBins';
import { useWarehouseEntityDialogs } from '@/composables/warehouse/useWarehouseEntityDialogs';
import { useWarehouseOutbounds } from '@/composables/warehouse/useWarehouseOutbounds';
import { useWarehousePurchaseOrders } from '@/composables/warehouse/useWarehousePurchaseOrders';
import { useWarehouseStocktakes } from '@/composables/warehouse/useWarehouseStocktakes';
import { useWarehouseTabLoader } from '@/composables/warehouse/useWarehouseTabLoader';
import { useWarehouseTransfers } from '@/composables/warehouse/useWarehouseTransfers';
import { useAuthStore } from '@/stores/auth';
import { csvFileName } from '@/utils/csv';
import { dictLabel, dictOptions, dictTagType, displayLabel } from '@aicabinet/shared-dict';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';
import { onPurchaseOrderReviewed } from '@/utils/purchase-order-sync';

const loadSeq = createLoadSeq();

/** 仓储调拨明细行 */
type WarehouseLine = {
  skuId?: string;
  quantity?: number;
  batchNo?: string;
  expiryDate?: string;
};
/** 仓储多 Tab 共用行（字段随业务表变化） */
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

type WarehouseTabGroup = 'overview' | 'procurement' | 'inventory' | 'fulfillment';

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
  syncRouteQuery(next);
  loadTab(next);
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

const pageHint = computed(() => {
  if (tab.value === 'transit') {
    return `仓→柜在途：补货完成才从本列表消失；时限 ${TRANSIT_OVERDUE_HOURS} 小时，超时标红`;
  }
  return '仓库 / 供应商 / 库存 / 采购与退货';
});

/** 仅真正有筛选项的 Tab 才挂 filter-bar，避免空条占位像「中间少了字」 */
const showFilterBar = computed(() =>
  [
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
      ? `设备 ${focusDeviceId.value} 无超过 ${TRANSIT_OVERDUE_HOURS} 小时的到柜超时`
      : `当前无超过 ${TRANSIT_OVERDUE_HOURS} 小时的到柜超时`;
  }
  if (focusDeviceId.value) return `设备 ${focusDeviceId.value} 暂无在途（已到柜签收或不在发运中）`;
  return '暂无在途（发运后出现于此；补货员到柜完成任务后自动消失）';
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
    downloadWarehouseTemplate([
      '示例中心仓',
      'WH-DEMO-001',
      '上海市示例路 1 号',
      displayLabel('warehouse_status', 'ACTIVE')
    ]);
  } else if (tab.value === 'suppliers') {
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
      deviceName(row.deviceId, row.deviceName),
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
    ElMessage.error(errorMessage(e, '导出失败'));
  }
}

function returnStatusLabel(status?: string) {
  const code = (status || 'COMPLETED').toUpperCase();
  if (code === 'COMPLETED') return displayLabel('order_status', 'COMPLETED');
  if (code === 'CANCELLED') return displayLabel('order_status', 'CANCELLED');
  return status || displayLabel('order_status', 'COMPLETED');
}
function supplierName(id: string) {
  return suppliers.value.find((s) => s.supplierId === id)?.supplierName || id || '无';
}
function warehouseName(id: string) {
  return warehouses.value.find((w) => w.warehouseId === id)?.warehouseName || id || '无';
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
  return devices.value.find((d) => d.deviceId === deviceId)?.deviceName || deviceId;
}
function skuName(id?: string) {
  const skuId = id != null ? String(id) : '';
  if (!skuId) return '无';
  return skus.value.find((s) => s.skuId === skuId)?.skuName || skuId;
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
  loadWarehousesSoft,
  loadSuppliersSoft,
  loadPurchase,
  loadTab
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

function onTabChange(name: string | number) {
  page.value = 1;
  const next = String(name);
  tabGroup.value = tabGroupFor(next);
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
function onPurchaseFilterChange() {
  localStorage.setItem(HIDE_TEST_PO_KEY, hideTestPurchaseOrders.value ? '1' : '0');
  page.value = 1;
  loadedTabs.value.delete('purchase');
  loadTab('purchase', true);
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
    'transfers',
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
  syncTabGroupFromTab(tab.value);
  if (tab.value === 'transit') {
    applyQueryFilters();
  } else {
    overdueOnly.value = false;
    focusDeviceId.value = '';
  }
}

let offPurchaseReviewed: (() => void) | undefined;

onMounted(async () => {
  offPurchaseReviewed = onPurchaseOrderReviewed((updated) => {
    patchPurchaseOrderRow(updated as Row);
    if (tab.value === 'purchase') {
      loadedTabs.value.delete('purchase');
      loadTab('purchase', true).catch((err) => {
        console.warn('[warehouse] 采购单更新后刷新列表失败', err);
      });
    }
  });
  applyTabFromQuery();
  await loadTab(tab.value, true);
});

onUnmounted(() => {
  offPurchaseReviewed?.();
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
