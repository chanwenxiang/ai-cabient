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

    <el-card class="page-card qr-card" shadow="never">
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
        <div v-else class="qr-empty">
          {{ qrHydrated ? (qrLoading ? '加载中…' : '暂无二维码') : '加载中…' }}
        </div>
        <div class="qr-meta">
          <div class="qr-url mono">{{ qrUrl || '—' }}</div>
        </div>
      </div>
    </el-card>

    <el-row :gutter="12" class="stat-row">
      <el-col :xs="12" :sm="6" :md="4">
        <button
          type="button"
          class="stat-tile"
          :aria-label="
            metricsHydrated ? `填充率 ${metrics?.fillRatePct ?? 0}%` : '填充率 — 加载中…'
          "
        >
          <div class="stat-label">填充率</div>
          <div class="stat-value">
            {{ metricsHydrated ? `${metrics?.fillRatePct ?? 0}%` : '—' }}
          </div>
          <div v-if="!metricsHydrated" class="stat-hint">加载中…</div>
        </button>
      </el-col>
      <el-col :xs="12" :sm="6" :md="4">
        <button
          type="button"
          class="stat-tile"
          :class="{ warn: metricsHydrated && (metrics?.oosSlotCount || 0) > 0 }"
          :aria-label="
            metricsHydrated ? `缺货货道 ${metrics?.oosSlotCount ?? 0}` : '缺货货道 — 加载中…'
          "
        >
          <div class="stat-label">缺货货道</div>
          <div class="stat-value">{{ metricsHydrated ? (metrics?.oosSlotCount ?? 0) : '—' }}</div>
          <div v-if="!metricsHydrated" class="stat-hint">加载中…</div>
        </button>
      </el-col>
      <el-col :xs="12" :sm="6" :md="4">
        <button
          type="button"
          class="stat-tile"
          :class="{ warn: metricsHydrated && (metrics?.lowStockSlotCount || 0) > 0 }"
          :aria-label="
            metricsHydrated
              ? `低库存货道 ${metrics?.lowStockSlotCount ?? 0}`
              : '低库存货道 — 加载中…'
          "
        >
          <div class="stat-label">低库存货道</div>
          <div class="stat-value">
            {{ metricsHydrated ? (metrics?.lowStockSlotCount ?? 0) : '—' }}
          </div>
          <div v-if="!metricsHydrated" class="stat-hint">加载中…</div>
        </button>
      </el-col>
      <el-col :xs="12" :sm="6" :md="4">
        <button
          type="button"
          class="stat-tile"
          :class="{ warn: metricsHydrated && (metrics?.nearExpiryLotCount || 0) > 0 }"
          :aria-label="
            metricsHydrated ? `临期批次 ${metrics?.nearExpiryLotCount ?? 0}` : '临期批次 — 加载中…'
          "
        >
          <div class="stat-label">临期批次</div>
          <div class="stat-value">
            {{ metricsHydrated ? (metrics?.nearExpiryLotCount ?? 0) : '—' }}
          </div>
          <div v-if="!metricsHydrated" class="stat-hint">加载中…</div>
        </button>
      </el-col>
      <el-col :xs="12" :sm="6" :md="4">
        <button
          type="button"
          class="stat-tile"
          :aria-label="
            metricsHydrated
              ? `柜内温度 ${metrics?.currentTempC != null ? metrics.currentTempC + '°C' : '无'}`
              : '柜内温度 — 加载中…'
          "
        >
          <div class="stat-label">柜内温度</div>
          <div class="stat-value">
            {{
              metricsHydrated
                ? metrics?.currentTempC != null
                  ? `${metrics.currentTempC}°C`
                  : '无'
                : '—'
            }}
          </div>
          <div v-if="!metricsHydrated" class="stat-hint">加载中…</div>
        </button>
      </el-col>
    </el-row>

    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title">资产与投放</span>
              <span class="hint">IMEI / 合作方式 / 路线与生命周期流转</span>
            </div>
          </div>
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="primary"
            size="small"
            :loading="assetSaving"
            @click="saveAsset"
            >保存资产</el-button
          >
        </div>
      </template>
      <el-form label-width="100px" class="asset-form" @submit.prevent>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="IMEI">
              <el-input
                v-model="asset.imei"
                :disabled="!canEditDevice"
                clearable
                placeholder="主板/通信识别"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="资产方">
              <el-input
                v-model="asset.assetOwner"
                :disabled="!canEditDevice"
                clearable
                placeholder="自营/加盟商名"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="合作方式">
              <el-select
                v-model="asset.coopMode"
                :disabled="!canEditDevice"
                clearable
                placeholder="选择"
                style="width: 100%"
              >
                <el-option
                  v-for="item in dictOptions('device_coop_mode')"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="开门押金(分)">
              <el-input-number
                v-model="asset.depositCents"
                :disabled="!canEditDevice"
                :min="0"
                :step="100"
                controls-position="right"
                style="width: 100%"
              />
              <div class="field-hint">
                &gt;0 时作为该柜开门预授权冻结额；否则用系统配置 checkout.preauth_cents（默认 2000）
              </div>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="流量费(分/月)">
              <el-input-number
                v-model="asset.dataFeeCents"
                :disabled="!canEditDevice"
                :min="0"
                :step="100"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="路线编码">
              <el-select
                v-model="asset.routeCode"
                :disabled="!canEditDevice"
                clearable
                filterable
                allow-create
                default-first-option
                placeholder="选择或输入路线"
                style="width: 100%"
              >
                <el-option
                  v-for="item in dictOptions('route_code')"
                  :key="item.value"
                  :label="`${item.label}（${item.value}）`"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="运营标签">
              <el-input
                v-model="asset.opsTags"
                :disabled="!canEditDevice"
                clearable
                placeholder="逗号分隔"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="纬度">
              <el-input-number
                v-model="asset.latitude"
                :disabled="!canEditDevice"
                :controls="false"
                :precision="6"
                :step="0.0001"
                style="width: 100%"
                placeholder="如 31.230400"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="经度">
              <el-input-number
                v-model="asset.longitude"
                :disabled="!canEditDevice"
                :controls="false"
                :precision="6"
                :step="0.0001"
                style="width: 100%"
                placeholder="如 121.473700"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="16">
            <el-form-item label="投放地址">
              <div class="address-row">
                <el-input
                  v-model="asset.address"
                  :disabled="!canEditDevice"
                  clearable
                  placeholder="门店/点位地址"
                />
                <el-button
                  v-hasPermi="['ops:device:edit']"
                  :disabled="!canEditDevice || !asset.address?.trim() || !geoConfigured"
                  :loading="geoLoading"
                  :title="geoConfigured ? '调用高德解析经纬度' : '未配置 AMAP_WEB_KEY'"
                  @click="resolveAddress"
                  >解析坐标</el-button
                >
              </div>
              <div v-if="!geoConfigured" class="field-hint">
                未配置 AMAP_WEB_KEY，地址解析不可用
              </div>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="生命周期">
              <el-tag size="small">{{ lifecycleLabel(asset.lifecycleStatus) }}</el-tag>
              <span v-if="asset.deployedAt" class="muted asset-deployed"
                >投放 {{ formatDateTime(asset.deployedAt) }}</span
              >
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="24" :md="16">
            <el-form-item label="备注">
              <el-input
                v-model="asset.lifecycleRemark"
                :disabled="!canEditDevice"
                clearable
                placeholder="投放/退役备注"
              />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <div class="cmd-section-label">生命周期操作</div>
      <div class="cmd-bar">
        <el-button
          v-hasPermi="['ops:device:edit']"
          :loading="lifeLoading === 'BIND'"
          @click="lifecycleBind"
          >绑定商户</el-button
        >
        <el-button
          v-hasPermi="['ops:device:edit']"
          :loading="lifeLoading === 'UNBIND'"
          @click="runLifecycle('UNBIND')"
          >解绑</el-button
        >
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="primary"
          plain
          :loading="lifeLoading === 'DEPLOY'"
          @click="runLifecycle('DEPLOY')"
          >投放</el-button
        >
        <el-button
          v-hasPermi="['ops:device:edit']"
          plain
          :loading="lifeLoading === 'UNDEPLOY'"
          @click="runLifecycle('UNDEPLOY')"
          >撤回未投放</el-button
        >
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="warning"
          plain
          :loading="lifeLoading === 'RETURN'"
          @click="runLifecycle('RETURN')"
          >返厂</el-button
        >
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="danger"
          plain
          :loading="lifeLoading === 'RETIRE'"
          @click="runLifecycle('RETIRE', true)"
          >退役</el-button
        >
        <el-button
          v-hasPermi="['ops:device:edit']"
          plain
          :loading="lifeLoading === 'INBOUND'"
          @click="runLifecycle('INBOUND')"
          >入库</el-button
        >
      </div>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="page-card-head">
          <div class="page-card-head__meta">
            <div class="page-card-head__title">
              <span class="title">远程运维</span>
              <span class="hint">运维指令与补货开门是两条链路，请勿混用</span>
            </div>
          </div>
        </div>
      </template>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="open-door-alert"
        title="开门请分清场景"
        description="「运维远程开门」：应急/检修，会创建运维会话（开门记录可筛「运维」），关门后不结算；不绑定补货任务。现场补货请用「补货调度 → 补货开门」或商户小程序（需先签到）。锁机停售时也可运维开门检修。"
      />
      <div class="cmd-section-label">运维指令</div>
      <div class="cmd-bar">
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="primary"
          :loading="cmdLoading === 'OPEN_DOOR'"
          @click="sendCommand('OPEN_DOOR')"
          >运维远程开门</el-button
        >
        <el-button
          v-if="!metrics?.salesLocked"
          v-hasPermi="['ops:device:edit']"
          type="warning"
          :loading="cmdLoading === 'LOCK'"
          @click="sendCommand('LOCK')"
          >锁机停售</el-button
        >
        <el-button
          v-else
          v-hasPermi="['ops:device:edit']"
          type="success"
          :loading="cmdLoading === 'UNLOCK'"
          @click="sendCommand('UNLOCK')"
          >解锁营业</el-button
        >
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="danger"
          plain
          :loading="cmdLoading === 'REBOOT'"
          @click="sendCommand('REBOOT')"
          >重启设备</el-button
        >
      </div>

      <div class="cmd-section-label">柜机策略锁</div>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="policy-lock-alert"
        title="营业锁机与「锁机停售」同源"
        description="打开营业锁机或禁售，会同步下发边端锁机；关闭营业锁机会解除边端锁并清除禁售。勿与运维按钮各改一套。"
      />
      <el-form v-if="policy" label-width="120px" class="policy-form" @submit.prevent>
        <el-form-item label="营业锁机">
          <el-switch
            v-model="policy.salesLocked"
            :disabled="!canEditDevice"
            @change="() => savePolicy()"
          />
        </el-form-item>
        <el-form-item label="价格锁">
          <el-switch
            v-model="policy.priceLocked"
            :disabled="!canEditDevice"
            @change="() => savePolicy()"
          />
        </el-form-item>
        <el-form-item label="禁改 SKU">
          <el-switch
            v-model="policy.skuEditForbidden"
            :disabled="!canEditDevice"
            @change="() => savePolicy()"
          />
        </el-form-item>
        <el-form-item label="禁售">
          <el-switch
            v-model="policy.saleForbidden"
            :disabled="!canEditDevice"
            @change="() => savePolicy()"
          />
          <div class="field-hint">
            禁售会同时营业锁机；停售期间仍可签到后补货开门（不产生消费者账单）
          </div>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="metrics?.salesLocked"
        type="warning"
        :closable="false"
        show-icon
        class="lock-restock-hint"
        title="当前已锁机停售：消费者无法开门；补货请走「补货调度 → 签到 → 补货开门」，或使用上方「运维远程开门」检修。"
      />

      <div class="cmd-section-label">补货入口</div>
      <div class="cmd-bar">
        <el-button v-if="canAccessPath('/replenishment')" @click="goReplenish">缺货建议</el-button>
        <el-button
          v-if="canAccessPath('/replenishment') && (metrics?.oosSlotCount || 0) > 0"
          v-hasPermi="['ops:replenishment:edit']"
          type="primary"
          @click="goPlanReplenish"
        >
          一键规划补货
        </el-button>
        <el-button
          v-if="canAccessPath('/replenishment')"
          type="success"
          plain
          @click="goRestockTasks"
        >
          补货调度 / 补货开门
        </el-button>
        <span v-else class="muted">无补货调度权限</span>
      </div>

      <div class="cmd-section-label">维修工单</div>
      <div class="cmd-bar">
        <el-button
          v-if="canAccessPath('/repair-tickets')"
          @click="goPath('/repair-tickets', { deviceId })"
          >工单列表</el-button
        >
        <el-button v-hasPermi="['ops:repair:edit']" type="primary" plain @click="createRepair"
          >新建工单</el-button
        >
      </div>
      <el-table
        v-if="repairTickets.length"
        :data="repairTickets"
        size="small"
        class="repair-mini-table"
      >
        <el-table-column prop="ticketId" label="#" width="70" align="center" />
        <el-table-column
          prop="title"
          label="标题"
          min-width="140"
          show-overflow-tooltip
          align="center"
        />
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">{{ repairStatusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建" width="160" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      <div v-else class="muted">{{ repairHydrated ? '暂无最近工单' : '加载中…' }}</div>
    </el-card>

    <el-card class="page-card report-page" shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane label="概览" name="overview">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="设备编号">
              <span class="cell-id">{{ device?.deviceId || deviceId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="商户">
              <div class="name-cell inline">
                <strong>{{
                  metricsHydrated ? device?.merchantName || device?.merchantId || '无' : '—'
                }}</strong>
                <small
                  v-if="metricsHydrated && device?.merchantId && device?.merchantName"
                  class="cell-id"
                  >{{ device.merchantId }}</small
                >
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="地址">{{
              metricsHydrated ? metrics?.address || '无' : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="App 版本">{{
              metricsHydrated ? metrics?.appVersion || '无' : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="固件版本">{{
              metricsHydrated ? metrics?.firmwareVersion || '无' : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="目标温度">
              <div class="temp-set-row">
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
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="温度上报">
              <span class="cell-datetime">{{
                metricsHydrated ? formatDateTime(metrics?.tempReportedAt) || '无' : '—'
              }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="告警联系人">{{
              metricsHydrated ? metrics?.alertContactName || '无' : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="联系电话">{{
              metricsHydrated ? metrics?.alertContactPhone || '无' : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="最近会话">
              <span class="cell-id">{{
                metricsHydrated ? device?.activeSessionId || '无' : '—'
              }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="会话状态">
              <template v-if="metricsHydrated">
                <el-tag v-if="device?.activeSessionState" size="small" effect="plain">
                  {{ dictLabel('session_state', device.activeSessionState) }}
                </el-tag>
                <span v-else>-</span>
              </template>
              <span v-else>—</span>
            </el-descriptions-item>
            <el-descriptions-item label="最近补货">
              <span class="cell-datetime">{{
                metricsHydrated ? formatDateTime(metrics?.lastRestockAt) || '无' : '—'
              }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="库存准确率">
              {{
                metricsHydrated
                  ? metrics?.inventoryAccuracyPct != null
                    ? `${metrics.inventoryAccuracyPct}%`
                    : '无'
                  : '—'
              }}
            </el-descriptions-item>
          </el-descriptions>
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
            <el-button size="small" :icon="Refresh" @click="loadDetail">刷新货道</el-button>
          </div>
          <SlotGrid
            v-if="slotsHydrated && slots.length"
            :slots="slots"
            :editable="canEditSlots"
            @edit="openEditor"
          />
          <el-empty v-else-if="slotsHydrated" description="暂无货道配置" :image-size="64" />
          <div v-else class="muted">货道加载中…</div>
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
          <h4 class="section-title">最近开门记录</h4>
          <el-table
            v-loading="!relatedHydrated"
            :data="sessions"
            stripe
            border
            size="small"
            class="report-table"
            empty-text=" "
          >
            <template #empty
              ><el-empty v-if="relatedHydrated" description="暂无会话" :image-size="48"
            /></template>
            <el-table-column
              label="会话"
              min-width="160"
              align="center"
              class-name="col-text"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <span class="cell-id">{{ row.sessionId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{
                  dictLabel('session_state', row.state)
                }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column
              label="订单"
              min-width="120"
              align="center"
              class-name="col-text"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <span class="cell-id">{{ row.orderId || '无' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="168" align="center" class-name="col-text">
              <template #default="{ row }">
                <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="88" class-name="col-action" align="center">
              <template #default="{ row }">
                <TableActions
                  v-if="canAccessPath('/sessions')"
                  :actions="[{ key: 'sessions', label: '查看', icon: View, type: 'primary' }]"
                  @action="
                    () =>
                      goPath(
                        '/sessions',
                        row.sessionId ? { deviceId, sessionId: row.sessionId } : { deviceId }
                      )
                  "
                />
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <h4 class="section-title">最近订单</h4>
          <el-table
            v-loading="!relatedHydrated"
            :data="orders"
            stripe
            border
            size="small"
            class="report-table"
            empty-text=" "
          >
            <template #empty
              ><el-empty v-if="relatedHydrated" description="暂无订单" :image-size="48"
            /></template>
            <el-table-column
              label="订单"
              min-width="160"
              align="center"
              class-name="col-text"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <span class="cell-id">{{ row.orderId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{
                  dictLabel('order_status', row.status)
                }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="金额" width="100" align="center" class-name="col-money">
              <template #default="{ row }"
                >¥{{ ((row.totalAmountCents || 0) / 100).toFixed(2) }}</template
              >
            </el-table-column>
            <el-table-column label="时间" width="168" align="center" class-name="col-text">
              <template #default="{ row }">
                <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="88" class-name="col-action" align="center">
              <template #default>
                <TableActions
                  v-if="canAccessPath('/orders')"
                  :actions="[{ key: 'orders', label: '查看', icon: View, type: 'primary' }]"
                  @action="() => goPath('/orders', { deviceId })"
                />
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="editorVisible" :title="`编辑货道 ${editForm.slotCode}`" width="520px">
      <el-form label-width="110px">
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
        <el-form-item label="启用"><el-switch v-model="editForm.enabled" /></el-form-item>
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
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button v-hasPermi="['ops:device:edit']" :loading="stocktaking" @click="stocktakeSlot"
          >仅记实盘</el-button
        >
        <el-button
          v-hasPermi="['ops:device:edit']"
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
import { computed, onActivated, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api, downloadAuthFile } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import SlotGrid from '@/components/SlotGrid.vue';
import { useNavAccess } from '@/composables/useNavAccess';
import { useAuthStore } from '@/stores/auth';
import type {
  DeviceInfo,
  DeviceSlot,
  PageResult,
  SkuCatalog,
  UpsertDeviceSlotRequest
} from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface DeviceRow {
  deviceId: string;
  deviceName?: string;
  onlineStatus?: string;
  merchantId?: string;
  merchantName?: string;
  activeSessionId?: string;
  activeSessionState?: string;
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
const { canAccessPath, goPath } = useNavAccess();
const deviceId = route.params.id as string;
const canEditSlots = computed(() => auth.hasPerm('ops:device:edit'));
const canEditDevice = computed(() => auth.hasPerm('ops:device:edit'));
const loading = ref(true);
const metricsHydrated = ref(false);
const relatedHydrated = ref(false);
const lifecycleHydrated = ref(false);
const repairHydrated = ref(false);
const slotsHydrated = ref(false);
const applying = ref(false);
const saving = ref(false);
const stocktaking = ref(false);
const assetSaving = ref(false);
const geoLoading = ref(false);
const geoConfigured = ref(false);
const lifeLoading = ref('');
const cmdLoading = ref('');
const tempDraft = ref<number | undefined>(undefined);
const tab = ref('overview');
const device = ref<DeviceRow | null>(null);
const metrics = ref<Metrics | null>(null);
const policy = ref<{
  deviceId: string;
  salesLocked: boolean;
  priceLocked: boolean;
  skuEditForbidden: boolean;
  saleForbidden: boolean;
} | null>(null);
const asset = reactive({
  lifecycleStatus: '' as string,
  imei: '' as string,
  assetOwner: '' as string,
  coopMode: '' as string,
  depositCents: undefined as number | undefined,
  dataFeeCents: undefined as number | undefined,
  opsTags: '' as string,
  routeCode: '' as string,
  latitude: undefined as number | undefined,
  longitude: undefined as number | undefined,
  address: '' as string,
  deployedAt: '' as string | undefined,
  lifecycleRemark: '' as string,
  merchantId: '' as string
});
const lifecycleEvents = ref<LifecycleEventRow[]>([]);
const repairTickets = ref<
  Array<{ ticketId: number; title: string; status: string; createdAt?: string }>
>([]);
const slots = ref<DeviceSlot[]>([]);
const skus = ref<SkuCatalog[]>([]);
const sessions = ref<any[]>([]);
const orders = ref<any[]>([]);
const editorVisible = ref(false);
const qrUrl = ref('');
const qrPreviewUrl = ref('');
const qrLoading = ref(true);
const qrHydrated = ref(false);
const qrDownloading = ref(false);
let qrObjectUrl: string | null = null;

function lifecycleLabel(status?: string | null) {
  switch ((status || '').toUpperCase()) {
    case 'INBOUND':
      return '入库';
    case 'IDLE':
      return '未投放';
    case 'DEPLOYED':
      return '投放';
    case 'RETURNING':
      return '返厂中';
    case 'RETIRED':
      return '退役';
    default:
      return status || '未知状态';
  }
}

function lifecycleActionLabel(action?: string | null) {
  switch ((action || '').toUpperCase()) {
    case 'BIND':
      return '绑定商户';
    case 'UNBIND':
      return '解绑';
    case 'DEPLOY':
      return '投放';
    case 'UNDEPLOY':
      return '撤回未投放';
    case 'RETURN':
      return '返厂';
    case 'RETIRE':
      return '退役';
    case 'INBOUND':
      return '入库';
    default:
      return action || '未知';
  }
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
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/qr-link`,
      'GET'
    );
    qrUrl.value = link.url || '';
    revokeQrPreview();
    const token = localStorage.getItem('admin_token');
    const res = await fetch(
      `${(import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || window.location.origin}/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/qr.png`,
      { headers: token ? { Authorization: `Bearer ${token}` } : {} }
    );
    if (!res.ok) throw new Error('二维码图片加载失败');
    const blob = await res.blob();
    qrObjectUrl = URL.createObjectURL(blob);
    qrPreviewUrl.value = qrObjectUrl;
  } catch (e) {
    qrUrl.value = '';
    revokeQrPreview();
    ElMessage.error(e instanceof Error ? e.message : '加载二维码失败');
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
    await downloadAuthFile(
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/qr.png`,
      `${deviceId}-qr.png`
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '下载失败');
  } finally {
    qrDownloading.value = false;
  }
}

function fillAsset(row: DeviceInfo) {
  asset.lifecycleStatus = row.lifecycleStatus || '';
  asset.imei = row.imei || '';
  asset.assetOwner = row.assetOwner || '';
  asset.coopMode = row.coopMode || '';
  asset.depositCents = row.depositCents != null ? Number(row.depositCents) : undefined;
  asset.dataFeeCents = row.dataFeeCents != null ? Number(row.dataFeeCents) : undefined;
  asset.opsTags = row.opsTags || '';
  asset.routeCode = row.routeCode || '';
  asset.latitude = row.latitude != null ? Number(row.latitude) : undefined;
  asset.longitude = row.longitude != null ? Number(row.longitude) : undefined;
  asset.address = row.address || '';
  asset.deployedAt = row.deployedAt;
  asset.lifecycleRemark = row.lifecycleRemark || '';
  asset.merchantId = row.merchantId || '';
}
const editForm = reactive({
  slotCode: '',
  assignedSkuId: '' as string | undefined,
  parLevel: 0,
  minLevel: 0,
  maxLevel: 0,
  enabled: true,
  bookQty: 0,
  physicalQty: 0,
  qtyDiff: 0,
  hasDiscrepancy: false,
  adjustBookQty: false
});

async function loadAsset() {
  const row = await api.request<DeviceInfo>(
    `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}`,
    'GET'
  );
  fillAsset(row);
  if (device.value) {
    device.value = {
      ...device.value,
      merchantId: row.merchantId || device.value.merchantId,
      merchantName: row.merchantName || device.value.merchantName,
      deviceName: row.deviceName || device.value.deviceName,
      onlineStatus: row.onlineStatus || device.value.onlineStatus
    };
  }
}

async function loadLifecycleEvents() {
  try {
    lifecycleEvents.value = await api
      .request<LifecycleEventRow[]>(
        `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/lifecycle-events?limit=40`,
        'GET'
      )
      .catch(() => []);
  } finally {
    lifecycleHydrated.value = true;
  }
}

async function loadRepairTickets() {
  try {
    repairTickets.value = await api
      .request<Array<{ ticketId: number; title: string; status: string; createdAt?: string }>>(
        `/api/v2/ops/admin/repair-tickets/by-device/${encodeURIComponent(deviceId)}?limit=5`,
        'GET'
      )
      .catch(() => []);
  } finally {
    repairHydrated.value = true;
  }
}

function repairStatusLabel(s?: string) {
  return dictLabel('repair_ticket_status', s) || s || '未知状态';
}

async function createRepair() {
  try {
    const { value: title } = await ElMessageBox.prompt('请输入工单标题', '新建维修工单', {
      inputValidator: (v) => !!String(v || '').trim() || '标题必填',
      confirmButtonText: '创建'
    });
    await api.request('/api/v2/ops/admin/repair-tickets', 'POST', {
      deviceId,
      title: String(title).trim(),
      priority: 'NORMAL'
    });
    ElMessage.success('工单已创建');
    await loadRepairTickets();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '创建失败');
    }
  }
}

async function loadDetail() {
  const detail = await api.request<DeviceDetail>(
    `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/detail`,
    'GET'
  );
  device.value = detail.device;
  metrics.value = detail.metrics;
  slots.value = detail.slots || [];
  slotsHydrated.value = true;
  tempDraft.value = detail.metrics?.targetTempC != null ? detail.metrics.targetTempC : undefined;
  await Promise.all([loadAsset(), loadLifecycleEvents(), loadRepairTickets()]);
  try {
    policy.value = await api.request(
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/policy`,
      'GET'
    );
  } catch {
    policy.value = {
      deviceId,
      salesLocked: !!detail.metrics?.salesLocked,
      priceLocked: false,
      skuEditForbidden: false,
      saleForbidden: false
    };
  }
}

async function saveAsset() {
  if (!canEditDevice.value) return;
  assetSaving.value = true;
  try {
    const row = await api.request<DeviceInfo>(
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}`,
      'PATCH',
      {
        imei: asset.imei || null,
        assetOwner: asset.assetOwner || null,
        coopMode: asset.coopMode || null,
        depositCents: asset.depositCents ?? null,
        dataFeeCents: asset.dataFeeCents ?? null,
        opsTags: asset.opsTags || null,
        routeCode: asset.routeCode || null,
        latitude: asset.latitude ?? null,
        longitude: asset.longitude ?? null,
        address: asset.address || null,
        lifecycleRemark: asset.lifecycleRemark || null
      }
    );
    fillAsset(row);
    ElMessage.success('资产信息已保存');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    assetSaving.value = false;
  }
}

async function resolveAddress() {
  const address = (asset.address || '').trim();
  if (!address) {
    ElMessage.warning('请先填写投放地址');
    return;
  }
  if (!geoConfigured.value) {
    ElMessage.warning('未配置 AMAP_WEB_KEY，无法解析');
    return;
  }
  geoLoading.value = true;
  try {
    const data = await api.request<{
      longitude: number;
      latitude: number;
      formattedAddress?: string;
    }>(`/api/v2/ops/admin/geo/geocode?address=${encodeURIComponent(address)}`, 'GET');
    asset.longitude = data.longitude;
    asset.latitude = data.latitude;
    if (data.formattedAddress) {
      asset.address = data.formattedAddress;
    }
    ElMessage.success('已写入经纬度，可再手动微调后保存');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '地址解析失败');
  } finally {
    geoLoading.value = false;
  }
}

async function loadGeoStatus() {
  if (!canEditDevice.value) {
    geoConfigured.value = false;
    return;
  }
  try {
    const data = await api.request<{ configured: boolean }>('/api/v2/ops/admin/geo/status', 'GET');
    geoConfigured.value = !!data.configured;
  } catch {
    geoConfigured.value = false;
  }
}

async function lifecycleBind() {
  try {
    const { value: merchantId } = await ElMessageBox.prompt('请输入要绑定的商户编号', '绑定商户', {
      inputValue: asset.merchantId || '',
      inputValidator: (v) => !!String(v || '').trim() || '商户编号必填',
      confirmButtonText: '确认绑定'
    });
    await runLifecycle('BIND', false, String(merchantId).trim());
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '绑定失败');
    }
  }
}

async function runLifecycle(action: string, requireRemark = false, merchantId?: string) {
  try {
    let remark = asset.lifecycleRemark || '';
    if (requireRemark || action === 'RETIRE' || action === 'RETURN') {
      const { value } = await ElMessageBox.prompt(
        `确认执行「${lifecycleActionLabel(action)}」？请填写备注。`,
        lifecycleActionLabel(action),
        {
          inputValue: remark,
          inputValidator: (v) => !!String(v || '').trim() || '必须填写备注',
          confirmButtonText: '确认',
          type: action === 'RETIRE' ? 'warning' : undefined
        }
      );
      remark = String(value).trim();
    } else {
      await ElMessageBox.confirm(
        `确认执行「${lifecycleActionLabel(action)}」？`,
        lifecycleActionLabel(action),
        {
          type: 'warning',
          confirmButtonText: '确认',
          cancelButtonText: '取消'
        }
      );
    }
    lifeLoading.value = action;
    const row = await api.request<DeviceInfo>(
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/lifecycle`,
      'POST',
      { action, merchantId, remark: remark || undefined }
    );
    fillAsset(row);
    ElMessage.success(`${lifecycleActionLabel(action)}成功`);
    await Promise.all([loadDetail(), loadLifecycleEvents()]);
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '操作失败');
    }
  } finally {
    lifeLoading.value = '';
  }
}

async function savePolicy() {
  if (!policy.value || !canEditDevice.value) return;
  try {
    policy.value = await api.request(
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/policy`,
      'PUT',
      policy.value
    );
    ElMessage.success('策略已更新');
    await loadDetail();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '策略保存失败');
  }
}

async function loadRelated() {
  try {
    const [sess, ord] = await Promise.all([
      api
        .request<PageResult<any>>(
          `/api/v2/ops/admin/sessions?page=0&size=8&deviceId=${encodeURIComponent(deviceId)}`,
          'GET'
        )
        .catch(() => ({ items: [] as any[] })),
      api
        .request<PageResult<any>>(
          `/api/v2/ops/admin/orders?page=0&size=8&deviceId=${encodeURIComponent(deviceId)}`,
          'GET'
        )
        .catch(() => ({ items: [] as any[] }))
    ]);
    sessions.value = sess.items || [];
    orders.value = ord.items || [];
  } finally {
    relatedHydrated.value = true;
  }
}

async function loadSkus() {
  skus.value = await api.request<SkuCatalog[]>('/api/v2/ops/admin/skus', 'GET').catch(() => []);
}

async function reload() {
  loading.value = true;
  // 软刷新：保留已渲染 KPI/货道/关联表，避免 keep-alive 回页或点刷新时闪「—」/空表
  try {
    await Promise.all([loadDetail(), loadQr()]);
    metricsHydrated.value = true;
    await Promise.all([loadRelated(), loadSkus()]);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
    metricsHydrated.value = true;
    slotsHydrated.value = true;
    repairHydrated.value = true;
    relatedHydrated.value = true;
    lifecycleHydrated.value = true;
    qrHydrated.value = true;
  } finally {
    loading.value = false;
  }
}

async function sendCommand(command: string) {
  const labels: Record<string, string> = {
    OPEN_DOOR: '运维远程开门',
    LOCK: '锁机停售',
    UNLOCK: '解锁营业',
    REBOOT: '重启设备'
  };
  try {
    const hint =
      command === 'OPEN_DOOR'
        ? '确认执行「运维远程开门」？将创建运维会话并占柜（关门后不结算）。补货请用补货调度页的「补货开门」。请填写原因。'
        : `确认执行「${labels[command]}」？请填写原因。`;
    const { value: reason } = await ElMessageBox.prompt(hint, '运维指令', {
      inputValidator: (v) => !!String(v || '').trim() || '必须填写原因',
      confirmButtonText: '确认下发',
      type:
        command === 'REBOOT' || command === 'LOCK' || command === 'OPEN_DOOR'
          ? 'warning'
          : undefined
    });
    cmdLoading.value = command;
    const result = await api.request<{ message?: string; salesLocked?: boolean }>(
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/commands`,
      'POST',
      { command, reason: reason }
    );
    ElMessage.success(result.message || '指令已下发');
    await loadDetail();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '指令失败');
    }
  } finally {
    cmdLoading.value = '';
  }
}

async function setTargetTemp() {
  if (tempDraft.value == null || Number.isNaN(tempDraft.value)) {
    ElMessage.warning('请填写目标温度');
    return;
  }
  try {
    const { value: reason } = await ElMessageBox.prompt(
      `确认将目标温度设为 ${tempDraft.value}°C 并下发柜机？`,
      '设置目标温度',
      {
        inputValue: '运营设温',
        inputValidator: (v) => !!String(v || '').trim() || '必须填写原因',
        confirmButtonText: '确认下发'
      }
    );
    cmdLoading.value = 'SET_TEMP';
    const result = await api.request<{ message?: string }>(
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/commands`,
      'POST',
      { command: 'SET_TEMP', reason, targetTempC: tempDraft.value }
    );
    ElMessage.success(result.message || '温度已下发');
    await loadDetail();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '设温失败');
    }
  } finally {
    cmdLoading.value = '';
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

async function applyTemplate() {
  applying.value = true;
  try {
    const n = await api.request<number>(
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/slots/apply-template`,
      'POST'
    );
    ElMessage.success(`已套用模板，新增 ${n} 个货道`);
    await loadDetail();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '套用失败');
  } finally {
    applying.value = false;
  }
}

function openEditor(slot: DeviceSlot) {
  if (!canEditSlots.value) return;
  editForm.slotCode = slot.slotCode;
  editForm.assignedSkuId = slot.assignedSkuId || '';
  editForm.parLevel = slot.parLevel;
  editForm.minLevel = slot.minLevel;
  editForm.maxLevel = slot.maxLevel;
  editForm.enabled = slot.enabled;
  editForm.bookQty = slot.bookQty ?? 0;
  editForm.physicalQty =
    slot.lastPhysicalQty != null ? Number(slot.lastPhysicalQty) : (slot.bookQty ?? 0);
  editForm.qtyDiff = slot.qtyDiff ?? 0;
  editForm.hasDiscrepancy = !!slot.hasDiscrepancy;
  editForm.adjustBookQty = false;
  editorVisible.value = true;
}

async function runStocktake(adjustBookQty: boolean) {
  if (editForm.physicalQty == null || editForm.physicalQty < 0) {
    ElMessage.warning('请填写实盘数量');
    return;
  }
  if (adjustBookQty && !editForm.assignedSkuId) {
    ElMessage.warning('货道未绑定商品，无法调账面');
    return;
  }
  if (adjustBookQty) {
    try {
      await ElMessageBox.confirm(
        `确认将货道 ${editForm.slotCode} 账面按实盘 ${editForm.physicalQty} 回写？\n将调整该货道绑定 SKU 的批次库存。`,
        '按实盘调账面',
        { type: 'warning', confirmButtonText: '确认调账' }
      );
    } catch {
      return;
    }
  }
  stocktaking.value = true;
  try {
    const updated = await api.request<DeviceSlot>(
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/slots/stocktake`,
      'POST',
      {
        slotCode: editForm.slotCode,
        physicalQty: editForm.physicalQty,
        adjustBookQty
      }
    );
    ElMessage.success(adjustBookQty ? '已按实盘调账面' : '已记录实盘数量');
    editForm.bookQty = updated.bookQty ?? editForm.physicalQty;
    editForm.qtyDiff = updated.qtyDiff ?? 0;
    editForm.hasDiscrepancy = !!updated.hasDiscrepancy;
    editForm.adjustBookQty = false;
    await loadDetail();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '盘点失败');
  } finally {
    stocktaking.value = false;
  }
}

async function stocktakeSlot() {
  await runStocktake(false);
}

async function stocktakeAndAdjust() {
  await runStocktake(true);
}

async function saveSlot() {
  saving.value = true;
  const body: UpsertDeviceSlotRequest[] = [
    {
      slotCode: editForm.slotCode,
      assignedSkuId: editForm.assignedSkuId || '',
      parLevel: editForm.parLevel,
      minLevel: editForm.minLevel,
      maxLevel: editForm.maxLevel,
      enabled: editForm.enabled
    }
  ];
  try {
    slots.value = await api.request<DeviceSlot[]>(
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/slots`,
      'PUT',
      body
    );
    editorVisible.value = false;
    ElMessage.success('已保存');
    await loadDetail();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

onMounted(async () => {
  loading.value = true;
  try {
    await Promise.all([loadDetail(), loadGeoStatus(), loadQr()]);
    metricsHydrated.value = true;
    await Promise.all([loadRelated(), loadSkus()]);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
    metricsHydrated.value = true;
    slotsHydrated.value = true;
    repairHydrated.value = true;
    relatedHydrated.value = true;
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
.device-ops {
  display: flex;
  flex-direction: column;
  gap: 12px;
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
  font-size: 15px;
}
.page-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.stat-row {
  margin-top: 4px;
}
.qr-card .qr-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.qr-body {
  display: flex;
  gap: 20px;
  align-items: flex-start;
  flex-wrap: wrap;
}
.qr-preview {
  width: 180px;
  height: 180px;
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
  width: 180px;
  height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}
.qr-meta {
  flex: 1;
  min-width: 220px;
}
.qr-url {
  font-size: 13px;
  word-break: break-all;
  margin-bottom: 8px;
}
.stat-tile {
  display: block;
  width: 100%;
  text-align: left;
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
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
  margin-top: 4px;
  font-variant-numeric: tabular-nums;
}
.stat-hint {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.slot-diff.warn {
  color: #ea580c;
  margin-left: 6px;
  font-size: 12px;
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
  font-size: 15px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.open-door-alert {
  margin-bottom: 14px;
}
.cmd-section-label {
  margin: 4px 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.cmd-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}
.policy-lock-alert {
  margin-bottom: 12px;
}
.field-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 4px;
}
.address-row {
  display: flex;
  gap: 8px;
  width: 100%;
  align-items: center;
}
.address-row .el-input {
  flex: 1;
}
.lock-restock-hint {
  margin: 8px 0 12px;
}
.temp-set-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.muted {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}
.asset-form {
  margin-bottom: 4px;
}
.asset-deployed {
  margin-left: 8px;
}
.life-event {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.life-remark {
  font-size: 12px;
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
.section-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
}
.name-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
}
.name-cell.inline {
  display: inline-grid;
}
.name-cell strong {
  font-weight: 650;
}
.name-cell small {
  color: var(--el-text-color-secondary);
  font-family: inherit;
}
</style>
