<template>
  <div v-loading="loading" class="device-ops">
    <el-page-header @back="goPath('/devices')">
      <template #content>
        <div class="page-head-meta">
          <div class="page-title-row">
            <span class="page-title">{{ device?.deviceName || deviceId }}</span>
            <el-tag v-if="device" :type="device.onlineStatus === 'ONLINE' ? 'success' : 'info'" size="small">
              {{ dictLabel('online_status', device.onlineStatus) }}
            </el-tag>
            <el-tag v-if="metrics?.salesLocked" type="danger" size="small">已锁机</el-tag>
          </div>
          <span class="page-hint">设备 ID {{ deviceId }} · 远程运维与货道陈列</span>
        </div>
      </template>
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </template>
    </el-page-header>

    <el-row :gutter="12" class="stat-row">
      <el-col :xs="12" :sm="6">
        <div class="stat-tile">
          <div class="stat-label">填充率</div>
          <div class="stat-value">{{ metrics?.fillRatePct ?? 0 }}%</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-tile" :class="{ warn: (metrics?.oosSlotCount || 0) > 0 }">
          <div class="stat-label">缺货货道</div>
          <div class="stat-value">{{ metrics?.oosSlotCount ?? 0 }}</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-tile" :class="{ warn: (metrics?.lowStockSlotCount || 0) > 0 }">
          <div class="stat-label">低库存货道</div>
          <div class="stat-value">{{ metrics?.lowStockSlotCount ?? 0 }}</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-tile">
          <div class="stat-label">柜内温度</div>
          <div class="stat-value">
            {{ metrics?.currentTempC != null ? `${metrics.currentTempC}°C` : '-' }}
          </div>
        </div>
      </el-col>
    </el-row>

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
        description="「运维远程开门」：应急/检修，不绑定补货任务、不产生补货签收。现场补货请用「补货调度 → 补货开门」或商户小程序（需先签到）。"
      />
      <div class="cmd-section-label">运维指令</div>
      <div class="cmd-bar">
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="primary"
          :loading="cmdLoading === 'OPEN_DOOR'"
          @click="sendCommand('OPEN_DOOR')"
        >运维远程开门</el-button>
        <el-button
          v-if="!metrics?.salesLocked"
          v-hasPermi="['ops:device:edit']"
          type="warning"
          :loading="cmdLoading === 'LOCK'"
          @click="sendCommand('LOCK')"
        >锁机停售</el-button>
        <el-button
          v-else
          v-hasPermi="['ops:device:edit']"
          type="success"
          :loading="cmdLoading === 'UNLOCK'"
          @click="sendCommand('UNLOCK')"
        >解锁营业</el-button>
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="danger"
          plain
          :loading="cmdLoading === 'REBOOT'"
          @click="sendCommand('REBOOT')"
        >重启设备</el-button>
      </div>
      <div class="cmd-section-label">补货入口</div>
      <div class="cmd-bar">
        <el-button v-if="canAccessPath('/replenishment')" @click="goReplenish">缺货建议</el-button>
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
                <strong>{{ device?.merchantName || device?.merchantId || '-' }}</strong>
                <small v-if="device?.merchantId && device?.merchantName" class="cell-id">{{ device.merchantId }}</small>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="地址">{{ metrics?.address || '-' }}</el-descriptions-item>
            <el-descriptions-item label="App / 固件">
              {{ metrics?.appVersion || '-' }} / {{ metrics?.firmwareVersion || '-' }}
            </el-descriptions-item>
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
                >下发温度</el-button>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="温度上报">
              <span class="cell-datetime">{{ formatDateTime(metrics?.tempReportedAt) || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="告警联系人">{{ metrics?.alertContactName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="联系电话">{{ metrics?.alertContactPhone || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最近会话">
              <span class="cell-id">{{ device?.activeSessionId || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="会话状态">
              <el-tag v-if="device?.activeSessionState" size="small" effect="plain">
                {{ dictLabel('session_state', device.activeSessionState) }}
              </el-tag>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="最近补货">
              <span class="cell-datetime">{{ formatDateTime(metrics?.lastRestockAt) || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="库存准确率">{{ metrics?.inventoryAccuracyPct ?? '-' }}%</el-descriptions-item>
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
            >套用模板</el-button>
            <el-button size="small" :icon="Refresh" @click="loadDetail">刷新货道</el-button>
          </div>
          <SlotGrid :slots="slots" :editable="canEditSlots" @edit="openEditor" />
        </el-tab-pane>

        <el-tab-pane label="关联单据" name="related">
          <h4 class="section-title">最近开门记录</h4>
          <el-table :data="sessions" stripe border size="small" class="report-table" empty-text="暂无会话">
            <el-table-column label="会话" min-width="160" class-name="col-text" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-id">{{ row.sessionId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ dictLabel('session_state', row.state) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="订单" min-width="120" class-name="col-text" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-id">{{ row.orderId || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="168" class-name="col-text">
              <template #default="{ row }">
                <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="88" class-name="col-action" align="center">
              <template #default="{ row }">
                <TableActions
                  v-if="canAccessPath('/sessions')"
                  :actions="[{ key: 'sessions', label: '查看', icon: View, type: 'primary' }]"
                  @action="() => goPath('/sessions', row.sessionId ? { deviceId, sessionId: row.sessionId } : { deviceId })"
                />
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <h4 class="section-title">最近订单</h4>
          <el-table :data="orders" stripe border size="small" class="report-table" empty-text="暂无订单">
            <el-table-column label="订单" min-width="160" class-name="col-text" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-id">{{ row.orderId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ dictLabel('order_status', row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="金额" width="100" align="right" class-name="col-money">
              <template #default="{ row }">¥{{ ((row.totalAmountCents || 0) / 100).toFixed(2) }}</template>
            </el-table-column>
            <el-table-column label="时间" width="168" class-name="col-text">
              <template #default="{ row }">
                <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="88" class-name="col-action" align="center">
              <template #default="{ row }">
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

    <el-dialog v-model="editorVisible" :title="`编辑货道 ${editForm.slotCode}`" width="480px">
      <el-form label-width="100px">
        <el-form-item label="SKU">
          <el-select v-model="editForm.assignedSkuId" filterable clearable placeholder="选择商品" style="width:100%">
            <el-option v-for="s in skus" :key="s.skuId" :label="`${s.skuName} (${s.skuId})`" :value="s.skuId" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标陈列"><el-input-number v-model="editForm.parLevel" :min="0" /></el-form-item>
        <el-form-item label="最低库存"><el-input-number v-model="editForm.minLevel" :min="0" /></el-form-item>
        <el-form-item label="最大容量"><el-input-number v-model="editForm.maxLevel" :min="0" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="editForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button v-hasPermi="['ops:device:edit']" type="primary" :loading="saving" @click="saveSlot">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import SlotGrid from '@/components/SlotGrid.vue';
import { useNavAccess } from '@/composables/useNavAccess';
import { useAuthStore } from '@/stores/auth';
import type { DeviceSlot, PageResult, SkuCatalog, UpsertDeviceSlotRequest } from '@aicabinet/shared-types';
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

interface Metrics {
  fillRatePct?: number;
  oosSlotCount?: number;
  lowStockSlotCount?: number;
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
const { router, canAccessPath, goPath } = useNavAccess();
const deviceId = route.params.id as string;
const canEditSlots = computed(() => auth.hasPerm('ops:device:edit'));
const loading = ref(false);
const applying = ref(false);
const saving = ref(false);
const cmdLoading = ref('');
const tempDraft = ref<number | undefined>(undefined);
const tab = ref('overview');
const device = ref<DeviceRow | null>(null);
const metrics = ref<Metrics | null>(null);
const slots = ref<DeviceSlot[]>([]);
const skus = ref<SkuCatalog[]>([]);
const sessions = ref<any[]>([]);
const orders = ref<any[]>([]);
const editorVisible = ref(false);
const editForm = reactive({
  slotCode: '',
  assignedSkuId: '' as string | undefined,
  parLevel: 0,
  minLevel: 0,
  maxLevel: 0,
  enabled: true
});

async function loadDetail() {
  const detail = await api.request<DeviceDetail>(
    `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/detail`,
    'GET'
  );
  device.value = detail.device;
  metrics.value = detail.metrics;
  slots.value = detail.slots || [];
  tempDraft.value = detail.metrics?.targetTempC != null ? detail.metrics.targetTempC : undefined;
}

async function loadRelated() {
  const [sess, ord] = await Promise.all([
    api.request<PageResult<any>>(
      `/api/v2/ops/admin/sessions?page=0&size=8&deviceId=${encodeURIComponent(deviceId)}`,
      'GET'
    ),
    api.request<PageResult<any>>(
      `/api/v2/ops/admin/orders?page=0&size=8&deviceId=${encodeURIComponent(deviceId)}`,
      'GET'
    )
  ]);
  sessions.value = sess.items || [];
  orders.value = ord.items || [];
}

async function loadSkus() {
  skus.value = await api.request<SkuCatalog[]>('/api/v2/ops/admin/skus', 'GET');
}

async function reload() {
  loading.value = true;
  try {
    await Promise.all([loadDetail(), loadRelated()]);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
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
        ? '确认执行「运维远程开门」？此操作不绑定补货任务、不走补货结算。补货请用补货调度页的「补货开门」。请填写原因。'
        : `确认执行「${labels[command]}」？请填写原因。`;
    const { value: reason } = await ElMessageBox.prompt(hint, '运维指令', {
      inputValidator: (v) => !!String(v || '').trim() || '必须填写原因',
      confirmButtonText: '确认下发',
      type: command === 'REBOOT' || command === 'LOCK' || command === 'OPEN_DOOR' ? 'warning' : undefined
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
  editorVisible.value = true;
}

async function saveSlot() {
  saving.value = true;
  const body: UpsertDeviceSlotRequest[] = [{
    slotCode: editForm.slotCode,
    assignedSkuId: editForm.assignedSkuId || '',
    parLevel: editForm.parLevel,
    minLevel: editForm.minLevel,
    maxLevel: editForm.maxLevel,
    enabled: editForm.enabled
  }];
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
    await Promise.all([loadDetail(), loadRelated(), loadSkus()]);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
});

onActivated(() => {
  void reload();
});
</script>

<style scoped>
.device-ops { display: flex; flex-direction: column; gap: 12px; }
.page-head-meta { display: flex; flex-direction: column; gap: 4px; }
.page-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.page-title { font-weight: 600; font-size: 15px; }
.page-hint { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.4; }
.stat-row { margin-top: 4px; }
.stat-tile {
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 8px;
}
.stat-tile.warn { background: color-mix(in srgb, var(--el-color-warning) 12%, transparent); }
.stat-label { font-size: 12px; color: var(--el-text-color-secondary); }
.stat-value { font-size: 22px; font-weight: 600; margin-top: 4px; font-variant-numeric: tabular-nums; }
.page-card-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.4; }
.open-door-alert { margin-bottom: 14px; }
.cmd-section-label {
  margin: 4px 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.cmd-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; align-items: center; }
.temp-set-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.muted { color: var(--el-text-color-placeholder); font-size: 13px; }
.slot-toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.section-title { margin: 16px 0 8px; font-size: 14px; font-weight: 600; }
.name-cell { display: grid; gap: 2px; line-height: 1.35; }
.name-cell.inline { display: inline-grid; }
.name-cell strong { font-weight: 650; }
.name-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
</style>
