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
          <span class="page-hint">设备编号 {{ deviceId }} · 资产投放与远程运维</span>
        </div>
      </template>
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </template>
    </el-page-header>

    <!--
      2026-10-16 改版（用户需求 + 图1 对照 2026-10-16 截图）：货柜基本信息 + 二维码
      常驻首屏（不进页签），其余区块收进下方页签，默认选中「在售商品」；
      重页签（将邑/温控/定价/流水/关联）lazy，切入才挂载。
    -->
    <div class="device-hero">
      <el-card class="page-card hero-info" shadow="never">
        <template #header>
          <div class="page-card-head">
            <div class="page-card-head__meta">
              <div class="page-card-head__title">
                <span class="title">设备概览</span>
                <span class="hint">运营指标 / 基础信息 / 投放位置 / 运行状态</span>
              </div>
            </div>
          </div>
        </template>

        <!--
          首屏信息顺序＝运营阅读顺序（CB-024）：指标条 → 温度操作 → 分组信息。
          原先 15 个信息格平铺在上、KPI 沉底，运营最关心的填充率/缺货要滚过整卡才看到。
        -->
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
                ? `缺货商品 ${metrics?.oosSlotCount ?? 0}`
                : `缺货商品 ${UI_COPY.loading}`
            "
          >
            <div class="stat-label">缺货商品</div>
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
                ? `低库存商品 ${metrics?.lowStockSlotCount ?? 0}`
                : `低库存商品 ${UI_COPY.loading}`
            "
          >
            <div class="stat-label">低库存商品</div>
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

        <!-- 温度下发从信息格里拿出来：它是操作不是属性，混在只读格子里既难找又误触 -->
        <div class="temp-action-bar">
          <span class="temp-bar-label">温度设置</span>
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
        </div>

        <div class="info-groups">
          <section class="info-group">
            <div class="info-group-title">基础信息</div>
            <div class="info-grid">
              <div class="info-item">
                <span class="info-label">设备编号</span>
                <!-- 2026-10-16 编号规则：编号本体即 EG+12 位（EG 由系统生成，非展示层拼接） -->
                <span class="info-value cell-id">{{ device?.deviceId || deviceId }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">投放场景</span>
                <span class="info-value">{{
                  device?.sceneType ? dictLabel('device_scene_type', device.sceneType) : '未分类'
                }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">所属仓库</span>
                <span class="info-value">{{ homeWarehouseLabel }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">柜机软件版本</span>
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
            </div>
          </section>

          <section class="info-group">
            <div class="info-group-title">投放位置</div>
            <div class="info-grid">
              <div class="info-item">
                <span class="info-label">商户</span>
                <span class="info-value">
                  <template v-if="metricsHydrated">
                    <strong v-if="device?.merchantName || device?.merchantId">{{
                      device.merchantName || device.merchantId
                    }}</strong>
                    <span v-else class="muted">无</span>
                    <small
                      v-if="device?.merchantName && device?.merchantId"
                      class="cell-id info-sub"
                      >{{ device.merchantId }}</small
                    >
                  </template>
                  <span v-else class="muted">{{ UI_COPY.loading }}</span>
                </span>
              </div>
              <div class="info-item">
                <span class="info-label">位置坐标</span>
                <span class="info-value cell-id">{{
                  asset.latitude != null && asset.longitude != null
                    ? `${asset.latitude}, ${asset.longitude}`
                    : '未采集'
                }}</span>
              </div>
              <div class="info-item info-item--full">
                <span class="info-label">投放地址</span>
                <!-- 整行 + 允许换行：长地址（省市区+街道门牌+门店名）不再被格子截断 -->
                <span class="info-value info-value--wrap">{{
                  metricsHydrated ? metrics?.address || '无' : UI_COPY.loading
                }}</span>
              </div>
            </div>
          </section>

          <section class="info-group">
            <div class="info-group-title">运行状态</div>
            <div class="info-grid">
              <div class="info-item">
                <span class="info-label">最近开门</span>
                <span class="info-value cell-id">{{
                  metricsHydrated ? device?.activeSessionId || '无' : UI_COPY.loading
                }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">开门状态</span>
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
              <!-- CB-049 扩展（2026-10-16 用户需求）：最近上线/离线时间、分配（绑定商户）时间 -->
              <div class="info-item">
                <span class="info-label">最近上线</span>
                <span class="info-value cell-datetime">{{
                  metricsHydrated ? formatDateTime(device?.lastOnlineAt) || '无' : UI_COPY.loading
                }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">最近离线</span>
                <span class="info-value cell-datetime">{{
                  metricsHydrated ? formatDateTime(device?.lastOfflineAt) || '无' : UI_COPY.loading
                }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">分配时间</span>
                <span class="info-value cell-datetime">{{
                  metricsHydrated ? formatDateTime(asset.deployedAt) || '未分配' : UI_COPY.loading
                }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">温度上报</span>
                <span class="info-value cell-datetime">{{
                  metricsHydrated
                    ? formatDateTime(metrics?.tempReportedAt) || '无'
                    : UI_COPY.loading
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
            </div>
          </section>

          <section class="info-group">
            <div class="info-group-title">售后与联系</div>
            <div class="info-grid">
              <div class="info-item">
                <span class="info-label">退款规则</span>
                <span class="info-value">
                  <el-tag
                    size="small"
                    :type="effectiveRefundPolicy === 'DISPUTE_ONLY' ? 'warning' : 'success'"
                  >
                    {{ policyLabel(effectiveRefundPolicy) }}
                  </el-tag>
                  <span class="inherit-hint">{{
                    device?.refundPolicy ? '本柜覆盖' : '跟随全局'
                  }}</span>
                </span>
              </div>
              <div class="info-item">
                <span class="info-label">告警联系人</span>
                <span class="info-value">{{
                  metricsHydrated
                    ? metrics?.alertContactName || metrics?.merchantContactName || '无'
                    : UI_COPY.loading
                }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">联系电话</span>
                <span class="info-value">{{
                  metricsHydrated
                    ? metrics?.alertContactPhone || metrics?.merchantContactPhone || '无'
                    : UI_COPY.loading
                }}</span>
              </div>
            </div>
          </section>
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

    <el-tabs v-model="tab" class="detail-tabs">
      <el-tab-pane label="在售商品" name="slots">
        <el-card class="page-card slots-pane-card" shadow="never">
          <div class="slot-toolbar">
            <el-button
              v-hasPermi="['ops:device:edit']"
              size="small"
              type="primary"
              @click="openInvCreate"
              >上架商品</el-button
            >
            <el-button size="small" :icon="Refresh" @click="loadDetail">刷新</el-button>
            <el-button
              v-if="canEditPricing"
              size="small"
              :loading="resettingPrice"
              :disabled="!skuCards.length"
              @click="onResetPricing"
              >重置价格</el-button
            >
          </div>
          <div class="slot-meta-bar">
            <span>
              已上架商品数：<strong>{{ listedSkuCount }}</strong
              >/{{ MAX_SKUS_PER_DEVICE }}
            </span>
            <span class="stockout-tip">
              缺货口径：当前库存 ≤ 低库存阈值（逐商品可在「编辑库存」调整），推送商户缺货通知。
            </span>
          </div>
          <!--
            CB-051（2026-10-16 用户拍板）：开门柜无「货道」概念——在售商品=商品卡片，
            数据源 device_sku_inventory（柜×商品库存），卡片上改价（ops:pricing:edit）、
            编辑库存/下架（ops:device:edit，SKU 级端点）；补货/盘点的 slot 记账仅保留在后端语义。
          -->
          <div v-if="slotsHydrated && skuCards.length" class="sku-cards">
            <div
              v-for="card in skuCards"
              :key="card.skuId"
              class="sku-card"
              :class="{ warn: card.stockoutWarn }"
            >
              <div class="sku-card-head">
                <span class="sku-card-brand">{{ card.brand || '未分类品牌' }}</span>
                <span v-if="card.stockoutWarn" class="sku-card-badge">缺货</span>
              </div>
              <div class="sku-card-top">
                <img
                  v-if="card.imageUrl"
                  :src="card.imageUrl"
                  class="sku-card-img"
                  loading="lazy"
                />
                <div v-else class="sku-card-img sku-card-img-empty">无图</div>
                <div class="sku-card-main">
                  <div class="sku-card-name">{{ card.skuName }}</div>
                  <div class="sku-card-price-row">
                    <template v-if="pricingRowFor(card.skuId)">
                      <template v-if="canEditPricing">
                        <el-input-number
                          :model-value="pricingRowFor(card.skuId)?.overridePriceCents ?? undefined"
                          :min="pricingRowFor(card.skuId)?.minPriceCents"
                          :max="pricingRowFor(card.skuId)?.maxPriceCents"
                          :precision="0"
                          :controls="false"
                          size="small"
                          class="sku-price-input"
                          placeholder="跟随基准"
                          :disabled="pricingSaving[card.skuId] === 'price'"
                          @change="(v: number | undefined) => onCardPriceChange(card.skuId, v)"
                        />
                        <span class="sku-price-base">
                          基准 {{ yuan(pricingRowFor(card.skuId)?.basePriceCents ?? 0) }}
                        </span>
                        <el-button
                          v-if="pricingRowFor(card.skuId)?.overridePriceCents != null"
                          link
                          size="small"
                          :loading="pricingSaving[card.skuId] === 'reset'"
                          @click="onCardPriceReset(card.skuId)"
                          >恢复基准</el-button
                        >
                      </template>
                      <span v-else class="sku-card-price">
                        {{ yuan(pricingRowFor(card.skuId)?.effectivePriceCents ?? 0) }}
                      </span>
                    </template>
                    <span v-else class="muted">价格加载中</span>
                  </div>
                </div>
              </div>
              <div class="sku-card-fields">
                <div>
                  <label>当前库存:</label><strong>{{ card.quantity }}</strong>
                </div>
                <div><label>上次配送后库存:</label>{{ card.lastRestockQty ?? '--' }}</div>
                <div><label>商品容量:</label>{{ card.capacity }}</div>
                <div><label>规格:</label>{{ card.spec || '--' }}</div>
                <div><label>品牌:</label>{{ card.brand || '--' }}</div>
                <div><label>商品ID:</label>{{ card.skuId }}</div>
                <div><label>条形码:</label>{{ card.barcode || '--' }}</div>
                <div>
                  <label>上架时间:</label
                  >{{ card.shelvedAt ? formatDateTime(card.shelvedAt) : '--' }}
                </div>
              </div>
              <div class="sku-card-actions">
                <el-button v-hasPermi="['ops:device:edit']" size="small" @click="openInvEdit(card)"
                  >编辑库存</el-button
                >
                <el-button
                  v-hasPermi="['ops:device:edit']"
                  size="small"
                  type="danger"
                  plain
                  @click="onRemoveInventory(card)"
                  >下架</el-button
                >
              </div>
            </div>
          </div>

          <el-empty
            v-if="slotsHydrated && !skuCards.length"
            description="暂无在售商品，点上方「上架商品」选品上架"
            :image-size="64"
          />

          <!-- CB-051：上架/编辑库存弹窗（SKU 级） -->
          <el-dialog
            v-model="invDialogVisible"
            :title="invMode === 'create' ? '上架商品' : '编辑库存'"
            width="440px"
          >
            <el-form label-width="90px">
              <el-form-item v-if="invMode === 'create'" label="商品">
                <el-select
                  v-model="invForm.skuId"
                  filterable
                  placeholder="选择要上架的商品"
                  style="width: 100%"
                >
                  <el-option
                    v-for="sku in availableSkus"
                    :key="sku.skuId"
                    :label="`${sku.skuName} (${sku.skuId})`"
                    :value="sku.skuId"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="当前库存">
                <el-input-number v-model="invForm.quantity" :min="0" style="width: 100%" />
              </el-form-item>
              <el-form-item label="商品容量">
                <el-input-number v-model="invForm.capacity" :min="0" style="width: 100%" />
              </el-form-item>
              <el-form-item label="低库存阈值">
                <el-input-number v-model="invForm.lowThreshold" :min="0" style="width: 100%" />
              </el-form-item>
            </el-form>
            <template #footer>
              <el-button @click="invDialogVisible = false">取消</el-button>
              <el-button type="primary" :loading="invSaving" @click="submitInventory"
                >保存</el-button
              >
            </template>
          </el-dialog>

          <div v-if="!slotsHydrated" class="muted">在售商品{{ UI_COPY.loading }}</div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="资产与投放" name="asset">
        <DeviceAssetDeploymentCard
          v-model:asset="asset"
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
          :warehouse-options="warehouseOptions"
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
      </el-tab-pane>

      <el-tab-pane label="远程运维" name="ops">
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
          v-model:policy="policy"
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
      </el-tab-pane>

      <!-- 将邑接入（CB-022 收尾）：未接入的普通柜机整页展示「未接入」说明，可忽略 -->
      <el-tab-pane label="将邑对接" name="jiangyi" lazy>
        <DeviceJiangyiCard :device-id="deviceId" :can-edit="canEditDevice" />
      </el-tab-pane>

      <el-tab-pane label="温控与环境" name="temp-env" lazy>
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

      <el-tab-pane label="单柜定价" name="pricing" lazy>
        <DevicePricingCard
          ref="pricingCardRef"
          :device-id="deviceId"
          :can-edit="canEditPricing"
          :active="tab === 'pricing'"
        />
      </el-tab-pane>

      <el-tab-pane label="投放流水" name="lifecycle" lazy>
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

      <el-tab-pane label="关联单据" name="related" lazy>
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
                p.sessionId
                  ? { deviceId: p.deviceId, sessionId: p.sessionId }
                  : { deviceId: p.deviceId }
              )
          "
          @open-orders="(p) => goPath('/orders', { deviceId: p.deviceId })"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- CB-028 弹窗规范：dialog-wide + 字段分组两列（与商品建档弹窗同款 form-group-title） -->
    <el-dialog
      v-model="editorVisible"
      :title="`编辑货道 ${editForm.slotCode}`"
      class="dialog-wide"
      :close-on-click-modal="false"
    >
      <el-form label-width="auto">
        <div class="form-group-title">陈列配置</div>
        <el-row :gutter="16">
          <el-col :span="24">
            <el-form-item label="商品">
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
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="目标陈列">
              <el-input-number v-model="editForm.parLevel" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="最低库存">
              <el-input-number v-model="editForm.minLevel" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="最大容量">
              <el-input-number v-model="editForm.maxLevel" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="displayLabel('enable_status', 'ACTIVE')">
              <el-switch v-model="editForm.enabled" />
            </el-form-item>
          </el-col>
        </el-row>
        <!-- 🔴 2026-09-24：整块按 canStocktake 隐藏。无 `ops:replenishment:edit` 时
             这三个字段点下去只会 403（后端 stocktake 要该权限），且「保存配置」也提交不了它们
             ⇒ 与其给一个改不生效的输入框，不如根本不渲染（连分组标题一起）。
             判据：后端 OpsReplenishmentController#stocktakeSlot 的 @RequiresPermissions。 -->
        <template v-if="canStocktake">
          <div class="form-group-title">现场盘点</div>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="账面库存">
                <span>{{ editForm.bookQty }}</span>
                <span v-if="editForm.hasDiscrepancy" class="slot-diff warn">
                  · 账实差异 {{ editForm.qtyDiff }}</span
                >
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="实盘数量">
                <el-input-number v-model="editForm.physicalQty" :min="0" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>
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

    <!-- 图1 对照（叮当小柜，2026-10-15）：商品排序——交换货道展示坐标，不改 SKU 绑定 -->
    <el-dialog v-model="sortDialogVisible" title="商品排序" width="520px">
      <div class="muted" style="margin-bottom: 8px">
        调整商品在货柜中的展示顺序（按陈列网格 行×列
        排布），保存时会交换货道的展示坐标，不改动商品绑定。
      </div>
      <div v-loading="sortSaving" class="sort-list">
        <div v-for="(row, idx) in sortRows" :key="row.slotCode" class="sort-row">
          <span class="sort-idx">{{ idx + 1 }}</span>
          <span class="sort-code">{{ row.slotCode }}</span>
          <span class="sort-name">{{ row.skuName || row.assignedSkuId || '未配置' }}</span>
          <span class="sort-actions">
            <el-button size="small" :disabled="idx === 0" @click="moveSortRow(idx, -1)"
              >上移</el-button
            >
            <el-button
              size="small"
              :disabled="idx === sortRows.length - 1"
              @click="moveSortRow(idx, 1)"
              >下移</el-button
            >
          </span>
        </div>
      </div>
      <template #footer>
        <el-button @click="sortDialogVisible = false">取消</el-button>
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="primary"
          :loading="sortSaving"
          @click="saveSort"
          >保存排序</el-button
        >
      </template>
    </el-dialog>

    <!-- 图1 对照：修改缺货阈值（商户级配置 merchant_ops_config.stockout_threshold_pct） -->
    <el-dialog v-model="thresholdDialogVisible" title="修改缺货阈值" width="440px">
      <el-form label-width="auto">
        <el-form-item label="缺货阈值（%）">
          <el-input-number v-model="thresholdDraft" :min="1" :max="100" />
        </el-form-item>
      </el-form>
      <div class="muted">
        当剩余库存 &lt;
        货柜容量的该比例时，库存状态标记为缺货并推送商户。该配置为商户级，影响该商户名下全部柜机。
      </div>
      <template #footer>
        <el-button @click="thresholdDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="thresholdSaving" @click="saveThreshold">保存</el-button>
      </template>
    </el-dialog>

    <!-- C3 商品模板：存当前陈列 -->
    <el-dialog v-model="saveTemplateVisible" title="存为陈列模板" width="420px">
      <el-form label-width="90px">
        <el-form-item label="模板名称">
          <el-input v-model="saveTemplateName" maxlength="64" placeholder="如：标准饮料柜-春" />
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          title="将保存当前所有已绑定商品的货道（货道×SKU×售价×目层库存）"
        />
      </el-form>
      <template #footer>
        <el-button @click="saveTemplateVisible = false">取消</el-button>
        <el-button type="primary" :loading="templateSaving" @click="doSaveTemplate"
          >保存模板</el-button
        >
      </template>
    </el-dialog>

    <!-- C3 商品模板：套用命名模板 -->
    <el-dialog v-model="applyTemplateVisible" title="套用陈列模板" width="480px">
      <el-form label-width="90px">
        <el-form-item label="选择模板">
          <el-select v-model="applyTemplateId" placeholder="选择模板" style="width: 100%">
            <el-option
              v-for="t in templateList"
              :key="t.templateId"
              :label="`${t.templateName}（${t.lineCount} 货道）`"
              :value="t.templateId"
            />
          </el-select>
        </el-form-item>
        <el-alert
          type="warning"
          :closable="false"
          title="将覆盖本设备同名货道的商品绑定与价格；缺失货道自动补骨架。请确认后继续。"
        />
      </el-form>
      <template #footer>
        <el-button @click="applyTemplateVisible = false">取消</el-button>
        <el-button type="primary" :loading="templateApplying" @click="doApplyTemplate"
          >套用</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { createLoadSeq } from '@/composables/createLoadSeq';
import { useRoute } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { errorMessage } from '@/utils/error-message';
import { softFallback } from '@/utils/soft-fallback';
import { dictLabel, displayLabel } from '@aicabinet/shared-dict';
import { api, authFetch, downloadAuthFile } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import DeviceAssetDeploymentCard from '@/components/device/DeviceAssetDeploymentCard.vue';
import DeviceJiangyiCard from '@/components/device/DeviceJiangyiCard.vue';
// V350（CB-037）：单柜定价——行内编辑覆盖价/启停/恢复基准，不走弹窗
import DevicePricingCard from '@/components/device/DevicePricingCard.vue';
import DeviceRelatedRecordsTab from '@/components/device/DeviceRelatedRecordsTab.vue';
import DeviceRemoteOpsCard from '@/components/device/DeviceRemoteOpsCard.vue';
import DeviceTempEnvTab from '@/components/device/DeviceTempEnvTab.vue';
import { useDeviceAsset } from '@/composables/device/useDeviceAsset';
import { useDeviceLifecycleActions } from '@/composables/device/useDeviceLifecycleActions';
import { useDevicePricing } from '@/composables/device/useDevicePricing';
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
  deviceType?: string;
  /** 投放场景（CB-049）：OFFICE/CAMPUS/...；空=未分类 */
  sceneType?: string;
  onlineStatus?: string;
  /** 最近上线时间（在线起点；CB-049 扩展展示字段） */
  lastOnlineAt?: string;
  /** 最近离线时间（CB-049 扩展展示字段） */
  lastOfflineAt?: string;
  merchantId?: string;
  merchantName?: string;
  activeSessionId?: string;
  activeSessionState?: string;
  /** 设备覆盖：AUTO_REFUND | DISPUTE_ONLY | null=继承全局 */
  refundPolicy?: string | null;
  /** 已解析的生效策略 */
  effectiveRefundPolicy?: string;
  homeWarehouseId?: string | null;
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
  merchantContactName?: string;
  merchantContactPhone?: string;
  lastRestockAt?: string;
  inventoryAccuracyPct?: number;
}

/** CB-051：柜×商品库存行（device_sku_inventory，开门柜库存口径） */
interface DeviceInventoryRow {
  skuId: string;
  quantity: number;
  capacity: number;
  lowThreshold: number;
  updatedAt?: string;
  /** 上次配送后库存快照（补货完成后写入；null/缺省=无补货记录） */
  lastRestockQty?: number | null;
}

interface DeviceDetail {
  device: DeviceRow;
  metrics: Metrics;
  slots: DeviceSlot[];
  skuInventory?: DeviceInventoryRow[];
}

const route = useRoute();
const auth = useAuthStore();
const { canAccessPath, goPath, router } = useNavAccess();
const deviceId = route.params.id as string;

const canEditSlots = computed(() => auth.hasPerm('ops:device:edit'));
// 单柜定价（V350，CB-037）：改价走独立权限点 ops:pricing:edit（资金敏感，与商户端对称）；
// 查看沿用面板口径 ops:device:list（能进详情页即可看，铁律 66）
const canEditPricing = computed(() => auth.hasPerm('ops:pricing:edit'));
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
// 默认停在「在售商品」：概览信息在第一个页签，这里先给运营最常改的陈列与库存
const tab = ref('slots');
// 🔴 页签懒加载 watch 不能放这里：immediate 回调引用的 pricingCardRef /
//    ensurePricingLoaded 都在其后声明（TDZ），immediate 立即执行必炸渲染。
//    整块移到 useDevicePricing 解构之后（见 ensurePricingLoaded 定义处）。
const pricingCardRef = ref<InstanceType<typeof DevicePricingCard> | null>(null);
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
  loadGeoStatus,
  warehouseOptions
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
      onlineStatus: row.onlineStatus || device.value.onlineStatus,
      homeWarehouseId: row.homeWarehouseId ?? device.value.homeWarehouseId
    };
  }
});
const homeWarehouseLabel = computed(() => {
  const id = asset.homeWarehouseId || device.value?.homeWarehouseId;
  if (!id) return '未归线';
  const w = warehouseOptions.value.find((item) => item.warehouseId === id);
  return w ? `${w.warehouseName || w.warehouseId}` : id;
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
const skuInventory = ref<DeviceInventoryRow[]>([]);
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

// ── C3 商品模板（铺货模板）：存当前陈列 / 套用命名模板 ──────────────
type SlotTemplateView = {
  templateId: number;
  templateName: string;
  createdAt?: string;
  lineCount: number;
};
const saveTemplateVisible = ref(false);
const saveTemplateName = ref('');
const templateSaving = ref(false);
const applyTemplateVisible = ref(false);
const applyTemplateId = ref<number | null>(null);
const templateList = ref<SlotTemplateView[]>([]);
const templateApplying = ref(false);

async function openSaveTemplateDialog() {
  saveTemplateName.value = '';
  saveTemplateVisible.value = true;
}

async function doSaveTemplate() {
  if (!saveTemplateName.value.trim()) {
    ElMessage.warning('请填写模板名称');
    return;
  }
  templateSaving.value = true;
  try {
    await api.request(AdminEndpoints.slotTemplateSaveFromDevice(), 'POST', {
      templateName: saveTemplateName.value.trim(),
      sourceDeviceId: deviceId
    });
    ElMessage.success('模板已保存');
    saveTemplateVisible.value = false;
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存模板失败'));
  } finally {
    templateSaving.value = false;
  }
}

async function openApplyTemplateDialog() {
  templateApplying.value = true;
  try {
    templateList.value = await api.request<SlotTemplateView[]>(
      AdminEndpoints.slotTemplates(),
      'GET'
    );
    if (!templateList.value.length) {
      ElMessage.info('暂无模板：先在配置好的设备上「存为陈列模板」');
      return;
    }
    applyTemplateId.value = templateList.value[0]?.templateId ?? null;
    applyTemplateVisible.value = true;
  } catch (e) {
    ElMessage.error(errorMessage(e, '模板列表加载失败'));
  } finally {
    templateApplying.value = false;
  }
}

async function doApplyTemplate() {
  if (!applyTemplateId.value) {
    ElMessage.warning('请选择模板');
    return;
  }
  templateApplying.value = true;
  try {
    const res = await api.request<{ applied: number }>(
      AdminEndpoints.slotTemplateApply(applyTemplateId.value, deviceId),
      'POST'
    );
    ElMessage.success(`已套用模板，生效 ${res.applied} 个货道`);
    applyTemplateVisible.value = false;
    await loadDetail();
  } catch (e) {
    ElMessage.error(errorMessage(e, '套用失败'));
  } finally {
    templateApplying.value = false;
  }
}
const skus = ref<SkuCatalog[]>([]);
const qrUrl = ref('');
const qrPreviewUrl = ref('');
const qrLoading = ref(true);
const qrHydrated = ref(false);
const qrDownloading = ref(false);
let qrObjectUrl: string | null = null;

// ===== 图1 对照（叮当小柜，2026-10-15）：卡片墙 / 单柜上限 / 缺货阈值 / 排序 / 重置价格 =====
// 2026-10-16 改版：陈列网格下线（用户需求），卡片即编辑入口——卡上改覆盖价 + 货道行内编辑。
const MAX_SKUS_PER_DEVICE = 50; // 与后端 DeviceSlotService.MAX_SKUS_PER_DEVICE 对齐
const listedSkuCount = computed(() => skuInventory.value.length);
const stockoutThresholdPct = ref(50);
const canEditMerchantConfig = computed(() => auth.hasPerm('ops:merchant:edit'));

/**
 * 卡片价格：复用单柜定价 composable（CB-037 覆盖价体系 + min/max 限价 + 乐观锁），
 * 与「单柜定价」页签同一数据源、同一保存路径，避免两套改价逻辑漂移。
 */
const {
  rows: pricingRows,
  saving: pricingSaving,
  load: loadPricingRows,
  ensureLoaded: ensurePricingLoaded,
  savePrice: savePricingRow,
  resetToBase: resetPricingRow
} = useDevicePricing({ deviceId, canEdit: canEditPricing });

// 页签懒加载（须在 pricingCardRef / ensurePricingLoaded 声明之后：
// immediate 回调 setup 期即执行，放前面会踩 TDZ —— 2026-10-16 设备详情白屏实锤）
watch(
  tab,
  (v) => {
    if (v === 'temp-env') {
      ensureTempEnvLoaded();
    }
    if (v === 'pricing') {
      pricingCardRef.value?.onActive(true);
    }
    // 卡片价格复用单柜定价数据（覆盖价/基准价/乐观锁同一口径）
    if (v === 'slots') {
      ensurePricingLoaded();
    }
  },
  { immediate: true }
);

function pricingRowFor(skuId: string) {
  return pricingRows.value.find((r) => r.skuId === skuId);
}

function yuan(cents: number): string {
  return `¥${(cents / 100).toFixed(2)}`;
}

async function onCardPriceChange(skuId: string, v: number | undefined) {
  const row = pricingRowFor(skuId);
  // 清空输入不等于恢复基准——恢复走显式「恢复基准」按钮（语义见 CB-037）
  if (!row || v == null) return;
  await savePricingRow(row, v);
}

async function onCardPriceReset(skuId: string) {
  const row = pricingRowFor(skuId);
  if (!row) return;
  await resetPricingRow(row);
}

interface SkuCard {
  skuId: string;
  skuName: string;
  imageUrl: string;
  brand: string;
  spec: string;
  barcode: string;
  quantity: number;
  capacity: number;
  lowThreshold: number;
  /** CB-051 收尾：上次配送后库存快照（补货完成后写入；null=无补货记录） */
  lastRestockQty: number | null;
  /** 上架时间：暂用商品建档时间（柜×SKU 绑定时戳后续随补货快照一并补） */
  shelvedAt?: string;
  stockoutWarn: boolean;
  priceCents: number | null;
}

/** CB-051：卡片=柜×商品库存行（device_sku_inventory），不再经货道聚合 */
const skuCards = computed<SkuCard[]>(() =>
  skuInventory.value
    .map((inv) => {
      const sku = skus.value.find((k) => k.skuId === inv.skuId);
      return {
        skuId: inv.skuId,
        skuName: sku?.skuName || inv.skuId,
        imageUrl: sku?.imageUrl || '',
        brand: sku?.brand || '',
        spec: sku?.spec || '',
        barcode: sku?.barcode || '',
        quantity: inv.quantity,
        capacity: inv.capacity,
        lowThreshold: inv.lowThreshold,
        lastRestockQty: inv.lastRestockQty ?? null,
        shelvedAt: sku?.createdAt,
        stockoutWarn: inv.quantity <= inv.lowThreshold,
        priceCents: pricingRowFor(inv.skuId)?.effectivePriceCents ?? null
      };
    })
    .sort((a, b) => (a.shelvedAt || '').localeCompare(b.shelvedAt || ''))
);

// ── 上架 / 编辑库存 / 下架（SKU 级，ops:device:edit） ──────────────────
const invDialogVisible = ref(false);
const invMode = ref<'create' | 'edit'>('create');
const invSaving = ref(false);
const invForm = reactive({ skuId: '', quantity: 0, capacity: 0, lowThreshold: 2 });

const availableSkus = computed(() =>
  skus.value.filter((k) => !skuInventory.value.some((i) => i.skuId === k.skuId))
);

function openInvCreate() {
  invMode.value = 'create';
  invForm.skuId = '';
  invForm.quantity = 0;
  invForm.capacity = 0;
  invForm.lowThreshold = 2;
  invDialogVisible.value = true;
}

function openInvEdit(card: SkuCard) {
  invMode.value = 'edit';
  invForm.skuId = card.skuId;
  invForm.quantity = card.quantity;
  invForm.capacity = card.capacity;
  invForm.lowThreshold = card.lowThreshold;
  invDialogVisible.value = true;
}

async function submitInventory() {
  if (invMode.value === 'create' && !invForm.skuId) {
    ElMessage.warning('请选择要上架的商品');
    return;
  }
  invSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceSkuInventoryPut(deviceId), 'PUT', {
      skuId: invForm.skuId,
      quantity: invForm.quantity,
      capacity: invForm.capacity,
      lowThreshold: invForm.lowThreshold
    });
    ElMessage.success('已保存');
    invDialogVisible.value = false;
    await loadDetail();
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存失败'));
  } finally {
    invSaving.value = false;
  }
}

async function onRemoveInventory(card: SkuCard) {
  try {
    await ElMessageBox.confirm(
      `确认下架「${card.skuName}」？下架后本柜不再售卖该商品。`,
      '下架确认',
      {
        type: 'warning',
        confirmButtonText: '确认下架',
        cancelButtonText: '取消'
      }
    );
  } catch {
    return;
  }
  try {
    await api.request(AdminEndpoints.deviceSkuInventoryDelete(deviceId, card.skuId), 'DELETE');
    ElMessage.success('已下架');
    await loadDetail();
  } catch (e) {
    ElMessage.error(errorMessage(e, '下架失败'));
  }
}

async function loadStockoutThreshold() {
  const mid = device.value?.merchantId;
  if (!mid) return;
  try {
    const cfg = await api.request<{ stockoutThresholdPct?: number }>(
      AdminEndpoints.merchantOpsConfig(mid)
    );
    if (cfg?.stockoutThresholdPct && cfg.stockoutThresholdPct > 0) {
      stockoutThresholdPct.value = Math.min(100, cfg.stockoutThresholdPct);
    }
  } catch {
    // 无配置记录走默认 50（与后端回落口径一致）
  }
}

const thresholdDialogVisible = ref(false);
const thresholdDraft = ref(50);
const thresholdSaving = ref(false);
function openThresholdDialog() {
  thresholdDraft.value = stockoutThresholdPct.value;
  thresholdDialogVisible.value = true;
}
async function saveThreshold() {
  const mid = device.value?.merchantId;
  if (!mid) return;
  thresholdSaving.value = true;
  try {
    // PUT 语义=整单保存：先取当前配置快照，仅改阈值字段回传
    const cfg = await api.request<Record<string, unknown>>(AdminEndpoints.merchantOpsConfig(mid));
    await api.request<Record<string, unknown>>(
      AdminEndpoints.merchantOpsConfig(mid),
      'PUT',
      Object.assign({}, cfg, { stockoutThresholdPct: thresholdDraft.value })
    );
    stockoutThresholdPct.value = thresholdDraft.value;
    thresholdDialogVisible.value = false;
    ElMessage.success('缺货阈值已更新');
    await refreshSlots();
  } finally {
    thresholdSaving.value = false;
  }
}

const resettingPrice = ref(false);
async function onResetPricing() {
  const n = listedSkuCount.value;
  try {
    await ElMessageBox.confirm(
      `将清除该柜全部 ${n} 个商品的覆盖价，恢复为商品库默认价。是否继续？`,
      '重置价格',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  resettingPrice.value = true;
  try {
    const count = await api.request<number>(AdminEndpoints.devicePricingReset(deviceId), 'POST');
    ElMessage.success(count > 0 ? `已重置 ${count} 个覆盖价` : '该柜没有覆盖价，无需重置');
    await loadPricingRows();
    await refreshSlots();
    pricingCardRef.value?.onActive(true);
  } finally {
    resettingPrice.value = false;
  }
}

const sortDialogVisible = ref(false);
const sortSaving = ref(false);
interface SortRow {
  slotCode: string;
  skuName?: string;
  assignedSkuId?: string;
  rowNo: number;
  colNo: number;
}
const sortRows = ref<SortRow[]>([]);
function openSortDialog() {
  sortRows.value = [...slots.value]
    .sort((a, b) => a.rowNo - b.rowNo || a.colNo - b.colNo || a.slotCode.localeCompare(b.slotCode))
    .map((s) => ({
      slotCode: s.slotCode,
      skuName: s.assignedSkuName || undefined,
      assignedSkuId: s.assignedSkuId || undefined,
      rowNo: s.rowNo,
      colNo: s.colNo
    }));
  sortDialogVisible.value = true;
}
function moveSortRow(idx: number, dir: -1 | 1) {
  const target = idx + dir;
  if (target < 0 || target >= sortRows.value.length) return;
  const rows = [...sortRows.value];
  [rows[idx], rows[target]] = [rows[target], rows[idx]];
  sortRows.value = rows;
}
async function saveSort() {
  sortSaving.value = true;
  try {
    // 坐标池：保持原网格形状（行×列集合不变），仅按新顺序重新分配坐标
    const pool = [...slots.value]
      .sort((a, b) => a.rowNo - b.rowNo || a.colNo - b.colNo)
      .map((s) => ({ rowNo: s.rowNo, colNo: s.colNo }));
    const body = sortRows.value.map((r, i) => ({
      slotCode: r.slotCode,
      rowNo: pool[i].rowNo,
      colNo: pool[i].colNo
    }));
    await api.request<DeviceSlot[]>(AdminEndpoints.deviceSlots(deviceId), 'PUT', body);
    sortDialogVisible.value = false;
    ElMessage.success('商品排序已保存');
    await refreshSlots();
  } finally {
    sortSaving.value = false;
  }
}

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

// 🔴 过期响应守卫：生命周期/温度环境等操作完成后的 loadDetail 与在途 loadDetail 并发时，
// 携带变更前数据的旧响应不得覆盖新详情（createLoadSeq 同款）。
const loadSeq = createLoadSeq();

async function loadDetail() {
  const seq = loadSeq.begin();
  const detail = await api.request<DeviceDetail>(AdminEndpoints.deviceDetail(deviceId), 'GET');
  // 过时响应丢弃：避免旧详情盖掉刚完成操作后的新状态
  if (!loadSeq.isCurrent(seq)) return;
  device.value = detail.device;
  syncRefundDraftFromDevice(detail.device?.refundPolicy);
  metrics.value = detail.metrics;
  slots.value = detail.slots || [];
  skuInventory.value = detail.skuInventory || [];
  slotsHydrated.value = true;
  syncTempDraftFromMetrics(detail.metrics?.targetTempC);
  void loadStockoutThreshold();
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
.detail-tabs {
  margin-top: 4px;
}
.detail-tabs :deep(.el-tabs__header) {
  margin: 4px 0 20px;
}
.detail-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
}
.detail-tabs :deep(.el-tabs__item) {
  font-size: var(--admin-font-size-title, 15px);
  color: var(--el-text-color-secondary);
}
.detail-tabs :deep(.el-tabs__item.is-active) {
  font-weight: 600;
  color: var(--app-primary, #0f766e);
}
.detail-tabs :deep(.el-tabs__active-bar) {
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
   窄屏回落单列。两卡等高：二维码卡拉伸填满右列，内部内容垂直居中，
   避免概览较高时右列下方露出一片空白。 */
.device-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
  align-items: stretch;
}
@media (max-width: 1280px) {
  .device-hero {
    grid-template-columns: minmax(0, 1fr);
  }
}
/* 关键信息按业务分组（CB-024）：组标题 + 标签在上、值在下的堆叠式格子 */
.info-groups {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.info-group-title {
  margin-bottom: 8px;
  font-size: var(--admin-font-size-sm);
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px 20px;
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
}
.kpi-grid .stat-tile {
  margin-bottom: 0;
}
/* 二维码卡拉伸到与左侧概览等高：card 与 body 都走纵向 flex，
   body 占满剩余高度，二维码 + 提示整体垂直居中 */
.hero-qr {
  display: flex;
  flex-direction: column;
}
.hero-qr :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.hero-qr .page-card-head {
  flex-wrap: wrap;
  row-gap: 8px;
}
.hero-qr .qr-tips {
  max-width: 100%;
}
/* 温度操作条：紧贴指标条下方，操作与「柜内温度」指标相邻 */
.temp-action-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 14px;
  margin-bottom: 14px;
}
.temp-bar-label {
  font-size: var(--admin-font-size-sm);
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
/* 目标温度输入框给稳定宽度，避免值为空时被压成一个「小方块」 */
.temp-action-bar :deep(.el-input-number) {
  width: 120px;
}
.qr-card .qr-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.qr-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
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
/* 弹窗分组标题（陈列配置/现场盘点），与商品建档弹窗同款（CB-028） */
.form-group-title {
  margin: 4px 0 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
}
.form-group-title:not(:first-child) {
  margin-top: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}
/* ===== 图1 对照（叮当小柜，2026-10-15）：上限/阈值提示条 + 商品卡片墙 + 排序列表 ===== */
.slot-meta-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: var(--admin-font-size-sm);
}
.slot-meta-bar .stockout-tip {
  color: var(--el-text-color-regular);
}
.sku-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 12px;
}
.sku-card {
  position: relative;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 10px;
  background: var(--el-fill-color-blank);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.sku-card.warn {
  border-color: var(--el-color-danger);
}
.sku-card-head {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
/* 卡片顶部：图 + 名称/价格 横排（2026-10-16 改版，卡片即编辑入口） */
.sku-card-top {
  width: 100%;
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.sku-card-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.sku-card-brand {
  font-size: var(--admin-font-size-sm);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-radius: 4px;
  padding: 1px 6px;
}
.sku-card-badge {
  font-size: var(--admin-font-size-sm);
  font-weight: 600;
  color: var(--el-color-danger);
}
.sku-card-img {
  width: 72px;
  height: 72px;
  object-fit: contain;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}
.sku-card-img-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-placeholder);
  font-size: var(--admin-font-size-sm);
}
.sku-card-name {
  font-weight: 600;
  overflow-wrap: anywhere;
}
.sku-card-price {
  color: var(--el-color-danger);
  font-weight: 600;
}
.sku-card-price-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: var(--admin-font-size-sm);
}
.sku-price-input {
  width: 96px;
}
.sku-price-base {
  color: var(--el-text-color-secondary);
}
.sku-card-slots {
  width: 100%;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.sku-card-slots-title {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
  overflow-wrap: anywhere;
}
.empty-slots {
  margin-top: 16px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--el-fill-color-lighter);
}
.empty-slots-title {
  font-size: var(--admin-font-size-sm);
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
/* SlotInlineRow 展开态在卡片内/空货道区都要有呼吸感（scoped 深度选择器） */
.sku-cards :deep(.slot-line-editor),
.empty-slots :deep(.slot-line-editor) {
  background: var(--el-fill-color-blank);
}
.sku-card-fields {
  width: 100%;
  font-size: var(--admin-font-size-sm);
  display: grid;
  grid-template-columns: 1fr;
  gap: 2px;
}
.sku-card-fields label {
  color: var(--el-text-color-secondary);
  margin-right: 6px;
}
.sort-list {
  max-height: 420px;
  overflow: auto;
}
.sort-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.sort-idx {
  width: 24px;
  color: var(--el-text-color-secondary);
  text-align: right;
}
.sort-code {
  width: 56px;
  font-weight: 600;
}
.sort-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sort-actions {
  flex-shrink: 0;
}
</style>
