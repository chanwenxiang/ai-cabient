<template>
  <div v-loading="loading" class="device-ops">
    <el-page-header @back="goPath('/devices')">
      <template #content>
        <div class="page-head-meta">
          <div class="page-title-row">
            <span class="page-title">{{ device?.deviceName || deviceId }}</span>
            <el-tag
              v-if="device"
              :type="device.onlineStatus === 'ONLINE' ? 'success' : 'info'"
              size="small"
            >
              {{ dictLabel('online_status', device.onlineStatus) }}
            </el-tag>
            <el-tag v-if="asset.lifecycleStatus" size="small" effect="plain">{{
              lifecycleLabel(asset.lifecycleStatus)
            }}</el-tag>
            <el-tag v-if="metrics?.salesLocked" type="danger" size="small">已锁机</el-tag>
          </div>
          <span class="page-hint">设备 ID {{ deviceId }} · 资产投放与远程运维</span>
        </div>
      </template>
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </template>
    </el-page-header>

    <!--
      首屏：左侧「设备概览」（关键信息 + 健康指标），右侧「柜机二维码」。
      二维码原先是首屏的整张卡片（200×200 + 提示撑满一屏），把商户/地址/版本全挤到折叠线以下；
      改为并排后首屏即可读完设备身份与健康度，二维码仍可预览/复制/下载。
    -->
    <div class="device-hero">
      <el-card class="page-card hero-info" shadow="never">
        <template #header>
          <div class="page-card-head">
            <div class="page-card-head__meta">
              <div class="page-card-head__title">
                <span class="title">设备概览</span>
                <span class="hint">商户 / 投放位置 / 版本号 / 告警联系人 / 会话与补货</span>
              </div>
            </div>
          </div>
        </template>

        <div class="info-grid">
          <div class="info-item">
            <span class="info-label">设备编号</span>
            <span class="info-value cell-id">{{ device?.deviceId || deviceId }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">商户</span>
            <span class="info-value">
              <template v-if="metricsHydrated">
                <strong v-if="device?.merchantName || device?.merchantId">{{
                  device.merchantName || device.merchantId
                }}</strong>
                <span v-else class="muted">无</span>
                <small v-if="device?.merchantName && device?.merchantId" class="cell-id info-sub">{{
                  device.merchantId
                }}</small>
              </template>
              <span v-else class="muted">{{ UI_COPY.loading }}</span>
            </span>
          </div>
          <div class="info-item">
            <span class="info-label">经纬度</span>
            <span class="info-value cell-id">{{
              asset.latitude != null && asset.longitude != null
                ? `${asset.latitude}, ${asset.longitude}`
                : '未采集'
            }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">App 版本</span>
            <span class="info-value">{{
              metricsHydrated ? metrics?.appVersion || '无' : UI_COPY.loading
            }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">固件版本</span>
            <span class="info-value">{{
              metricsHydrated ? metrics?.firmwareVersion || '无' : UI_COPY.loading
            }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">告警联系人</span>
            <span class="info-value">{{
              metricsHydrated ? metrics?.alertContactName || '无' : UI_COPY.loading
            }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">联系电话</span>
            <span class="info-value">{{
              metricsHydrated ? metrics?.alertContactPhone || '无' : UI_COPY.loading
            }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">最近会话</span>
            <span class="info-value cell-id">{{
              metricsHydrated ? device?.activeSessionId || '无' : UI_COPY.loading
            }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">会话状态</span>
            <span class="info-value">
              <template v-if="metricsHydrated">
                <el-tag v-if="device?.activeSessionState" size="small" effect="plain">{{
                  dictLabel('session_state', device.activeSessionState)
                }}</el-tag>
                <span v-else class="muted">暂无</span>
              </template>
              <span v-else class="muted">{{ UI_COPY.loading }}</span>
            </span>
          </div>
          <div class="info-item">
            <span class="info-label">最近补货</span>
            <span class="info-value cell-datetime">{{
              metricsHydrated ? formatDateTime(metrics?.lastRestockAt) || '无' : UI_COPY.loading
            }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">温度上报</span>
            <span class="info-value cell-datetime">{{
              metricsHydrated ? formatDateTime(metrics?.tempReportedAt) || '无' : UI_COPY.loading
            }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">库存准确率</span>
            <span class="info-value">
              {{
                metricsHydrated
                  ? metrics?.inventoryAccuracyPct != null
                    ? `${metrics.inventoryAccuracyPct}%`
                    : '无'
                  : UI_COPY.loading
              }}
            </span>
          </div>
          <div class="info-item">
            <span class="info-label">退款规则</span>
            <span class="info-value">
              <el-tag
                size="small"
                :type="effectiveRefundPolicy === 'DISPUTE_ONLY' ? 'warning' : 'success'"
              >
                {{ policyLabel(effectiveRefundPolicy) }}
              </el-tag>
              <span class="inherit-hint">{{ device?.refundPolicy ? '本柜覆盖' : '跟随全局' }}</span>
            </span>
          </div>
          <div class="info-item info-item--full">
            <span class="info-label">投放地址</span>
            <!-- 整行 + 允许换行：长地址（省市区+街道门牌+门店名）不再被格子截断 -->
            <span class="info-value info-value--wrap">{{
              metricsHydrated ? metrics?.address || '无' : UI_COPY.loading
            }}</span>
          </div>
          <div class="info-item info-item--full">
            <span class="info-label">目标温度</span>
            <span class="info-value">
              <span class="temp-set-row">
                <el-input-number
                  v-model="tempDraft"
                  :min="-30"
                  :max="30"
                  :step="1"
                  size="small"
                  controls-position="right"
                />
                <span class="muted">°C</span>
                <el-button
                  v-hasPermi="['ops:device:edit']"
                  type="primary"
                  size="small"
                  plain
                  :loading="cmdLoading === 'SET_TEMP'"
                  @click="setTargetTemp"
                  >下发温度</el-button
                >
                <span class="muted"
                  >柜内当前
                  {{
                    metricsHydrated
                      ? metrics?.currentTempC != null
                        ? `${metrics.currentTempC}°C`
                        : '无'
                      : UI_COPY.loading
                  }}</span
                >
              </span>
            </span>
          </div>
        </div>

        <div class="kpi-grid">
          <div
            class="stat-tile"
            role="group"
            :aria-label="
              metricsHydrated ? `填充率 ${metrics?.fillRatePct ?? 0}%` : `填充率 ${UI_COPY.loading}`
            "
          >
            <div class="stat-label">填充率</div>
            <div class="stat-value">
              {{ metricsHydrated ? `${metrics?.fillRatePct ?? 0}%` : '暂无' }}
            </div>
            <div v-if="!metricsHydrated" class="stat-hint">{{ UI_COPY.loading }}</div>
          </div>
          <div
            class="stat-tile"
            role="group"
            :class="{ warn: metricsHydrated && (metrics?.oosSlotCount || 0) > 0 }"
            :aria-label="
              metricsHydrated
                ? `缺货货道 ${metrics?.oosSlotCount ?? 0}`
                : `缺货货道 ${UI_COPY.loading}`
            "
          >
            <div class="stat-label">缺货货道</div>
            <div class="stat-value">
              {{ metricsHydrated ? (metrics?.oosSlotCount ?? 0) : '暂无' }}
            </div>
            <div v-if="!metricsHydrated" class="stat-hint">{{ UI_COPY.loading }}</div>
          </div>
          <div
            class="stat-tile"
            role="group"
            :class="{ warn: metricsHydrated && (metrics?.lowStockSlotCount || 0) > 0 }"
            :aria-label="
              metricsHydrated
                ? `低库存货道 ${metrics?.lowStockSlotCount ?? 0}`
                : `低库存货道 ${UI_COPY.loading}`
            "
          >
            <div class="stat-label">低库存货道</div>
            <div class="stat-value">
              {{ metricsHydrated ? (metrics?.lowStockSlotCount ?? 0) : '暂无' }}
            </div>
            <div v-if="!metricsHydrated" class="stat-hint">{{ UI_COPY.loading }}</div>
          </div>
          <div
            class="stat-tile"
            role="group"
            :class="{ warn: metricsHydrated && (metrics?.nearExpiryLotCount || 0) > 0 }"
            :aria-label="
              metricsHydrated
                ? `临期批次 ${metrics?.nearExpiryLotCount ?? 0}`
                : `临期批次 ${UI_COPY.loading}`
            "
          >
            <div class="stat-label">临期批次</div>
            <div class="stat-value">
              {{ metricsHydrated ? (metrics?.nearExpiryLotCount ?? 0) : '暂无' }}
            </div>
            <div v-if="!metricsHydrated" class="stat-hint">{{ UI_COPY.loading }}</div>
          </div>
          <div
            class="stat-tile"
            role="group"
            :aria-label="
              metricsHydrated
                ? `柜内温度 ${metrics?.currentTempC != null ? metrics.currentTempC + '°C' : '无'}`
                : `柜内温度 ${UI_COPY.loading}`
            "
          >
            <div class="stat-label">柜内温度</div>
            <div class="stat-value">
              {{
                metricsHydrated
                  ? metrics?.currentTempC != null
                    ? `${metrics.currentTempC}°C`
                    : '无'
                  : '暂无'
              }}
            </div>
            <div v-if="!metricsHydrated" class="stat-hint">{{ UI_COPY.loading }}</div>
          </div>
        </div>
      </el-card>

      <el-card class="page-card hero-qr qr-card" shadow="never">
        <template #header>
          <div class="page-card-head">
            <div class="page-card-head__meta">
              <div class="page-card-head__title">
                <span class="title">柜机二维码</span>
              </div>
            </div>
            <div class="qr-actions">
              <el-button size="small" :loading="qrLoading" @click="loadQr">刷新</el-button>
              <el-button size="small" :disabled="!qrUrl" @click="copyQrLink">复制链接</el-button>
              <el-button
                type="primary"
                size="small"
                :disabled="!qrUrl"
                :loading="qrDownloading"
                @click="downloadQr"
                >下载 PNG</el-button
              >
            </div>
          </div>
        </template>
        <div class="qr-body">
          <div v-if="qrPreviewUrl" class="qr-preview">
            <img :src="qrPreviewUrl" alt="柜机二维码" />
          </div>
          <div v-else class="qr-empty" :class="{ 'is-loading': !qrHydrated || qrLoading }">
            <template v-if="!qrHydrated || qrLoading">
              <div class="qr-skeleton" aria-hidden="true" />
              <span class="qr-empty-text">二维码{{ UI_COPY.loading }}</span>
            </template>
            <template v-else>
              <div class="qr-empty-icon" aria-hidden="true">▦</div>
              <span class="qr-empty-text">暂无二维码</span>
              <span class="qr-empty-hint">生成或刷新后可在此预览并下载</span>
            </template>
          </div>
          <div class="qr-tips">
            <p>消费者微信扫码即可开门购物；打印后贴于柜门显眼位置。</p>
            <p>链接变更或柜机换码后，请重新下载打印；也可用「复制链接」发给现场同事。</p>
          </div>
        </div>
      </el-card>
    </div>

    <DeviceAssetDeploymentCard
      :asset="asset"
      :can-edit-device="canEditDevice"
      :can-regenerate-device-id="canRegenerateDeviceId"
      :asset-saving="assetSaving"
      :geo-configured="geoConfigured"
      :hardware-reset-loading="hardwareResetLoading"
      :regenerate-id-loading="regenerateIdLoading"
      :life-loading="lifeLoading"
      :bind-dialog-visible="bindDialogVisible"
      :bind-merchant-id="bindMerchantId"
      :bind-merchants-loading="bindMerchantsLoading"
      :bind-merchant-options="bindMerchantOptions"
      :lifecycle-label="lifecycleLabel"
      :can-lifecycle="canLifecycle"
      :lifecycle-disabled-reason="lifecycleDisabledReason"
      @save="saveAsset"
      @reset-hardware="resetHardwareBinding"
      @regenerate-id="regenerateDeviceId"
      @open-bind="openBindDialog"
      @run-lifecycle="runLifecycle"
      @confirm-bind="confirmBindMerchant"
      @update:bind-dialog-visible="bindDialogVisible = $event"
      @update:bind-merchant-id="bindMerchantId = $event"
    />

    <DeviceRemoteOpsCard
      :can-edit-device="canEditDevice"
      :can-access-replenishment="canAccessPath('/replenishment')"
      :can-access-repair-tickets="canAccessPath('/repair-tickets')"
      :cmd-loading="cmdLoading"
      :sales-locked="!!metrics?.salesLocked"
      :oos-slot-count="metrics?.oosSlotCount || 0"
      :refund-policy-draft="refundPolicyDraft"
      :refund-policy-saving="refundPolicySaving"
      :global-refund-policy="globalRefundPolicy"
      :effective-refund-policy="effectiveRefundPolicy"
      :refund-draft-hint="refundDraftHint"
      :refund-priority-hint="refundPriorityHint"
      :device-refund-policy="device?.refundPolicy"
      :policy="policy"
      :repair-tickets="repairTickets"
      :repair-hydrated="repairHydrated"
      :policy-label="policyLabel"
      :repair-status-label="repairStatusLabel"
      :priority-label="priorityLabel"
      @send-command="sendCommand"
      @go-replenish="goReplenish"
      @go-plan-replenish="goPlanReplenish"
      @go-restock-tasks="goRestockTasks"
      @go-repair-list="goPath('/repair-tickets', { deviceId })"
      @create-repair="createRepair"
      @save-refund-policy="saveRefundPolicy"
      @save-policy="savePolicy"
      @update:refund-policy-draft="refundPolicyDraft = $event"
    />

    <el-card class="page-card report-page" shadow="never">
      <el-tabs v-model="tab">
        <!--
          「概览」页签已并入首屏「设备概览」卡片：同一批字段原先在两处各写一遍，
          既重复又占一个页签位置。本区保留的四个页签都是「数据量大、需要时才展开」的内容。
        -->
        <el-tab-pane label="温控与环境" name="temp-env">
          <DeviceTempEnvTab
            :can-edit-temp-plan="canEditTempPlan"
            :temp-plan-enabled="tempPlanEnabled"
            :temp-plan-entries="tempPlanEntries"
            :temp-plan-saving="tempPlanSaving"
            :env-rows="envRows"
            :env-type-label="envTypeLabel"
            :env-unit="envUnit"
            @update:temp-plan-enabled="tempPlanEnabled = $event"
            @add-entry="addTempPlanEntry"
            @remove-entry="(i) => tempPlanEntries.splice(i, 1)"
            @save="saveTempPlan"
            @apply="applyTempPlanNow"
            @refresh-env="loadEnvReadings"
          />
        </el-tab-pane>

        <el-tab-pane label="货道陈列" name="slots">
          <div class="slot-toolbar">
            <el-button
              v-hasPermi="['ops:device:edit']"
              type="primary"
              size="small"
              :loading="applying"
              @click="applyTemplate"
              >套用模板</el-button
            >
            <el-button size="small" :icon="Refresh" :loading="slotsRefreshing" @click="refreshSlots"
              >刷新货道</el-button
            >
          </div>
          <SlotGrid
            v-if="slotsHydrated && slots.length"
            :slots="slots"
            :editable="canEditSlots"
            @edit="openEditor"
          />
          <el-empty v-else-if="slotsHydrated" description="暂无货道配置" :image-size="64" />
          <div v-else class="muted">货道{{ UI_COPY.loading }}</div>
        </el-tab-pane>

        <el-tab-pane label="投放流水" name="lifecycle">
          <div v-loading="!lifecycleHydrated" class="lifecycle-pane">
            <el-timeline v-if="lifecycleEvents.length">
              <el-timeline-item
                v-for="ev in lifecycleEvents"
                :key="ev.eventId"
                :timestamp="formatDateTime(ev.createdAt)"
                placement="top"
              >
                <div class="life-event">
                  <strong>{{ lifecycleActionLabel(ev.action) }}</strong>
                  <span class="muted"
                    >{{ lifecycleLabel(ev.fromStatus) }} → {{ lifecycleLabel(ev.toStatus) }}</span
                  >
                  <div v-if="ev.remark" class="life-remark">{{ ev.remark }}</div>
                </div>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else-if="lifecycleHydrated" description="暂无生命周期流水" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="关联单据" name="related">
          <DeviceRelatedRecordsTab
            :device-id="deviceId"
            :hydrated="relatedHydrated"
            :sessions="sessions"
            :orders="orders"
            :can-access-sessions="canAccessPath('/sessions')"
            :can-access-orders="canAccessPath('/orders')"
            @open-sessions="
              (p) =>
                goPath(
                  '/sessions',
                  p.sessionId ? { deviceId: p.deviceId, sessionId: p.sessionId } : { deviceId: p.deviceId }
                )
            "
            @open-orders="(p) => goPath('/orders', { deviceId: p.deviceId })"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="editorVisible" :title="`编辑货道 ${editForm.slotCode}`">
      <el-form label-width="auto">
        <el-form-item label="SKU">
          <el-select
            v-model="editForm.assignedSkuId"
            filterable
            clearable
            placeholder="选择商品"
            style="width: 100%"
          >
            <el-option
              v-for="s in skus"
              :key="s.skuId"
              :label="`${s.skuName} (${s.skuId})`"
              :value="s.skuId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标陈列"
          ><el-input-number v-model="editForm.parLevel" :min="0"
        /></el-form-item>
        <el-form-item label="最低库存"
          ><el-input-number v-model="editForm.minLevel" :min="0"
        /></el-form-item>
        <el-form-item label="最大容量"
          ><el-input-number v-model="editForm.maxLevel" :min="0"
        /></el-form-item>
        <el-form-item :label="displayLabel('enable_status', 'ACTIVE')"
          ><el-switch v-model="editForm.enabled"
        /></el-form-item>
        <!-- 🔴 2026-09-24：整块按 canStocktake 隐藏。无 `ops:replenishment:edit` 时
             这三个字段点下去只会 403（后端 stocktake 要该权限），且「保存配置」也提交不了它们
             ⇒ 与其给一个改不生效的输入框，不如根本不渲染（连分割线一起）。
             判据：后端 OpsReplenishmentController#stocktakeSlot 的 @RequiresPermissions。 -->
        <template v-if="canStocktake">
          <el-divider content-position="left">现场盘点</el-divider>
          <el-form-item label="账面库存">
            <span>{{ editForm.bookQty }}</span>
            <span v-if="editForm.hasDiscrepancy" class="slot-diff warn">
              · 账实差异 {{ editForm.qtyDiff }}</span
            >
          </el-form-item>
          <el-form-item label="实盘数量">
            <el-input-number v-model="editForm.physicalQty" :min="0" />
          </el-form-item>
          <el-form-item label="调账面">
            <el-checkbox v-model="editForm.adjustBookQty">按实盘回写该货道批次库存</el-checkbox>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <!-- 权限只由 canStocktake 一处表达（= ops:replenishment:edit），不再叠 v-hasPermi，
             避免「同一权限两条通道」再次各自漂移。 -->
        <el-button v-if="canStocktake" :loading="stocktaking" @click="stocktakeSlot"
          >仅记实盘</el-button
        >
        <el-button
          v-if="canStocktake"
          type="warning"
          :loading="stocktaking"
          @click="stocktakeAndAdjust"
        >
          按实盘调账面
        </el-button>
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="primary"
          :loading="saving"
          @click="saveSlot"
          >保存配置</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { errorMessage } from '@/utils/error-message';
import { softFallback } from '@/utils/soft-fallback';
import { dictLabel, displayLabel } from '@aicabinet/shared-dict';
import { api, authFetch, downloadAuthFile } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import SlotGrid from '@/components/SlotGrid.vue';
import DeviceAssetDeploymentCard from '@/components/device/DeviceAssetDeploymentCard.vue';
import DeviceRelatedRecordsTab from '@/components/device/DeviceRelatedRecordsTab.vue';
import DeviceRemoteOpsCard from '@/components/device/DeviceRemoteOpsCard.vue';
import DeviceTempEnvTab from '@/components/device/DeviceTempEnvTab.vue';
import { useDeviceAsset } from '@/composables/device/useDeviceAsset';
import { useDeviceLifecycleActions } from '@/composables/device/useDeviceLifecycleActions';
import { useDeviceRelatedRecords } from '@/composables/device/useDeviceRelatedRecords';
import { useDeviceRemoteOps } from '@/composables/device/useDeviceRemoteOps';
import { useDeviceSlotActions } from '@/composables/device/useDeviceSlotActions';
import { useDeviceTempEnv } from '@/composables/device/useDeviceTempEnv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useAuthStore } from '@/stores/auth';
import type { DeviceSlot, SkuCatalog } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

interface DeviceRow {
  deviceId: string;
  deviceName?: string;
  onlineStatus?: string;
  merchantId?: string;
  merchantName?: string;
  activeSessionId?: string;
  activeSessionState?: string;
  /** 设备覆盖：AUTO_REFUND | DISPUTE_ONLY | null=继承全局 */
  refundPolicy?: string | null;
  /** 已解析的生效策略 */
  effectiveRefundPolicy?: string;
}

interface LifecycleEventRow {
  eventId: number;
  deviceId?: string;
  fromStatus?: string;
  toStatus?: string;
  action?: string;
  operatorId?: number;
  remark?: string;
  createdAt?: string;
}

interface Metrics {
  fillRatePct?: number;
  oosSlotCount?: number;
  lowStockSlotCount?: number;
  nearExpiryLotCount?: number;
  currentTempC?: number | null;
  targetTempC?: number | null;
  tempReportedAt?: string;
  address?: string;
  salesLocked?: boolean;
  appVersion?: string;
  firmwareVersion?: string;
  alertContactName?: string;
  alertContactPhone?: string;
  lastRestockAt?: string;
  inventoryAccuracyPct?: number;
}

interface DeviceDetail {
  device: DeviceRow;
  metrics: Metrics;
  slots: DeviceSlot[];
}

const route = useRoute();
const auth = useAuthStore();
const { canAccessPath, goPath, router } = useNavAccess();
const deviceId = route.params.id as string;

const canEditSlots = computed(() => auth.hasPerm('ops:device:edit'));
const canEditDevice = computed(() => auth.hasPerm('ops:device:edit'));
const canEditTempPlan = computed(() => auth.hasPerm('ops:device:edit'));
/**
 * 盘点 / 调账的权限口径**必须与后端一致**：`OpsReplenishmentController.stocktakeSlot`
 * 要的是 `ops:replenishment:edit`，而模板里两个实盘按钮原先用 `ops:device:edit` 判显隐 ——
 * 有 device:edit 没有 replenishment:edit 的账号会「按钮可见、点了 403」。
 */
const canStocktake = computed(() => auth.hasPerm('ops:replenishment:edit'));
const {
  sessions,
  orders,
  sessionTotal,
  orderTotal,
  relatedHydrated,
  loadRelated,
  markRelatedHydrated
} = useDeviceRelatedRecords({ deviceId });
const loading = ref(true);
const metricsHydrated = ref(false);
const lifecycleHydrated = ref(false);
const slotsHydrated = ref(false);
const cmdLoading = ref('');
const {
  tempPlanEnabled,
  tempPlanEntries,
  tempPlanSaving,
  envRows,
  tempDraft,
  addTempPlanEntry,
  saveTempPlan,
  applyTempPlanNow,
  loadEnvReadings,
  envTypeLabel,
  envUnit,
  syncTempDraftFromMetrics,
  setTargetTemp,
  ensureTempEnvLoaded
} = useDeviceTempEnv({
  deviceId,
  cmdLoading,
  loadDetail: () => loadDetail(),
  canEditTempPlan
});
// 默认停在「货道陈列」：概览信息已提到首屏，这里先给运营最常查的陈列与库存
const tab = ref('slots');

watch(tab, (v) => {
  if (v === 'temp-env') {
    ensureTempEnvLoaded();
  }
});
const device = ref<DeviceRow | null>(null);
const metrics = ref<Metrics | null>(null);
const {
  policy,
  refundPolicyDraft,
  refundPolicySaving,
  globalRefundPolicy,
  effectiveRefundPolicy,
  refundDraftHint,
  refundPriorityHint,
  policyLabel,
  repairTickets,
  repairHydrated,
  syncRefundDraftFromDevice,
  loadGlobalRefundPolicy,
  loadPolicy,
  loadRepairTickets,
  markRepairHydrated,
  repairStatusLabel,
  priorityLabel,
  createRepair,
  saveRefundPolicy,
  savePolicy,
  sendCommand
} = useDeviceRemoteOps({
  deviceId,
  device,
  canEditDevice,
  cmdLoading,
  loadDetail: () => loadDetail()
});
const {
  asset,
  assetSaving,
  geoConfigured,
  fillAsset,
  loadAsset,
  saveAsset,
  loadGeoStatus
} = useDeviceAsset({
  deviceId,
  canEditDevice,
  onDeviceSynced: (row) => {
    if (!device.value) return;
    device.value = {
      ...device.value,
      merchantId: row.merchantId || device.value.merchantId,
      merchantName: row.merchantName || device.value.merchantName,
      deviceName: row.deviceName || device.value.deviceName,
      onlineStatus: row.onlineStatus || device.value.onlineStatus
    };
  }
});
const canRegenerateDeviceId = computed(
  () =>
    canEditDevice.value &&
    asset.lifecycleStatus === 'INBOUND' &&
    relatedHydrated.value &&
    sessionTotal.value === 0 &&
    orderTotal.value === 0
);
const lifecycleEvents = ref<LifecycleEventRow[]>([]);
const slots = ref<DeviceSlot[]>([]);
const {
  applying,
  slotsRefreshing,
  saving,
  stocktaking,
  editorVisible,
  editForm,
  applyTemplate,
  refreshSlots,
  openEditor,
  stocktakeSlot,
  stocktakeAndAdjust,
  saveSlot
} = useDeviceSlotActions({
  deviceId,
  slots,
  canEditSlots,
  canStocktake,
  loadDetail: () => loadDetail()
});
const skus = ref<SkuCatalog[]>([]);
const qrUrl = ref('');
const qrPreviewUrl = ref('');
const qrLoading = ref(true);
const qrHydrated = ref(false);
const qrDownloading = ref(false);
let qrObjectUrl: string | null = null;

function lifecycleLabel(status?: string | null) {
  return displayLabel('device_lifecycle', status || 'DEPLOYED', '未知状态');
}

function lifecycleActionLabel(action?: string | null) {
  return displayLabel('device_lifecycle_action', action, '未知');
}

function revokeQrPreview() {
  if (qrObjectUrl) {
    URL.revokeObjectURL(qrObjectUrl);
    qrObjectUrl = null;
  }
  qrPreviewUrl.value = '';
}

async function loadQr() {
  qrLoading.value = true;
  try {
    const link = await api.request<{ deviceId: string; url: string }>(
      AdminEndpoints.deviceQrLink(deviceId),
      'GET'
    );
    qrUrl.value = link.url || '';
    revokeQrPreview();
    const res = await authFetch(
      `${(import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin}${AdminEndpoints.deviceQrPng(deviceId)}`
    );
    if (!res.ok) throw new Error('二维码图片加载失败');
    const blob = await res.blob();
    qrObjectUrl = URL.createObjectURL(blob);
    qrPreviewUrl.value = qrObjectUrl;
  } catch (e) {
    qrUrl.value = '';
    revokeQrPreview();
    ElMessage.error(errorMessage(e, '加载二维码失败'));
  } finally {
    qrHydrated.value = true;
    qrLoading.value = false;
  }
}

async function copyQrLink() {
  if (!qrUrl.value) return;
  try {
    await navigator.clipboard.writeText(qrUrl.value);
    ElMessage.success('已复制链接');
  } catch {
    ElMessage.error('复制失败，请手动选中链接');
  }
}

async function downloadQr() {
  qrDownloading.value = true;
  try {
    await downloadAuthFile(AdminEndpoints.deviceQrPng(deviceId), `${deviceId}-qr.png`);
  } catch (e) {
    ElMessage.error(errorMessage(e, '下载失败'));
  } finally {
    qrDownloading.value = false;
  }
}

async function loadLifecycleEvents() {
  try {
    lifecycleEvents.value = await softFallback(
      api.request<LifecycleEventRow[]>(AdminEndpoints.deviceLifecycleEvents(deviceId), 'GET'),
      [],
      '生命周期流水'
    );
  } finally {
    lifecycleHydrated.value = true;
  }
}

async function loadDetail() {
  const detail = await api.request<DeviceDetail>(AdminEndpoints.deviceDetail(deviceId), 'GET');
  device.value = detail.device;
  syncRefundDraftFromDevice(detail.device?.refundPolicy);
  metrics.value = detail.metrics;
  slots.value = detail.slots || [];
  slotsHydrated.value = true;
  syncTempDraftFromMetrics(detail.metrics?.targetTempC);
  await Promise.all([
    loadAsset(),
    loadLifecycleEvents(),
    loadRepairTickets(),
    loadGlobalRefundPolicy()
  ]);
  await loadPolicy(!!detail.metrics?.salesLocked);
}

const {
  lifeLoading,
  bindDialogVisible,
  bindMerchantId,
  bindMerchantsLoading,
  bindMerchantOptions,
  hardwareResetLoading,
  regenerateIdLoading,
  canLifecycle,
  lifecycleDisabledReason,
  openBindDialog,
  confirmBindMerchant,
  runLifecycle,
  resetHardwareBinding,
  regenerateDeviceId
} = useDeviceLifecycleActions({
  deviceId,
  asset,
  device,
  canEditDevice,
  canRegenerateDeviceId,
  lifecycleActionLabel,
  fillAsset,
  loadDetail,
  loadLifecycleEvents,
  router
});

async function loadSkus() {
  skus.value =
    (
      await softFallback(
        api.request<{ items: SkuCatalog[] }>(AdminEndpoints.skusCatalogPage, 'GET'),
        { items: [] as SkuCatalog[] },
        '商品目录'
      )
    ).items || [];
}

async function reload() {
  loading.value = true;
  // 软刷新：保留已渲染 KPI/货道/关联表，避免 keep-alive 回页或点刷新时闪「—」/空表
  try {
    await Promise.all([loadDetail(), loadQr()]);
    metricsHydrated.value = true;
    await Promise.all([loadRelated(), loadSkus()]);
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载失败'));
    metricsHydrated.value = true;
    slotsHydrated.value = true;
    markRepairHydrated();
    markRelatedHydrated();
    lifecycleHydrated.value = true;
    qrHydrated.value = true;
  } finally {
    loading.value = false;
  }
}

function goReplenish() {
  goPath('/replenishment', { tab: 'shortage', deviceId });
}

function goPlanReplenish() {
  goPath('/replenishment', { tab: 'shortage', plan: '1', deviceId, deviceIds: deviceId });
}

function goRestockTasks() {
  goPath('/replenishment', { tab: 'routes', deviceId });
}

onMounted(async () => {
  loading.value = true;
  try {
    await Promise.all([loadDetail(), loadGeoStatus(), loadQr()]);
    metricsHydrated.value = true;
    await Promise.all([loadRelated(), loadSkus()]);
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载失败'));
    metricsHydrated.value = true;
    slotsHydrated.value = true;
    markRepairHydrated();
    markRelatedHydrated();
    lifecycleHydrated.value = true;
  } finally {
    loading.value = false;
  }
});

onActivated(() => {
  void reload();
});
</script>

<style scoped>
.muted {
  color: var(--el-text-color-placeholder);
  font-size: var(--admin-font-size-table);
}
.device-ops {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.device-ops > .el-page-header {
  margin-bottom: 8px;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.report-page {
  margin-top: 4px;
}
.report-page :deep(.el-tabs__header) {
  margin: 4px 0 20px;
}
.report-page :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
}
.report-page :deep(.el-tabs__item) {
  font-size: var(--admin-font-size-title, 15px);
  color: var(--el-text-color-secondary);
}
.report-page :deep(.el-tabs__item.is-active) {
  font-weight: 600;
  color: var(--app-primary, #0f766e);
}
.report-page :deep(.el-tabs__active-bar) {
  height: 3px;
  border-radius: 2px;
  background-color: var(--app-primary, #0f766e);
}
.page-head-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.page-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.page-title {
  font-weight: 600;
  font-size: var(--admin-font-size-title);
}
.page-hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
/* ── 首屏：左「设备概览」（信息 + 指标），右「柜机二维码」 ────────────────
   窄屏回落单列；二维码卡片跟随内容高度，不拉伸。 */
.device-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
  align-items: start;
}
@media (max-width: 1280px) {
  .device-hero {
    grid-template-columns: minmax(0, 1fr);
  }
}
/* 关键信息：标签在上、值在下（堆叠式），避免 el-descriptions 半宽格把长地址截断 */
.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px 20px;
  margin-bottom: 16px;
}
.info-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.info-item--full {
  grid-column: 1 / -1;
}
.info-label {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
.info-value {
  min-width: 0;
  font-size: var(--admin-font-size-table);
  line-height: 1.5;
  /* 地址 / 会话号等长文本在格内断行：既不溢出到邻格，也不被省略号吃掉 */
  overflow-wrap: anywhere;
}
.info-value--wrap {
  white-space: normal;
}
.info-sub {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
}
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(104px, 1fr));
  gap: 8px;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.kpi-grid .stat-tile {
  margin-bottom: 0;
}
.hero-qr .page-card-head {
  flex-wrap: wrap;
  row-gap: 8px;
}
.hero-qr .qr-tips {
  max-width: 100%;
}
/* 目标温度输入框给稳定宽度，避免值为空时被压成一个「小方块」 */
.temp-set-row :deep(.el-input-number) {
  width: 120px;
}
.qr-card .qr-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.qr-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  text-align: center;
  padding: 8px 0 4px;
  /* A01′：首屏/弱网时避免卡片相对内容区塌陷过矮 */
  min-height: 240px;
}
.qr-preview {
  width: 200px;
  height: 200px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}
.qr-preview img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}
.qr-empty {
  width: 200px;
  height: 200px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  box-sizing: border-box;
  padding: 16px;
}
.qr-empty.is-loading {
  border-style: solid;
  border-color: var(--el-border-color-lighter);
}
.qr-skeleton {
  width: 120px;
  height: 120px;
  border-radius: 8px;
  background: linear-gradient(
    90deg,
    var(--el-fill-color) 25%,
    var(--el-fill-color-dark) 37%,
    var(--el-fill-color) 63%
  );
  background-size: 400% 100%;
  animation: qr-shimmer 1.2s ease infinite;
}
@keyframes qr-shimmer {
  0% {
    background-position: 100% 0;
  }
  100% {
    background-position: 0 0;
  }
}
.qr-empty-icon {
  font-size: var(--admin-font-size-hero-lg);
  line-height: 1;
  opacity: 0.45;
}
.qr-empty-text {
  font-size: var(--admin-font-size-table);
  color: var(--el-text-color-regular);
}
.qr-empty-hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  text-align: center;
  line-height: 1.4;
}
.qr-tips {
  max-width: 420px;
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-table);
  line-height: 1.6;
}
.qr-tips p {
  margin: 0 0 6px;
}
.qr-tips p:last-child {
  margin-bottom: 0;
}
.stat-tile {
  display: block;
  width: 100%;
  text-align: center;
  font: inherit;
  color: inherit;
  border: none;
  cursor: default;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 8px;
}
.stat-tile.warn {
  background: color-mix(in srgb, var(--el-color-warning) 12%, var(--layout-card, #fff));
}
.stat-label {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
.stat-value {
  font-size: var(--admin-font-size-display-md);
  font-weight: 600;
  margin-top: 4px;
  font-variant-numeric: tabular-nums;
}
.stat-hint {
  margin-top: 2px;
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
.slot-diff.warn {
  color: #c2410c;
  margin-left: 6px;
  font-size: var(--admin-font-size-sm);
}
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
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
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.inherit-hint {
  margin-left: 8px;
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
.temp-set-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.life-event {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.life-remark {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-regular);
}
.lifecycle-pane {
  min-height: 120px;
}
.slot-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
</style>
