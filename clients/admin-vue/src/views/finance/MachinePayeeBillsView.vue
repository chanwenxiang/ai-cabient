<template>
  <div class="machine-bills-page">
    <el-tabs v-model="activeTab">
      <!-- 月度账单（CrudTable 统一壳：分页/排序/刷新/空态内建） -->
      <el-tab-pane label="月度账单" name="bills">
        <CrudTable
          :table="crud"
          row-key="billId"
          :actions="rowActions"
          actions-test-id="machine-bill"
          @action="onRowAction"
        >
          <template #toolbar>
            <el-date-picker
              v-model="filterMonth"
              type="month"
              value-format="YYYY-MM"
              placeholder="账期（空=全部）"
              clearable
            />
            <el-select
              v-model="filterStatus"
              placeholder="状态（空=全部）"
              clearable
              style="width: 140px"
            >
              <el-option label="未结清" value="PENDING" />
              <el-option label="已结清" value="SETTLED" />
            </el-select>
            <el-button type="primary" @click="applyFilters">查询</el-button>
            <el-button type="warning" @click="openGenerate">手动出账</el-button>
          </template>
          <el-table-column prop="billId" label="ID" width="80" />
          <el-table-column prop="billMonth" label="账期" width="100" />
          <el-table-column prop="deviceId" label="柜机" width="140" />
          <el-table-column label="费用类型" width="110">
            <template #default="{ row }">{{ feeTypeText(row.feeType) }}</template>
          </el-table-column>
          <el-table-column label="金额（元）" width="120">
            <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
          </el-table-column>
          <el-table-column label="分成基数 GMV（元）" width="150">
            <template #default="{ row }">{{
              row.gmvCents == null ? '—' : yuan(row.gmvCents)
            }}</template>
          </el-table-column>
          <el-table-column prop="payeeName" label="收款人（场地方）" width="130" />
          <el-table-column prop="payeePhone" label="电话" width="130" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'SETTLED' ? 'success' : 'warning'" size="small">
                {{ row.status === 'SETTLED' ? '已结清' : '未结清' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="120" />
        </CrudTable>
      </el-tab-pane>

      <!-- 点位费用配置 -->
      <el-tab-pane label="点位费用配置" name="config">
        <div class="toolbar">
          <el-input
            v-model="configDeviceId"
            placeholder="柜机 deviceId（12 位）"
            style="width: 220px"
            @keyup.enter="loadConfig"
          />
          <el-button type="primary" @click="loadConfig">查询配置</el-button>
        </div>
        <el-alert
          v-if="configLoaded"
          type="info"
          :closable="false"
          show-icon
          title="费用项随时可改，只影响后续账期；场地方不是系统用户，收款人为文本快照，线下结算。"
          style="margin-bottom: 10px"
        />
        <el-form v-if="configLoaded" label-width="160px" style="max-width: 560px">
          <el-form-item label="柜机 deviceId">
            <el-input v-model="configForm.deviceId" disabled />
          </el-form-item>
          <el-form-item label="电费类型">
            <el-select v-model="configForm.electricType" style="width: 200px">
              <el-option label="无电费" value="NONE" />
              <el-option label="固定月额" value="FIXED" />
            </el-select>
          </el-form-item>
          <el-form-item label="月电费（元）">
            <el-input-number
              v-model="configElectricYuan"
              :min="0"
              :precision="2"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="客情说明（送水/其他）">
            <el-input v-model="configForm.welfareInfo" maxlength="200" />
          </el-form-item>
          <el-form-item label="场地方销售分成（bps）">
            <el-input-number
              v-model="configForm.shareBps"
              :min="0"
              :max="10000"
              controls-position="right"
            />
            <span class="hint">{{ (configForm.shareBps / 100).toFixed(2) }}%</span>
          </el-form-item>
          <el-form-item label="收款人姓名" required>
            <el-input v-model="configForm.payeeName" maxlength="64" />
          </el-form-item>
          <el-form-item label="收款人电话">
            <el-input v-model="configForm.payeePhone" maxlength="20" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="configEnabled" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="saveConfig">保存配置</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>

    <!-- 手动出账 -->
    <el-dialog v-model="generateDialogVisible" title="手动出账" width="420px">
      <el-form label-width="120px">
        <el-form-item label="账期（出上月账）" required>
          <el-date-picker
            v-model="generateMonth"
            type="month"
            value-format="YYYY-MM"
            :clearable="false"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitGenerate">出账（幂等，可重跑）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Check } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';

interface MachinePayeeBill {
  billId: number;
  deviceId: string;
  billMonth: string;
  feeType: string;
  amountCents: number;
  gmvCents: number | null;
  payeeName: string;
  payeePhone: string | null;
  status: string;
  remark: string | null;
}
interface MachinePositionConfig {
  deviceId: string;
  electricType: string;
  electricAmountCents: number;
  welfareInfo: string | null;
  shareBps: number;
  payeeName: string;
  payeePhone: string | null;
  enabled: number;
}

const activeTab = ref('bills');
const filterMonth = ref<string | null>(null);
const filterStatus = ref<string | null>(null);

function yuan(cents?: number | null): string {
  return ((cents ?? 0) / 100).toFixed(2);
}
function feeTypeText(t: string): string {
  return t === 'ELECTRIC' ? '电费' : t === 'SHARE' ? '销售分成' : t;
}

// 列表状态机统一交给 CrudTable（防回潮门禁：新页面禁裸 el-table）。
// 后端 list 无分页参数 ⇒ fetchPage 拉全量后按页切片，排序/分页在本页收敛。
const crud = useCrudTable<MachinePayeeBill>({
  rowKey: (r) => r.billId,
  sort: { prop: 'billId', mode: 'local', defaultDir: 'desc' },
  fetchPage: async (params) => {
    const q = new URLSearchParams();
    if (filterMonth.value) q.set('billMonth', filterMonth.value);
    if (filterStatus.value) q.set('status', filterStatus.value);
    const all = await api.request<MachinePayeeBill[]>(
      `${AdminEndpoints.machinePayeeBills}?${q.toString()}`,
      'GET'
    );
    const start = params.page * params.size;
    return { items: all.slice(start, start + params.size), total: all.length };
  }
});

function applyFilters(): void {
  void crud.load({ resetPage: true });
}

function rowActions(row: MachinePayeeBill): CrudRowAction[] {
  if (row.status === 'SETTLED') return [];
  return [{ key: 'settle', label: '标记结清', icon: Check, perm: 'ops:device:edit' }];
}

async function onRowAction(payload: { key: string; row: MachinePayeeBill }): Promise<void> {
  if (payload.key !== 'settle') return;
  await api.request(AdminEndpoints.machinePayeeBillSettle(payload.row.billId), 'POST');
  ElMessage.success('已标记结清（线下收款后）');
  await crud.load();
}

const generateDialogVisible = ref(false);
// 出账=出「上月」账：默认上个月（与每月 1 日自动任务同口径）
const generateMonth = ref(upperMonth());

function upperMonth(): string {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() - 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function openGenerate(): void {
  generateDialogVisible.value = true;
}

async function submitGenerate(): Promise<void> {
  if (!generateMonth.value) return;
  const n = await api.request<number>(
    AdminEndpoints.machinePayeeBillGenerate(generateMonth.value),
    'POST'
  );
  ElMessage.success(`出账完成，共 ${n} 条（幂等，重跑不重复）`);
  generateDialogVisible.value = false;
  filterMonth.value = generateMonth.value;
  await applyFilters();
}

const configDeviceId = ref('');
const configLoaded = ref(false);
const configForm = ref<MachinePositionConfig>(emptyConfig(''));
const configEnabled = ref(true);
const configElectricYuan = computed({
  get: () => (configForm.value.electricAmountCents ?? 0) / 100,
  set: (v: number) => {
    configForm.value.electricAmountCents = Math.round((v ?? 0) * 100);
  }
});

function emptyConfig(deviceId: string): MachinePositionConfig {
  return {
    deviceId,
    electricType: 'NONE',
    electricAmountCents: 0,
    welfareInfo: '',
    shareBps: 0,
    payeeName: '',
    payeePhone: '',
    enabled: 1
  };
}

async function loadConfig(): Promise<void> {
  const deviceId = configDeviceId.value.trim();
  if (!deviceId) {
    ElMessage.warning('请输入柜机 deviceId');
    return;
  }
  const cfg = await api.request<MachinePositionConfig | null>(
    AdminEndpoints.machinePayeeBillConfig(deviceId),
    'GET'
  );
  configForm.value = cfg ?? emptyConfig(deviceId);
  if (!cfg) configForm.value.deviceId = deviceId;
  configEnabled.value = (configForm.value.enabled ?? 1) === 1;
  configLoaded.value = true;
}

async function saveConfig(): Promise<void> {
  if (!configForm.value.payeeName.trim()) {
    ElMessage.warning('收款人姓名必填');
    return;
  }
  const body = { ...configForm.value, enabled: configEnabled.value ? 1 : 0 };
  await api.request(AdminEndpoints.machinePayeeBillConfig(configForm.value.deviceId), 'PUT', body);
  ElMessage.success('配置已保存（只影响后续账期）');
}
</script>

<style scoped>
.machine-bills-page {
  padding: 8px 4px;
}
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}
.hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
