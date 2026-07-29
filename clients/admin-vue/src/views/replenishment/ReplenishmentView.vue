<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">补货调度</span>
            <span class="hint">路线 / 履约取证 / 要货 / 缺货；签到 GPS、用时与理货明细可核对现场履约</span>
          </div>
          <div class="kpi-tags">
            <el-tag size="small" type="info">待执行 {{ plannedCount }}</el-tag>
            <el-tag size="small" type="warning">待处理设备 {{ pendingTaskCount }}</el-tag>
            <el-tag size="small" type="success">已履约 {{ fulfilledCount }}</el-tag>
            <el-tag size="small">要货待审 {{ pendingRequestCount }}</el-tag>
            <el-tag size="small" type="danger">临期 {{ expiryAlerts.length }}</el-tag>
            <el-tag v-if="focusDeviceId" size="small" type="success" closable @close="clearDeviceFocus">
              设备 {{ focusDeviceId }}
            </el-tag>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-if="canEdit" type="primary" @click="openPlan">规划补货路线</el-button>
          <el-button v-hasPermi="['ops:replenishment:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="补货路线" name="routes">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="loading"
              :data="pagedRoutes"
              stripe
              border
              :empty-text="routesEmptyText"
              row-key="routeId"
              @selection-change="onRoutesSelectionChange"
            >
            <el-table-column type="selection" width="48" />
<el-table-column type="expand">
            <template #default="{ row }">
              <div class="route-detail">
                <div class="route-meta">
                  <span>计划日期：{{ row.plannedDate || '-' }}</span>
                  <span>负责人：{{ row.assigneeUserId || '未分配' }}</span>
                  <span>预计里程：{{ row.totalDistanceM ? `${row.totalDistanceM} 米` : '未计算' }}</span>
                </div>
                <el-table :data="row.tasks || []" size="small" class="line-table">
                  <el-table-column label="设备" min-width="200">
                    <template #default="scope">
                      <div class="master-data-cell">
                        <strong>
                          {{ deviceName(scope.row.deviceId) }}
                          <el-tag
                            size="small"
                            :type="deviceOnline(scope.row.deviceId) ? 'success' : 'info'"
                            class="online-tag"
                          >{{ deviceOnline(scope.row.deviceId) ? '在线' : '离线' }}</el-tag>
                        </strong>
                        <small>{{ scope.row.deviceId }}</small>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="任务状态" width="110">
                    <template #default="scope">
                      <el-tag :type="dictTagType(scope.row.status)" size="small">
                        {{ dictLabel('replenishment_task_status', scope.row.status) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="人员" width="88" align="center">
                    <template #default="scope">
                      <span class="mono">{{ scope.row.assigneeUserId || row.assigneeUserId || '-' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="签到" min-width="150" align="center">
                    <template #default="scope">
                      <div class="check-in-cell">
                        <el-tag :type="scope.row.checkInAt ? 'success' : 'info'" size="small">
                          {{ scope.row.checkInAt ? '已签到' : '未签到' }}
                        </el-tag>
                        <el-tag
                          v-if="scope.row.checkInAt && !checkInHasGps(scope.row)"
                          size="small"
                          type="warning"
                          effect="plain"
                        >无定位</el-tag>
                        <small v-else-if="formatCheckInGps(scope.row)" class="gps-text">{{ formatCheckInGps(scope.row) }}</small>
                        <small v-if="formatCheckInDistance(scope.row)" class="gps-text">{{ formatCheckInDistance(scope.row) }}</small>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="用时" width="88" align="center">
                    <template #default="scope">{{ formatTaskDuration(scope.row) }}</template>
                  </el-table-column>
                  <el-table-column label="完成" width="148" class-name="col-text">
                    <template #default="scope">
                      <span class="cell-datetime">{{ scope.row.completedAt ? formatDateTime(scope.row.completedAt) : '-' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="出库单" width="88" align="center">
                    <template #default="scope">
                      <el-tag v-if="scope.row.outboundId" size="small" type="warning">#{{ scope.row.outboundId }}</el-tag>
                      <span v-else class="muted">现场</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" :width="canEdit ? 220 : 88" align="center">
                    <template #default="scope">
                      <el-button link type="primary" @click="openTaskLines(scope.row)">明细</el-button>
                      <el-button
                        v-if="canEdit && canOpenRestock(scope.row)"
                        link
                        type="primary"
                        :loading="openDoorLoading === scope.row.taskId"
                        @click="openRestockDoor(scope.row)"
                      >开门</el-button>
                      <el-button
                        v-if="canEdit && canCompleteTask(scope.row)"
                        link
                        type="success"
                        :loading="completeLoading === scope.row.taskId"
                        @click="completeRestockTask(scope.row)"
                      >完成上架</el-button>
                      <span
                        v-else-if="canEdit && openDoorHint(scope.row) !== '-' && !canCompleteTask(scope.row)"
                        class="muted"
                      >{{ openDoorHint(scope.row) }}</span>
                    </template>
                  </el-table-column>
                  <template #empty><el-empty description="该路线暂无设备任务" :image-size="48" /></template>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="路线" min-width="220" class-name="col-text">
            <template #default="{ row }">
              <div class="master-data-cell">
                <strong>{{ row.routeName || row.routeId }}</strong>
                <small>{{ row.routeId }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="设备数" width="88" align="center">
            <template #default="{ row }">{{ row.tasks?.length || 0 }}</template>
          </el-table-column>
          <el-table-column prop="plannedDate" label="计划日期" width="120" class-name="col-text" />
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">
                {{ dictLabel('replenishment_route_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="100" align="center" class-name="col-action">
            <template #default="{ row }">
              <el-button
                v-if="canCancelEmptyRoute(row)"
                link
                type="danger"
                :loading="cancelRouteLoading === row.routeId"
                data-testid="cancel-empty-route"
                @click="cancelEmptyRoute(row)"
              >{{ row.status === 'CANCELLED' ? '收口脏出库' : '取消空路线' }}</el-button>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无补货路线" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="履约记录" name="fulfillment">
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="page = 1">
          <el-form-item label="状态">
            <el-select v-model="fulfillmentStatus" clearable placeholder="全部" style="width: 140px" @change="page = 1">
              <el-option label="已完成" value="COMPLETED" />
              <el-option label="进行中" value="IN_PROGRESS" />
              <el-option label="待执行" value="PENDING" />
              <el-option label="已取消" value="CANCELLED" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-checkbox
              v-model="fulfillmentUnassignedOnly"
              @change="page = 1"
            >
              仅待分配货道{{ unassignedHintCount ? ` (${unassignedHintCount})` : '' }}
            </el-checkbox>
          </el-form-item>
        </el-form>
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 1080px">
            <el-table
              v-loading="loading"
              :data="pagedFulfillment"
              stripe
              border
              class="report-table"
              row-key="taskId"
              :empty-text="fulfillmentEmptyText"
            >
              <el-table-column label="任务" width="88" class-name="col-text">
                <template #default="{ row }">
                  <span class="cell-id">#{{ row.taskId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="设备" min-width="180" class-name="col-text">
                <template #default="{ row }">
                  <button type="button" class="device-link-cell" @click="goDevice(row.deviceId)">
                    <strong>{{ deviceName(row.deviceId) }}</strong>
                    <small>{{ row.deviceId }}</small>
                  </button>
                </template>
              </el-table-column>
              <el-table-column label="路线" min-width="140" class-name="col-text" show-overflow-tooltip>
                <template #default="{ row }">{{ row.routeName || row.routeId || '-' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="148" align="center">
                <template #default="{ row }">
                  <div class="status-stack">
                    <el-tag :type="dictTagType(row.status)" size="small">
                      {{ dictLabel('replenishment_task_status', row.status) }}
                    </el-tag>
                    <el-tag
                      v-if="taskUnassignedHint[row.taskId]"
                      type="danger"
                      size="small"
                      effect="plain"
                    >
                      待分配
                    </el-tag>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="人员" width="88" align="center">
                <template #default="{ row }">
                  <span class="mono">{{ row.assigneeUserId || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="签到 / GPS" min-width="160" align="center">
                <template #default="{ row }">
                  <div class="check-in-cell">
                    <span class="cell-datetime">{{ row.checkInAt ? formatDateTime(row.checkInAt) : '未签到' }}</span>
                    <small
                      v-if="formatCheckInGps(row)"
                      class="gps-text"
                      :class="{ 'gps-missing': row.checkInAt && !checkInHasGps(row) }"
                    >{{ formatCheckInGps(row) }}</small>
                    <small v-if="formatCheckInDistance(row)" class="gps-text">{{ formatCheckInDistance(row) }}</small>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="用时" width="88" align="center">
                <template #default="{ row }">{{ formatTaskDuration(row) }}</template>
              </el-table-column>
              <el-table-column label="完成时间" width="168" class-name="col-text">
                <template #default="{ row }">
                  <span class="cell-datetime">{{ row.completedAt ? formatDateTime(row.completedAt) : '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" align="center" class-name="col-action">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openTaskLines(row)">
                    理货明细
                    <el-badge
                      v-if="taskUnassignedHint[row.taskId]"
                      is-dot
                      class="lines-dot"
                    />
                  </el-button>
                </template>
              </el-table-column>
              <template #empty><el-empty description="暂无履约记录" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="商户要货" name="requests">
        <div class="shortage-toolbar">
          <el-radio-group v-model="requestStatusFilter" size="small" @change="page = 1">
            <el-radio-button value="SUBMITTED">待审核</el-radio-button>
            <el-radio-button value="ACCEPTED">已接单</el-radio-button>
            <el-radio-button value="COMPLETED">已完成</el-radio-button>
            <el-radio-button value="REJECTED">已驳回</el-radio-button>
            <el-radio-button value="ALL">全部</el-radio-button>
          </el-radio-group>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="loading"
              :data="pagedRequests"
              stripe
              border
              row-key="requestId"
              @selection-change="onRequestsSelectionChange"
            >
          <el-table-column type="selection" width="48" />
          <el-table-column label="要货单" min-width="120" class-name="col-text">
            <template #default="{ row }"><span class="cell-id">{{ row.requestId }}</span></template>
          </el-table-column>
          <el-table-column prop="merchantName" label="商户" min-width="160" class-name="col-text" show-overflow-tooltip />
          <el-table-column label="目标设备" min-width="200" class-name="col-text">
            <template #default="{ row }">
              <button type="button" class="device-link-cell" @click="goDevice(row.deviceId)">
                <strong>{{ deviceName(row.deviceId) }}</strong>
                <small>{{ row.deviceId }}</small>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="明细" min-width="220" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ formatRequestLines(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">
                {{ dictLabel('replenishment_request_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="驳回原因" min-width="160" class-name="col-text" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.rejectReason" class="reject-reason">{{ row.rejectReason }}</span>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="补货任务" width="110" align="center" class-name="col-text">
            <template #default="{ row }">
              <el-button
                v-if="row.replenishmentTaskId"
                link
                type="primary"
                @click="goRequestTask(row)"
              >#{{ row.replenishmentTaskId }}</el-button>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.submittedAt || row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="160" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                v-if="requestActionsFor(row).length"
                :actions="requestActionsFor(row)"
                @action="(k) => onRequestAction(row, String(k))"
              />
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <template #empty><el-empty :description="requestsEmptyText" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="缺货建议" name="shortage">
        <div class="shortage-toolbar">
          <el-button v-if="canEdit && shortageDevices.length" type="primary" @click="planFromShortage">
            一键规划补货（{{ shortageDevices.length }} 台）
          </el-button>
          <el-button :icon="Refresh" :loading="shortageLoading" @click="loadShortage">刷新缺货</el-button>
        </div>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="shortageLoading"
              :data="pagedShortages"
              stripe
              border
              empty-text="当前无缺货/低库存货道"
              row-key="slotKey"
              @selection-change="onShortageSelectionChange"
            >
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="设备" min-width="180" class-name="col-text">
                <template #default="{ row }">
                  <button type="button" class="device-link-cell" @click="goDevice(row.deviceId)">
                    <strong>{{ deviceName(row.deviceId) }}</strong>
                    <small>{{ row.deviceId }}</small>
                  </button>
                </template>
              </el-table-column>
              <el-table-column prop="slotCode" label="货道" width="90" align="center" />
              <el-table-column prop="assignedSkuName" label="商品" min-width="140" class-name="col-text" show-overflow-tooltip />
              <el-table-column prop="bookQty" label="账面" width="80" align="center" />
              <el-table-column prop="minLevel" label="最低" width="80" align="center" />
              <el-table-column prop="parLevel" label="目标" width="80" align="center" />
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="stockTagType(row)" size="small">{{ stockLabel(row) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="canEdit" label="操作" width="100" align="center" class-name="col-action">
                <template #default="{ row }">
                  <el-button link type="primary" @click="planSingleDevice(row.deviceId)">补货</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="临期下架" name="expiry">
        <div class="shortage-toolbar">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="临期批次建议优先下架或换新；可跳转仓库批次或按设备规划补货。"
            class="expiry-hint"
          />
          <el-button :icon="Refresh" :loading="expiryLoading" @click="loadExpiryAlerts">刷新临期</el-button>
        </div>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="expiryLoading"
              :data="pagedExpiry"
              stripe
              border
              empty-text="当前无临期下架任务"
              row-key="taskId"
              @selection-change="onExpirySelectionChange"
            >
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="设备" min-width="180" class-name="col-text">
                <template #default="{ row }">
                  <button type="button" class="device-link-cell" @click="goDevice(row.deviceId)">
                    <strong>{{ deviceName(row.deviceId) }}</strong>
                    <small>{{ row.deviceId }}</small>
                  </button>
                </template>
              </el-table-column>
              <el-table-column prop="skuId" label="商品 SKU" min-width="140" class-name="col-text" show-overflow-tooltip />
              <el-table-column prop="batchNo" label="批次" min-width="120" class-name="col-text" show-overflow-tooltip />
              <el-table-column prop="lotId" label="批次 ID" min-width="120" class-name="col-text" show-overflow-tooltip />
              <el-table-column prop="quantity" label="数量" width="80" align="center" />
              <el-table-column label="原因" min-width="160" class-name="col-text" show-overflow-tooltip>
                <template #default="{ row }">{{ row.reason || '临期' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag size="small" type="warning">{{ row.status || 'OPEN' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="创建时间" width="168" class-name="col-text">
                <template #default="{ row }">
                  <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="240" align="center" class-name="col-action">
                <template #default="{ row }">
                  <el-button
                    v-if="canEdit"
                    link
                    type="danger"
                    :loading="expiryActingId === row.taskId"
                    @click="createFromExpiry(row, 'PULL_OFF')"
                  >下架任务</el-button>
                  <el-tooltip
                    :disabled="expiryRestockEnabled(row)"
                    content="货道已满，请先下架腾出库存后再补货"
                    placement="top"
                  >
                    <span class="expiry-restock-wrap">
                      <el-button
                        v-if="canEdit"
                        link
                        type="primary"
                        :disabled="!expiryRestockEnabled(row)"
                        :loading="expiryActingId === row.taskId"
                        @click="createFromExpiry(row, 'RESTOCK')"
                      >补货任务</el-button>
                    </span>
                  </el-tooltip>
                  <el-button link type="primary" @click="goWarehouse(row.deviceId)">仓库</el-button>
                </template>
              </el-table-column>
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
        background
      />
    </div>

    <el-drawer
      v-model="linesDrawer"
      :title="linesDrawerTitle"
      size="520px"
      destroy-on-close
      append-to-body
    >
      <div v-loading="linesLoading" class="lines-drawer">
        <el-descriptions v-if="linesTask" :column="1" border size="small" class="lines-meta">
          <el-descriptions-item label="设备">{{ deviceName(linesTask.deviceId) }}（{{ linesTask.deviceId }}）</el-descriptions-item>
          <el-descriptions-item label="人员">{{ linesTask.assigneeUserId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="签到">
            {{ linesTask.checkInAt ? formatDateTime(linesTask.checkInAt) : '未签到' }}
            <span
              v-if="formatCheckInGps(linesTask)"
              class="gps-inline"
              :class="{ 'gps-missing': !checkInHasGps(linesTask) }"
            > · {{ formatCheckInGps(linesTask) }}</span>
            <span v-if="formatCheckInDistance(linesTask)" class="gps-inline"> · {{ formatCheckInDistance(linesTask) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="用时">{{ formatTaskDuration(linesTask) }}</el-descriptions-item>
          <el-descriptions-item label="完成">
            {{ linesTask.completedAt ? formatDateTime(linesTask.completedAt) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="linesTask.notes" label="说明">{{ linesTask.notes }}</el-descriptions-item>
        </el-descriptions>
        <el-alert
          class="lines-photo-hint"
          type="info"
          :closable="false"
          show-icon
          title="现场照片"
          :description="taskEvidence.length
            ? `已采集 ${taskEvidence.length} 张（商户端上传）`
            : '尚未采集现场照片；可凭签到 GPS、用时与上架明细核对履约。'"
        />
        <div v-if="taskEvidence.length" class="evidence-grid">
          <div v-for="f in taskEvidence" :key="f.fileId" class="evidence-item">
            <button
              v-if="f.previewUrl"
              type="button"
              class="evidence-thumb"
              @click="openEvidencePreview(f)"
            >
              <img :src="f.previewUrl" :alt="f.fileName || `文件 #${f.fileId}`" />
            </button>
            <div class="evidence-meta">
              <span class="mono">{{ f.fileName || `文件 #${f.fileId}` }}</span>
              <span class="meta">{{ formatFileSize(f.fileSize) }}</span>
            </div>
          </div>
        </div>
        <div class="table-scroll">
        <el-table :data="taskLines" stripe border size="small" empty-text="暂无理货明细（未上架或未确认）">
          <el-table-column label="类型" width="88" align="center">
            <template #default="{ row }">{{ lineTypeLabel(row.lineType) }}</template>
          </el-table-column>
          <el-table-column label="商品" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono">{{ row.skuId || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="72" align="center" />
          <el-table-column label="货道" min-width="150" align="center">
            <template #default="{ row }">
              <el-select
                v-if="canAssignSlot(row)"
                v-model="row.slotId"
                clearable
                filterable
                size="small"
                placeholder="待分配"
                style="width: 130px"
                @change="(v: string | null) => onSlotAssign(row, v)"
              >
                <el-option
                  v-for="opt in slotOptionsForLine(row)"
                  :key="opt.slotCode"
                  :label="`${opt.slotCode} · 余${opt.room}`"
                  :value="opt.slotCode"
                  :disabled="opt.room <= 0 && opt.slotCode !== row.slotId"
                />
              </el-select>
              <template v-else>
                <el-tag v-if="!row.slotId && isRestockLine(row)" type="warning" size="small">待分配</el-tag>
                <span v-else>{{ row.slotId || '-' }}</span>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="批次" min-width="100" show-overflow-tooltip>
            <template #default="{ row }">{{ row.batchNo || '-' }}</template>
          </el-table-column>
          <el-table-column label="效期" width="110" align="center">
            <template #default="{ row }">{{ row.expiryDate || '-' }}</template>
          </el-table-column>
          <el-table-column label="已入账" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.applied ? 'success' : 'info'" size="small">
                {{ row.applied ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        </div>
        <div v-if="taskLines.length" class="lines-summary">
          <span>合计上架 {{ restockQtyTotal }} 件 · {{ taskLines.length }} 行</span>
          <el-tag v-if="unassignedRestockCount" type="warning" size="small" class="unassigned-badge">
            待分配 {{ unassignedRestockCount }}
          </el-tag>
        </div>
        <div
          v-if="canEdit && linesTask && String(linesTask.status) !== 'COMPLETED' && editablePendingLines.length"
          class="lines-actions"
        >
          <el-button
            type="primary"
            :loading="slotSaving"
            :disabled="!!unassignedRestockCount"
            @click="saveTaskSlots"
          >保存货道分配</el-button>
          <span v-if="unassignedRestockCount" class="lines-action-hint">请先为待分配行选择货道</span>
        </div>
      </div>
    </el-drawer>

    <el-dialog
      v-model="planDialog"
      title="规划补货路线"
      width="620px"
      append-to-body
      destroy-on-close
      data-testid="plan-route-dialog"
    >
      <el-form label-width="96px" class="plan-form">
        <el-form-item label="路线名称" required>
          <el-input
            v-model="planForm.routeName"
            maxlength="80"
            placeholder="例如：浦东早班补货路线"
            data-testid="plan-route-name"
          />
        </el-form-item>
        <el-form-item label="计划日期">
          <input v-model="planForm.plannedDate" class="native-date" type="date" data-testid="plan-route-date" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input-number v-model="planForm.assigneeUserId" :min="1" :precision="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="目标设备" required>
          <!-- 勾选列表替代下拉：热区更大，Browser 不易难点选 -->
          <div class="plan-device-list" data-testid="plan-device-select">
            <el-checkbox-group v-model="planForm.deviceIds" class="plan-device-group">
              <!-- div 而非 label：避免外层 label 与 el-checkbox 内部 label 双绑导致偶发点选无效 -->
              <div
                v-for="device in devices"
                :key="device.deviceId"
                class="plan-device-option"
                :data-testid="`plan-device-option-${device.deviceId}`"
              >
                <el-checkbox :label="device.deviceId">
                  {{ device.deviceName || device.deviceId }}（{{ device.deviceId }}）
                </el-checkbox>
              </div>
            </el-checkbox-group>
          </div>
          <div v-if="!shortageDevices.length" class="plan-hint">
            当前无缺货建议：满柜时无法规划。请先盘点/消费产生缺口，或到「缺货建议」查看。
          </div>
          <div v-else-if="selectedDevicesWithoutShortage.length" class="plan-hint">
            所选设备中 {{ selectedDevicesWithoutShortage.join('、') }} 不在缺货建议内，满柜可能无法生成出库单。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="plan-dialog-footer">
          <el-button native-type="button" @click="planDialog = false">取消</el-button>
          <el-button
            type="primary"
            native-type="button"
            class="plan-create-btn"
            :loading="saving"
            :disabled="!planForm.deviceIds.length || saving"
            data-testid="plan-create-route"
            @click.stop="createPlan"
          >
            创建路线
          </el-button>
        </div>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Check, Close, Refresh, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, downloadAuthFile } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { csvFileName } from '@/utils/csv';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
const route = useRoute();
const router = useRouter();
const { goPath } = useNavAccess();
const auth = useAuthStore();
const canEdit = computed(() => auth.hasPerm('ops:replenishment:edit'));

const loading = ref(false);
const saving = ref(false);
const shortageLoading = ref(false);
const expiryLoading = ref(false);
const expiryActingId = ref<number | null>(null);
const openDoorLoading = ref<number | null>(null);
const completeLoading = ref<number | null>(null);
const cancelRouteLoading = ref<number | null>(null);
const tab = ref('routes');
const page = ref(1);
const size = ref(20);
const focusDeviceId = ref('');
const fulfillmentStatus = ref('');
const fulfillmentUnassignedOnly = ref(false);
const requestStatusFilter = ref('SUBMITTED');
const routes = ref<Row[]>([]);
const allRequests = ref<Row[]>([]);
const devices = ref<Row[]>([]);
const shortages = ref<Row[]>([]);
const expiryAlerts = ref<Row[]>([]);
const linesDrawer = ref(false);
const linesLoading = ref(false);
const slotSaving = ref(false);
const linesTask = ref<Row | null>(null);
const taskLines = ref<Row[]>([]);
const deviceSlots = ref<Row[]>([]);
const taskUnassignedHint = ref<Record<number, boolean>>({});
const taskEvidence = ref<
  { fileId: number; fileName?: string; fileSize?: number; contentType?: string; previewUrl?: string }[]
>([]);
const evidenceObjectUrls = ref<string[]>([]);

const shortageDevices = computed(() =>
  [...new Set(shortages.value.map((s) => s.deviceId).filter(Boolean))]
);
const planDialog = ref(false);
const planForm = reactive({
  routeName: '',
  plannedDate: '',
  assigneeUserId: currentAssigneeId(),
  deviceIds: [] as string[]
});
const selectedDevicesWithoutShortage = computed(() =>
  planForm.deviceIds.filter((id) => !shortageDevices.value.includes(id))
);

const requests = computed(() => {
  const status = requestStatusFilter.value;
  if (!status || status === 'ALL') return allRequests.value;
  return allRequests.value.filter((r) => String(r.status || '') === status);
});
const pendingRequestCount = computed(
  () => allRequests.value.filter((r) => String(r.status || '') === 'SUBMITTED').length
);
const requestsEmptyText = computed(() => {
  switch (requestStatusFilter.value) {
    case 'ACCEPTED':
      return '暂无已接单要货';
    case 'COMPLETED':
      return '暂无已完成要货';
    case 'REJECTED':
      return '暂无已驳回要货';
    case 'ALL':
      return '暂无要货申请';
    default:
      return '暂无待处理要货申请';
  }
});
const plannedCount = computed(() =>
  routes.value.filter((item) => ['PLANNED', 'IN_PROGRESS'].includes(item.status)).length
);
const pendingTaskCount = computed(() =>
  routes.value
    .flatMap((item) => item.tasks || [])
    .filter((item) => ['PENDING', 'IN_PROGRESS'].includes(item.status)).length
);
const fulfillmentTasksBase = computed(() => {
  const id = focusDeviceId.value.trim();
  const status = fulfillmentStatus.value;
  const rows: Row[] = [];
  for (const routeRow of routes.value) {
    for (const task of routeRow.tasks || []) {
      if (id && task.deviceId !== id) continue;
      if (status && String(task.status || '') !== status) continue;
      rows.push({
        ...task,
        routeId: routeRow.routeId,
        routeName: routeRow.routeName,
        assigneeUserId: task.assigneeUserId || routeRow.assigneeUserId
      });
    }
  }
  return rows.sort((a, b) => {
    const ta = Date.parse(String(a.completedAt || a.checkInAt || a.createdAt || 0)) || 0;
    const tb = Date.parse(String(b.completedAt || b.checkInAt || b.createdAt || 0)) || 0;
    return tb - ta;
  });
});
const fulfillmentTasks = computed(() => {
  if (!fulfillmentUnassignedOnly.value) return fulfillmentTasksBase.value;
  return fulfillmentTasksBase.value.filter((t) => taskUnassignedHint.value[Number(t.taskId)]);
});
const unassignedHintCount = computed(
  () => fulfillmentTasksBase.value.filter((t) => taskUnassignedHint.value[Number(t.taskId)]).length
);
const fulfilledCount = computed(
  () => routes.value.flatMap((r) => r.tasks || []).filter((t) => String(t.status) === 'COMPLETED').length
);
const fulfillmentEmptyText = computed(() => {
  if (focusDeviceId.value.trim()) return `设备 ${focusDeviceId.value} 暂无履约记录`;
  if (fulfillmentUnassignedOnly.value) return '暂无待分配货道的开放任务';
  if (fulfillmentStatus.value) return '当前筛选下暂无履约记录';
  return '暂无履约记录';
});
const filteredRoutes = computed(() => {
  const id = focusDeviceId.value.trim();
  if (!id) return routes.value;
  return routes.value.filter((row) => (row.tasks || []).some((task: Row) => task.deviceId === id));
});
const routesEmptyText = computed(() =>
  focusDeviceId.value.trim()
    ? `设备 ${focusDeviceId.value} 暂无关联补货路线`
    : '暂无补货路线'
);

function slicePage<T>(rows: T[]) {
  const start = (page.value - 1) * size.value;
  return rows.slice(start, page.value * size.value);
}

const tabTotal = computed(() => {
  if (tab.value === 'requests') return requests.value.length;
  if (tab.value === 'shortage') return shortages.value.length;
  if (tab.value === 'expiry') return expiryAlerts.value.length;
  if (tab.value === 'fulfillment') return fulfillmentTasks.value.length;
  return filteredRoutes.value.length;
});
const pagedRoutes = computed(() => slicePage(filteredRoutes.value));
const pagedRequests = computed(() => slicePage(requests.value));
const pagedShortages = computed(() => slicePage(shortages.value));
const pagedExpiry = computed(() => slicePage(expiryAlerts.value));
const pagedFulfillment = computed(() => slicePage(fulfillmentTasks.value));
const linesDrawerTitle = computed(() =>
  linesTask.value?.taskId ? `理货明细 · 任务 #${linesTask.value.taskId}` : '理货明细'
);
const restockQtyTotal = computed(() =>
  taskLines.value
    .filter((l) => String(l.lineType || 'RESTOCK').toUpperCase() === 'RESTOCK')
    .reduce((sum, l) => sum + (Number(l.quantity) || 0), 0)
);
const editableRestockLines = computed(() =>
  taskLines.value.filter(
    (l) =>
      !l.applied &&
      String(l.lineType || 'RESTOCK').toUpperCase() === 'RESTOCK' &&
      String(linesTask.value?.status || '') !== 'COMPLETED'
  )
);
const editablePendingLines = computed(() =>
  taskLines.value.filter(
    (l) => !l.applied && String(linesTask.value?.status || '') !== 'COMPLETED'
  )
);
const unassignedRestockCount = computed(
  () => editableRestockLines.value.filter((l) => !String(l.slotId || '').trim()).length
);

function isRestockLine(row: Row) {
  return String(row.lineType || 'RESTOCK').toUpperCase() === 'RESTOCK';
}

function canAssignSlot(row: Row) {
  return (
    canEdit.value &&
    !!linesTask.value &&
    String(linesTask.value.status || '') !== 'COMPLETED' &&
    !row.applied &&
    isRestockLine(row)
  );
}

function slotRoom(slot: Row) {
  const maxLevel = Number(slot.maxLevel) || 0;
  const bookQty = Number(slot.bookQty) || 0;
  if (maxLevel <= 0) return 99;
  return Math.max(0, maxLevel - bookQty);
}

function slotOptionsForLine(row: Row) {
  const skuId = String(row.skuId || '');
  return deviceSlots.value
    .filter((s) => s.enabled !== false)
    .filter((s) => !s.assignedSkuId || String(s.assignedSkuId) === skuId)
    .map((s) => ({
      slotCode: String(s.slotCode || '').toUpperCase(),
      room: slotRoom(s)
    }))
    .filter((s) => !!s.slotCode)
    .sort((a, b) => b.room - a.room || a.slotCode.localeCompare(b.slotCode));
}

function onSlotAssign(row: Row, slotCode: string | null | undefined) {
  const code = String(slotCode || '').trim().toUpperCase();
  row.slotId = code || undefined;
  if (!code) return;
  const opt = slotOptionsForLine(row).find((o) => o.slotCode === code);
  if (opt && Number(row.quantity) > opt.room) {
    row.quantity = opt.room;
    ElMessage.info(`已按货道余量调至 ${opt.room}`);
  }
}

watch([tab, focusDeviceId, fulfillmentStatus, fulfillmentUnassignedOnly, requestStatusFilter], () => {
  page.value = 1;
});

watch(linesDrawer, (open) => {
  if (!open) revokeEvidencePreviews();
});

const requestActions: TableAction[] = [
  { key: 'accept', label: '接单', icon: Check, type: 'primary' },
  { key: 'reject', label: '驳回', icon: Close, type: 'danger' }
];

function requestActionsFor(row: Row): TableAction[] {
  if (row.status === 'SUBMITTED') return requestActions;
  if (row.replenishmentTaskId && (row.status === 'ACCEPTED' || row.status === 'COMPLETED')) {
    return [{ key: 'view-task', label: '查看任务', icon: View, type: 'primary' }];
  }
  return [];
}

const {
  onSelectionChange: onRoutesSelectionChange,
  pickSelected: pickRoutes,
  exportButtonLabel: routesExportLabel,
  clearSelection: clearRoutesSelection
} = useTableSelection<Row>((r) => r.routeId);

const {
  onSelectionChange: onRequestsSelectionChange,
  pickSelected: pickRequests,
  exportButtonLabel: requestsExportLabel,
  clearSelection: clearRequestsSelection
} = useTableSelection<Row>((r) => r.requestId);

const {
  onSelectionChange: onShortageSelectionChange,
  pickSelected: pickShortages,
  exportButtonLabel: shortageExportLabel,
  clearSelection: clearShortageSelection
} = useTableSelection<Row>((r) => r.slotKey || `${r.deviceId}-${r.slotCode}`);

const {
  onSelectionChange: onExpirySelectionChange,
  pickSelected: pickExpiry,
  exportButtonLabel: expiryExportLabel,
  clearSelection: clearExpirySelection
} = useTableSelection<Row>((r) => r.taskId || `${r.deviceId}-${r.lotId}-${r.skuId}`);

const exportButtonLabel = computed(() => {
  if (tab.value === 'requests') return requestsExportLabel.value;
  if (tab.value === 'shortage') return shortageExportLabel.value;
  if (tab.value === 'expiry') return expiryExportLabel.value;
  if (tab.value === 'fulfillment') return '导出履约记录';
  return routesExportLabel.value;
});

const { onExport: exportRoutes } = useListCsv({
  filePrefix: '补货路线',
  headers: ['路线编号', '路线名称', '设备数', '计划日期', '状态'],
  toRows: () =>
    pickRoutes(routes.value).map((row) => [
      row.routeId,
      row.routeName || '',
      row.tasks?.length || 0,
      row.plannedDate || '',
      dictLabel('replenishment_route_status', row.status)
    ])
});

const { onExport: exportFulfillment } = useListCsv({
  filePrefix: '履约记录',
  headers: ['任务', '设备编号', '设备名称', '路线', '状态', '人员', '签到时间', 'GPS', '距柜机', '用时', '完成时间'],
  toRows: () =>
    fulfillmentTasks.value.map((row) => [
      row.taskId,
      row.deviceId || '',
      deviceName(row.deviceId),
      row.routeName || row.routeId || '',
      dictLabel('replenishment_task_status', row.status),
      row.assigneeUserId || '',
      row.checkInAt ? formatDateTime(row.checkInAt) : '',
      formatCheckInGps(row),
      formatCheckInDistance(row),
      formatTaskDuration(row),
      row.completedAt ? formatDateTime(row.completedAt) : ''
    ])
});

const { onExport: exportRequests } = useListCsv({
  filePrefix: '商户要货',
  headers: ['要货单', '商户', '目标设备', '状态', '驳回原因', '提交时间'],
  toRows: () =>
    pickRequests(requests.value).map((row) => [
      row.requestId,
      row.merchantName || '',
      deviceName(row.deviceId),
      dictLabel('replenishment_request_status', row.status),
      row.rejectReason || '',
      formatDateTime(row.submittedAt || row.createdAt)
    ])
});

const { onExport: exportShortages } = useListCsv({
  filePrefix: '缺货建议',
  headers: ['设备', '货道', '商品', '账面', '最低', '目标', '状态'],
  toRows: () =>
    pickShortages(shortages.value).map((row) => [
      row.deviceId,
      row.slotCode,
      row.assignedSkuName || '',
      row.bookQty,
      row.minLevel,
      row.parLevel,
      row.stockStatus || (row.bookQty <= 0 ? '缺货' : '低库存')
    ])
});

const { onExport: exportExpiry } = useListCsv({
  filePrefix: '临期下架',
  headers: ['任务', '设备', 'SKU', '批次', '批次ID', '数量', '原因', '状态', '创建时间'],
  toRows: () =>
    pickExpiry(expiryAlerts.value).map((row) => [
      row.taskId,
      row.deviceId,
      row.skuId,
      row.batchNo || '',
      row.lotId || '',
      row.quantity,
      row.reason || '临期',
      row.status || '',
      formatDateTime(row.createdAt)
    ])
});

async function onExport() {
  if (tab.value === 'shortage') {
    exportShortages();
    return;
  }
  if (tab.value === 'expiry') {
    exportExpiry();
    return;
  }
  if (tab.value === 'fulfillment') {
    exportFulfillment();
    return;
  }
  if (tab.value === 'requests') {
    const selected = pickRequests(requests.value);
    if (selected.length && selected.length < requests.value.length) {
      exportRequests();
      return;
    }
    try {
      await downloadAuthFile('/api/v2/ops/admin/replenishment/requests/export', csvFileName('商户要货'));
      ElMessage.success('已导出');
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '导出失败');
    }
    return;
  }
  const selected = pickRoutes(routes.value);
  if (selected.length && selected.length < routes.value.length) {
    exportRoutes();
    return;
  }
  try {
    await downloadAuthFile('/api/v2/ops/admin/replenishment/routes/export', csvFileName('补货路线'));
    ElMessage.success('已导出');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

function currentAssigneeId() {
  const id = Number(auth.userId || localStorage.getItem('admin_userId') || 0);
  return Number.isFinite(id) && id > 0 ? id : 1;
}
function deviceName(deviceId: string) {
  return devices.value.find((item) => item.deviceId === deviceId)?.deviceName || deviceId || '-';
}
function localDate() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

function stockLabel(row: Row) {
  const code = String(row.stockStatus || '').toUpperCase();
  if (code === 'OOS' || (row.bookQty ?? 0) <= 0) return '缺货';
  if (code === 'LOW') return '低库存';
  if (code === 'OK' || code === 'NORMAL') return '正常';
  return row.stockStatus || ((row.bookQty ?? 0) <= (row.minLevel ?? 0) ? '低库存' : '缺货');
}
function stockTagType(row: Row) {
  const label = stockLabel(row);
  if (label === '缺货') return 'danger';
  if (label === '低库存') return 'warning';
  return 'info';
}

function goDevice(deviceId?: string) {
  if (!deviceId) return;
  goPath(`/devices/${encodeURIComponent(deviceId)}`);
}

function openPlan() {
  Object.assign(planForm, {
    routeName: `${new Date().toLocaleDateString('zh-CN')} 补货路线`,
    plannedDate: localDate(),
    assigneeUserId: currentAssigneeId(),
    deviceIds: focusDeviceId.value.trim() ? [focusDeviceId.value.trim()] : []
  });
  planDialog.value = true;
}

function syncRouteQuery() {
  const query: Record<string, string> = { tab: tab.value };
  if (focusDeviceId.value.trim()) query.deviceId = focusDeviceId.value.trim();
  router.replace({ query });
}

function onTabChange() {
  page.value = 1;
  syncRouteQuery();
}

function clearDeviceFocus() {
  focusDeviceId.value = '';
  syncRouteQuery();
}

function applyRouteQuery() {
  let changed = false;
  const allowed = ['routes', 'requests', 'shortage', 'fulfillment', 'expiry'] as const;
  const qTab = typeof route.query.tab === 'string' ? route.query.tab : '';
  if (allowed.includes(qTab as (typeof allowed)[number])) {
    if (tab.value !== qTab) {
      tab.value = qTab;
      changed = true;
    }
  } else if (!qTab && tab.value !== 'routes') {
    tab.value = 'routes';
    changed = true;
  }
  const nextFocus = typeof route.query.deviceId === 'string' ? route.query.deviceId : '';
  if (focusDeviceId.value !== nextFocus) {
    focusDeviceId.value = nextFocus;
    changed = true;
  }
  return changed;
}

async function planFromShortage() {
  const ids = shortageDevices.value;
  if (!ids.length) return ElMessage.warning('当前无缺货设备');
  Object.assign(planForm, {
    routeName: `${new Date().toLocaleDateString('zh-CN')} 缺货补货`,
    plannedDate: localDate(),
    assigneeUserId: currentAssigneeId(),
    deviceIds: focusDeviceId.value && ids.includes(focusDeviceId.value)
      ? [focusDeviceId.value]
      : ids
  });
  planDialog.value = true;
}

function planSingleDevice(deviceId: string) {
  if (!deviceId) return;
  Object.assign(planForm, {
    routeName: `${new Date().toLocaleDateString('zh-CN')} ${deviceId} 补货`,
    plannedDate: localDate(),
    assigneeUserId: currentAssigneeId(),
    deviceIds: [deviceId]
  });
  planDialog.value = true;
}

async function loadShortage() {
  shortageLoading.value = true;
  try {
    await load();
  } finally {
    shortageLoading.value = false;
  }
}

async function loadExpiryAlerts() {
  expiryLoading.value = true;
  try {
    const list = await api.request<Row[]>('/api/v2/ops/admin/expiry/alerts', 'GET');
    let rows = list || [];
    if (focusDeviceId.value.trim()) {
      rows = rows.filter((x) => x.deviceId === focusDeviceId.value.trim());
    }
    expiryAlerts.value = rows;
    clearExpirySelection();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '临期告警加载失败');
  } finally {
    expiryLoading.value = false;
  }
}

function goWarehouse(deviceId?: string) {
  const query: Record<string, string> = { tab: 'transit' };
  if (deviceId) query.deviceId = deviceId;
  goPath('/warehouse', query);
}

function deviceOnline(deviceId?: string) {
  if (!deviceId) return false;
  const d = devices.value.find((item) => item.deviceId === deviceId);
  return String(d?.onlineStatus || '').toUpperCase() === 'ONLINE';
}

function formatCheckInGps(row: Row) {
  if (!row?.checkInAt) return '';
  const lat = row.checkInLat;
  const lng = row.checkInLng;
  if (lat == null || lng == null || Number.isNaN(Number(lat)) || Number.isNaN(Number(lng))) {
    return '无定位';
  }
  return `${Number(lat).toFixed(4)},${Number(lng).toFixed(4)}`;
}

function formatCheckInDistance(row: Row) {
  if (!row?.checkInAt || !checkInHasGps(row)) return '';
  const dist = row.checkInDistanceM;
  if (dist == null || Number.isNaN(Number(dist))) return '';
  const meters = Math.round(Number(dist));
  if (meters < 1000) return `距柜机 ${meters}m`;
  return `距柜机 ${(meters / 1000).toFixed(1)}km`;
}

function checkInHasGps(row: Row) {
  if (!row?.checkInAt) return false;
  const lat = row.checkInLat;
  const lng = row.checkInLng;
  return lat != null && lng != null && !Number.isNaN(Number(lat)) && !Number.isNaN(Number(lng));
}

function parseInstantMs(value: unknown) {
  if (value == null || value === '') return null;
  const ms = Date.parse(String(value));
  return Number.isFinite(ms) ? ms : null;
}

function formatTaskDuration(row: Row) {
  const start = parseInstantMs(row?.checkInAt);
  if (start == null) return '-';
  const end = parseInstantMs(row?.completedAt) ?? (['COMPLETED', 'CANCELLED'].includes(String(row?.status || ''))
    ? start
    : Date.now());
  const mins = Math.max(0, Math.round((end - start) / 60000));
  if (mins < 60) return `${mins} 分`;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m ? `${h} 时 ${m} 分` : `${h} 时`;
}

function lineTypeLabel(type?: string) {
  const code = String(type || 'RESTOCK').toUpperCase();
  if (code === 'RESTOCK') return '上架';
  if (code === 'PULL_OFF' || code === 'REMOVE' || code === 'PULL') return '下架';
  return code;
}

function formatFileSize(size?: number) {
  if (size == null || size <= 0) return '-';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function expiryRestockEnabled(row: Row) {
  const headroom = Number(row?.restockHeadroom);
  return Number.isFinite(headroom) ? headroom > 0 : true;
}

async function createFromExpiry(row: Row, lineType: 'PULL_OFF' | 'RESTOCK') {
  if (!row?.taskId || expiryActingId.value != null) return;
  if (lineType === 'RESTOCK' && !expiryRestockEnabled(row)) {
    ElMessage.warning('货道已满，请先下架腾出库存后再补货');
    return;
  }
  expiryActingId.value = Number(row.taskId);
  try {
    const route = await api.request<{ routeId?: number; tasks?: { taskId?: number }[] }>(
      `/api/v2/ops/admin/expiry/alerts/${row.taskId}/create-replenishment`,
      'POST',
      { lineType }
    );
    ElMessage.success(
      lineType === 'PULL_OFF'
        ? `已生成下架任务 #${route?.tasks?.[0]?.taskId || route?.routeId || ''}`
        : `已生成补货任务 #${route?.tasks?.[0]?.taskId || route?.routeId || ''}`
    );
    await loadExpiryAlerts();
    await load();
    tab.value = 'routes';
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生成任务失败');
  } finally {
    expiryActingId.value = null;
  }
}

async function openTaskLines(task: Row) {
  if (!task?.taskId) return;
  linesTask.value = task;
  taskLines.value = [];
  deviceSlots.value = [];
  revokeEvidencePreviews();
  taskEvidence.value = [];
  linesDrawer.value = true;
  linesLoading.value = true;
  try {
    const [lines, evidence, slots] = await Promise.all([
      api.request<Row[]>(`/api/v2/ops/admin/replenishment/tasks/${task.taskId}/lines`, 'GET'),
      api.request<{ fileId: number; fileName?: string; fileSize?: number; contentType?: string }[]>(
        `/api/v2/ops/admin/replenishment/tasks/${task.taskId}/evidence`,
        'GET'
      ).catch(() => []),
      task.deviceId
        ? api
            .request<Row[]>(
              `/api/v2/ops/admin/devices/${encodeURIComponent(String(task.deviceId))}/slots`,
              'GET'
            )
            .catch(() => [])
        : Promise.resolve([])
    ]);
    taskLines.value = (lines || []).map((l) => ({ ...l }));
    deviceSlots.value = slots || [];
    taskEvidence.value = evidence || [];
    const unassigned = (lines || []).some(
      (l) =>
        !l.applied &&
        String(l.lineType || 'RESTOCK').toUpperCase() === 'RESTOCK' &&
        !String(l.slotId || '').trim()
    );
    taskUnassignedHint.value = {
      ...taskUnassignedHint.value,
      [Number(task.taskId)]: unassigned && String(task.status) !== 'COMPLETED'
    };
    await loadEvidencePreviews(task.taskId, taskEvidence.value);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '理货明细加载失败');
  } finally {
    linesLoading.value = false;
  }
}

async function saveTaskSlots() {
  if (!linesTask.value?.taskId || slotSaving.value) return;
  if (unassignedRestockCount.value) {
    ElMessage.warning('请先为待分配行选择货道');
    return;
  }
  const pending = editablePendingLines.value;
  if (!pending.length) {
    ElMessage.info('没有可保存的明细行');
    return;
  }
  slotSaving.value = true;
  try {
    const saved = await api.request<Row[]>(
      `/api/v2/ops/admin/replenishment/tasks/${linesTask.value.taskId}/lines`,
      'POST',
      {
        lines: pending.map((l) => ({
          lineType: l.lineType || 'RESTOCK',
          skuId: l.skuId,
          batchNo: l.batchNo || null,
          productionDate: l.productionDate || null,
          expiryDate: l.expiryDate || null,
          quantity: Number(l.quantity) || 0,
          slotId: String(l.slotId || '').trim().toUpperCase() || null,
          applied: false
        }))
      }
    );
    taskLines.value = saved || [];
    taskUnassignedHint.value = {
      ...taskUnassignedHint.value,
      [Number(linesTask.value.taskId)]: false
    };
    ElMessage.success('货道已保存');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存货道失败');
  } finally {
    slotSaving.value = false;
  }
}

function revokeEvidencePreviews() {
  for (const url of evidenceObjectUrls.value) {
    URL.revokeObjectURL(url);
  }
  evidenceObjectUrls.value = [];
}

async function loadEvidencePreviews(
  taskId: number,
  files: { fileId: number; fileName?: string; fileSize?: number; contentType?: string; previewUrl?: string }[]
) {
  const token = localStorage.getItem('admin_token');
  const base = window.location.origin;
  const next: typeof files = [];
  const urls: string[] = [];
  for (const f of files) {
    const item = { ...f };
    const looksImage =
      String(f.contentType || '').startsWith('image/') ||
      /\.(png|jpe?g|gif|webp|bmp)$/i.test(String(f.fileName || ''));
    if (looksImage) {
      try {
        const res = await fetch(
          `${base}/api/v2/ops/admin/replenishment/tasks/${taskId}/evidence/${f.fileId}`,
          { headers: token ? { Authorization: `Bearer ${token}` } : {} }
        );
        if (res.ok) {
          const blob = await res.blob();
          const url = URL.createObjectURL(blob);
          urls.push(url);
          item.previewUrl = url;
        }
      } catch {
        /* list-only fallback */
      }
    }
    next.push(item);
  }
  evidenceObjectUrls.value = urls;
  taskEvidence.value = next;
}

function openEvidencePreview(f: { previewUrl?: string; fileName?: string }) {
  if (!f.previewUrl) return;
  window.open(f.previewUrl, '_blank');
}

function canOpenRestock(task: Row) {
  if (!task?.taskId || !task?.deviceId) return false;
  if (['COMPLETED', 'CANCELLED'].includes(String(task.status || ''))) return false;
  return !!task.checkInAt && deviceOnline(task.deviceId);
}

/** 已签到且未完成的任务可「完成上架」（后端亦校验签到）。 */
function canCompleteTask(task: Row) {
  if (!task?.taskId) return false;
  if (['COMPLETED', 'CANCELLED'].includes(String(task.status || ''))) return false;
  return !!task.checkInAt;
}

function openDoorHint(task: Row) {
  if (['COMPLETED', 'CANCELLED'].includes(String(task.status || ''))) return '-';
  if (!task.checkInAt) return '需先签到';
  if (!deviceOnline(task.deviceId)) return '设备离线';
  return '-';
}

function canCancelEmptyRoute(row: Row) {
  if (!row?.routeId) return false;
  if (String(row.status || '') === 'COMPLETED') return false;
  // CANCELLED：仍可幂等收口历史脏出库/在途
  if (String(row.status || '') === 'CANCELLED') return true;
  const tasks: Row[] = row.tasks || [];
  if (!tasks.length) return true;
  return tasks.every((t) => {
    if (['COMPLETED', 'CANCELLED'].includes(String(t.status || ''))) return true;
    return !t.checkInAt;
  });
}

async function cancelEmptyRoute(row: Row) {
  if (!row?.routeId) return;
  const orphanCleanup = String(row.status || '') === 'CANCELLED';
  try {
    await ElMessageBox.confirm(
      orphanCleanup
        ? `确认收口路线 #${row.routeId} 的脏出库/在途？\n已发运未签收将回仓并取消在途。`
        : `确认取消空路线 #${row.routeId}（${row.routeName || ''}）？\n仅未签到且未交接的任务可取消；已发运未签收会回仓。`,
      orphanCleanup ? '收口脏出库' : '取消空路线',
      { type: 'warning', confirmButtonText: orphanCleanup ? '确认收口' : '确认取消' }
    );
  } catch {
    return;
  }
  cancelRouteLoading.value = row.routeId;
  try {
    await api.request(`/api/v2/ops/admin/replenishment/routes/${row.routeId}/cancel-empty`, 'POST');
    ElMessage.success(orphanCleanup ? `路线 #${row.routeId} 脏出库已收口` : `路线 #${row.routeId} 已取消`);
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '取消失败');
  } finally {
    cancelRouteLoading.value = null;
  }
}

async function openRestockDoor(task: Row) {
  if (!task?.checkInAt) {
    ElMessage.warning('请先到店签到后再补货开门');
    return;
  }
  if (!deviceOnline(task.deviceId)) {
    ElMessage.warning(`${deviceName(task.deviceId)} 当前离线，无法下发补货开门`);
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认对 ${deviceName(task.deviceId)}（${task.deviceId}）下发补货开门？\n将绑定任务 #${task.taskId}，不产生消费者账单。\n（与设备详情「远程开门」不同）`,
      '补货开门',
      { type: 'warning', confirmButtonText: '开门' }
    );
  } catch {
    return;
  }
  openDoorLoading.value = task.taskId;
  try {
    const session = await api.request<{ sessionId?: string }>(
      '/api/v2/ops/restock/open-door',
      'POST',
      { deviceId: task.deviceId, taskId: task.taskId }
    );
    ElMessage.success({
      message: session?.sessionId ? `开门已下发（${session.sessionId}）` : '开门指令已下发',
      duration: 4000
    });
    await load();
  } catch (error) {
    ElMessage.error({
      message: error instanceof Error ? error.message : '开门失败',
      duration: 5000
    });
  } finally {
    openDoorLoading.value = null;
  }
}

async function completeRestockTask(task: Row) {
  if (!task?.taskId) return;
  if (!task.checkInAt) {
    ElMessage.warning('请先到店签到后再完成上架');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认完成任务 #${task.taskId}（${deviceName(task.deviceId)}）上架？\n未签到将被后端拒绝；完成后将写入库存。`,
      '完成上架',
      { type: 'warning', confirmButtonText: '确认完成' }
    );
  } catch {
    return;
  }
  completeLoading.value = task.taskId;
  try {
    await api.request(`/api/v2/ops/admin/replenishment/tasks/${task.taskId}/complete`, 'POST');
    ElMessage.success(`任务 #${task.taskId} 已完成上架`);
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '完成上架失败');
  } finally {
    completeLoading.value = null;
  }
}

async function createPlan() {
  if (!planForm.routeName.trim()) return ElMessage.warning('请填写路线名称');
  if (!planForm.deviceIds.length) return ElMessage.warning('请至少选择一台设备');
  saving.value = true;
  try {
    const route = await api.request<Row>('/api/v2/ops/admin/replenishment/plan', 'POST', {
      ...planForm,
      startLatitude: null,
      startLongitude: null
    });
    planDialog.value = false;
    tab.value = 'routes';
    syncRouteQuery();
    await load();
    const outbounds = await api.request<Row[]>('/api/v2/ops/admin/warehouse/outbounds').catch(() => []);
    const linked = (outbounds || []).filter((o) => o.routeId === route?.routeId);
    if (linked.length) {
      ElMessage.success({
        message: `路线已创建，出库单 #${linked[0].outboundId} 待拣货发运（仓库页）`,
        duration: 5000
      });
    } else {
      ElMessage.warning({
        message: '路线已创建，但未生成出库明细（仓库可用库存不足），可现场补录上架',
        duration: 5000
      });
    }
  } catch (error) {
    ElMessage.error({
      message: error instanceof Error ? error.message : '路线创建失败',
      duration: 5000
    });
  } finally {
    saving.value = false;
  }
}

async function onRequestAction(row: Row, key: string) {
  try {
    if (key === 'view-task') {
      await goRequestTask(row);
      return;
    }
    if (key === 'accept') {
      const linesPreview = formatRequestLines(row);
      await ElMessageBox.confirm(
        `确认接单要货 #${row.requestId}？\n设备：${deviceName(row.deviceId)}（${row.deviceId}）\n明细：${linesPreview}`,
        '接单',
        { type: 'warning', confirmButtonText: '确认接单' }
      );
      const accepted = await api.request<{
        requestId?: number;
        outboundId?: number | null;
        replenishmentTaskId?: number | null;
      }>(`/api/v2/ops/admin/replenishment/requests/${row.requestId}/accept`, 'POST');
      if (accepted?.outboundId) {
        ElMessage.success(
          `已接单，出库 #${accepted.outboundId}，补货任务 #${accepted.replenishmentTaskId ?? '-'}`
        );
      } else {
        ElMessage.success(
          `已接单，无仓配库存，已建现场补货任务 #${accepted?.replenishmentTaskId ?? '-'}`
        );
      }
    } else if (key === 'reject') {
      const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回要货', {
        inputValidator: (v) => !!String(v || '').trim() || '必须填写原因',
        confirmButtonText: '确认驳回',
        type: 'warning'
      });
      await api.request(`/api/v2/ops/admin/replenishment/requests/${row.requestId}/reject`, 'POST', {
        reason: value
      });
      ElMessage.success('已驳回');
    }
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '操作失败');
    }
  }
}

function findTaskById(taskId: number | string | undefined | null): Row | null {
  if (taskId == null || taskId === '') return null;
  const id = Number(taskId);
  for (const routeRow of routes.value) {
    for (const task of routeRow.tasks || []) {
      if (Number(task.taskId) === id) {
        return {
          ...task,
          routeId: routeRow.routeId,
          routeName: routeRow.routeName,
          assigneeUserId: task.assigneeUserId || routeRow.assigneeUserId
        };
      }
    }
  }
  return null;
}

async function goRequestTask(row: Row) {
  const taskId = row.replenishmentTaskId;
  if (!taskId) {
    ElMessage.info('该要货尚未关联补货任务');
    return;
  }
  let task = findTaskById(taskId);
  if (!task) {
    await load();
    task = findTaskById(taskId);
  }
  if (!task) {
    ElMessage.warning(`未找到补货任务 #${taskId}，请到履约记录中查找`);
    tab.value = 'fulfillment';
    syncRouteQuery();
    return;
  }
  tab.value = 'fulfillment';
  syncRouteQuery();
  await openTaskLines(task);
}

function formatRequestLines(row: Row) {
  const lines = (row.lines || []) as { skuName?: string; skuId?: string; requestedQty?: number }[];
  if (!lines.length) return '无明细';
  return lines
    .map((l) => `${l.skuName || l.skuId || '-'}×${l.requestedQty ?? 0}`)
    .join('、');
}

async function load() {
  loading.value = true;
  try {
    const [r, req, devicePage, disc] = await Promise.all([
      api.request<Row[]>('/api/v2/ops/admin/replenishment/routes', 'GET'),
      api.request<Row[]>('/api/v2/ops/admin/replenishment/requests?status=ALL', 'GET'),
      api.request<PageResult<Row>>('/api/v2/ops/admin/devices?page=0&size=200', 'GET'),
      api.request<Row[]>('/api/v2/ops/admin/slots/discrepancies', 'GET').catch(() => [])
    ]);
    routes.value = r || [];
    allRequests.value = req || [];
    devices.value = devicePage?.items || [];
    clearRoutesSelection();
    clearRequestsSelection();
    const lowStock: Row[] = [];
    for (const d of devices.value) {
      try {
        const suggest = await api.request<Row[]>(
          `/api/v2/ops/admin/replenishment/suggest/slots?deviceId=${encodeURIComponent(d.deviceId)}`,
          'GET'
        );
        for (const s of suggest || []) {
          if ((s.bookQty ?? 0) <= (s.minLevel ?? 0) || s.stockStatus === 'OOS' || s.stockStatus === 'LOW') {
            lowStock.push({ ...s, deviceId: d.deviceId });
          }
        }
      } catch {
        /* ignore per-device */
      }
    }
    const byKey = new Map<string, Row>();
    for (const row of [...(disc || []), ...lowStock]) {
      const key = `${row.deviceId}:${row.slotCode || row.skuId || ''}`;
      if (!byKey.has(key)) byKey.set(key, { ...row, slotKey: key });
    }
    let list = [...byKey.values()];
    if (focusDeviceId.value.trim()) {
      list = list.filter((x) => x.deviceId === focusDeviceId.value.trim());
    }
    shortages.value = list;
    clearShortageSelection();
    await loadExpiryAlerts();
    void prefetchUnassignedHints();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '补货数据加载失败');
  } finally {
    loading.value = false;
  }
}

/** 履约开放任务：预拉明细，标出待分配货道红点（最多 24 个，避免打爆接口） */
async function prefetchUnassignedHints() {
  const open = fulfillmentTasksBase.value
    .filter((t) => {
      const st = String(t.status || '');
      return t.taskId && st !== 'COMPLETED' && st !== 'CANCELLED';
    })
    .slice(0, 24);
  if (!open.length) return;
  const next: Record<number, boolean> = { ...taskUnassignedHint.value };
  const chunkSize = 6;
  for (let i = 0; i < open.length; i += chunkSize) {
    const chunk = open.slice(i, i + chunkSize);
    await Promise.all(
      chunk.map(async (task) => {
        const taskId = Number(task.taskId);
        try {
          const lines = await api.request<Row[]>(
            `/api/v2/ops/admin/replenishment/tasks/${taskId}/lines`,
            'GET'
          );
          next[taskId] = (lines || []).some(
            (l) =>
              !l.applied &&
              String(l.lineType || 'RESTOCK').toUpperCase() === 'RESTOCK' &&
              !String(l.slotId || '').trim()
          );
        } catch {
          /* keep previous hint */
        }
      })
    );
  }
  taskUnassignedHint.value = next;
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await load();
}

watch(
  () => [route.query.tab, route.query.deviceId] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  syncRouteQuery();
  load();
});
onActivated(() => {
  void reloadFromRouteQuery();
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
.page-card-head__meta { min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.kpi-tags { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.page-card-head__actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.route-detail { padding: 8px 44px 12px; }
.route-meta { display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 12px; color: var(--layout-muted); font-size: 13px; }
.line-table { width: 100%; }
.master-data-cell { display: grid; gap: 2px; line-height: 1.35; }
.master-data-cell strong { color: var(--layout-text); font-weight: 650; }
.master-data-cell small { color: var(--layout-muted); font-size: 11px; font-family: var(--app-font-mono); }
.device-link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  display: grid;
  gap: 2px;
  text-align: left;
  cursor: pointer;
  color: inherit;
  font: inherit;
  line-height: 1.35;
}
.device-link-cell strong { color: var(--el-color-primary); font-weight: 650; }
.device-link-cell small { color: var(--layout-muted); font-size: 11px; font-family: var(--app-font-mono); }
.device-link-cell:hover strong { text-decoration: underline; }
.muted { color: var(--layout-muted); font-size: 13px; }
.check-in-cell { display: grid; gap: 4px; justify-items: center; line-height: 1.3; }
.gps-text { color: var(--layout-muted); font-size: 11px; font-family: var(--app-font-mono); }
.gps-inline { color: var(--layout-muted); font-family: var(--app-font-mono); font-size: 12px; }
.gps-missing { color: var(--el-color-warning); font-weight: 600; font-family: inherit; }
.mono { font-family: var(--app-font-mono); font-size: 12px; }
.cell-datetime { font-variant-numeric: tabular-nums; }
.lines-drawer { display: grid; gap: 12px; }
.lines-meta { margin-bottom: 0; }
.lines-photo-hint { margin: 0; }
.evidence-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
  margin: 0 0 12px;
}
.evidence-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  font-size: 13px;
  background: var(--el-fill-color-blank);
}
.evidence-thumb {
  display: block;
  padding: 0;
  border: 0;
  border-radius: 6px;
  overflow: hidden;
  background: var(--el-fill-color-light);
  cursor: zoom-in;
}
.evidence-thumb img {
  display: block;
  width: 100%;
  height: 96px;
  object-fit: cover;
}
.evidence-meta { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.evidence-item .meta { color: var(--el-text-color-secondary); font-size: 12px; }
.lines-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--layout-muted);
  font-size: 13px;
}
.reject-reason { color: var(--el-color-danger); font-size: 13px; }
.unassigned-badge { margin-left: 2px; }
.lines-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
}
.lines-action-hint { font-size: 12px; color: var(--el-color-warning); }
.lines-dot { margin-left: 4px; vertical-align: super; }
.status-stack {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.online-tag { margin-left: 6px; vertical-align: middle; }
.shortage-toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.expiry-hint { flex: 1; min-width: 240px; margin: 0; }
.expiry-restock-wrap { display: inline-flex; vertical-align: middle; }
.plan-hint { margin-top: 6px; font-size: 12px; color: var(--el-color-warning); line-height: 1.4; }
.plan-form { margin-top: 4px; }
.plan-device-list {
  width: 100%;
  max-height: 220px;
  overflow: auto;
  border: 1px solid var(--layout-border);
  border-radius: 6px;
  padding: 4px 0;
  background: var(--layout-card);
}
.plan-device-group {
  display: flex;
  flex-direction: column;
  width: 100%;
}
.plan-device-option {
  display: flex;
  align-items: center;
  min-height: 40px;
  padding: 6px 12px;
  cursor: pointer;
  box-sizing: border-box;
}
.plan-device-option:hover {
  background: var(--el-fill-color-light);
}
.plan-device-option :deep(.el-checkbox) {
  width: 100%;
  height: auto;
  margin-right: 0;
}
.plan-device-option :deep(.el-checkbox__label) {
  white-space: normal;
  line-height: 1.35;
}
.plan-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  position: relative;
  z-index: 2;
}
.plan-create-btn {
  position: relative;
  z-index: 3;
  min-width: 96px;
  min-height: 36px;
  pointer-events: auto;
}
.native-date {
  width: 100%; height: 32px; padding: 0 10px; border: 1px solid var(--layout-border);
  border-radius: 4px; color: var(--layout-text); background: var(--layout-card); box-sizing: border-box;
}
@media (max-width: 760px) {
  .route-detail { padding: 8px 12px; }
}
</style>
