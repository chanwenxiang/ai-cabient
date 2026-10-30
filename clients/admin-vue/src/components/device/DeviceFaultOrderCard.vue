<template>
  <el-card class="page-card fault-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">故障记录（设备自报）</span>
            <span class="hint"
              >柜机固件自动上报的故障（如摄像头打开失败）；跨重启重复出现 =
              硬件级问题，请现场检修或联系厂商售后</span
            >
          </div>
        </div>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>

    <el-table v-if="items.length" :data="items" size="small" class="fault-table">
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.reportedAt) }}</template>
      </el-table-column>
      <el-table-column label="故障描述" min-width="220">
        <template #default="{ row }">
          <span :class="{ 'fault-msg-critical': isCritical(row) }">{{
            row.errorMsg || '（无描述）'
          }}</span>
        </template>
      </el-table-column>
      <el-table-column label="错误码" width="120">
        <template #default="{ row }">{{ row.errorCode || '—' }}</template>
      </el-table-column>
      <el-table-column label="关联单号" min-width="180">
        <template #default="{ row }">{{ row.orderNo || '—' }}</template>
      </el-table-column>
    </el-table>
    <div v-else class="fault-empty">
      {{ loading ? '加载中…' : '暂无故障记录——设备自报口径，固件上报后才会出现在这里' }}
    </div>
  </el-card>
</template>

<script setup lang="ts">
/**
 * 设备侧故障记录卡（CB-077）：固件故障单（将邑 uploadFaultOrder）最近 N 条，
 * reported_at 降序。CB-031 下钻范式——告警点进去看具体故障原因；本期纯列表，
 * 不接待办/红点（台账拍板）。仅 BOUND 设备渲染由父级 DeviceJiangyiCard 同款逻辑控制。
 */
import { onMounted, ref } from 'vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { formatDateTime } from '@aicabinet/shared-uni/format';

const props = defineProps<{ deviceId: string }>();

interface FaultItem {
  id: number;
  orderNo?: string | null;
  errorCode?: string | null;
  errorMsg?: string | null;
  reportedAt: string;
}

const items = ref<FaultItem[]>([]);
const loading = ref(false);

/** 摄像头类故障直接标红：开门柜识别靠摄像头，这类故障=柜子不可用（2026-10-30 CQYB10720 实锤）。 */
function isCritical(row: FaultItem): boolean {
  return /摄像头|camera/i.test(row.errorMsg ?? '');
}

async function load() {
  loading.value = true;
  try {
    const list = await api.request<FaultItem[]>(
      AdminEndpoints.deviceJiangyiFaultOrders(props.deviceId)
    );
    items.value = Array.isArray(list) ? list : [];
  } catch {
    // 拉取失败保留现状：故障记录是展示增强，不该把设备详情页打红
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.fault-card {
  margin-top: 16px;
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
  font-size: var(--admin-font-size-title, 15px);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.fault-table {
  width: 100%;
}
.fault-msg-critical {
  color: var(--el-color-danger);
  font-weight: 600;
}
.fault-empty {
  padding: 18px 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  text-align: center;
}
</style>
