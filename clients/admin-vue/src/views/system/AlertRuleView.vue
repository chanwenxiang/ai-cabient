<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">告警规则</span>
            <span class="hint">集中配置告警与自动处置阈值，保存即生效（与「参数配置」同源）</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div v-loading="loading" class="table-scroll">
      <div class="table-scroll-inner">
        <el-table :data="rows" stripe border class="report-table" row-key="configKey">
          <el-table-column label="分组" width="140" align="center">
            <template #default="{ row }">{{ row.group }}</template>
          </el-table-column>
          <el-table-column label="规则说明" min-width="220" align="center">
            <template #default="{ row }">{{ row.description }}</template>
          </el-table-column>
          <el-table-column label="配置键" min-width="220" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.configKey }}</span>
            </template>
          </el-table-column>
          <el-table-column label="当前值" min-width="160" align="center">
            <template #default="{ row }">
              <el-switch
                v-if="row.configKey === 'device.offline.auto_unlock_enabled'"
                :model-value="row.configValue === 'true'"
                :disabled="!canEdit"
                @change="(v: boolean) => onToggle(row, v)"
              />
              <el-input
                v-else
                v-model="row.configValue"
                :disabled="!canEdit"
                placeholder="请输入"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="center" class-name="col-action">
            <template #default="{ row }">
              <el-button
                v-if="canEdit"
                size="small"
                type="primary"
                plain
                :loading="savingKey === row.configKey"
                @click="save(row)"
              >
                保存
              </el-button>
              <span v-else class="cell-hint">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';

interface SystemConfigRow {
  configKey: string;
  configValue: string;
  description?: string;
  updatedAt?: string;
}

interface RuleRow extends SystemConfigRow {
  group: string;
}

const RULE_GROUPS: Record<string, string[]> = {
  设备离线与解锁: [
    'device.offline.auto_sales_lock_minutes',
    'device.offline.auto_unlock_enabled',
    'device.offline.auto_unlock_stable_minutes'
  ],
  '争议 SLA': ['dispute.sla.hours', 'dispute.sla.reminder_hours', 'dispute.sla.webhook'],
  卡点扫描: [
    'ops.scan.door_open_minutes',
    'ops.scan.upload_stuck_minutes',
    'ops.scan.recognition_stuck_minutes',
    'ops.scan.settlement_stuck_minutes'
  ],
  自动处置: ['order.unpaid.auto_cancel_hours', 'recharge.pending.auto_cancel_minutes']
};

const auth = useAuthStore();
const loading = ref(false);
const savingKey = ref('');
const rows = ref<RuleRow[]>([]);

const canEdit = computed(() => auth.hasPerm('ops:config:edit'));

async function load() {
  loading.value = true;
  try {
    const all = await api.request<SystemConfigRow[]>('/api/v2/ops/admin/system-configs', 'GET');
    const byKey = new Map(all.map((r) => [r.configKey, r]));
    const out: RuleRow[] = [];
    for (const [group, keys] of Object.entries(RULE_GROUPS)) {
      for (const key of keys) {
        const row = byKey.get(key);
        if (row) {
          out.push({ ...row, group });
        }
      }
    }
    rows.value = out;
  } catch (e: any) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function save(row: RuleRow) {
  savingKey.value = row.configKey;
  try {
    await api.request('/api/v2/ops/admin/system-configs', 'PUT', {
      configKey: row.configKey,
      configValue: String(row.configValue ?? '').trim(),
      description: row.description
    });
    ElMessage.success('已保存并生效');
  } catch (e: any) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    savingKey.value = '';
  }
}

function onToggle(row: RuleRow, v: boolean) {
  row.configValue = String(v);
}

onMounted(load);
</script>
