<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">争议审核</span>
            <span class="hint"
              >识别争议可按低置信 / 兜底识别 / 重力错配分拣；同屏对照录像改 SKU
              后一键落账或免单</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <!-- 导出迁入 CrudTable 内建工具条（:csv + exportPerm）；刷新迁入壳内统一交互行 -->
        </div>
      </div>
    </template>

    <el-tabs v-model="categoryTab" class="status-tabs" @tab-change="onCategoryTab">
      <el-tab-pane label="全部类型" name="ALL" />
      <el-tab-pane label="识别争议" name="RECOGNITION" />
    </el-tabs>

    <el-radio-group
      v-if="categoryTab === 'RECOGNITION'"
      v-model="reviewCodeTab"
      size="small"
      class="review-code-tabs"
      @change="onReviewCodeTab"
    >
      <el-radio-button value="ALL">全部识别</el-radio-button>
      <el-radio-button value="LOW_CONF">低置信</el-radio-button>
      <el-radio-button value="MOCK">兜底识别</el-radio-button>
      <el-radio-button value="GRAVITY_MISMATCH">重力错配</el-radio-button>
      <el-radio-button value="GRAVITY_FILL">重力回填</el-radio-button>
      <el-radio-button value="UNMAPPED">未映射</el-radio-button>
      <el-radio-button value="EMPTY">空识别</el-radio-button>
      <el-radio-button value="NEED_REVIEW">需复核</el-radio-button>
      <el-radio-button value="WHITELIST">白名单</el-radio-button>
    </el-radio-group>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="状态">
        <el-select
          v-model="status"
          clearable
          placeholder="全部"
          style="width: 140px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('dispute_status')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="工单 / 设备 / 会话 / 订单…"
          style="width: 260px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          auto-refresh
          row-key="ticketId"
          manage-table="dispute_ticket"
          selectable
          :actions="rowActions"
          :action-width="168"
          :empty-text="emptyHint"
          sort-field-label="工单号"
          :csv="csvOptions"
          @action="onRowAction"
        >
          <el-table-column prop="ticketId" label="工单号" min-width="140" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id cell-ellipsis" :title="String(row.ticketId || '')">{{
                row.ticketId
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="工单" min-width="160" class-name="col-text">
            <template #default="{ row }">
              <button
                type="button"
                class="link-cell cell-ellipsis"
                :title="row.reason || '无'"
                @click="openDetail(row)"
              >
                {{ row.reason || '无' }}
              </button>
            </template>
          </el-table-column>
          <el-table-column
            label="置信度"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag
                v-if="confidenceHint(row)"
                size="small"
                :type="reviewChipType(row)"
                effect="plain"
                >{{ confidenceHint(row) }}</el-tag
              >
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="设备" min-width="110" class-name="col-text">
            <template #default="{ row }">
              <button
                v-if="row.deviceId"
                type="button"
                class="link-cell cell-ellipsis"
                :title="row.deviceId"
                @click="goDevice(row.deviceId)"
              >
                {{ row.deviceId }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="会话" min-width="130" class-name="col-text">
            <template #default="{ row }">
              <button
                v-if="row.sessionId"
                type="button"
                class="link-cell mono cell-ellipsis"
                :title="String(row.sessionId)"
                @click="goSessions(row.deviceId, row.sessionId)"
              >
                {{ displayBizNo(row.sessionId, '无') }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="关联订单" min-width="130" class-name="col-text">
            <template #default="{ row }">
              <button
                v-if="row.orderId"
                type="button"
                class="link-cell mono cell-ellipsis"
                :title="String(row.orderId)"
                @click="goOrders(row.deviceId, row.orderId)"
              >
                {{ displayBizNo(row.orderId) }}
              </button>
              <span
                v-else
                class="muted"
                :title="
                  row.sessionId
                    ? '争议未结案前通常尚无订单；审单落账（确认/调整/免单）后才会生成订单号'
                    : '无关联会话'
                "
                >{{ row.sessionId ? '待落账' : '无' }}</span
              >
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="96"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" :type="disputeStatusType(row.status)">
                {{ displayLabel('dispute_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="处理人" width="110" class-name="col-text">
            <template #default="{ row }">
              <span v-if="row.assignee" class="cell-ellipsis" :title="row.assignee">{{
                row.assignee
              }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="分类" width="100" class-name="col-text">
            <template #default="{ row }">
              <span
                class="cell-ellipsis"
                :title="displayLabel('dispute_category', row.category, '未知')"
                >{{ displayLabel('dispute_category', row.category, '未知') }}</span
              >
            </template>
          </el-table-column>
          <el-table-column
            label="优先级"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag
                v-if="row.priority"
                size="small"
                :type="row.priority === 'HIGH' || row.priority === 'URGENT' ? 'danger' : 'info'"
              >
                {{ displayLabel('dispute_priority', row.priority, '未知') }}
              </el-tag>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="已扣金额" width="100" align="center" class-name="col-money">
            <template #default="{ row }">¥{{ money(row.billedAmountCents) }}</template>
          </el-table-column>
          <el-table-column label="建议金额" width="100" align="center" class-name="col-money">
            <template #default="{ row }">
              <span v-if="row.claimedAmountCents != null"
                >¥{{ money(row.claimedAmountCents) }}</span
              >
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="已退金额" width="100" align="center" class-name="col-money">
            <template #default="{ row }">
              <span v-if="row.refundedAmountCents != null"
                >¥{{ money(row.refundedAmountCents) }}</span
              >
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column
            label="SLA"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag v-if="row.slaOverdue" type="danger" size="small" effect="plain"
                >已超时</el-tag
              >
              <span v-else-if="row.slaHoursRemaining != null" class="muted"
                >剩 {{ row.slaHoursRemaining }}h</span
              >
              <span v-else-if="row.slaDueAt" class="muted">{{ formatDateTime(row.slaDueAt) }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column
            label="证据"
            width="88"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">
              <el-tag
                size="small"
                :type="evidenceCount(row) > 0 ? 'success' : 'info'"
                effect="plain"
              >
                {{ evidenceCount(row) > 0 ? `${evidenceCount(row)} 件` : '无' }}
              </el-tag>
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
            align="center"
            label="结案时间"
            width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.resolvedAt) || '无' }}</span>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <ResizableDrawer
      v-if="detailVisible"
      v-model="detailVisible"
      title="争议审单工作台"
      storage-key="admin.drawer.disputes.workbench"
      :default-width="1120"
      :min-width="640"
      :max-width="1400"
      append-to-body
      destroy-on-close
      class="dispute-workbench drawer-workbench"
      @closed="onDetailClosed"
    >
      <el-alert
        v-if="resolveFeedback"
        type="success"
        title="已处理"
        :description="resolveFeedback.message"
        show-icon
        :closable="false"
        class="resolve-feedback"
      />

      <div v-if="selected" class="workbench-stack">
        <div class="workbench-grid">
          <section class="workbench-media">
            <div class="items-title">会话录像</div>
            <div v-if="embedVideoUrl" class="video-wrap">
              <video :src="embedVideoUrl" controls playsinline class="session-video">
                <track
                  kind="captions"
                  srclang="zh"
                  label="现场录像无对白字幕"
                  src="data:text/vtt,WEBVTT"
                />
                <track
                  kind="descriptions"
                  srclang="zh"
                  label="购物过程监控录像"
                  src="data:text/vtt,WEBVTT"
                />
              </video>
            </div>
            <el-empty
              v-else-if="videoAttempted && !videoLoading"
              description="暂无录像或加载失败"
              :image-size="64"
            />
            <div v-else-if="videoLoading" class="video-loading">录像{{ UI_COPY.loading }}</div>
            <div v-else class="video-loading muted">尚未加载录像</div>
            <el-alert
              v-if="!embedVideoUrl && videoAttempted && !videoLoading"
              type="info"
              :closable="false"
              show-icon
              class="no-video-guide"
              title="无录像时的结案步骤"
              description="先点「重新加载录像」或「新窗口打开」再试；仍无法播放时，勾选「无录像 / 无法播放，仍结案」，再勾选「已对照录像核对」，然后处理结案。有录像时必须先观看并勾选核对。"
            />
            <div class="workbench-media-actions">
              <el-button
                v-if="
                  selected.sessionId &&
                  (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))
                "
                type="warning"
                size="small"
                :loading="videoLoading"
                @click="loadEmbedVideo(selected.sessionId, true)"
                >重新加载录像</el-button
              >
              <el-button
                v-if="
                  selected.sessionId &&
                  (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))
                "
                link
                type="primary"
                @click="playVideo(selected.sessionId)"
                >新窗口打开</el-button
              >
              <el-checkbox v-model="videoReviewed" :disabled="!embedVideoUrl && !noVideoAck"
                >已对照录像核对</el-checkbox
              >
              <el-checkbox
                v-if="!embedVideoUrl && videoAttempted && !videoLoading"
                v-model="noVideoAck"
                >无录像 / 无法播放，仍结案</el-checkbox
              >
              <el-button
                v-if="selected.deviceId && canAccessPath('/exceptions')"
                size="small"
                @click="goExceptions(selected.deviceId)"
                >异常中心</el-button
              >
              <el-button
                v-if="selected.orderId || selected.deviceId"
                size="small"
                @click="goOrders(selected.deviceId, selected.orderId)"
                >关联订单</el-button
              >
            </div>
          </section>

          <section class="workbench-meta">
            <el-alert
              v-if="selected.reviewCode === 'MOCK' || /模拟|非生产精度/.test(selected.reason || '')"
              type="warning"
              :closable="false"
              show-icon
              title="当前为兜底识别，精度有限；请对照录像人工确认后再落账。"
              class="suggest-alert"
            />
            <el-descriptions :column="2" border size="small" class="workbench-desc">
              <el-descriptions-item label="工单" :span="2">
                <span class="cell-id">{{ selected.ticketId }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="会话">
                <button
                  v-if="selected.sessionId"
                  type="button"
                  class="link-cell mono"
                  @click="goSessions(selected.deviceId, selected.sessionId)"
                >
                  {{ displayBizNo(selected.sessionId, '无') }}
                </button>
                <span v-else class="muted">暂无</span>
              </el-descriptions-item>
              <el-descriptions-item label="设备">
                <button
                  v-if="selected.deviceId"
                  type="button"
                  class="link-cell"
                  @click="goDevice(selected.deviceId)"
                >
                  {{ selected.deviceId }}
                </button>
                <span v-else class="muted">暂无</span>
              </el-descriptions-item>
              <el-descriptions-item label="原因" :span="2">
                <div class="reason-block">
                  <span>{{ selected.reason || '无' }}</span>
                  <el-tag
                    v-if="confidenceHint(selected)"
                    size="small"
                    :type="reviewChipType(selected)"
                    effect="plain"
                  >
                    {{ confidenceHint(selected) }}
                  </el-tag>
                </div>
              </el-descriptions-item>
              <el-descriptions-item
                v-if="selected.detectedClasses?.length"
                label="检出类"
                :span="2"
              >
                <div class="detected-classes">
                  {{ selected.detectedClasses.join('、') }}
                  <el-button
                    v-if="selected.reviewCode === 'UNMAPPED' || selected.detectedClasses.length"
                    link
                    type="primary"
                    @click="goVisionMapping(selected)"
                    >去映射</el-button
                  >
                </div>
              </el-descriptions-item>
              <el-descriptions-item label="已扣金额"
                >¥{{ money(selected.billedAmountCents) }}</el-descriptions-item
              >
              <el-descriptions-item label="建议金额">
                <span v-if="selected.claimedAmountCents != null"
                  >¥{{ money(selected.claimedAmountCents) }}</span
                >
                <span v-else class="muted">—</span>
              </el-descriptions-item>
              <el-descriptions-item v-if="selectedAmountDiffNote" label="差额说明" :span="2">
                <span class="amount-diff">{{ selectedAmountDiffNote }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="已退金额">
                <span v-if="selected.refundedAmountCents != null"
                  >¥{{ money(selected.refundedAmountCents) }}</span
                >
                <span v-else class="muted">—</span>
              </el-descriptions-item>
              <el-descriptions-item label="SLA">
                <el-tag v-if="selected.slaOverdue" type="danger" size="small">已超时</el-tag>
                <span v-else-if="selected.slaHoursRemaining != null"
                  >剩余 {{ selected.slaHoursRemaining }} 小时</span
                >
                <span v-else-if="selected.slaDueAt">{{ formatDateTime(selected.slaDueAt) }}</span>
                <span v-else class="muted">—</span>
              </el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag
                  v-if="resolveFeedback || selected.status !== 'OPEN'"
                  type="success"
                  effect="light"
                  size="small"
                >
                  {{ resolveFeedback ? '已处理' : dictLabel('dispute_status', selected.status) }}
                </el-tag>
                <el-tag v-else size="small" type="warning">
                  {{ dictLabel('dispute_status', selected.status) }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="处理人">
                <span v-if="selected.assignee">{{ selected.assignee }}</span>
                <span v-else class="muted">—</span>
              </el-descriptions-item>
              <el-descriptions-item v-if="selected.resolvedAt" label="处理时间">
                {{ formatDateTime(selected.resolvedAt) }}
              </el-descriptions-item>
              <el-descriptions-item label="关联订单" :span="2">
                <button
                  v-if="selected.orderId"
                  type="button"
                  class="link-cell mono"
                  @click="goOrders(selected.deviceId, selected.orderId)"
                >
                  {{ displayBizNo(selected.orderId) }}
                </button>
                <span
                  v-else
                  class="muted"
                  title="争议未结案前通常尚无订单；审单落账（确认/调整/免单）后才会生成订单号"
                  >待落账</span
                >
              </el-descriptions-item>
            </el-descriptions>
          </section>
        </div>

        <section v-if="selected.suggestedItems?.length" class="workbench-suggest">
          <div class="items-title">识别建议（只读）</div>
          <el-table
            :data="selected.suggestedItems"
            size="small"
            stripe
            border
            class="suggest-table"
          >
            <el-table-column prop="skuName" label="商品" min-width="110" class-name="col-text" />
            <el-table-column prop="skuId" label="SKU" min-width="110" class-name="col-text">
              <template #default="{ row }">
                <span class="cell-ellipsis" :title="String(row.skuId || '')">{{ row.skuId }}</span>
              </template>
            </el-table-column>
            <el-table-column
              prop="quantity"
              label="数量"
              width="72"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            />
            <el-table-column
              label="单价"
              width="88"
              align="center"
              class-name="col-money"
              label-class-name="col-money"
            >
              <template #default="{ row }">¥{{ money(row.unitPriceCents) }}</template>
            </el-table-column>
            <el-table-column
              label="小计"
              width="88"
              align="center"
              class-name="col-money"
              label-class-name="col-money"
            >
              <template #default="{ row }">¥{{ money(row.lineAmountCents) }}</template>
            </el-table-column>
            <el-table-column
              prop="slotId"
              label="货道"
              width="72"
              align="center"
              class-name="col-text"
              label-class-name="col-text"
            >
              <template #default="{ row }">{{ row.slotId || '暂无' }}</template>
            </el-table-column>
          </el-table>
        </section>

        <div v-if="selected.status === 'OPEN'" class="workbench-settle">
          <div class="adjust-block">
            <div class="items-title">调整明细（落账依据）</div>
            <el-alert
              type="info"
              :closable="false"
              show-icon
              title="对照左侧录像修改 SKU / 数量后，用「按调整明细落账」写回账单差额。"
              class="suggest-alert"
            />
            <div class="manual-lines">
              <div v-for="(line, index) in draftLines" :key="index" class="manual-line">
                <el-select v-model="line.skuId" filterable placeholder="选择商品">
                  <el-option
                    v-for="sku in skus"
                    :key="sku.skuId"
                    :label="`${sku.skuName}（¥${((sku.priceCents || 0) / 100).toFixed(2)}）`"
                    :value="sku.skuId"
                  />
                </el-select>
                <el-input-number
                  v-model="line.quantity"
                  :min="1"
                  :max="99"
                  controls-position="right"
                />
                <el-button type="danger" link @click="removeDraftLine(index)">删除</el-button>
              </div>
              <div class="manual-line-toolbar">
                <el-button size="small" @click="draftLines.push({ skuId: '', quantity: 1 })"
                  >添加商品</el-button
                >
                <el-button link type="primary" @click="resetDraftFromSuggested"
                  >从识别建议填充</el-button
                >
              </div>
            </div>
          </div>
          <div class="ai-suggest-block">
            <div class="items-title">智能识别建议</div>
            <input
              ref="disputeImageInput"
              type="file"
              accept="image/*"
              class="hidden-input"
              @change="onDisputeImagePick"
            />
            <el-button size="small" :loading="suggestingDispute" @click="triggerDisputeImage">
              上传关键帧获取商品建议
            </el-button>
            <el-alert
              v-if="disputeSuggestHint"
              :title="disputeSuggestHint"
              type="info"
              show-icon
              :closable="false"
              class="suggest-alert"
            />
          </div>
          <div class="workbench-settle-actions">
            <el-button
              v-hasPermi="['ops:dispute:resolve']"
              type="warning"
              plain
              :loading="claiming"
              @click="claimSelected"
              >认领工单</el-button
            >
            <el-button
              v-if="hasPriorBill"
              v-hasPermi="['ops:dispute:resolve']"
              type="primary"
              :loading="resolving"
              @click="resolveSelected('KEEP')"
              >{{ displayLabel('dispute_resolution', 'KEEP') }}</el-button
            >
            <el-button
              v-hasPermi="['ops:dispute:resolve']"
              type="success"
              :loading="resolving"
              :disabled="!draftConfirmItems.length"
              @click="resolveSelected('ADJUST')"
              >{{ displayLabel('dispute_resolution', 'ADJUST') }}</el-button
            >
            <el-button
              v-hasPermi="['ops:dispute:resolve']"
              type="danger"
              plain
              :loading="resolving"
              @click="resolveSelected('WAIVE')"
              >{{ displayLabel('dispute_resolution', 'WAIVE') }}</el-button
            >
          </div>
        </div>
      </div>
    </ResizableDrawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  CircleClose,
  Link,
  RefreshLeft,
  VideoCamera,
  View,
  Warning
} from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { api, authFetch } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import ResizableDrawer from '@/components/ResizableDrawer.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useNavAccess } from '@/composables/useNavAccess';
import { useSessionVideo } from '@/composables/useSessionVideo';
import { disputeAmountDiffNote } from '@/utils/dispute-amount-note';
import { buildDisputeResolveBody, type DisputeResolutionType } from '@/utils/money-ui-contracts';
import type {
  DevRecognitionPreviewDto,
  DisputeTicketDto,
  OrderLineDto,
  PageResult
} from '@aicabinet/shared-types';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';
import { errorMessage, isUserDismiss } from '@/utils/error-message';
import { validateImageFile } from '@/utils/upload-validate';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

interface ResolveDisputeResultDto {
  order?: { orderId?: string } | null;
  resolutionType?: string;
  originalAmountCents?: number;
  finalAmountCents?: number;
  adjustmentCents?: number;
  message?: string;
}

type SkuOption = { skuId: string; skuName: string; priceCents?: number };

const route = useRoute();
const router = useRouter();
const { auth, canAccessPath, goPath } = useNavAccess();
const { playSessionVideo, fetchSessionVideoBlob } = useSessionVideo();
const videoLoading = ref(false);
const videoAttempted = ref(false);
const embedVideoUrl = ref('');
let embedVideoRevoke: (() => void) | null = null;
const status = ref('OPEN');
const categoryTab = ref('ALL');
const reviewCodeTab = ref('ALL');
const keyword = ref('');
const focusDisputeId = ref('');
const selected = ref<DisputeTicketDto | null>(null);
const detailVisible = ref(false);
const resolving = ref(false);
const claiming = ref(false);
const suggestingDispute = ref(false);
const disputeSuggestHint = ref('');
const disputeImageInput = ref<HTMLInputElement | null>(null);
const resolveFeedback = ref<{ message: string } | null>(null);
const videoReviewed = ref(false);
const noVideoAck = ref(false);
const skus = ref<SkuOption[]>([]);
const draftLines = ref<{ skuId: string; quantity: number }[]>([]);

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 全部内建。
// 首查依赖路由查询参数（status/category/reviewCode/keyword/ticketId）先落位，故 autoLoad:false，onMounted 显式首查。
const crud = useCrudTable<DisputeTicketDto>({
  rowKey: (r) => r.ticketId,
  errorMessage: '加载失败',
  autoLoad: false,
  fetchPage: async (params) => {
    const data = await api.request<PageResult<DisputeTicketDto>>(
      AdminEndpoints.disputesList(buildDisputeListQuery(params.page, params.size)),
      'GET'
    );
    // 关键词若是工单号（雪花）被当成 sessionId 会空；回退按 ticketId 拉详情
    if (!(data.items || []).length && keyword.value.trim()) {
      const fallback = await tryLoadTicketByNumericKeyword(keyword.value.trim());
      if (fallback) return { items: [fallback], total: 1 };
    }
    return data;
  },
  // 排序走壳内「升/降序」工具条（全站无表头箭头），默认升序对齐原 useIdColumnSort
  sort: { prop: 'ticketId', mode: 'local' }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '争议',
  exportPerm: 'ops:dispute:export',
  headers: [
    '工单',
    '设备',
    '会话',
    '关联订单',
    '状态',
    '处理人',
    '分类',
    '优先级',
    '已扣金额',
    '建议金额',
    '已退金额',
    'SLA',
    '原因',
    '创建时间',
    '结案时间'
  ],
  toRows: (rows) =>
    rows.map((row) => [
      row.ticketId,
      row.deviceId,
      row.sessionId,
      row.orderId,
      displayLabel('dispute_status', row.status, '未知'),
      row.assignee || '',
      displayLabel('dispute_category', row.category, '未知'),
      displayLabel('dispute_priority', row.priority, '未知'),
      money(row.billedAmountCents),
      row.claimedAmountCents != null ? money(row.claimedAmountCents) : '',
      row.refundedAmountCents != null ? money(row.refundedAmountCents) : '',
      row.slaOverdue
        ? '已超时'
        : row.slaHoursRemaining != null
          ? `剩${row.slaHoursRemaining}h`
          : row.slaDueAt
            ? formatDateTime(row.slaDueAt)
            : '',
      row.reason,
      formatDateTime(row.createdAt),
      formatDateTime(row.resolvedAt)
    ])
};

const emptyHint = computed(() => {
  if (categoryTab.value === 'RECOGNITION' && reviewCodeTab.value !== 'ALL') {
    return `当前无「${confidenceHint({ reviewCode: reviewCodeTab.value, category: 'RECOGNITION' })}」待审工单`;
  }
  return status.value === 'OPEN' ? '当前无待审核工单，可切换「已结案」查看历史' : '暂无数据';
});

const hasPriorBill = computed(() => (selected.value?.billedAmountCents || 0) > 0);
const selectedAmountDiffNote = computed(() => disputeAmountDiffNote(selected.value));

const draftConfirmItems = computed(() =>
  draftLines.value
    .filter((line) => line.skuId && (line.quantity || 0) > 0)
    .map((line) => ({ skuId: line.skuId, quantity: line.quantity }))
);

function resetDraftFromSuggested() {
  const suggested = selected.value?.suggestedItems || [];
  draftLines.value = suggested
    .filter((line: OrderLineDto) => line.skuId && (line.quantity || 0) > 0)
    .map((line: OrderLineDto) => ({
      skuId: String(line.skuId),
      quantity: (() => {
        const n = Number(line.quantity);
        return Number.isFinite(n) && n > 0 ? n : 1;
      })()
    }));
  if (!draftLines.value.length) {
    draftLines.value = [{ skuId: '', quantity: 1 }];
  }
}

async function removeDraftLine(index: number) {
  try {
    await ElMessageBox.confirm('确定删除该商品行吗？', '删除商品', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  draftLines.value.splice(index, 1);
  if (!draftLines.value.length) {
    draftLines.value = [{ skuId: '', quantity: 1 }];
  }
}

async function ensureSkusLoaded() {
  if (skus.value.length) return;
  try {
    skus.value =
      (await api.request<{ items: SkuOption[] }>(AdminEndpoints.skusCatalogPage, 'GET')).items ||
      [];
  } catch {
    skus.value = [];
  }
}

function clearEmbedVideo() {
  if (embedVideoRevoke) {
    embedVideoRevoke();
    embedVideoRevoke = null;
  }
  embedVideoUrl.value = '';
}

function onDetailClosed() {
  resolveFeedback.value = null;
  videoReviewed.value = false;
  noVideoAck.value = false;
  videoAttempted.value = false;
  clearEmbedVideo();
  draftLines.value = [];
}

async function loadEmbedVideo(sessionId?: string, force = false) {
  if (!sessionId) return;
  if (embedVideoUrl.value && !force) return;
  videoLoading.value = true;
  try {
    clearEmbedVideo();
    const { url, revoke } = await fetchSessionVideoBlob(sessionId);
    embedVideoUrl.value = url;
    embedVideoRevoke = revoke;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '录像加载失败');
  } finally {
    videoAttempted.value = true;
    videoLoading.value = false;
  }
}

function money(cents?: number) {
  return ((cents || 0) / 100).toFixed(2);
}

function evidenceCount(row: { evidence?: unknown[] | null }) {
  return Array.isArray(row.evidence) ? row.evidence.length : 0;
}

function disputeStatusType(s?: string) {
  if (s === 'OPEN') return 'warning';
  if (s === 'RESOLVED') return 'success';
  if (s === 'CLOSED') return 'info';
  return '';
}

const REVIEW_CODE_HINTS: Record<string, string> = {
  LOW_CONF: '低置信',
  MOCK: '兜底识别',
  GRAVITY_MISMATCH: '重力错配',
  GRAVITY_FILL: '重力回填',
  UNMAPPED: '未映射',
  EMPTY: '空识别',
  WHITELIST: '白名单',
  NEED_REVIEW: '需复核'
};

function confidenceHintFromCode(code: string): string {
  return REVIEW_CODE_HINTS[code] || '';
}

const RECOGNITION_HINT_RULES: Array<{
  test: (text: string, row: { category?: string }) => boolean;
  hint: string;
}> = [
  { test: (text) => /模拟|非生产精度|mock|兜底/i.test(text), hint: '兜底识别' },
  { test: (text) => /视觉与重力|重力.*不一致|错配/.test(text), hint: '重力错配' },
  { test: (text) => /仅有重力|重力信号/.test(text), hint: '重力回填' },
  { test: (text) => /低置信|置信度|阈值/.test(text), hint: '低置信' },
  { test: (text) => /未映射|检出类/.test(text), hint: '未映射' },
  { test: (text) => /未识别/.test(text), hint: '空识别' },
  { test: (text) => /白名单|视觉状态/.test(text), hint: '白名单' },
  { test: (_text, row) => row.category === 'RECOGNITION', hint: '识别争议' }
];

function confidenceHintFromRecognitionText(row: { category?: string; reason?: string }): string {
  const text = `${row.reason || ''} ${row.category || ''}`;
  if (row.category !== 'RECOGNITION' && !/识别|置信|未映射|存疑|模拟|重力/.test(text)) {
    return '';
  }
  for (const rule of RECOGNITION_HINT_RULES) {
    if (rule.test(text, row)) return rule.hint;
  }
  return '';
}

function confidenceHint(
  row?: DisputeTicketDto | null | { reviewCode?: string; category?: string; reason?: string }
) {
  if (!row) return '';
  const fromCode = confidenceHintFromCode(String(row.reviewCode || '').toUpperCase());
  if (fromCode) return fromCode;
  return confidenceHintFromRecognitionText(row);
}

function reviewChipType(row?: DisputeTicketDto | null) {
  const code = String(row?.reviewCode || '').toUpperCase();
  if (code === 'LOW_CONF' || code === 'EMPTY' || code === 'GRAVITY_MISMATCH') return 'danger';
  if (code === 'MOCK' || code === 'GRAVITY_FILL' || code === 'UNMAPPED' || code === 'WHITELIST')
    return 'warning';
  return 'info';
}

function appendDisputeVideoAction(actions: CrudRowAction[], row: DisputeTicketDto) {
  if (!row.sessionId) return;
  if (!(auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))) return;
  actions.push({
    key: 'video',
    label: '录像',
    icon: VideoCamera,
    type: 'warning',
    overflow: true
  });
}

function appendDisputeMappingAction(actions: CrudRowAction[], row: DisputeTicketDto) {
  if (row.reviewCode !== 'UNMAPPED' && !(row.detectedClasses && row.detectedClasses.length)) return;
  actions.push({ key: 'mapping', label: '去映射', icon: Link, overflow: true });
}

function appendDisputeStatusActions(actions: CrudRowAction[], row: DisputeTicketDto) {
  if (row.status === 'RESOLVED' && auth.hasPerm('ops:dispute:resolve')) {
    actions.push({ key: 'close', label: '关闭', icon: CircleClose, overflow: true });
  }
  if (row.status === 'CLOSED' && auth.hasPerm('ops:dispute:resolve')) {
    actions.push({ key: 'reopen', label: '重开', icon: RefreshLeft, overflow: true });
  }
}

function rowActions(row: DisputeTicketDto): CrudRowAction[] {
  const actions: CrudRowAction[] = [{ key: 'detail', label: '详情', icon: View, type: 'primary' }];
  appendDisputeVideoAction(actions, row);
  appendDisputeMappingAction(actions, row);
  if (row.deviceId && canAccessPath('/exceptions')) {
    actions.push({ key: 'exception', label: '异常', icon: Warning, overflow: true });
  }
  if (row.orderId || row.deviceId) {
    actions.push({ key: 'order', label: '订单', icon: Link, overflow: true });
  }
  appendDisputeStatusActions(actions, row);
  return actions;
}

function onRowAction({ key, row }: { key: string; row: DisputeTicketDto }) {
  if (key === 'detail') openDetail(row);
  if (key === 'video') playVideo(row.sessionId);
  if (key === 'exception') goExceptions(row.deviceId);
  if (key === 'order') goOrders(row.deviceId, row.orderId);
  if (key === 'mapping') goVisionMapping(row);
  if (key === 'close') void closeTicket(row);
  if (key === 'reopen') void reopenTicket(row);
}

async function closeTicket(row: DisputeTicketDto) {
  try {
    await ElMessageBox.confirm(`确认关闭争议 ${row.ticketId}？关闭后仍可重开。`, '关闭争议', {
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await api.request(AdminEndpoints.disputeClose(row.ticketId), 'POST', {});
    ElMessage.success('已关闭');
    await crud.load();
    await openFocusedTicket(); // 原 load() 收尾：深链工单号自动打开工作台
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '关闭失败');
  }
}

async function reopenTicket(row: DisputeTicketDto) {
  try {
    await ElMessageBox.confirm(`确认重开争议 ${row.ticketId}？`, '重开争议', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await api.request(AdminEndpoints.disputeReopen(row.ticketId), 'POST', {});
    ElMessage.success('已重开');
    await crud.load();
    await openFocusedTicket(); // 原 load() 收尾：深链工单号自动打开工作台
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重开失败');
  }
}

function goVisionMapping(row?: DisputeTicketDto | null) {
  if (!canAccessPath('/vision-mappings')) {
    ElMessage.warning('无识别映射访问权限');
    return;
  }
  const query: Record<string, string> = {};
  const cls = row?.detectedClasses?.[0];
  if (cls) query.keyword = cls;
  goPath('/vision-mappings', query);
}

async function playVideo(sessionId?: string) {
  videoLoading.value = true;
  try {
    await playSessionVideo(sessionId);
  } finally {
    videoLoading.value = false;
  }
}

function goDevice(id: string) {
  if (!canAccessPath('/devices')) {
    ElMessage.warning('无访问权限');
    return;
  }
  router.push(`/devices/${encodeURIComponent(id)}`);
}
function goSessions(device?: string, sessionId?: string) {
  const query: Record<string, string> = {};
  if (device) query.deviceId = device;
  if (sessionId) query.sessionId = sessionId;
  goPath('/sessions', query);
}
function goOrders(device?: string, orderId?: string) {
  const query: Record<string, string> = {};
  if (orderId) query.orderId = orderId;
  if (device) query.deviceId = device;
  goPath('/orders', query);
}
function goExceptions(device?: string) {
  const query: Record<string, string> = { status: 'OPEN' };
  if (device) query.deviceId = device;
  goPath('/exceptions', query);
}

function openDetail(row: DisputeTicketDto) {
  resolveFeedback.value = null;
  disputeSuggestHint.value = '';
  videoReviewed.value = false;
  noVideoAck.value = false;
  videoAttempted.value = false;
  clearEmbedVideo();
  selected.value = row;
  detailVisible.value = true;
  resetDraftFromSuggested();
  void ensureSkusLoaded();
  if (row.sessionId && (auth.hasPerm('ops:session:list') || auth.hasPerm('ops:session:upload'))) {
    void loadEmbedVideo(row.sessionId);
  }
}

function triggerDisputeImage() {
  disputeImageInput.value?.click();
}

function applyDisputeSuggestPreview(preview: DevRecognitionPreviewDto) {
  disputeSuggestHint.value = preview.hint || '未返回建议';
  if (!preview.items?.length || !selected.value) return;
  selected.value = {
    ...selected.value,
    suggestedItems: preview.items.map((i) => ({
      skuId: i.skuId,
      skuName: i.skuName,
      quantity: i.quantity,
      unitPriceCents: 0,
      lineAmountCents: 0
    }))
  };
  resetDraftFromSuggested();
}

async function onDisputeImagePick(ev: Event) {
  if (!selected.value?.deviceId) {
    ElMessage.warning('工单缺少设备 ID');
    return;
  }
  const input = ev.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  const check = validateImageFile(file);
  if (!check.ok) {
    ElMessage.warning(check.message);
    input.value = '';
    return;
  }
  suggestingDispute.value = true;
  disputeSuggestHint.value = '';
  try {
    const base =
      (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
    const form = new FormData();
    form.append('deviceId', selected.value.deviceId);
    form.append('image', file);
    const res = await authFetch(`${base}${AdminEndpoints.disputeSuggest}`, {
      method: 'POST',
      body: form
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok || json.code !== 0) {
      throw new Error(json.message || `请求失败 (${res.status})`);
    }
    applyDisputeSuggestPreview(json.data as DevRecognitionPreviewDto);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '智能识别建议失败');
  } finally {
    suggestingDispute.value = false;
    input.value = '';
  }
}

function patchResolvedListItem(ticketId: string, patched: DisputeTicketDto) {
  if (status.value === 'OPEN') {
    crud.items = crud.items.filter((t) => t.ticketId !== ticketId);
    crud.total = Math.max(0, crud.total - 1);
    return;
  }
  crud.items = crud.items.map((t) => (t.ticketId === ticketId ? patched : t));
}

function applyResolvedTicket(result: ResolveDisputeResultDto) {
  if (!selected.value) return;
  const ticketId = selected.value.ticketId;
  const patched: DisputeTicketDto = {
    ...selected.value,
    status: 'RESOLVED',
    resolvedAt: new Date().toISOString(),
    orderId: result.order?.orderId ?? selected.value.orderId,
    billedAmountCents: result.finalAmountCents ?? selected.value.billedAmountCents
  };
  selected.value = patched;
  patchResolvedListItem(ticketId, patched);
  resolveFeedback.value = { message: result.message || '争议已结案' };
}

function validateResolveVideoReview(): boolean {
  if (videoReviewed.value || noVideoAck.value) {
    if (!noVideoAck.value || videoReviewed.value) return true;
    ElMessage.warning('已确认无录像后，仍需勾选「已对照录像核对」表示人工已知情结案');
    return false;
  }
  ElMessage.warning(
    embedVideoUrl.value
      ? '请先观看录像并勾选「已对照录像核对」'
      : '请先勾选「无录像 / 无法播放，仍结案」，再勾选「已对照录像核对」后结案'
  );
  return false;
}

function resolveActionLabel(resolutionType: DisputeResolutionType): string {
  if (resolutionType === 'WAIVE') {
    return displayLabel('dispute_resolution', 'WAIVE', '免单并退款') + '（退回全部已扣余额）';
  }
  if (resolutionType === 'ADJUST') {
    return displayLabel('dispute_resolution', 'ADJUST', '按调整明细落账') + '（可能补扣或退差）';
  }
  if (resolutionType === 'CONFIRM') {
    return displayLabel('dispute_resolution', 'CONFIRM', '按识别清单结案');
  }
  return displayLabel('dispute_resolution', 'KEEP', '维持原账单');
}

async function confirmResolveAction(
  resolutionType: DisputeResolutionType,
  action: string
): Promise<boolean> {
  try {
    await ElMessageBox.confirm(
      `确认${action}？该操作会写入资金与审计记录。${noVideoAck.value ? '\n（已确认无录像仍结案）' : '\n（已确认对照录像）'}`,
      '确认争议处理',
      {
        type: resolutionType === 'WAIVE' ? 'warning' : 'info',
        confirmButtonText: '确认处理',
        cancelButtonText: '取消'
      }
    );
    return true;
  } catch (e: unknown) {
    if (isUserDismiss(e)) return false;
    ElMessage.error(errorMessage(e, '确认失败'));
    return false;
  }
}

async function promptRestoreInventoryOnWaive(): Promise<boolean | undefined> {
  try {
    await ElMessageBox.confirm(
      '免单库存处理：\n「退货退款」= 误识别/货仍在柜，回库\n「仅退款」= 顾客已拿走，不回库',
      '免单是否回库',
      {
        distinguishCancelAndClose: true,
        confirmButtonText: '退货退款（回库）',
        cancelButtonText: '仅退款（不回库）',
        type: 'warning'
      }
    );
    return true;
  } catch (error_) {
    if (error_ === 'cancel') return false;
    return undefined;
  }
}

function validateResolveDraftItems(resolutionType: DisputeResolutionType): boolean {
  if (
    (resolutionType !== 'ADJUST' && resolutionType !== 'CONFIRM') ||
    draftConfirmItems.value.length
  ) {
    return true;
  }
  ElMessage.warning('请先填写至少一行有效商品');
  return false;
}

async function collectWaiveRestoreInventory(): Promise<boolean | undefined> {
  const choice = await promptRestoreInventoryOnWaive();
  return choice;
}

async function submitDisputeResolve(
  resolutionType: DisputeResolutionType,
  restoreInventory?: boolean
) {
  if (!selected.value) return;
  const result = await api.request<ResolveDisputeResultDto>(
    AdminEndpoints.disputeResolve(selected.value.ticketId),
    'POST',
    buildDisputeResolveBody({
      resolutionType,
      restoreInventory,
      items:
        resolutionType === 'ADJUST' || resolutionType === 'CONFIRM'
          ? draftConfirmItems.value
          : undefined
    })
  );
  applyResolvedTicket(result);
  ElMessage.success(result.message || '争议已处理');
}

async function claimSelected() {
  if (!selected.value?.ticketId || claiming.value) return;
  claiming.value = true;
  try {
    const updated = await api.request<DisputeTicketDto>(
      AdminEndpoints.disputeClaim(selected.value.ticketId),
      'POST'
    );
    selected.value = updated;
    const idx = crud.items.findIndex((r) => r.ticketId === updated.ticketId);
    if (idx >= 0) crud.items[idx] = { ...crud.items[idx], ...updated };
    ElMessage.success(`已认领：${updated.assignee || '当前账号'}`);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '认领失败');
  } finally {
    claiming.value = false;
  }
}

async function resolveSelected(resolutionType: DisputeResolutionType) {
  if (!selected.value || resolving.value) return;
  if (!validateResolveVideoReview()) return;
  const action = resolveActionLabel(resolutionType);
  if (!validateResolveDraftItems(resolutionType)) return;
  if (!(await confirmResolveAction(resolutionType, action))) return;
  let restoreInventory: boolean | undefined;
  if (resolutionType === 'WAIVE') {
    const choice = await collectWaiveRestoreInventory();
    if (choice === undefined) return;
    restoreInventory = choice;
  }
  resolving.value = true;
  try {
    await submitDisputeResolve(resolutionType, restoreInventory);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '处理失败');
  } finally {
    resolving.value = false;
  }
}

function classifyKeyword(raw: string): { orderId?: string; sessionId?: string; deviceId?: string } {
  const v = raw.trim();
  if (!v) return {};
  if (/^O/i.test(v)) return { orderId: v };
  // 会话号：历史 S 前缀，或雪花数字（看板关联里常见）
  if (/^S/i.test(v) || /^\d{10,}$/.test(v)) return { sessionId: v };
  // 设备号多含字母/短横线（如 CAB-001）
  return { deviceId: v };
}

function appendDisputeRouteReviewCode(query: Record<string, string>) {
  if (
    categoryTab.value !== 'RECOGNITION' ||
    !reviewCodeTab.value ||
    reviewCodeTab.value === 'ALL'
  ) {
    return;
  }
  query.reviewCode = reviewCodeTab.value;
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (status.value) query.status = status.value;
  if (categoryTab.value && categoryTab.value !== 'ALL') query.category = categoryTab.value;
  appendDisputeRouteReviewCode(query);
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  // 看板深链工单号：tab 变更触发的 replace 不得冲掉，否则详情无法自动打开
  if (focusDisputeId.value) query.ticketId = focusDisputeId.value;
  router.replace({ query });
}

function onCategoryTab(name: string | number) {
  categoryTab.value = String(name);
  if (categoryTab.value !== 'RECOGNITION') reviewCodeTab.value = 'ALL';
  search();
}

function onReviewCodeTab() {
  search();
}

function appendDisputeListReviewCode(q: URLSearchParams) {
  if (
    categoryTab.value !== 'RECOGNITION' ||
    !reviewCodeTab.value ||
    reviewCodeTab.value === 'ALL'
  ) {
    return;
  }
  q.set('reviewCode', reviewCodeTab.value);
}

function appendDisputeListKeyword(q: URLSearchParams) {
  const classified = classifyKeyword(keyword.value);
  if (classified.sessionId) q.set('sessionId', classified.sessionId);
  if (classified.deviceId) q.set('deviceId', classified.deviceId);
  if (classified.orderId) q.set('orderId', classified.orderId);
}

function buildDisputeListQuery(page0: number, pageSize: number): URLSearchParams {
  // page 由 useCrudTable 传入，已是全站约定的 0 起
  const q = new URLSearchParams({
    page: String(page0),
    size: String(pageSize)
  });
  if (status.value) q.set('status', status.value);
  if (categoryTab.value && categoryTab.value !== 'ALL') {
    q.set('category', categoryTab.value);
  }
  appendDisputeListReviewCode(q);
  appendDisputeListKeyword(q);
  return q;
}

async function tryLoadTicketByNumericKeyword(raw: string): Promise<DisputeTicketDto | null> {
  if (!/^\d{10,}$/.test(raw)) return null;
  try {
    const byTicket = await api.request<DisputeTicketDto>(AdminEndpoints.dispute(raw), 'GET');
    return byTicket?.ticketId ? byTicket : null;
  } catch {
    return null;
  }
}

/** 检索：重置回第 1 页并同步路由；就绪后按工单号深链自动打开审单工作台（原 load() 收尾行为） */
async function search() {
  syncRouteQuery();
  await crud.search();
  await openFocusedTicket();
}

function reset() {
  // 与状态下拉「可清空 / 全部」一致：重置不清成 OPEN，避免已结案场景被打回空列表
  status.value = '';
  categoryTab.value = 'ALL';
  reviewCodeTab.value = 'ALL';
  keyword.value = '';
  return search();
}

/** 看板/深链带入筛选时，清掉本地粘住的分拣 tab，避免 MOCK 把 EMPTY 工单滤没 */
function hasInboundDeepLink() {
  return (
    typeof route.query.status === 'string' ||
    typeof route.query.sessionId === 'string' ||
    typeof route.query.ticketId === 'string' ||
    typeof route.query.disputeId === 'string' ||
    typeof route.query.keyword === 'string' ||
    typeof route.query.orderId === 'string' ||
    typeof route.query.deviceId === 'string' ||
    typeof route.query.category === 'string' ||
    typeof route.query.reviewCode === 'string'
  );
}

function syncDisputeStatusFromRoute(changed: boolean): boolean {
  const allowedStatus = new Set(['OPEN', 'RESOLVED', 'CLOSED']);
  if (typeof route.query.status !== 'string' || !route.query.status) return changed;
  const next = route.query.status.trim().toUpperCase();
  if (!allowedStatus.has(next) || next === status.value) return changed;
  status.value = next;
  return true;
}

function syncDisputeCategoryFromRoute(changed: boolean, inbound: boolean): boolean {
  if (typeof route.query.category === 'string') {
    const next = route.query.category || 'ALL';
    if (next === categoryTab.value) return changed;
    if (next === 'RECOGNITION') categoryTab.value = 'RECOGNITION';
    else if (next === 'ALL') categoryTab.value = 'ALL';
    else categoryTab.value = next;
    return true;
  }
  if (!inbound || categoryTab.value === 'ALL') return changed;
  categoryTab.value = 'ALL';
  return true;
}

function syncDisputeReviewCodeFromRoute(changed: boolean, inbound: boolean): boolean {
  if (typeof route.query.reviewCode === 'string') {
    const next = route.query.reviewCode || 'ALL';
    if (next === reviewCodeTab.value) return changed;
    reviewCodeTab.value = next;
    if (next !== 'ALL') categoryTab.value = 'RECOGNITION';
    return true;
  }
  if (!inbound || reviewCodeTab.value === 'ALL') return changed;
  reviewCodeTab.value = 'ALL';
  return true;
}

function resolveDisputeRouteKeyword(): string {
  if (typeof route.query.keyword === 'string') return route.query.keyword;
  if (typeof route.query.orderId === 'string') return route.query.orderId;
  if (typeof route.query.sessionId === 'string') return route.query.sessionId;
  if (typeof route.query.deviceId === 'string') return route.query.deviceId;
  return '';
}

function syncDisputeKeywordFromRoute(changed: boolean): boolean {
  const routeKeyword = resolveDisputeRouteKeyword();
  if (routeKeyword === keyword.value) return changed;
  keyword.value = routeKeyword;
  return true;
}

function resolveDisputeFocusId(): string {
  if (typeof route.query.ticketId === 'string') return route.query.ticketId;
  if (typeof route.query.disputeId === 'string') return route.query.disputeId;
  return '';
}

function syncDisputeFocusFromRoute(changed: boolean): boolean {
  const focusId = resolveDisputeFocusId();
  if (focusId === focusDisputeId.value) return changed;
  focusDisputeId.value = focusId;
  return true;
}

function applyRouteQuery() {
  let changed = false;
  const inbound = hasInboundDeepLink();
  changed = syncDisputeStatusFromRoute(changed);
  changed = syncDisputeCategoryFromRoute(changed, inbound);
  changed = syncDisputeReviewCodeFromRoute(changed, inbound);
  changed = syncDisputeKeywordFromRoute(changed);
  changed = syncDisputeFocusFromRoute(changed);
  return changed;
}

async function openFocusedTicket() {
  if (!focusDisputeId.value || detailVisible.value) return;
  let row = crud.items.find((it) => String(it.ticketId ?? '') === String(focusDisputeId.value));
  if (!row) {
    try {
      row = await api.request<DisputeTicketDto>(
        AdminEndpoints.dispute(focusDisputeId.value),
        'GET'
      );
    } catch {
      row = undefined;
    }
  }
  if (row) openDetail(row);
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  // 路由深链（如看板跳入）只落状态不回写 URL，避免 replace 抹掉入口参数形态
  await crud.search();
  await openFocusedTicket();
}

watch(
  () =>
    [
      route.query.status,
      route.query.category,
      route.query.reviewCode,
      route.query.keyword,
      route.query.sessionId,
      route.query.deviceId,
      route.query.orderId,
      route.query.ticketId,
      route.query.disputeId
    ] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onActivated(async () => {
  detailVisible.value = false;
  selected.value = null;
  resolveFeedback.value = null;
  clearEmbedVideo();
  // keep-alive 复用时必须按本次 query 重置分拣 tab，再拉列表
  applyRouteQuery();
  await crud.search();
  await openFocusedTicket();
});
onDeactivated(() => {
  detailVisible.value = false;
  selected.value = null;
  clearEmbedVideo();
});
onMounted(async () => {
  // crud 已配 autoLoad:false：首查需等路由查询参数落位（applyRouteQuery/syncRouteQuery 后）再显式触发
  applyRouteQuery();
  syncRouteQuery();
  await crud.load();
  await openFocusedTicket(); // 原 load() 收尾：深链工单号自动打开工作台
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
.page-card-head__actions {
  display: flex;
  gap: 8px;
}
.review-code-tabs {
  margin: 0 0 12px;
}
.detected-classes {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.reason-block {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.ticket-cell:hover strong {
  text-decoration: underline;
}
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  text-align: center;
  font: inherit;
}
.link-cell:hover {
  text-decoration: underline;
}
.link-cell.mono {
  font-family: inherit;
  font-size: inherit;
}
.muted {
  color: var(--el-text-color-secondary);
}
.amount-diff {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-table);
  line-height: 1.5;
}
.resolve-feedback {
  margin-bottom: 12px;
}
.status-tabs {
  margin: 0 0 10px;
}
.items-title {
  font-weight: 600;
  margin-bottom: 8px;
  font-size: var(--admin-font-size-table);
}
.ai-suggest-block {
  margin: 12px 0 4px;
}
.suggest-alert {
  margin: 0 0 10px;
}
.no-video-guide {
  margin: 8px 0;
}
.hidden-input {
  display: none;
}
.workbench-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.workbench-grid {
  display: grid;
  /* minmax(0,…)：避免抽屉被滚动条瞬间挤窄时，300/340 硬下限把侧栏压成单字列 */
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr);
  gap: 14px;
  align-items: start;
}
.workbench-media,
.workbench-meta,
.workbench-suggest,
.workbench-settle {
  min-width: 0;
}
.workbench-suggest,
.adjust-block {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: color-mix(in srgb, var(--el-fill-color-blank) 70%, transparent);
}
.video-wrap {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow: hidden;
  background: #0f172a;
}
.session-video {
  display: block;
  width: 100%;
  max-height: 280px;
  background: #0f172a;
}
.video-loading {
  padding: 36px 12px;
  text-align: center;
  color: var(--el-text-color-secondary);
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}
.manual-lines {
  display: grid;
  gap: 8px;
  margin-top: 8px;
}
.workbench-settle-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
@media (max-width: 900px) {
  .workbench-grid {
    grid-template-columns: 1fr;
  }
}
</style>
