<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">补货调度</span>
            <span class="hint"
              >路线 / 履约取证 / 要货 / 缺货；签到 GPS、用时与理货明细可核对现场履约</span
            >
          </div>
          <div class="kpi-tags">
            <button
              type="button"
              class="kpi-tag-btn"
              :aria-label="listHydrated ? `待执行 ${plannedCount}` : `待执行 ${UI_COPY.loading}`"
            >
              <el-tag size="small" type="info"
                >待执行 {{ listHydrated ? plannedCount : '…' }}</el-tag
              >
            </button>
            <button
              type="button"
              class="kpi-tag-btn"
              :aria-label="
                listHydrated ? `待处理设备 ${pendingTaskCount}` : `待处理设备 ${UI_COPY.loading}`
              "
            >
              <el-tag size="small" type="warning"
                >待处理设备 {{ listHydrated ? pendingTaskCount : '…' }}</el-tag
              >
            </button>
            <button
              type="button"
              class="kpi-tag-btn"
              :aria-label="listHydrated ? `已履约 ${fulfilledCount}` : `已履约 ${UI_COPY.loading}`"
            >
              <el-tag size="small" type="success"
                >已履约 {{ listHydrated ? fulfilledCount : '…' }}</el-tag
              >
            </button>
            <button
              type="button"
              class="kpi-tag-btn"
              :aria-label="
                listHydrated ? `要货待审 ${pendingRequestCount}` : `要货待审 ${UI_COPY.loading}`
              "
            >
              <el-tag size="small">要货待审 {{ listHydrated ? pendingRequestCount : '…' }}</el-tag>
            </button>
            <button
              type="button"
              class="kpi-tag-btn"
              :aria-label="
                listHydrated && !crudExpiry.loading
                  ? `临期 ${expiryAlerts.length}`
                  : `临期 ${UI_COPY.loading}`
              "
            >
              <el-tag size="small" type="danger"
                >临期
                {{ listHydrated && !crudExpiry.loading ? expiryAlerts.length : '暂无' }}</el-tag
              >
            </button>
            <el-tag
              v-if="focusDeviceId"
              size="small"
              type="success"
              closable
              @close="clearDeviceFocus"
            >
              设备 {{ focusDeviceId }}
            </el-tag>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-if="canEdit" type="primary" @click="openPlan">规划补货路线</el-button>
          <el-button v-hasPermi="['ops:replenishment:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="补货路线" name="routes">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <CrudTable
              :table="crudRoutes"
              row-key="routeId"
              selectable
              :empty-text="routesEmptyText"
              sort-field-label="路线ID"
            >
              <el-table-column
                type="expand"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <div class="route-detail">
                    <div class="route-meta">
                      <span>计划日期：{{ row.plannedDate || '无' }}</span>
                      <span>负责人：{{ assigneeLabel(row.assigneeUserId) }}</span>
                      <span
                        >预计里程：{{
                          row.totalDistanceM ? `${row.totalDistanceM} 米` : '未计算'
                        }}</span
                      >
                    </div>
                    <div class="route-task-scroll table-scroll table-scroll--h">
                      <div class="table-scroll-inner route-task-scroll-inner">
                        <el-table
                          :data="sortedRouteTasks(row.tasks)"
                          size="small"
                          class="line-table"
                          empty-text=" "
                        >
                          <el-table-column label="任务" width="70" class-name="col-text">
                            <template #default="scope">
                              <span class="cell-id">{{ scope.row.taskId }}</span>
                            </template>
                          </el-table-column>
                          <el-table-column
                            label="设备"
                            min-width="110"
                            class-name="col-text"
                            label-class-name="col-text"
                          >
                            <template #default="scope">
                              {{ deviceName(scope.row.deviceId, scope.row.deviceName) }}
                              <el-tag
                                size="small"
                                :type="deviceOnline(scope.row.deviceId) ? 'success' : 'info'"
                                class="online-tag"
                                >{{
                                  displayLabel(
                                    'online_status',
                                    deviceOnline(scope.row.deviceId) ? 'ONLINE' : 'OFFLINE'
                                  )
                                }}</el-tag
                              >
                            </template>
                          </el-table-column>
                          <el-table-column
                            label="设备ID"
                            min-width="100"
                            align="center"
                            class-name="col-status"
                            label-class-name="col-status"
                          >
                            <template #default="scope">
                              <span class="mono">{{ scope.row.deviceId }}</span>
                            </template>
                          </el-table-column>
                          <el-table-column
                            label="任务状态"
                            width="92"
                            align="center"
                            class-name="col-status"
                            label-class-name="col-status"
                          >
                            <template #default="scope">
                              <el-tag :type="dictTagType(scope.row.status)" size="small">
                                {{ dictLabel('replenishment_task_status', scope.row.status) }}
                              </el-tag>
                            </template>
                          </el-table-column>
                          <el-table-column
                            label="人员"
                            min-width="120"
                            align="center"
                            class-name="col-status"
                            label-class-name="col-status"
                          >
                            <template #default="scope">
                              <span>{{
                                assigneeLabel(scope.row.assigneeUserId || row.assigneeUserId, '无')
                              }}</span>
                            </template>
                          </el-table-column>
                          <el-table-column
                            label="签到"
                            min-width="124"
                            align="center"
                            class-name="col-status"
                            label-class-name="col-status"
                          >
                            <template #default="scope">
                              <div class="check-in-cell">
                                <el-tag
                                  :type="scope.row.checkInAt ? 'success' : 'info'"
                                  size="small"
                                >
                                  {{ scope.row.checkInAt ? '已签到' : '未签到' }}
                                </el-tag>
                                <el-tag
                                  v-if="scope.row.checkInAt && !checkInHasGps(scope.row)"
                                  size="small"
                                  type="warning"
                                  effect="plain"
                                  >无定位</el-tag
                                >
                                <small v-else-if="formatCheckInGps(scope.row)" class="gps-text">{{
                                  formatCheckInGps(scope.row)
                                }}</small>
                                <small v-if="formatCheckInDistance(scope.row)" class="gps-text">{{
                                  formatCheckInDistance(scope.row)
                                }}</small>
                              </div>
                            </template>
                          </el-table-column>
                          <el-table-column
                            label="用时"
                            width="70"
                            align="center"
                            class-name="col-status"
                            label-class-name="col-status"
                          >
                            <template #default="scope">{{
                              formatTaskDuration(scope.row)
                            }}</template>
                          </el-table-column>
                          <el-table-column label="完成" width="122" class-name="col-text">
                            <template #default="scope">
                              <span class="cell-datetime">{{
                                scope.row.completedAt ? formatDateTime(scope.row.completedAt) : '无'
                              }}</span>
                            </template>
                          </el-table-column>
                          <el-table-column
                            label="出库单"
                            width="70"
                            class-name="col-text"
                            label-class-name="col-text"
                          >
                            <template #default="scope">
                              <el-tag v-if="scope.row.outboundId" size="small" type="warning">{{
                                scope.row.outboundId
                              }}</el-tag>
                              <span v-else class="muted">现场</span>
                            </template>
                          </el-table-column>
                          <el-table-column
                            label="操作"
                            :width="canEdit ? 210 : 70"
                            align="center"
                            class-name="col-action"
                            fixed="right"
                          >
                            <template #default="scope">
                              <el-button link type="primary" @click.stop="openTaskLines(scope.row)"
                                >明细</el-button
                              >
                              <el-button
                                v-if="canEdit && canCheckInTask(scope.row)"
                                link
                                type="warning"
                                :loading="checkInLoading === scope.row.taskId"
                                @click.stop="checkInRestockTask(scope.row)"
                                >签到</el-button
                              >
                              <el-button
                                v-if="canEdit && canOpenRestock(scope.row)"
                                link
                                type="primary"
                                :loading="openDoorLoading === scope.row.taskId"
                                @click.stop="openRestockDoor(scope.row)"
                                >{{
                                  deviceSalesLocked(scope.row.deviceId) ? '开门(停售)' : '开门'
                                }}</el-button
                              >
                              <el-button
                                v-if="canEdit && canCompleteTask(scope.row)"
                                link
                                type="success"
                                :loading="completeLoading === scope.row.taskId"
                                @click.stop="completeRestockTask(scope.row)"
                                >完成上架</el-button
                              >
                              <span
                                v-else-if="
                                  canEdit &&
                                  openDoorHint(scope.row) !== '无' &&
                                  !canCompleteTask(scope.row) &&
                                  !canCheckInTask(scope.row)
                                "
                                class="muted"
                                >{{ openDoorHint(scope.row) }}</span
                              >
                            </template>
                          </el-table-column>
                          <template #empty
                            ><el-empty
                              v-if="listHydrated && !crudRoutes.loading"
                              description="该路线暂无设备任务"
                              :image-size="48"
                          /></template>
                        </el-table>
                      </div>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="路线" min-width="140" class-name="col-text">
                <template #default="{ row }">{{ row.routeName || '无' }}</template>
              </el-table-column>
              <el-table-column prop="routeId" label="路线ID" min-width="120" class-name="col-text">
                <template #default="{ row }">
                  <span class="mono">{{ row.routeId }}</span>
                </template>
              </el-table-column>
              <el-table-column
                label="设备数"
                width="88"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">{{ row.tasks?.length || 0 }}</template>
              </el-table-column>
              <el-table-column
                align="center"
                prop="plannedDate"
                label="计划日期"
                width="120"
                class-name="col-status"
                label-class-name="col-status"
              />
              <el-table-column
                label="状态"
                width="110"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <el-tag :type="dictTagType(row.status)" size="small">
                    {{ dictLabel('replenishment_route_status', row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column
                v-if="showRouteCancelColumn"
                label="操作"
                width="100"
                align="center"
                class-name="col-action"
                fixed="right"
              >
                <template #default="{ row }">
                  <el-button
                    v-if="canCancelEmptyRoute(row)"
                    link
                    type="danger"
                    :loading="cancelRouteLoading === row.routeId"
                    data-testid="cancel-empty-route"
                    @click="cancelEmptyRoute(row)"
                    >{{ row.status === 'CANCELLED' ? '收口脏出库' : '取消空路线' }}</el-button
                  >
                </template>
              </el-table-column>
            </CrudTable>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="履约记录" name="fulfillment">
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="page = 1">
          <el-form-item label="状态">
            <el-select
              v-model="fulfillmentStatus"
              clearable
              placeholder="全部"
              style="width: 140px"
              @change="page = 1"
            >
              <el-option
                v-for="item in dictOptions('replenishment_task_status')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-checkbox v-model="fulfillmentUnassignedOnly" @change="page = 1">
              仅待分配货道{{ unassignedHintCount ? ` (${unassignedHintCount})` : '' }}
            </el-checkbox>
          </el-form-item>
          <el-form-item label="关键词">
            <el-input
              v-model="fulfillmentKeyword"
              clearable
              placeholder="设备 / 任务"
              style="width: 160px"
            />
          </el-form-item>
        </el-form>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              ref="fulfillmentTableRef"
              v-loading="isTabLoading('fulfillment')"
              :data="pagedFulfillment"
              stripe
              border
              class="report-table"
              empty-text=" "
              row-key="taskId"
              :default-sort="taskIdDefaultSort"
              @sort-change="onTaskIdSortChange"
              @selection-change="onFulfillmentSelectionChange"
            >
              <template #empty
                ><el-empty
                  v-if="listHydrated && !isTabLoading('fulfillment')"
                  :description="fulfillmentEmptyText"
              /></template>
              <el-table-column
                type="selection"
                width="48"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              />
              <el-table-column
                prop="taskId"
                label="任务"
                width="88"
                class-name="col-text"
                sortable="custom"
              >
                <template #default="{ row }">
                  <span class="cell-id">{{ row.taskId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="设备" min-width="120" class-name="col-text">
                <template #default="{ row }">
                  <button type="button" class="link-cell" @click="goDevice(row.deviceId)">
                    {{ deviceName(row.deviceId, row.deviceName) }}
                  </button>
                </template>
              </el-table-column>
              <el-table-column label="设备ID" min-width="120" class-name="col-text">
                <template #default="{ row }">
                  <span class="cell-id">{{ row.deviceId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="路线" min-width="140" class-name="col-text">
                <template #default="{ row }">{{ row.routeName || row.routeId || '无' }}</template>
              </el-table-column>
              <el-table-column
                label="状态"
                width="148"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
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
              <el-table-column
                label="人员"
                min-width="120"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <span>{{ assigneeLabel(row.assigneeUserId, '无') }}</span>
                </template>
              </el-table-column>
              <el-table-column
                label="签到 / GPS"
                min-width="160"
                class-name="col-text"
                label-class-name="col-text"
              >
                <template #default="{ row }">
                  <div class="check-in-cell">
                    <span class="cell-datetime">{{
                      row.checkInAt ? formatDateTime(row.checkInAt) : '未签到'
                    }}</span>
                    <small
                      v-if="formatCheckInGps(row)"
                      class="gps-text"
                      :class="{ 'gps-missing': row.checkInAt && !checkInHasGps(row) }"
                      >{{ formatCheckInGps(row) }}</small
                    >
                    <small v-if="formatCheckInDistance(row)" class="gps-text">{{
                      formatCheckInDistance(row)
                    }}</small>
                  </div>
                </template>
              </el-table-column>
              <el-table-column
                label="用时"
                width="88"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">{{ formatTaskDuration(row) }}</template>
              </el-table-column>
              <el-table-column
                align="center"
                label="完成时间"
                width="168"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <span class="cell-datetime">{{
                    row.completedAt ? formatDateTime(row.completedAt) : '无'
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column
                label="要货单"
                width="90"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <span v-if="row.requestId" class="cell-id">{{ row.requestId }}</span>
                  <span v-else class="muted">暂无</span>
                </template>
              </el-table-column>
              <el-table-column
                label="出库单"
                width="90"
                class-name="col-text"
                label-class-name="col-text"
              >
                <template #default="{ row }">
                  <span v-if="row.outboundId" class="cell-id">{{ row.outboundId }}</span>
                  <span v-else class="muted">暂无</span>
                </template>
              </el-table-column>
              <el-table-column label="备注" min-width="140" class-name="col-text">
                <template #default="{ row }">
                  <span
                    class="cell-ellipsis"
                    :title="
                      formatTaskNotesBrief(row.notes) === '暂无'
                        ? ''
                        : formatTaskNotesBrief(row.notes)
                    "
                    >{{ formatTaskNotesBrief(row.notes) }}</span
                  >
                </template>
              </el-table-column>
              <el-table-column
                label="操作"
                width="120"
                align="center"
                class-name="col-action"
                fixed="right"
              >
                <template #default="{ row }">
                  <el-button link type="primary" @click="openTaskLines(row)">
                    理货明细
                    <el-badge v-if="taskUnassignedHint[row.taskId]" is-dot class="lines-dot" />
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="商户要货" name="requests">
        <div class="shortage-toolbar">
          <el-radio-group v-model="requestStatusFilter" size="small" @change="page = 1">
            <el-radio-button
              v-for="item in dictOptions('replenishment_request_status')"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </el-radio-button>
            <el-radio-button value="ALL">全部</el-radio-button>
          </el-radio-group>
        </div>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <CrudTable
              :table="crudRequests"
              row-key="requestId"
              selectable
              :actions="showRequestActionColumn ? requestActionsFor : undefined"
              :action-width="200"
              :empty-text="requestsEmptyText"
              sort-field-label="要货单"
              @action="onRequestRowAction"
            >
              <el-table-column
                prop="requestId"
                label="要货单"
                min-width="120"
                class-name="col-text"
              >
                <template #default="{ row }"
                  ><span class="cell-id">{{ row.requestId }}</span></template
                >
              </el-table-column>
              <el-table-column
                prop="merchantName"
                label="商户"
                min-width="160"
                class-name="col-text"
              />
              <el-table-column label="目标设备" min-width="120" class-name="col-text">
                <template #default="{ row }">
                  <button type="button" class="link-cell" @click="goDevice(row.deviceId)">
                    {{ deviceName(row.deviceId, row.deviceName) }}
                  </button>
                </template>
              </el-table-column>
              <el-table-column label="设备ID" min-width="120" class-name="col-text">
                <template #default="{ row }">
                  <span class="cell-id">{{ row.deviceId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="明细" min-width="220" class-name="col-text">
                <template #default="{ row }">
                  <span>{{ formatRequestLines(row) }}</span>
                </template>
              </el-table-column>
              <el-table-column
                label="状态"
                width="110"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <el-tag :type="dictTagType(row.status)" size="small">
                    {{ dictLabel('replenishment_request_status', row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="审核人" min-width="120" class-name="col-text">
                <template #default="{ row }">
                  <span v-if="row.reviewerName || row.reviewerId">{{
                    row.reviewerName || row.reviewerId
                  }}</span>
                  <span v-else class="muted">待审核</span>
                </template>
              </el-table-column>
              <el-table-column
                align="center"
                label="审核时间"
                width="168"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <span v-if="row.reviewedAt" class="cell-datetime">{{
                    formatDateTime(row.reviewedAt)
                  }}</span>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="驳回原因" min-width="160" class-name="col-text">
                <template #default="{ row }">
                  <span v-if="row.rejectReason" class="reject-reason">{{ row.rejectReason }}</span>
                  <span v-else class="muted">无</span>
                </template>
              </el-table-column>
              <el-table-column label="补货任务" width="110" class-name="col-text">
                <template #default="{ row }">
                  <el-button
                    v-if="row.replenishmentTaskId"
                    link
                    type="primary"
                    @click="onRequestAction(row, 'view-task')"
                    >{{ row.replenishmentTaskId }}</el-button
                  >
                  <span v-else class="muted">无</span>
                </template>
              </el-table-column>
              <el-table-column
                align="center"
                label="提交时间"
                width="168"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <span class="cell-datetime">{{
                    formatDateTime(row.submittedAt || row.createdAt)
                  }}</span>
                </template>
              </el-table-column>
            </CrudTable>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="缺货建议" name="shortage">
        <div class="shortage-toolbar">
          <el-button
            v-if="canEdit && shortageDevices.length"
            type="primary"
            @click="planFromShortage"
          >
            一键规划补货（{{ shortageDevices.length }} 台）
          </el-button>
        </div>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <CrudTable
              :table="crudShortages"
              row-key="slotKey"
              selectable
              empty-text="当前无缺货/低库存货道"
              :actions="canEdit ? shortageRowActions : undefined"
              :action-width="100"
              @action="onShortageAction"
            >
              <el-table-column label="设备" min-width="120" class-name="col-text">
                <template #default="{ row }">
                  <button type="button" class="link-cell" @click="goDevice(row.deviceId)">
                    {{ deviceName(row.deviceId, row.deviceName) }}
                  </button>
                </template>
              </el-table-column>
              <el-table-column label="设备ID" min-width="120" class-name="col-text">
                <template #default="{ row }">
                  <span class="cell-id">{{ row.deviceId }}</span>
                </template>
              </el-table-column>
              <el-table-column
                prop="slotCode"
                label="货道"
                width="90"
                class-name="col-text"
                label-class-name="col-text"
              />
              <el-table-column
                prop="assignedSkuName"
                label="商品"
                min-width="140"
                class-name="col-text"
              />
              <el-table-column
                prop="bookQty"
                label="账面"
                width="80"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              />
              <el-table-column
                prop="minLevel"
                label="最低"
                width="80"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              />
              <el-table-column
                prop="parLevel"
                label="目标"
                width="80"
                class-name="col-text"
                label-class-name="col-text"
              />
              <el-table-column
                label="状态"
                width="100"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <el-tag :type="stockTagType(row)" size="small">{{ stockLabel(row) }}</el-tag>
                </template>
              </el-table-column>
            </CrudTable>
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
        </div>
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <CrudTable
              :table="crudExpiry"
              row-key="taskId"
              selectable
              empty-text="当前无临期下架任务"
            >
              <el-table-column label="设备" min-width="120" class-name="col-text">
                <template #default="{ row }">
                  <button type="button" class="link-cell" @click="goDevice(row.deviceId)">
                    {{ deviceName(row.deviceId, row.deviceName) }}
                  </button>
                </template>
              </el-table-column>
              <el-table-column label="设备ID" min-width="120" class-name="col-text">
                <template #default="{ row }">
                  <span class="cell-id">{{ row.deviceId }}</span>
                </template>
              </el-table-column>
              <el-table-column
                prop="skuId"
                label="商品 SKU"
                min-width="140"
                class-name="col-text"
              />
              <el-table-column prop="batchNo" label="批次" min-width="120" class-name="col-text" />
              <el-table-column prop="lotId" label="批次 ID" min-width="120" class-name="col-text" />
              <el-table-column
                prop="quantity"
                label="数量"
                width="80"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              />
              <el-table-column label="原因" min-width="160" class-name="col-text">
                <template #default="{ row }">{{
                  displayLabel('pull_off_reason', row.reason, '临期')
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
                  <el-tag size="small" type="warning">{{
                    displayLabel('exception_status', row.status, '待处理')
                  }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column
                align="center"
                label="创建时间"
                width="168"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column
                label="操作"
                width="240"
                align="center"
                class-name="col-action"
                fixed="right"
              >
                <template #default="{ row }">
                  <el-button
                    v-if="canEdit"
                    link
                    type="danger"
                    :loading="expiryActingId === row.taskId"
                    @click="createFromExpiry(row, 'PULL_OFF')"
                    >下架任务</el-button
                  >
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
                        >补货任务</el-button
                      >
                    </span>
                  </el-tooltip>
                  <el-button link type="primary" @click="goWarehouse(row.deviceId)">仓库</el-button>
                </template>
              </el-table-column>
            </CrudTable>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
    <!-- 共享分页器仅服务未迁壳的履约记录 tab；其余 tab 由 CrudTable 内建分页 -->
    <PagePager
      v-if="tab === 'fulfillment'"
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="tabTotal"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      background
      @current-change="onPagerChange"
      @size-change="onPagerSizeChange"
    />

    <ResizableDrawer
      v-model="linesDrawer"
      :title="linesDrawerTitle"
      storage-key="admin.drawer.replenishment.lines"
      :default-width="720"
      :min-width="480"
      :max-width="1200"
      destroy-on-close
      append-to-body
    >
      <div v-loading="linesLoading" class="lines-drawer">
        <el-descriptions v-if="linesTask" :column="1" border size="small" class="lines-meta">
          <el-descriptions-item label="任务">
            <span class="mono">{{ linesTask.taskId }}</span>
            <el-tag :type="dictTagType(linesTask.status)" size="small" class="lines-status-tag">{{
              dictLabel('replenishment_task_status', linesTask.status)
            }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="设备"
            >{{ deviceName(linesTask.deviceId, linesTask.deviceName) }}（{{
              linesTask.deviceId
            }}）</el-descriptions-item
          >
          <el-descriptions-item label="人员">{{
            assigneeLabel(linesTask.assigneeUserId, '无')
          }}</el-descriptions-item>
          <el-descriptions-item label="签到">
            {{ linesTask.checkInAt ? formatDateTime(linesTask.checkInAt) : '未签到' }}
            <span
              v-if="formatCheckInGps(linesTask)"
              class="gps-inline"
              :class="{ 'gps-missing': !checkInHasGps(linesTask) }"
            >
              · {{ formatCheckInGps(linesTask) }}</span
            >
            <span v-if="formatCheckInDistance(linesTask)" class="gps-inline">
              · {{ formatCheckInDistance(linesTask) }}</span
            >
          </el-descriptions-item>
          <el-descriptions-item label="用时">{{
            formatTaskDuration(linesTask)
          }}</el-descriptions-item>
          <el-descriptions-item label="完成">
            {{ linesTask.completedAt ? formatDateTime(linesTask.completedAt) : '无' }}
          </el-descriptions-item>
          <el-descriptions-item
            v-if="routePlanMeta(linesTask.notes).sequence != null"
            label="路线顺序"
          >
            第 {{ routePlanMeta(linesTask.notes).sequence }} 站
          </el-descriptions-item>
          <el-descriptions-item
            v-if="routePlanMeta(linesTask.notes).distanceM != null"
            label="路段距离"
          >
            {{ formatRouteLegDistance(routePlanMeta(linesTask.notes).distanceM!) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="formatTaskNotes(linesTask.notes)" label="说明">{{
            formatTaskNotes(linesTask.notes)
          }}</el-descriptions-item>
        </el-descriptions>
        <el-alert
          v-if="!linesLoading"
          class="lines-photo-hint"
          type="info"
          :closable="false"
          show-icon
          title="现场照片"
          :description="
            taskEvidence.length
              ? `已采集 ${taskEvidence.length} 张（商户端上传）`
              : '尚未采集现场照片；可凭签到 GPS、用时与上架明细核对履约。'
          "
        />
        <div v-if="taskEvidence.length" class="evidence-grid">
          <div v-for="f in taskEvidence" :key="f.fileId" class="evidence-item">
            <button
              v-if="f.previewUrl"
              type="button"
              class="evidence-thumb"
              @click="openEvidencePreview(f)"
            >
              <img :src="f.previewUrl" :alt="f.fileName || `文件 ${f.fileId}`" />
            </button>
            <div class="evidence-meta">
              <span class="mono">{{ f.fileName || `文件 ${f.fileId}` }}</span>
              <span class="meta">{{ formatFileSize(f.fileSize) }}</span>
            </div>
          </div>
        </div>
        <div class="table-scroll">
          <el-table :data="taskLines" stripe border size="small" empty-text=" ">
            <template #empty>
              <el-empty
                v-if="!linesLoading"
                description="暂无理货明细（未上架或未确认）"
                :image-size="48"
              />
            </template>
            <el-table-column
              label="类型"
              width="72"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">{{ lineTypeLabel(row.lineType) }}</template>
            </el-table-column>
            <el-table-column
              label="商品"
              min-width="120"
              class-name="col-text"
              label-class-name="col-text"
            >
              <template #default="{ row }">
                <div>{{ row.skuName || row.skuId || '无' }}</div>
                <small v-if="row.skuName && row.skuId" class="muted mono">{{ row.skuId }}</small>
              </template>
            </el-table-column>
            <el-table-column
              prop="quantity"
              label="数量"
              width="64"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            />
            <el-table-column
              label="货道"
              min-width="120"
              class-name="col-text"
              label-class-name="col-text"
            >
              <template #default="{ row }">
                <el-select
                  v-if="canAssignSlot(row)"
                  v-model="row.slotId"
                  clearable
                  filterable
                  size="small"
                  placeholder="待分配"
                  style="width: 110px"
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
                  <el-tag v-if="!row.slotId && isRestockLine(row)" type="warning" size="small"
                    >待分配</el-tag
                  >
                  <span v-else>{{ row.slotId || '无' }}</span>
                </template>
              </template>
            </el-table-column>
            <el-table-column
              label="批次"
              min-width="90"
              class-name="col-text"
              label-class-name="col-text"
            >
              <template #default="{ row }">{{ row.batchNo || '无' }}</template>
            </el-table-column>
            <el-table-column
              label="效期"
              width="100"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">{{ row.expiryDate || '无' }}</template>
            </el-table-column>
            <el-table-column
              label="已入账"
              width="72"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
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
          <el-tag
            v-if="unassignedRestockCount"
            type="warning"
            size="small"
            class="unassigned-badge"
          >
            待分配 {{ unassignedRestockCount }}
          </el-tag>
        </div>
        <div
          v-if="
            canEdit &&
            linesTask &&
            String(linesTask.status) !== 'COMPLETED' &&
            editablePendingLines.length
          "
          class="lines-actions"
        >
          <el-button
            type="primary"
            :loading="slotSaving"
            :disabled="!!unassignedRestockCount"
            @click="saveTaskSlots"
            >保存货道分配</el-button
          >
          <span v-if="unassignedRestockCount" class="lines-action-hint"
            >请先为待分配行选择货道</span
          >
        </div>
      </div>
    </ResizableDrawer>

    <ResizableDrawer
      v-model="requestFlowDrawer"
      :title="requestFlowTitle"
      storage-key="admin.drawer.replenishment.requestFlow"
      :default-width="520"
      :min-width="420"
      :max-width="900"
      destroy-on-close
      append-to-body
    >
      <div v-if="requestFlowRow" class="request-flow">
        <el-steps
          :active="requestFlowActiveStep"
          :process-status="requestFlowProcessStatus"
          finish-status="success"
          align-center
        >
          <el-step title="商户提交" :description="requestFlowSubmitDesc" />
          <el-step title="运营审核" :description="requestFlowReviewDesc" />
          <el-step title="履约补货" :description="requestFlowFulfillDesc" />
        </el-steps>
        <el-descriptions :column="1" border size="small" class="request-flow-meta">
          <el-descriptions-item label="要货单">{{ requestFlowRow.requestId }}</el-descriptions-item>
          <el-descriptions-item label="商户">{{
            requestFlowRow.merchantName || requestFlowRow.merchantId || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="设备"
            >{{ deviceName(requestFlowRow.deviceId, requestFlowRow.deviceName) }}（{{
              requestFlowRow.deviceId
            }}）</el-descriptions-item
          >
          <el-descriptions-item label="明细">{{
            formatRequestLines(requestFlowRow)
          }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="dictTagType(requestFlowRow.status)" size="small">
              {{ dictLabel('replenishment_request_status', requestFlowRow.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="requestFlowRow.rejectReason" label="驳回原因">
            <span class="reject-reason">{{ requestFlowRow.rejectReason }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="requestEvidence.length" class="request-evidence">
          <div class="lines-photo-hint">要货附图 · {{ requestEvidence.length }} 张</div>
          <div class="evidence-grid">
            <div v-for="f in requestEvidence" :key="f.fileId" class="evidence-item">
              <a
                :href="f.previewUrl"
                target="_blank"
                rel="noopener noreferrer"
                class="evidence-thumb"
              >
                <img :src="f.previewUrl" :alt="f.fileName || '要货附图'" loading="lazy" />
              </a>
            </div>
          </div>
        </div>
        <div v-if="canEdit && requestFlowRow.status === 'SUBMITTED'" class="request-flow-actions">
          <el-button type="primary" @click="onRequestAction(requestFlowRow, 'accept')"
            >接单</el-button
          >
          <el-button type="danger" plain @click="onRequestAction(requestFlowRow, 'reject')"
            >驳回</el-button
          >
        </div>
        <div v-else-if="requestFlowRow.replenishmentTaskId" class="request-flow-actions">
          <el-button type="primary" @click="onRequestAction(requestFlowRow, 'view-task')"
            >查看补货任务</el-button
          >
        </div>
      </div>
    </ResizableDrawer>

    <ReplenishmentPlanRouteDialog
      v-model="planDialog"
      v-model:plan-form="planForm"
      :plan-saving="planSaving"
      :assignee-loading="assigneeLoading"
      :assignee-options="assigneeOptions"
      :devices="devices"
      :shortage-device-ids="shortageDeviceIds"
      :selected-devices-without-shortage="selectedDevicesWithoutShortage"
      :assignee-option-label="assigneeOptionLabel"
      :plan-device-label="planDeviceLabel"
      @create="createPlan"
      @go-shortage="goShortageFromPlan"
      @go-stock-health="goStockHealthFromPlan"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Goods } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api, downloadAuthFile } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import type { TableAction } from '@/components/TableActions.vue';
import CrudTable from '@/components/CrudTable.vue';
import PagePager from '@/components/PagePager.vue';
import ResizableDrawer from '@/components/ResizableDrawer.vue';
import { useAdminListTable } from '@/composables/useAdminListTable';
import { createLoadSeq } from '@/composables/createLoadSeq';
import { useReplenishmentRequestFlow } from '@/composables/replenishment/useReplenishmentRequestFlow';
import { useReplenishmentTaskActions } from '@/composables/replenishment/useReplenishmentTaskActions';
import { useReplenishmentTaskLines } from '@/composables/replenishment/useReplenishmentTaskLines';
import { useReplenishmentRoutePlanning } from '@/composables/replenishment/useReplenishmentRoutePlanning';
import ReplenishmentPlanRouteDialog from '@/components/replenishment/ReplenishmentPlanRouteDialog.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useAuthStore } from '@/stores/auth';
import { csvFileName } from '@/utils/csv';
import { sortByPrimaryKey } from '@/utils/sort-by-pk';
import { dictLabel, dictOptions, dictTagType, displayLabel } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- 多 tab 动态行，字段随业务表变化
type Row = Record<string, any>;

const route = useRoute();
const router = useRouter();
const { goPath } = useNavAccess();
const auth = useAuthStore();
const canEdit = computed(() => auth.hasPerm('ops:replenishment:edit'));

// 主列表排序统一走 CrudTable 工具条「升/降序」；履约记录 tab 未迁壳，保留表头排序
const {
  defaultSort: taskIdDefaultSort,
  onSortChange: onTaskIdSortChange,
  sortById: sortTasksById
} = useIdColumnSort<Row>('taskId');

function sortedRouteTasks(tasks: Row[] | undefined | null): Row[] {
  return sortByPrimaryKey(tasks || [], 'taskId', 'asc');
}

const loadingTabs = ref(new Set<string>());
const listHydrated = ref(false);
const loadSeq = createLoadSeq();

function isTabLoading(name: string) {
  return loadingTabs.value.has(name);
}

function markTabsLoading(names: string[], on: boolean) {
  const next = new Set(loadingTabs.value);
  for (const name of names) {
    if (on) next.add(name);
    else next.delete(name);
  }
  loadingTabs.value = next;
}

const expiryActingId = ref<number | null>(null);
const tab = ref('routes');
const SERVER_PAGINATED_TABS = new Set(['routes', 'fulfillment', 'requests', 'expiry', 'shortage']);
const tabTotals = ref<Record<string, number>>({});
const summary = ref({
  pendingTaskCount: 0,
  fulfilledTaskCount: 0,
  plannedRouteCount: 0,
  pendingRequestCount: 0
});
const page = ref(1);
const size = ref(20);
const focusDeviceId = ref('');
const fulfillmentStatus = ref('');
const fulfillmentUnassignedOnly = ref(false);
const requestStatusFilter = ref('SUBMITTED');
const routes = ref<Row[]>([]);
const allRequests = ref<Row[]>([]);
const fulfillmentTasksList = ref<Row[]>([]);
const devices = ref<Row[]>([]);
const shortages = ref<Row[]>([]);
const shortageDeviceIds = ref<string[]>([]);
const expiryAlerts = ref<Row[]>([]);

const shortageDevices = computed(() => shortageDeviceIds.value);

const pendingRequestCount = computed(() => summary.value.pendingRequestCount);
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
const plannedCount = computed(() => summary.value.plannedRouteCount);
const pendingTaskCount = computed(() => summary.value.pendingTaskCount);
const fulfillmentTasksBase = computed(() => fulfillmentTasksList.value);

const {
  tableRef: fulfillmentTableRef,
  keyword: fulfillmentKeyword,
  onSelectionChange: onFulfillmentSelectionChange,
  pickSelected: pickFulfillment,
  exportButtonLabel: fulfillmentExportLabel,
  clearSelection: clearFulfillmentSelection,
  filterByKeyword: filterFulfillmentByKeyword
} = useAdminListTable<Row>((r) => r.taskId);

const fulfilledCount = computed(() => summary.value.fulfilledTaskCount);
const fulfillmentEmptyText = computed(() => {
  if (focusDeviceId.value.trim()) return `设备 ${focusDeviceId.value} 暂无履约记录`;
  if (fulfillmentUnassignedOnly.value) return '暂无待分配货道的开放任务';
  if (fulfillmentStatus.value) return '当前筛选下暂无履约记录';
  return '暂无履约记录';
});
const routesEmptyText = computed(() =>
  focusDeviceId.value.trim() ? `设备 ${focusDeviceId.value} 暂无关联补货路线` : '暂无补货路线'
);

const tabTotal = computed(() => {
  if (SERVER_PAGINATED_TABS.has(tab.value)) {
    if (tab.value === 'fulfillment' && fulfillmentUnassignedOnly.value) {
      return fulfillmentTasks.value.length;
    }
    return tabTotals.value[tab.value] || 0;
  }
  return 0;
});

const pagedFulfillment = computed(() => sortTasksById(fulfillmentTasks.value));

watch([focusDeviceId, fulfillmentStatus, requestStatusFilter], () => {
  page.value = 1;
  if (SERVER_PAGINATED_TABS.has(tab.value)) {
    void loadTab(tab.value, true);
  }
});

/** 缺货建议行操作（迁入 CrudTable 固定操作列；整列随 canEdit 显隐） */
function shortageRowActions(_row: Row): TableAction[] {
  return [{ key: 'restock', label: '补货', icon: Goods, type: 'primary' }];
}

function onShortageAction({ row }: { key: string; row: Row }) {
  planSingleDevice(row.deviceId);
}

// ── 主列表状态机统一交给 CrudTable（分页 / 多选 / 升降序 / 竞态 / 空态 内建）────────────
// 首查依赖 onMounted 应用路由查询参数（tab / deviceId / plan），故全部 autoLoad:false，由 loadTab 显式首查。
// 履约记录 tab 的关键词与「仅待分配」是拉取后逐键客户端过滤，CrudTable 无此数据钩子，该 tab 保持原 el-table。
function replenishmentPageQuery(
  params: { page: number; size: number },
  extra?: Record<string, string>
) {
  const q = new URLSearchParams({ page: String(params.page), size: String(params.size) });
  if (extra) {
    for (const [key, value] of Object.entries(extra)) {
      if (value) q.set(key, value);
    }
  }
  return q;
}

const crudRoutes = useCrudTable<Row>({
  rowKey: (r) => r.routeId,
  errorMessage: '补货数据加载失败',
  autoLoad: false,
  sort: { prop: 'routeId', mode: 'local' },
  fetchPage: async (params) => {
    await loadSummary();
    const extra: Record<string, string> = {};
    if (focusDeviceId.value.trim()) extra.deviceId = focusDeviceId.value.trim();
    const data = await api.request<{ items: Row[]; total: number }>(
      AdminEndpoints.replenishmentRoutes(replenishmentPageQuery(params, extra)),
      'GET'
    );
    routes.value = data.items || [];
    tabTotals.value = { ...tabTotals.value, routes: Number(data.total) || 0 };
    return data;
  }
});

const crudRequests = useCrudTable<Row>({
  rowKey: (r) => r.requestId,
  errorMessage: '补货数据加载失败',
  autoLoad: false,
  sort: { prop: 'requestId', mode: 'local' },
  fetchPage: async (params) => {
    await loadSummary();
    const status = requestStatusFilter.value || 'ALL';
    const data = await api.request<{ items: Row[]; total: number }>(
      AdminEndpoints.replenishmentRequests(replenishmentPageQuery(params, { status })),
      'GET'
    );
    allRequests.value = data.items || [];
    tabTotals.value = { ...tabTotals.value, requests: Number(data.total) || 0 };
    return data;
  }
});

const crudShortages = useCrudTable<Row>({
  rowKey: (r) => r.slotKey || `${r.deviceId}-${r.slotCode}`,
  errorMessage: '补货数据加载失败',
  autoLoad: false,
  fetchPage: async (params) => {
    await loadSummary();
    const extra: Record<string, string> = {};
    if (focusDeviceId.value.trim()) extra.deviceId = focusDeviceId.value.trim();
    const data = await api.request<{
      items: Row[];
      total: number;
      shortageDeviceIds: string[];
    }>(AdminEndpoints.replenishmentShortage(replenishmentPageQuery(params, extra)), 'GET');
    shortages.value = (data.items || []).map((row) => ({
      ...row,
      slotKey: row.slotKey || `${row.deviceId}:${row.slotCode || row.skuId || ''}`
    }));
    tabTotals.value = { ...tabTotals.value, shortage: Number(data.total) || 0 };
    shortageDeviceIds.value = data.shortageDeviceIds || [];
    return { items: shortages.value, total: Number(data.total) || 0 };
  }
});

const crudExpiry = useCrudTable<Row>({
  rowKey: (r) => r.taskId || `${r.deviceId}-${r.lotId}-${r.skuId}`,
  errorMessage: '临期告警加载失败',
  autoLoad: false,
  fetchPage: async (params) => {
    await loadSummary();
    const data = await api.request<{ items: Row[]; total: number }>(
      AdminEndpoints.expiryAlerts(replenishmentPageQuery(params)),
      'GET'
    );
    let rows = data.items || [];
    if (focusDeviceId.value.trim()) {
      rows = rows.filter((x) => x.deviceId === focusDeviceId.value.trim());
    }
    expiryAlerts.value = rows;
    tabTotals.value = { ...tabTotals.value, expiry: Number(data.total) || 0 };
    return { items: rows, total: Number(data.total) || 0 };
  }
});

const exportButtonLabel = computed(() => {
  if (tab.value === 'requests') return crudRequests.exportButtonLabel;
  if (tab.value === 'shortage') return crudShortages.exportButtonLabel;
  if (tab.value === 'expiry') return crudExpiry.exportButtonLabel;
  if (tab.value === 'fulfillment') return fulfillmentExportLabel.value;
  return crudRoutes.exportButtonLabel;
});

const { onExport: exportRoutes } = useListCsv({
  filePrefix: '补货路线',
  headers: ['路线编号', '路线名称', '设备数', '计划日期', '状态'],
  toRows: () =>
    crudRoutes
      .pickSelected(crudRoutes.items)
      .map((row) => [
        row.routeId,
        row.routeName || '',
        row.tasks?.length || 0,
        row.plannedDate || '',
        dictLabel('replenishment_route_status', row.status)
      ])
});

const { onExport: exportFulfillment } = useListCsv({
  filePrefix: '履约记录',
  headers: [
    '任务',
    '设备编号',
    '设备名称',
    '路线',
    '状态',
    '人员',
    '签到时间',
    'GPS',
    '距柜机',
    '用时',
    '完成时间'
  ],
  toRows: () =>
    pickFulfillment(fulfillmentTasks.value).map((row) => [
      row.taskId,
      row.deviceId || '',
      deviceName(row.deviceId, row.deviceName),
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
  headers: ['要货单', '商户', '目标设备', '状态', '审核人', '审核时间', '驳回原因', '提交时间'],
  toRows: () =>
    crudRequests
      .pickSelected(crudRequests.items)
      .map((row) => [
        row.requestId,
        row.merchantName || '',
        deviceName(row.deviceId, row.deviceName),
        dictLabel('replenishment_request_status', row.status),
        row.reviewerName || row.reviewerId || '',
        row.reviewedAt ? formatDateTime(row.reviewedAt) : '',
        row.rejectReason || '',
        formatDateTime(row.submittedAt || row.createdAt)
      ])
});

const { onExport: exportShortages } = useListCsv({
  filePrefix: '缺货建议',
  headers: ['设备', '货道', '商品', '账面', '最低', '目标', '状态'],
  toRows: () =>
    crudShortages
      .pickSelected(crudShortages.items)
      .map((row) => [
        row.deviceName || row.deviceId,
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
    crudExpiry
      .pickSelected(crudExpiry.items)
      .map((row) => [
        row.taskId,
        row.deviceId,
        row.skuId,
        row.batchNo || '',
        row.lotId || '',
        row.quantity,
        displayLabel('pull_off_reason', row.reason, '临期'),
        row.status || '',
        formatDateTime(row.createdAt)
      ])
});

async function exportRequestsFull() {
  try {
    await downloadAuthFile(AdminEndpoints.replenishmentRequestsExport, csvFileName('商户要货'));
    ElMessage.success('已导出');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

async function exportRoutesFull() {
  try {
    await downloadAuthFile(AdminEndpoints.replenishmentRoutesExport, csvFileName('补货路线'));
    ElMessage.success('已导出');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

async function exportRequestsTab() {
  const selected = crudRequests.pickSelected(crudRequests.items);
  if (selected.length && selected.length < crudRequests.items.length) {
    exportRequests();
    return;
  }
  await exportRequestsFull();
}

async function exportRoutesTab() {
  const selected = crudRoutes.pickSelected(crudRoutes.items);
  if (selected.length && selected.length < crudRoutes.items.length) {
    exportRoutes();
    return;
  }
  await exportRoutesFull();
}

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
    await exportRequestsTab();
    return;
  }
  await exportRoutesTab();
}

function currentAssigneeId() {
  const id = Number(auth.userId || localStorage.getItem('admin_userId') || 0);
  return Number.isFinite(id) && id > 0 ? id : 1;
}

/** Prefer API snapshot name; fall back to shortage/device list join. */
function isGarbledDeviceName(name?: string | null): boolean {
  const s = name != null ? String(name).trim() : '';
  if (!s) return true;
  // UTF-8 误读/损坏常见：全问号、替换符、大量不可打印字符
  if (/[\uFFFD]/.test(s) || /^\?+$/.test(s) || /\?{3,}/.test(s)) return true;
  if (/^[\x00-\x08\x0B\x0C\x0E-\x1F]+$/.test(s)) return true;
  return false;
}

function deviceName(deviceId?: string, snapshot?: string | null) {
  const snap = snapshot != null ? String(snapshot).trim() : '';
  if (snap && !isGarbledDeviceName(snap)) return snap;
  const id = deviceId != null ? String(deviceId) : '';
  if (!id) return '无';
  const fromShortage = shortages.value.find((item) => item.deviceId === id)?.deviceName;
  if (fromShortage && !isGarbledDeviceName(fromShortage)) return fromShortage;
  const fromDevices = devices.value.find((item) => item.deviceId === id)?.deviceName;
  if (fromDevices && !isGarbledDeviceName(fromDevices)) return fromDevices;
  return id;
}

function stockLabel(row: Row) {
  const code = String(row.stockStatus || '').toUpperCase();
  if (code === 'OOS' || (row.bookQty ?? 0) <= 0) return '缺货';
  if (code === 'LOW') return '低库存';
  if (code === 'OK' || code === 'NORMAL') return displayLabel('warehouse_status', 'ACTIVE');
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

function syncRouteQuery() {
  const query: Record<string, string> = { tab: tab.value };
  if (focusDeviceId.value.trim()) query.deviceId = focusDeviceId.value.trim();
  router.replace({ query });
}

function onTabChange() {
  page.value = 1;
  syncRouteQuery();
  void loadTab(tab.value, true);
}

function replenishmentListParams(extra?: Record<string, string>) {
  const q = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (extra) {
    for (const [key, value] of Object.entries(extra)) {
      if (value) q.set(key, value);
    }
  }
  return q;
}

function onPagerChange() {
  if (SERVER_PAGINATED_TABS.has(tab.value)) {
    void loadTab(tab.value, true);
  }
}

function onPagerSizeChange() {
  page.value = 1;
  if (SERVER_PAGINATED_TABS.has(tab.value)) {
    void loadTab(tab.value, true);
  }
}

async function loadSummary() {
  const seq = loadSeq.begin('loadSummary');
  try {
    const data = await api.request<{
      pendingTaskCount: number;
      fulfilledTaskCount: number;
      plannedRouteCount: number;
      pendingRequestCount: number;
    }>(AdminEndpoints.replenishmentSummary, 'GET');
    summary.value = {
      pendingTaskCount: Number(data.pendingTaskCount) || 0,
      fulfilledTaskCount: Number(data.fulfilledTaskCount) || 0,
      plannedRouteCount: Number(data.plannedRouteCount) || 0,
      pendingRequestCount: Number(data.pendingRequestCount) || 0
    };
  } catch {
    if (!loadSeq.isCurrent(seq, 'loadSummary')) return;
    /* KPI 汇总失败不阻断列表 */
  }
}

async function loadFulfillment() {
  loadSeq.begin('loadFulfillment');
  const extra: Record<string, string> = {};
  if (focusDeviceId.value.trim()) extra.deviceId = focusDeviceId.value.trim();
  if (fulfillmentStatus.value) extra.status = fulfillmentStatus.value;
  const data = await api.request<{ items: Row[]; total: number }>(
    AdminEndpoints.replenishmentFulfillmentTasks(replenishmentListParams(extra)),
    'GET'
  );
  fulfillmentTasksList.value = data.items || [];
  tabTotals.value = { ...tabTotals.value, fulfillment: Number(data.total) || 0 };
  clearFulfillmentSelection();
  void prefetchUnassignedHints(fulfillmentTasksList.value);
}

async function loadDeviceRefs() {
  const seq = loadSeq.begin('loadDeviceRefs');
  if (devices.value.length) return;
  try {
    devices.value = await api.request<Row[]>(AdminEndpoints.devicesRef, 'GET');
  } catch {
    if (!loadSeq.isCurrent(seq, 'loadDeviceRefs')) return;
    devices.value = [];
  }
}

async function loadTab(name: string, force = false) {
  const seq = loadSeq.begin('loadTab');
  if (!force && !SERVER_PAGINATED_TABS.has(name)) return;
  if (name === 'fulfillment') markTabsLoading(['fulfillment'], true);
  try {
    if (name === 'routes') await crudRoutes.search();
    else if (name === 'fulfillment') {
      await loadSummary();
      await loadFulfillment();
    } else if (name === 'requests') await crudRequests.search();
    else if (name === 'shortage') {
      await loadDeviceRefs();
      await crudShortages.search();
    } else if (name === 'expiry') await crudExpiry.search();
  } catch (error) {
    if (!loadSeq.isCurrent(seq, 'loadTab')) return;
    ElMessage.error(error instanceof Error ? error.message : '补货数据加载失败');
  } finally {
    if (!loadSeq.isCurrent(seq, 'loadTab')) return;
    listHydrated.value = true;
    if (name === 'fulfillment') markTabsLoading(['fulfillment'], false);
  }
}

const reloadCurrentTab = () => loadTab(tab.value, true);

const {
  linesDrawer,
  linesLoading,
  slotSaving,
  linesTask,
  taskLines,
  taskUnassignedHint,
  taskEvidence,
  linesDrawerTitle,
  restockQtyTotal,
  editablePendingLines,
  unassignedRestockCount,
  isRestockLine,
  canAssignSlot,
  slotOptionsForLine,
  onSlotAssign,
  formatFileSize,
  openEvidencePreview,
  openTaskLines,
  saveTaskSlots,
  prefetchUnassignedHints
} = useReplenishmentTaskLines({ canEdit, loadSeq });

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

async function openLinkedTask(taskId: number | string) {
  let task = findTaskById(taskId);
  if (!task) {
    await reloadCurrentTab();
    task = findTaskById(taskId);
  }
  if (!task) {
    ElMessage.warning(`未找到补货任务 ${taskId}，请到履约记录中查找`);
    tab.value = 'fulfillment';
    syncRouteQuery();
    return;
  }
  tab.value = 'fulfillment';
  syncRouteQuery();
  await openTaskLines(task);
}

const {
  requestFlowDrawer,
  requestFlowRow,
  requestEvidence,
  requestActionsFor,
  showRequestActionColumn,
  requestFlowTitle,
  requestFlowActiveStep,
  requestFlowProcessStatus,
  requestFlowSubmitDesc,
  requestFlowReviewDesc,
  requestFlowFulfillDesc,
  formatRequestLines,
  onRequestAction,
  onRequestRowAction
} = useReplenishmentRequestFlow({
  canEdit,
  allRequests,
  deviceName,
  reloadCurrentTab,
  loadSeq,
  openLinkedTask
});

const {
  openDoorLoading,
  checkInLoading,
  completeLoading,
  cancelRouteLoading,
  deviceOnline,
  deviceSalesLocked,
  canOpenRestock,
  canCheckInTask,
  canCompleteTask,
  openDoorHint,
  checkInRestockTask,
  canCancelEmptyRoute,
  showRouteCancelColumn,
  cancelEmptyRoute,
  openRestockDoor,
  completeRestockTask
} = useReplenishmentTaskActions({
  devices,
  routes,
  canEdit,
  deviceName,
  reloadCurrentTab
});

const {
  planDialog,
  planSaving,
  planForm,
  assigneeOptions,
  assigneeLoading,
  selectedDevicesWithoutShortage,
  assigneeLabel,
  assigneeOptionLabel,
  planDeviceLabel,
  loadAssignees,
  openPlan,
  closePlan,
  maybeAutoPlanFromQuery,
  planFromShortage,
  planSingleDevice,
  createPlan
} = useReplenishmentRoutePlanning({
  loadSeq,
  canEdit,
  focusDeviceId,
  shortageDeviceIds,
  devices,
  currentUserId: currentAssigneeId,
  currentUserName: () => auth.displayName || '',
  loadDeviceRefs,
  deviceName,
  onRouteCreated: async () => {
    tab.value = 'routes';
    syncRouteQuery();
    await loadTab(tab.value, true);
  },
  readPlanQuery: () => ({
    plan: String(route.query.plan || ''),
    deviceIds: typeof route.query.deviceIds === 'string' ? route.query.deviceIds : ''
  }),
  clearPlanQuery: () => syncRouteQuery()
});

function goShortageFromPlan() {
  closePlan();
  tab.value = 'shortage';
  page.value = 1;
  syncRouteQuery();
  void loadTab('shortage', true);
}

function goStockHealthFromPlan() {
  closePlan();
  router.push('/stock-health');
}

const fulfillmentTasks = computed(() => {
  let rows = fulfillmentTasksBase.value;
  if (fulfillmentUnassignedOnly.value) {
    rows = rows.filter((t) => taskUnassignedHint.value[Number(t.taskId)]);
  }
  return filterFulfillmentByKeyword(rows, (row, kw) => {
    const taskMatch = String(row.taskId ?? '').includes(kw);
    const deviceIdMatch = String(row.deviceId ?? '')
      .toLowerCase()
      .includes(kw);
    const deviceNameMatch = deviceName(row.deviceId, row.deviceName).toLowerCase().includes(kw);
    return taskMatch || deviceIdMatch || deviceNameMatch;
  });
});
const unassignedHintCount = computed(
  () => fulfillmentTasksBase.value.filter((t) => taskUnassignedHint.value[Number(t.taskId)]).length
);

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

function goWarehouse(deviceId?: string) {
  const query: Record<string, string> = { tab: 'transit' };
  if (deviceId) query.deviceId = deviceId;
  goPath('/warehouse', query);
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
  if (start == null) return '无';
  const end =
    parseInstantMs(row?.completedAt) ??
    (['COMPLETED', 'CANCELLED'].includes(String(row?.status || '')) ? start : Date.now());
  const mins = Math.max(0, Math.round((end - start) / 60000));
  if (mins < 60) return `${mins} 分`;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m ? `${h} 时 ${m} 分` : `${h} 时`;
}

function routePlanMeta(notes?: string) {
  const raw = String(notes || '').trim();
  if (!raw) return {} as { sequence?: number; distanceM?: number };
  const seqMatch = raw.match(/\bseq\s*=\s*(\d+)\b/i);
  const distMatch = raw.match(/\bdist\s*=\s*(\d+)\s*m?\b/i);
  return {
    sequence: seqMatch ? Number(seqMatch[1]) : undefined,
    distanceM: distMatch ? Number(distMatch[1]) : undefined
  };
}

function formatRouteLegDistance(meters: number) {
  const m = Math.max(0, Math.round(meters));
  if (m < 1000) return `距上一站 ${m} 米`;
  return `距上一站 ${(m / 1000).toFixed(1)} 公里`;
}

/** 任务备注：过滤路线规划内部字段，保留业务说明 */
function formatTaskNotes(notes?: string) {
  const raw = String(notes || '').trim();
  if (!raw) return '';
  if (/from-expiry/i.test(raw)) {
    if (/\bEXPIRED\b/i.test(raw)) return '已过期下架';
    return '临期商品下架';
  }
  if (/NEAR_EXPIRY/i.test(raw) && !/[\u4e00-\u9fff]/.test(raw)) return '临期商品下架';
  if (/PULL_OFF/i.test(raw) && !/[\u4e00-\u9fff]/.test(raw)) return '下架任务';
  if (/^merchant request\s+\d+$/i.test(raw)) {
    return `商户要货单 ${raw.replace(/^\D+/, '')}`;
  }
  const cleaned = raw
    .replaceAll(/from-expiry:\d+/gi, '')
    .replaceAll(/\bNEAR_EXPIRY\b/gi, '')
    .replaceAll(/\bEXPIRED\b/gi, '')
    .replaceAll(/\bPULL_OFF\b/gi, '')
    .replaceAll(/\bseq\s*=\s*\d+\b/gi, '')
    .replaceAll(/\bdist\s*=\s*\d+\s*m?\b/gi, '')
    .replaceAll(/[|;,]+/g, ' ')
    .trim();
  if (!cleaned) return '';
  if (!/[\u4e00-\u9fff]/.test(cleaned) && /^[\w:=\-.\s]+$/.test(cleaned)) return '';
  return cleaned;
}

/** 列表备注：路线顺序/距离用中文，否则业务说明 */
function formatTaskNotesBrief(notes?: string) {
  const meta = routePlanMeta(notes);
  const human = formatTaskNotes(notes);
  const parts: string[] = [];
  if (meta.sequence != null) parts.push(`第 ${meta.sequence} 站`);
  if (meta.distanceM != null) parts.push(formatRouteLegDistance(meta.distanceM));
  if (human) parts.push(human);
  return parts.length ? parts.join(' · ') : '暂无';
}

function lineTypeLabel(type?: string) {
  const code = String(type || 'RESTOCK').toUpperCase();
  return displayLabel('restock_line_type', code, '未知');
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
      AdminEndpoints.expiryAlertCreateReplenishment(row.taskId),
      'POST',
      { lineType }
    );
    ElMessage.success(
      lineType === 'PULL_OFF'
        ? `已生成下架任务 ${route?.tasks?.[0]?.taskId || route?.routeId || ''}`
        : `已生成补货任务 ${route?.tasks?.[0]?.taskId || route?.routeId || ''}`
    );
    await crudExpiry.load();
    await loadTab(tab.value, true);
    tab.value = 'routes';
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生成任务失败');
  } finally {
    expiryActingId.value = null;
  }
}

async function reloadFromRouteQuery() {
  applyRouteQuery();
  page.value = 1;
  await loadTab(tab.value, true);
  await maybeAutoPlanFromQuery();
}

watch(
  () => [route.query.tab, route.query.deviceId, route.query.plan, route.query.deviceIds] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

// 各列表控制器均 autoLoad:false：首查须在路由查询参数（tab/deviceId/plan）应用后由 loadTab 显式触发
onMounted(async () => {
  applyRouteQuery();
  syncRouteQuery();
  void loadAssignees();
  await loadTab(tab.value, true);
  await maybeAutoPlanFromQuery();
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
.page-card-head__meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
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
.kpi-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.kpi-tag-btn {
  display: inline-flex;
  padding: 0;
  margin: 0;
  border: none;
  background: transparent;
  font: inherit;
  color: inherit;
  cursor: default;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.route-detail {
  /* width:0 + min-width:100%：展开行不把外层表撑宽，横滚落在子表外壳 */
  width: 0;
  min-width: 100%;
  box-sizing: border-box;
  padding: 8px 44px 12px;
  overflow: hidden;
}
.route-meta {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  color: var(--layout-muted);
  font-size: var(--admin-font-size-table);
}
.route-task-scroll {
  /* 嵌套子任务表：底部可右拉的横滚条 */
  width: 100%;
  max-width: 100%;
  margin: 0;
  border-radius: 6px;
  overflow-x: auto !important;
  overflow-y: hidden !important;
}
.route-task-scroll-inner {
  min-width: 0;
  width: 100%;
}
.line-table {
  width: 100%;
}
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  font: inherit;
  font-weight: 650;
}
.link-cell:hover {
  text-decoration: underline;
}
.muted {
  color: var(--layout-muted);
  font-size: var(--admin-font-size-table);
}
.check-in-cell {
  display: grid;
  gap: 4px;
  justify-items: center;
  line-height: 1.3;
}
.gps-text {
  color: var(--layout-muted);
  font-size: var(--admin-font-size-xs);
  font-family: var(--app-font-mono);
}
.gps-inline {
  color: var(--layout-muted);
  font-family: var(--app-font-mono);
  font-size: var(--admin-font-size-sm);
}
.gps-missing {
  color: var(--el-color-warning);
  font-weight: 600;
  font-family: inherit;
}
.mono {
  font-family: inherit;
  font-size: inherit;
}
.cell-datetime {
  font-variant-numeric: tabular-nums;
}
.lines-drawer {
  display: grid;
  gap: 12px;
  min-width: 0;
}
.lines-drawer .table-scroll {
  overflow-x: auto;
  min-width: 0;
  /* 预留滚动条槽，避免拖宽时滚动条出现/消失导致合计行上下跳 */
  scrollbar-gutter: stable;
}
.lines-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 24px;
  color: var(--layout-muted);
  font-size: var(--admin-font-size-table);
}
.lines-meta {
  margin-bottom: 0;
}
.lines-photo-hint {
  margin: 0;
}
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
  font-size: var(--admin-font-size-table);
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
.evidence-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.evidence-item .meta {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
}
.reject-reason {
  color: var(--el-color-danger);
  font-size: var(--admin-font-size-table);
}
.unassigned-badge {
  margin-left: 2px;
}
.lines-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
}
.lines-action-hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-color-warning);
}
.request-flow {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 4px 0 12px;
}
.request-flow :deep(.el-step__description) {
  white-space: pre-line;
  line-height: 1.4;
}
.request-flow-meta {
  margin-top: 4px;
}
.request-flow-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.lines-dot {
  margin-left: 4px;
  vertical-align: super;
}
.status-stack {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.online-tag {
  margin-left: 6px;
  vertical-align: middle;
}
.shortage-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.expiry-hint {
  flex: 1;
  min-width: 240px;
  margin: 0;
}
.expiry-restock-wrap {
  display: inline-flex;
  vertical-align: middle;
}
@media (max-width: 760px) {
  .route-detail {
    padding: 8px 12px;
  }
}
</style>
