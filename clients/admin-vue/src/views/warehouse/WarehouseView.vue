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
          <el-button
            v-if="canEdit && tab === 'warehouses'"
            type="primary"
            @click="openWarehouse()"
          >新增仓库</el-button>
          <el-button
            v-if="canEdit && tab === 'suppliers'"
            type="primary"
            @click="openSupplier()"
          >新增供应商</el-button>
          <el-button
            v-if="canEdit && tab === 'purchase'"
            type="primary"
            @click="openPurchase()"
          >新建采购单</el-button>
          <el-button
            v-if="canEdit && tab === 'returns'"
            type="primary"
            @click="openReturn()"
          >新建退货</el-button>
          <el-button
            v-if="canEdit && (tab === 'inventory' || tab === 'movements')"
            type="primary"
            @click="openInbound()"
          >其他入库</el-button>
          <el-button
            v-if="canEdit && tab === 'outbounds'"
            :loading="cleanupStaleLoading"
            data-testid="cleanup-stale-outbounds"
            @click="cleanupStaleOutbounds"
          >清理空草稿/脏在途</el-button>
          <el-button
            v-if="canImportMaster"
            v-hasPermi="['ops:warehouse:import']"
            @click="onDownloadImportTemplate"
          >导入模板</el-button>
          <el-button
            v-if="canImportMaster"
            v-hasPermi="['ops:warehouse:import']"
            :loading="importing"
            @click="triggerImport"
          >导入</el-button>
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
          <el-button :icon="Refresh" :loading="loading" @click="reloadCurrent">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form v-if="showFilterBar" inline class="filter-bar filter-bar--compact" @submit.prevent>
      <el-form-item
        v-if="tab === 'inventory' || tab === 'movements' || tab === 'outbounds' || tab === 'purchase' || tab === 'returns'"
        label="仓库"
      >
        <el-select
          v-model="filterWarehouseId"
          clearable
          placeholder="全部仓库"
          style="width: 220px"
          @change="onWarehouseFilter"
        >
          <el-option v-for="w in warehouses" :key="w.warehouseId" :label="w.warehouseName" :value="w.warehouseId" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="tab === 'suppliers' || tab === 'purchase' || tab === 'returns'" label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="搜索关键词"
          style="width: 200px"
        />
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
        >仅超时</el-checkbox>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="tab === 'transit' && overdueTransitCount > 0"
      type="error"
      :closable="false"
      show-icon
      class="sla-banner"
      data-testid="transit-overdue-banner"
      :title="overdueOnly
        ? `共 ${overdueTransitCount} 条签收超时（发运超 ${TRANSIT_OVERDUE_HOURS} 小时未签收）`
        : `共 ${overdueTransitCount} 条签收超时，可勾选「仅超时」聚焦处理`"
    />

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="仓库概览" name="warehouses">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="loading"
              :data="pagedWarehouses"
              stripe
              border
              class="report-table"
              table-layout="auto"
              row-key="warehouseId"
              @selection-change="onSelectionChange"
            >
              <template #empty><el-empty description="暂无仓库" /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="仓库" min-width="180" class-name="col-text">
                <template #default="{ row }">
                  <div class="name-cell">
                    <strong>{{ row.warehouseName || row.warehouseId }}</strong>
                    <small class="cell-id">{{ row.warehouseId }}</small>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="address" label="地址" min-width="220" show-overflow-tooltip class-name="col-text" />
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="dictTagType(row.status)" size="small">
                    {{ dictLabel('warehouse_status', row.status || 'ACTIVE') }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="canEdit" label="操作" width="88" class-name="col-action" align="center">
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

      <el-tab-pane v-if="canProcurementList" label="供应商" name="suppliers">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="loading"
              :data="pagedSuppliers"
              stripe
              border
              table-layout="auto"
              row-key="supplierId"
              @selection-change="onSelectionChange"
            >
          <el-table-column type="selection" width="48" />
          <el-table-column prop="supplierName" label="供应商" min-width="200">
            <template #default="{ row }">
              <div class="name-cell"><strong>{{ row.supplierName || row.supplierId }}</strong><small class="cell-id">{{ row.supplierId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column prop="contactName" label="联系人" min-width="120" />
          <el-table-column prop="contactPhone" label="联系电话" min-width="150" />
          <el-table-column label="状态" min-width="100">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">{{ dictLabel('supplier_status', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="88" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                :actions="[{ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' }]"
                @action="() => openSupplier(row)"
              />
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无供应商" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canProcurementList" label="采购单" name="purchase">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="loading"
              :data="pagedPurchaseOrders"
              stripe
              border
              table-layout="auto"
              row-key="purchaseOrderId"
              @selection-change="onSelectionChange"
            >
          <el-table-column type="selection" width="48" />
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="expand-panel">
                <el-table :data="row.lines || []" size="small" border table-layout="auto" class="line-table">
                  <el-table-column label="商品" min-width="180">
                    <template #default="scope">
                      <div class="name-cell"><strong>{{ skuName(scope.row.skuId) }}</strong><small class="cell-id">{{ scope.row.skuId }}</small></div>
                    </template>
                  </el-table-column>
                  <el-table-column prop="batchNo" label="批次" min-width="140" />
                  <el-table-column prop="orderedQty" label="采购数" min-width="88" />
                  <el-table-column prop="receivedQty" label="已收数" min-width="88" />
                  <el-table-column prop="returnedQty" label="已退数" min-width="88" />
                  <el-table-column label="成本" min-width="96">
                    <template #default="scope">¥{{ money(scope.row.unitCostCents) }}</template>
                  </el-table-column>
                  <el-table-column prop="expiryDate" label="到期日期" min-width="120" />
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="purchaseOrderId" label="采购单" min-width="96" />
          <el-table-column prop="refNo" label="外部单号" min-width="140" show-overflow-tooltip />
          <el-table-column label="供应商" min-width="160">
            <template #default="{ row }">
              <div class="name-cell"><strong>{{ supplierName(row.supplierId) }}</strong><small class="cell-id">{{ row.supplierId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="入库仓库" min-width="160">
            <template #default="{ row }">
              <div class="name-cell"><strong>{{ warehouseName(row.warehouseId) }}</strong><small class="cell-id">{{ row.warehouseId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="120">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">{{ dictLabel('purchase_order_status', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="100" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                v-if="['CREATED', 'PARTIAL_RECEIVED'].includes(row.status)"
                :actions="[{ key: 'receive', label: '采购收货', icon: Box, type: 'primary' }]"
                @action="() => openReceive(row)"
              />
              <span v-else class="muted">已完成</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无采购单" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canProcurementList" label="采购退货" name="returns">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="loading"
              :data="pagedPurchaseReturns"
              stripe
              border
              table-layout="auto"
              row-key="returnId"
              @selection-change="onSelectionChange"
            >
          <el-table-column type="selection" width="48" />
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="expand-panel">
                <el-table :data="row.lines || []" size="small" border table-layout="auto" class="line-table">
                  <el-table-column label="商品" min-width="180">
                    <template #default="scope">
                      <div class="name-cell"><strong>{{ skuName(scope.row.skuId) }}</strong><small class="cell-id">{{ scope.row.skuId }}</small></div>
                    </template>
                  </el-table-column>
                  <el-table-column prop="batchNo" label="批次" min-width="140" />
                  <el-table-column prop="quantity" label="退货数" min-width="88" />
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="returnId" label="退货单" min-width="96" />
          <el-table-column prop="purchaseOrderId" label="采购单" min-width="96" />
          <el-table-column label="供应商" min-width="160">
            <template #default="{ row }">
              <div class="name-cell"><strong>{{ supplierName(row.supplierId) }}</strong><small class="cell-id">{{ row.supplierId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="仓库" min-width="160">
            <template #default="{ row }">
              <div class="name-cell"><strong>{{ warehouseName(row.warehouseId) }}</strong><small class="cell-id">{{ row.warehouseId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="100">
            <template #default="{ row }">
              <el-tag type="success" size="small">{{ returnStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty><el-empty description="暂无采购退货" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="出库单" name="outbounds">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="loading"
              :data="pagedOutbounds"
              stripe
              border
              table-layout="auto"
              row-key="outboundId"
              :row-class-name="outboundRowClassName"
              data-testid="outbound-table"
              @selection-change="onSelectionChange"
            >
          <el-table-column type="selection" width="48" />
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="expand-panel" :data-testid="`outbound-expand-${row.outboundId}`">
                <el-table :data="row.lines || []" size="small" border table-layout="auto" class="line-table">
                  <el-table-column label="目标设备" min-width="180">
                    <template #default="scope">
                      <div class="name-cell"><strong>{{ deviceName(scope.row.deviceId) }}</strong><small class="cell-id">{{ scope.row.deviceId }}</small></div>
                    </template>
                  </el-table-column>
                  <el-table-column label="商品" min-width="180">
                    <template #default="scope">
                      <div class="name-cell"><strong>{{ skuName(scope.row.skuId) }}</strong><small class="cell-id">{{ scope.row.skuId }}</small></div>
                    </template>
                  </el-table-column>
                  <el-table-column label="货道" min-width="88">
                    <template #default="scope">{{ scope.row.slotId || '—' }}</template>
                  </el-table-column>
                  <el-table-column prop="batchNo" label="批次" min-width="140" />
                  <el-table-column prop="quantity" label="数量" min-width="88" />
                  <el-table-column label="交接状态" min-width="110">
                    <template #default="scope">{{ dictLabel('handover_status', scope.row.handoverStatus || 'PENDING') }}</template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="出库单" min-width="110">
            <template #default="{ row }">
              <span :data-testid="`outbound-id-${row.outboundId}`" class="outbound-id-cell">#{{ row.outboundId }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="routeId" label="路线" min-width="88" />
          <el-table-column label="出库仓库" min-width="160">
            <template #default="{ row }">
              <div class="name-cell"><strong>{{ warehouseName(row.warehouseId) }}</strong><small class="cell-id">{{ row.warehouseId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="110">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">{{ dictLabel('warehouse_outbound_status', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="110" class-name="col-action" align="center">
            <template #default="{ row }">
              <div :data-testid="`outbound-row-${row.outboundId}`">
                <TableActions
                  v-if="outboundActions(row).length"
                  :actions="outboundActions(row)"
                  :test-id-prefix="`outbound-${row.outboundId}`"
                  @action="(k) => changeOutbound(row, String(k) as 'pick' | 'ship' | 'cancel-unreceived')"
                />
                <span v-else-if="!(row.lines?.length) && row.status !== 'SHIPPED'" class="muted">无明细</span>
                <span v-else-if="row.status === 'SHIPPED'" class="muted">已发运</span>
              </div>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无出库单" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="在途" name="transit">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="loading"
              :data="pagedInTransit"
              stripe
              border
              table-layout="auto"
              :row-key="transitRowKey"
              :row-class-name="transitRowClassName"
              :empty-text="transitEmptyHint"
              @selection-change="onSelectionChange"
            >
          <el-table-column type="selection" width="48" />
          <el-table-column prop="outboundId" label="出库单" min-width="96" />
          <el-table-column label="目标设备" min-width="180">
            <template #default="{ row }">
              <div class="name-cell"><strong>{{ deviceName(row.deviceId) }}</strong><small class="cell-id">{{ row.deviceId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="商品" min-width="180">
            <template #default="{ row }">
              <div class="name-cell"><strong>{{ skuName(row.skuId) }}</strong><small class="cell-id">{{ row.skuId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column prop="batchNo" label="批次" min-width="140" />
          <el-table-column prop="quantity" label="数量" min-width="88" />
          <el-table-column label="状态" min-width="110">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">{{ dictLabel('in_transit_status', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="在途 / 时限" min-width="160" class-name="col-text">
            <template #default="{ row }">
              <div class="sla-cell">
                <template v-if="isTransitOverdue(row)">
                  <el-tag type="danger" size="small">签收超时</el-tag>
                  <small class="sla-meta danger">超 {{ formatAge(transitOverdueMs(row)) }}</small>
                </template>
                <template v-else-if="isTransitDueSoon(row)">
                  <el-tag type="warning" size="small">临近超时</el-tag>
                  <small class="sla-meta">已运 {{ formatAge(transitAgeMs(row)) }} · 剩 {{ formatAge(transitRemainMs(row)) }}</small>
                </template>
                <template v-else>
                  <span class="cell-datetime">已运 {{ formatAge(transitAgeMs(row)) }}</span>
                  <small class="sla-meta">时限 {{ TRANSIT_OVERDUE_HOURS }} 小时</small>
                </template>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="发运时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty><el-empty :description="transitEmptyHint" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="批次库存" name="inventory">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              class="report-table"
              v-loading="loading"
              :data="pagedInventory"
              stripe
              border
              table-layout="auto"
              :row-key="inventoryRowKey"
              @selection-change="onSelectionChange"
            >
          <el-table-column type="selection" width="48" />
          <el-table-column label="仓库" min-width="140">
            <template #default="{ row }">{{ warehouseName(row.warehouseId) }}</template>
          </el-table-column>
          <el-table-column label="商品" min-width="180">
            <template #default="{ row }">
              <div class="name-cell"><strong>{{ skuName(row.skuId) }}</strong><small class="cell-id">{{ row.skuId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column prop="batchNo" label="批次" min-width="150" />
          <el-table-column prop="productionDate" label="生产日期" min-width="120" />
          <el-table-column prop="expiryDate" label="到期日期" min-width="120" />
          <el-table-column prop="quantity" label="库存" min-width="88" />
          <el-table-column label="效期" min-width="100">
            <template #default="{ row }">
              <el-tag :type="expiryType(row.expiryDate)" size="small">{{ expiryText(row.expiryDate) }}</el-tag>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无库存" /></template>
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
              v-loading="loading"
              :data="pagedMovements"
              stripe
              border
              table-layout="auto"
              row-key="movementId"
              @selection-change="onSelectionChange"
            >
          <el-table-column type="selection" width="48" />
          <el-table-column prop="movementId" label="流水" min-width="90" />
          <el-table-column label="类型" min-width="130">
            <template #default="{ row }">{{ dictLabel('warehouse_movement_type', row.movementType) }}</template>
          </el-table-column>
          <el-table-column label="商品" min-width="180">
            <template #default="{ row }">
              <div class="name-cell"><strong>{{ skuName(row.skuId) }}</strong><small class="cell-id">{{ row.skuId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column prop="batchNo" label="批次" min-width="140" />
          <el-table-column prop="deltaQty" label="变动" min-width="88">
            <template #default="{ row }">
              <span :class="row.deltaQty >= 0 ? 'positive' : 'negative'">{{ row.deltaQty > 0 ? '+' : '' }}{{ row.deltaQty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="关联业务" min-width="140">
            <template #default="{ row }">{{ dictLabel('business_reference_type', row.refType) }}</template>
          </el-table-column>
          <el-table-column prop="refId" label="关联单号" min-width="120" />
          <el-table-column label="时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty><el-empty description="暂无流水" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="tabTotal"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
      />
    </div>

    <el-dialog v-model="warehouseDialog" :title="warehouseForm.editing ? '编辑仓库' : '新增仓库'" width="480px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="仓库 ID" required>
          <el-input v-model="warehouseForm.warehouseId" :disabled="warehouseForm.editing" placeholder="如 WH-SH-001" />
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

    <el-dialog v-model="supplierDialog" :title="supplierForm.editing ? '编辑供应商' : '新增供应商'" width="520px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="供应商 ID"><el-input v-model="supplierForm.supplierId" :disabled="supplierForm.editing" /></el-form-item>
        <el-form-item label="供应商名称"><el-input v-model="supplierForm.supplierName" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="supplierForm.contactName" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="supplierForm.contactPhone" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="supplierForm.status" style="width: 100%">
            <el-option v-for="item in dictOptions('supplier_status')" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="supplierDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSupplier">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="purchaseDialog" title="新建采购单" width="760px" class="dialog-wide" destroy-on-close>
      <el-form label-width="90px">
        <div class="form-grid">
          <el-form-item label="供应商">
            <el-select v-model="purchaseForm.supplierId" filterable style="width: 100%">
              <el-option v-for="item in activeSuppliers" :key="item.supplierId" :label="item.supplierName" :value="item.supplierId" />
            </el-select>
          </el-form-item>
          <el-form-item label="入库仓库">
            <el-select v-model="purchaseForm.warehouseId" style="width: 100%">
              <el-option v-for="item in activeWarehouses" :key="item.warehouseId" :label="item.warehouseName" :value="item.warehouseId" />
            </el-select>
          </el-form-item>
          <el-form-item label="外部单号"><el-input v-model="purchaseForm.refNo" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="purchaseForm.notes" /></el-form-item>
        </div>
        <div class="section-title">
          <span>采购商品</span>
          <el-button link type="primary" @click="addPurchaseLine">添加一行</el-button>
        </div>
        <div v-for="(line, index) in purchaseForm.lines" :key="index" class="purchase-line-card">
          <div class="line-card-head">
            <strong>明细 {{ index + 1 }}</strong>
            <el-button link type="danger" :disabled="purchaseForm.lines.length === 1" @click="purchaseForm.lines.splice(index, 1)">删除</el-button>
          </div>
          <div class="line-grid">
            <label class="line-field">
              <span>商品</span>
              <el-select v-model="line.skuId" filterable placeholder="选择商品">
                <el-option v-for="sku in skus" :key="sku.skuId" :label="`${sku.skuName || sku.skuId}`" :value="sku.skuId" />
              </el-select>
            </label>
            <label class="line-field"><span>批次号</span><el-input v-model="line.batchNo" /></label>
            <label class="line-field"><span>数量（件）</span><el-input-number v-model="line.orderedQty" :min="1" controls-position="right" /></label>
            <label class="line-field"><span>单价（元）</span><el-input-number v-model="line.unitCostYuan" :min="0.01" :step="0.01" :precision="2" controls-position="right" /></label>
            <label class="line-field"><span>生产日期</span><input v-model="line.productionDate" class="native-date" type="date" /></label>
            <label class="line-field"><span>到期日期</span><input v-model="line.expiryDate" class="native-date" type="date" /></label>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="purchaseDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePurchase">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="receiveDialog" title="采购收货" width="700px" class="dialog-wide" destroy-on-close>
      <div class="table-scroll">
      <el-table :data="receiveForm.lines" class="receive-table">
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">
            <div class="name-cell"><strong>{{ skuName(row.skuId) }}</strong><small class="cell-id">{{ row.skuId }}</small></div>
          </template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批次" min-width="140" />
        <el-table-column prop="orderedQty" label="采购数" width="90" />
        <el-table-column label="累计收货" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.receivedQty" :min="row.minReceived" :max="row.orderedQty" controls-position="right" />
          </template>
        </el-table-column>
      </el-table>
      </div>
      <el-input v-model="receiveForm.notes" type="textarea" placeholder="收货备注" style="margin-top: 12px" />
      <template #footer>
        <el-button @click="receiveDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveReceive">确认收货</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="returnDialog" title="采购退货" width="760px" class="dialog-wide" destroy-on-close>
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
              :label="`#${po.purchaseOrderId} · ${supplierName(po.supplierId)} · ${warehouseName(po.warehouseId)}`"
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
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">
            <div class="name-cell"><strong>{{ skuName(row.skuId) }}</strong><small class="cell-id">{{ row.skuId }}</small></div>
          </template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批次" min-width="140" />
        <el-table-column prop="receivedQty" label="已收" width="80" />
        <el-table-column prop="returnedQty" label="已退" width="80" />
        <el-table-column label="本次退货" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0" :max="row.maxQty" controls-position="right" />
          </template>
        </el-table-column>
      </el-table>
      </div>
      <template #footer>
        <el-button @click="returnDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveReturn">确认退货</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="inboundDialog" title="其他入库" width="720px" class="dialog-wide" destroy-on-close>
      <el-form label-width="88px">
        <div class="form-grid">
          <el-form-item label="仓库" required>
            <el-select v-model="inboundForm.warehouseId" style="width: 100%">
              <el-option v-for="w in activeWarehouses" :key="w.warehouseId" :label="w.warehouseName" :value="w.warehouseId" />
            </el-select>
          </el-form-item>
          <el-form-item label="参考单号"><el-input v-model="inboundForm.refNo" /></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="inboundForm.notes" /></el-form-item>
        <div class="section-title">
          <span>入库明细</span>
          <el-button link type="primary" @click="inboundForm.lines.push(newInboundLine())">添加一行</el-button>
        </div>
        <div v-for="(line, index) in inboundForm.lines" :key="index" class="purchase-line-card">
          <div class="line-card-head">
            <strong>明细 {{ index + 1 }}</strong>
            <el-button link type="danger" :disabled="inboundForm.lines.length === 1" @click="inboundForm.lines.splice(index, 1)">删除</el-button>
          </div>
          <div class="line-grid">
            <label class="line-field">
              <span>商品</span>
              <el-select v-model="line.skuId" filterable>
                <el-option v-for="sku in skus" :key="sku.skuId" :label="sku.skuName || sku.skuId" :value="sku.skuId" />
              </el-select>
            </label>
            <label class="line-field"><span>批次</span><el-input v-model="line.batchNo" /></label>
            <label class="line-field"><span>数量</span><el-input-number v-model="line.quantity" :min="1" controls-position="right" /></label>
            <label class="line-field"><span>生产日期</span><input v-model="line.productionDate" class="native-date" type="date" /></label>
            <label class="line-field"><span>到期日期</span><input v-model="line.expiryDate" class="native-date" type="date" /></label>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="inboundDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveInbound">确认入库</el-button>
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
        <span class="outbound-confirm-id" data-testid="outbound-confirm-id">出库单 #{{ outboundConfirm.outboundId }}</span>
        <br />
        {{ outboundConfirm.message }}
      </p>
      <template #footer>
        <el-button data-testid="outbound-confirm-cancel" @click="cancelOutboundConfirm">取消</el-button>
        <el-button
          type="primary"
          :loading="outboundConfirm.saving"
          data-testid="outbound-confirm-ok"
          @click="submitOutboundConfirm"
        >确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Box, EditPen, Refresh, RefreshLeft, Van } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, downloadAuthFile } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import { csvFileName } from '@/utils/csv';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const canWarehouseEdit = computed(() => auth.hasPerm('ops:warehouse:edit'));
const canProcurementEdit = computed(() => auth.hasPerm('ops:procurement:edit'));
const canProcurementList = computed(() => auth.hasPerm('ops:procurement:list'));
const canEdit = computed(() => {
  if (['suppliers', 'purchase', 'returns'].includes(tab.value)) return canProcurementEdit.value;
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
    return upper === 'ENABLED' ? 'ACTIVE' : upper === 'DISABLED' ? 'INACTIVE' : upper;
  }
  if (v === '启用' || v === '正常') return 'ACTIVE';
  if (v === '停用' || v === '禁用') return 'INACTIVE';
  return fallback;
}

const loading = ref(false);
const saving = ref(false);
const cleanupStaleLoading = ref(false);
const tab = ref('warehouses');
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
const purchaseOrders = ref<Row[]>([]);
const purchaseReturns = ref<Row[]>([]);
const outbounds = ref<Row[]>([]);
const inTransit = ref<Row[]>([]);
const inventory = ref<Row[]>([]);
const movements = ref<Row[]>([]);
const devices = ref<Row[]>([]);
const skus = ref<Row[]>([]);
const loadedTabs = ref(new Set<string>(['warehouses']));

const warehouseDialog = ref(false);
const supplierDialog = ref(false);
const purchaseDialog = ref(false);
const receiveDialog = ref(false);
const returnDialog = ref(false);
const inboundDialog = ref(false);

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
  status: 'ACTIVE'
});
const purchaseForm = reactive<Row>({ supplierId: '', warehouseId: '', refNo: '', notes: '', lines: [] });
const receiveForm = reactive<Row>({ purchaseOrderId: null, notes: '', lines: [] });
const returnForm = reactive<Row>({ purchaseOrderId: null, notes: '', lines: [] });
const inboundForm = reactive<Row>({ warehouseId: '', refNo: '', notes: '', lines: [] });

const pageHint = computed(() => {
  if (tab.value === 'transit') {
    return `在途签收时限 ${TRANSIT_OVERDUE_HOURS} 小时；超时标红，可勾选「仅超时」`;
  }
  return '仓库 / 供应商 / 库存 / 采购与退货';
});

const showFilterBar = computed(() =>
  ['suppliers', 'purchase', 'returns', 'inventory', 'movements', 'outbounds', 'transit'].includes(tab.value)
);
const activeSuppliers = computed(() => suppliers.value.filter((s) => s.status === 'ACTIVE'));
const activeWarehouses = computed(() => warehouses.value.filter((w) => (w.status || 'ACTIVE') === 'ACTIVE'));
const filteredSuppliers = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q || tab.value !== 'suppliers') return suppliers.value;
  return suppliers.value.filter((s) =>
    [s.supplierId, s.supplierName, s.contactName, s.contactPhone].join(' ').toLowerCase().includes(q)
  );
});
const filteredPurchaseOrders = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  let list = purchaseOrders.value;
  if (filterWarehouseId.value) list = list.filter((p) => p.warehouseId === filterWarehouseId.value);
  if (!q) return list;
  return list.filter((p) =>
    [p.purchaseOrderId, p.refNo, p.supplierId, supplierName(p.supplierId)].join(' ').toLowerCase().includes(q)
  );
});
const filteredPurchaseReturns = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  let list = purchaseReturns.value;
  if (filterWarehouseId.value) list = list.filter((r) => r.warehouseId === filterWarehouseId.value);
  if (!q) return list;
  return list.filter((r) =>
    [r.returnId, r.purchaseOrderId, r.supplierId, supplierName(r.supplierId)].join(' ').toLowerCase().includes(q)
  );
});
const returnablePurchaseOrders = computed(() =>
  purchaseOrders.value.filter((po) =>
    ['RECEIVED', 'PARTIAL_RECEIVED'].includes(po.status)
    && (po.lines || []).some((l: Row) => (l.receivedQty || 0) - (l.returnedQty || 0) > 0)
  )
);
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
  if (filterWarehouseId.value) {
    list = list.filter((o) => o.warehouseId === filterWarehouseId.value);
  }
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

function slicePage<T>(rows: T[]) {
  const start = (page.value - 1) * size.value;
  return rows.slice(start, start + size.value);
}

const tabSource = computed(() => {
  switch (tab.value) {
    case 'suppliers':
      return filteredSuppliers.value;
    case 'purchase':
      return filteredPurchaseOrders.value;
    case 'returns':
      return filteredPurchaseReturns.value;
    case 'outbounds':
      return filteredOutbounds.value;
    case 'transit':
      return filteredInTransit.value;
    case 'inventory':
      return inventory.value;
    case 'movements':
      return movements.value;
    default:
      return warehouses.value;
  }
});
const tabTotal = computed(() => tabSource.value.length);
const pagedWarehouses = computed(() => slicePage(warehouses.value));
const pagedSuppliers = computed(() => slicePage(filteredSuppliers.value));
const pagedPurchaseOrders = computed(() => slicePage(filteredPurchaseOrders.value));
const pagedPurchaseReturns = computed(() => slicePage(filteredPurchaseReturns.value));
const pagedOutbounds = computed(() => slicePage(filteredOutbounds.value));
const pagedInTransit = computed(() => slicePage(filteredInTransit.value));
const pagedInventory = computed(() => slicePage(inventory.value));
const pagedMovements = computed(() => slicePage(movements.value));

watch(tab, () => {
  page.value = 1;
  selectedKeys.value = [];
});
watch([keyword, filterWarehouseId, overdueOnly, focusDeviceId], () => {
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
    downloadSupplierTemplate(['Demo Beverage Supplier', 'SUP-DEMO-001', '张三', '13800000000', '启用']);
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
      row.refNo || '',
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
  headers: ['出库单', '目标设备', '商品', '批次', '数量', '状态', '在途时长', '是否超时', '发运时间'],
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
  const serverTabs = new Set(['warehouses', 'suppliers', 'purchase', 'returns', 'inventory', 'outbounds']);
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
    outbounds: '出库单'
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
  return suppliers.value.find((s) => s.supplierId === id)?.supplierName || id || '-';
}
function warehouseName(id: string) {
  return warehouses.value.find((w) => w.warehouseId === id)?.warehouseName || id || '-';
}
function deviceName(id: string) {
  return devices.value.find((d) => d.deviceId === id)?.deviceName || id || '-';
}
function skuName(id: string) {
  return skus.value.find((s) => s.skuId === id)?.skuName || id || '-';
}
function localDate() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
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
function expiryDays(value: string) {
  return Math.ceil((new Date(value).getTime() - Date.now()) / 86400000);
}
function expiryText(value: string) {
  const days = expiryDays(value);
  return days < 0 ? '已过期' : days <= 7 ? '临期' : `${days} 天`;
}
function expiryType(value: string) {
  const days = expiryDays(value);
  return days < 0 ? 'danger' : days <= 7 ? 'warning' : 'success';
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
    devices.value = await api.request<PageResult<Row>>('/api/v2/ops/admin/devices?page=0&size=200', 'GET')
      .then((r) => r?.items || [])
      .catch(() => []);
  }
  if (!skus.value.length) {
    skus.value = await api.request<Row[]>('/api/v2/ops/admin/skus', 'GET').catch(() => []);
  }
}

async function loadWarehouses() {
  warehouses.value = await api.request<Row[]>('/api/v2/ops/admin/warehouse/list', 'GET');
}
async function loadWarehousesSoft() {
  try {
    await loadWarehouses();
  } catch {
    /* 筛选用元数据失败时保留旧列表，不拖垮库存/出库主数据 */
  }
}
async function loadSuppliers() {
  suppliers.value = await api.request<Row[]>('/api/v2/ops/admin/suppliers', 'GET');
}
async function loadSuppliersSoft() {
  try {
    await loadSuppliers();
  } catch {
    /* 采购/退货筛选项可选 */
  }
}
async function loadPurchase() {
  purchaseOrders.value = await api.request<Row[]>('/api/v2/ops/admin/purchase-orders', 'GET');
}
async function loadReturns() {
  purchaseReturns.value = await api.request<Row[]>('/api/v2/ops/admin/purchase-returns', 'GET');
}
async function loadOutbounds() {
  outbounds.value = await api.request<Row[]>('/api/v2/ops/admin/warehouse/outbounds', 'GET');
}
async function loadTransit() {
  inTransit.value = await api.request<Row[]>('/api/v2/ops/admin/warehouse/in-transit', 'GET');
}
async function loadInventory() {
  const q = filterWarehouseId.value ? `?warehouseId=${encodeURIComponent(filterWarehouseId.value)}` : '';
  inventory.value = await api.request<Row[]>(`/api/v2/ops/admin/warehouse/inventory${q}`, 'GET');
}
async function loadMovements() {
  const q = filterWarehouseId.value ? `?warehouseId=${encodeURIComponent(filterWarehouseId.value)}` : '';
  movements.value = await api.request<Row[]>(`/api/v2/ops/admin/warehouse/movements${q}`, 'GET');
}

async function loadTab(name: string, force = false) {
  if (!force && loadedTabs.value.has(name) && name !== 'inventory' && name !== 'movements') return;
  loading.value = true;
  try {
    await ensureMeta();
    if (name === 'warehouses') await loadWarehouses();
    else if (name === 'suppliers') await loadSuppliers();
    else if (name === 'purchase') {
      await Promise.all([loadPurchase(), loadSuppliersSoft(), loadWarehousesSoft()]);
    } else if (name === 'returns') {
      await Promise.all([loadReturns(), loadPurchase().catch(() => {}), loadSuppliersSoft(), loadWarehousesSoft()]);
    } else if (name === 'outbounds') {
      await Promise.all([loadOutbounds(), loadWarehousesSoft()]);
    } else if (name === 'transit') await loadTransit();
    else if (name === 'inventory') {
      await Promise.all([loadInventory(), loadWarehousesSoft()]);
    } else if (name === 'movements') {
      await Promise.all([loadMovements(), loadWarehousesSoft()]);
    }
    loadedTabs.value.add(name);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
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
  if (tab.value === 'inventory' || tab.value === 'movements') {
    loadedTabs.value.delete(tab.value);
    loadTab(tab.value, true);
  }
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
    await api.request(`/api/v2/ops/admin/warehouse/${encodeURIComponent(warehouseForm.warehouseId.trim())}`, 'PUT', {
      warehouseName: warehouseForm.warehouseName.trim(),
      address: warehouseForm.address,
      status: warehouseForm.status
    });
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
    await api.request(`/api/v2/ops/admin/suppliers/${encodeURIComponent(supplierForm.supplierId.trim())}`, 'PUT', {
      supplierId: supplierForm.supplierId.trim(),
      supplierName: supplierForm.supplierName.trim(),
      contactName: supplierForm.contactName,
      contactPhone: supplierForm.contactPhone,
      status: supplierForm.status
    });
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
  await Promise.all([loadSuppliersSoft(), loadWarehousesSoft(), ensureMeta()]);
  Object.assign(purchaseForm, {
    supplierId: activeSuppliers.value[0]?.supplierId || '',
    warehouseId: activeWarehouses.value[0]?.warehouseId || '',
    refNo: '',
    notes: '',
    lines: [newLine()]
  });
  purchaseDialog.value = true;
}
function addPurchaseLine() {
  purchaseForm.lines.push(newLine());
}
async function savePurchase() {
  if (!purchaseForm.supplierId || purchaseForm.lines.some((l: Row) => !l.skuId || !l.batchNo || !l.expiryDate)) {
    return ElMessage.warning('请完整填写供应商、商品、批次和到期日期');
  }
  saving.value = true;
  try {
    const body = {
      supplierId: purchaseForm.supplierId,
      warehouseId: purchaseForm.warehouseId,
      refNo: purchaseForm.refNo,
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
    ElMessage.success('采购单已创建');
    loadedTabs.value.delete('purchase');
    await loadTab('purchase', true);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  } finally {
    saving.value = false;
  }
}

function openReceive(row: Row) {
  Object.assign(receiveForm, {
    purchaseOrderId: row.purchaseOrderId,
    notes: '',
    lines: (row.lines || []).map((line: Row) => ({
      ...line,
      minReceived: line.receivedQty || 0,
      receivedQty: line.receivedQty || 0
    }))
  });
  receiveDialog.value = true;
}
async function saveReceive() {
  saving.value = true;
  try {
    await ElMessageBox.confirm('确认按累计收货数量入库？', '采购收货', { type: 'warning' });
    await api.request(`/api/v2/ops/admin/purchase-orders/${receiveForm.purchaseOrderId}/receive`, 'POST', {
      lines: receiveForm.lines,
      notes: receiveForm.notes
    });
    receiveDialog.value = false;
    ElMessage.success('收货完成');
    loadedTabs.value.delete('purchase');
    loadedTabs.value.delete('inventory');
    await loadTab('purchase', true);
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e instanceof Error ? e.message : '收货失败');
  } finally {
    saving.value = false;
  }
}

async function openReturn() {
  await Promise.all([
    loadPurchase().catch(() => {}),
    loadSuppliersSoft(),
    loadWarehousesSoft(),
    ensureMeta()
  ]);
  const first = returnablePurchaseOrders.value[0];
  Object.assign(returnForm, {
    purchaseOrderId: first?.purchaseOrderId || null,
    notes: '',
    lines: []
  });
  if (first) onReturnPoChange(first.purchaseOrderId);
  returnDialog.value = true;
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
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e instanceof Error ? e.message : '退货失败');
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
    outboundConfirm.message = `确认出库单 #${row.outboundId} 已完成拣货？`;
  } else if (action === 'ship') {
    outboundConfirm.title = '确认发运';
    outboundConfirm.message = `确认发运出库单 #${row.outboundId}？发运后库存将转为在途。`;
  } else {
    outboundConfirm.title = row.status === 'SHIPPED' ? '作废回仓' : '作废出库';
    outboundConfirm.message =
      row.status === 'SHIPPED'
        ? `确认作废出库单 #${row.outboundId}？将回仓并取消在途（仅未签收）。`
        : `确认作废出库单 #${row.outboundId}？未发运单据将直接取消。`;
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
    const okMsg =
      action === 'pick' ? '拣货完成' : action === 'ship' ? '已发运' : '出库单已作废';
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
  await Promise.all([loadWarehousesSoft(), ensureMeta()]);
  Object.assign(inboundForm, {
    warehouseId: filterWarehouseId.value || activeWarehouses.value[0]?.warehouseId || '',
    refNo: '',
    notes: '',
    lines: [newInboundLine()]
  });
  inboundDialog.value = true;
}
async function saveInbound() {
  if (!inboundForm.warehouseId || inboundForm.lines.some((l: Row) => !l.skuId || !l.batchNo || !l.expiryDate || !l.quantity)) {
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
  const query: Record<string, string> = { ...Object.fromEntries(
    Object.entries(route.query)
      .filter((entry): entry is [string, string] => typeof entry[1] === 'string')
      .filter(([k]) => !['tab', 'overdue', 'deviceId'].includes(k))
  ) };
  if (nextTab && nextTab !== 'warehouses') query.tab = nextTab;
  if (nextTab === 'transit') {
    if (overdueOnly.value) query.overdue = '1';
    if (focusDeviceId.value) query.deviceId = focusDeviceId.value;
  }
  const same =
    String(route.query.tab || '') === String(query.tab || '')
    && String(route.query.overdue || '') === String(query.overdue || '')
    && String(route.query.deviceId || '') === String(query.deviceId || '');
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
    'warehouses', 'suppliers', 'purchase', 'returns', 'outbounds', 'transit', 'inventory', 'movements'
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
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.page-card-head__actions { display: flex; gap: 8px; flex-wrap: wrap; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }

.line-table { margin: 0; width: 100% !important; }
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
.hidden-input { display: none; }
.outbound-id-cell { font-weight: 650; font-variant-numeric: tabular-nums; }
.outbound-confirm-body { margin: 0; color: var(--layout-text); line-height: 1.6; font-size: 14px; }
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
  background: color-mix(in srgb, var(--el-color-danger) 6%, transparent) !important;
}
:deep(.el-table .is-due-soon > td.el-table__cell) {
  background: color-mix(in srgb, var(--el-color-warning) 7%, transparent) !important;
}
:deep(.el-table .is-focus > td.el-table__cell) {
  outline: 1px solid color-mix(in srgb, var(--app-primary, #0f766e) 35%, transparent);
}
.sla-banner { margin: 0 0 12px; }
.sla-cell { display: grid; gap: 2px; line-height: 1.35; }
.sla-meta { color: var(--el-text-color-secondary); font-size: 11px; }
.sla-meta.danger { color: var(--el-color-danger); }
.cell-datetime { font-variant-numeric: tabular-nums; }
.muted, .tip { color: var(--layout-muted); font-size: 13px; }
.tip { margin: 0 0 8px; }
.positive { color: #059669; font-weight: 700; }
.negative { color: #dc2626; font-weight: 700; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.section-title { display: flex; justify-content: space-between; align-items: center; margin: 8px 0 12px; font-weight: 700; }
.purchase-line-card { padding: 16px; margin-bottom: 14px; border: 1px solid var(--layout-border); border-radius: 12px; background: var(--el-fill-color-light); }
.line-card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.line-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.line-field { display: grid; gap: 7px; color: var(--layout-muted); font-size: 13px; }
.line-field :deep(.el-input-number), .line-field :deep(.el-select) { width: 100%; }
.native-date { width: 100%; height: 32px; padding: 0 10px; border: 1px solid var(--layout-border); border-radius: 4px; color: var(--layout-text); background: var(--layout-card); box-sizing: border-box; }
.receive-table { margin-bottom: 12px; }

@media (max-width: 900px) {
  .form-grid, .line-grid { grid-template-columns: 1fr; }
}
.name-cell { display: grid; gap: 2px; line-height: 1.35; }
.name-cell strong { color: var(--layout-text); font-weight: 650; }
.name-cell small {
  color: var(--layout-muted);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
</style>
