<template>
  <div v-loading="loading" class="device-ops">
    <el-page-header @back="router.push('/devices')">
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
              <span class="hint">指令经 device-service 下发（模拟器与真机同一接口）</span>
            </div>
          </div>
        </div>
      </template>
      <div class="cmd-bar">
        <el-button type="primary" :loading="cmdLoading === 'OPEN_DOOR'" @click="sendCommand('OPEN_DOOR')">远程开门</el-button>
        <el-button
          v-if="!metrics?.salesLocked"
          type="warning"
          :loading="cmdLoading === 'LOCK'"
          @click="sendCommand('LOCK')"
        >锁机停售</el-button>
        <el-button
          v-else
          type="success"
          :loading="cmdLoading === 'UNLOCK'"
          @click="sendCommand('UNLOCK')"
        >解锁营业</el-button>
        <el-button type="danger" plain :loading="cmdLoading === 'REBOOT'" @click="sendCommand('REBOOT')">重启柜机</el-button>
        <el-button @click="goReplenish">缺货补货</el-button>
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
              {{ metrics?.targetTempC != null ? `${metrics.targetTempC}°C` : '-' }}
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
            <el-button type="primary" size="small" :loading="applying" @click="applyTemplate">套用模板</el-button>
            <el-button size="small" :icon="Refresh" @click="loadDetail">刷新货道</el-button>
          </div>
          <SlotGrid :slots="slots" editable @edit="openEditor" />
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
              <template #default>
                <TableActions
                  :actions="[{ key: 'sessions', label: '查看', icon: View, type: 'primary' }]"
                  @action="() => router.push({ path: '/sessions', query: { deviceId } })"
                />
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
              <template #default>
                <TableActions
                  :actions="[{ key: 'orders', label: '查看', icon: View, type: 'primary' }]"
                  @action="() => router.push({ path: '/orders', query: { deviceId } })"
                />
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
        <el-button type="primary" :loading="saving" @click="saveSlot">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import SlotGrid from '@/components/SlotGrid.vue';
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
const router = useRouter();
const deviceId = route.params.id as string;
const loading = ref(false);
const applying = ref(false);
const saving = ref(false);
const cmdLoading = ref('');
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
    OPEN_DOOR: '远程开门',
    LOCK: '锁机停售',
    UNLOCK: '解锁营业',
    REBOOT: '重启柜机'
  };
  try {
    const { value: reason } = await ElMessageBox.prompt(`确认执行「${labels[command]}」？请填写原因。`, '运维指令', {
      inputValidator: (v) => !!String(v || '').trim() || '必须填写原因',
      confirmButtonText: '确认下发',
      type: command === 'REBOOT' || command === 'LOCK' ? 'warning' : undefined
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

function goReplenish() {
  router.push({ path: '/replenishment', query: { tab: 'shortage', deviceId } });
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
.cmd-bar { display: flex; flex-wrap: wrap; gap: 8px; }
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
