<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';

const props = defineProps<{ deviceId: string; canEdit: boolean }>();

interface PreviewLine {
  skuId: string;
  skuName: string;
  barCode: string;
  stockNum: number;
  salePriceCents: number;
}
interface Preview {
  lines: PreviewLine[];
  skippedSkus: string[];
}
interface Progress {
  ready: boolean;
  stage: string;
  total: number;
  applied: number;
  pendingNames: string[];
  pendingCodes: string[];
}
interface SubmitResult {
  historyNo: string;
  progress: Progress;
  cabinetCaptureProducts: unknown[];
}

const loading = ref(false);
const preview = ref<Preview | null>(null);
const rows = ref<Array<PreviewLine & { priceYuan: string }>>([]);
const floorNum = ref(1);
const confirmDelist = ref(false);
const submitting = ref(false);
const historyNo = ref('');
const progress = ref<Progress | null>(null);
let pollTimer: ReturnType<typeof setInterval> | null = null;

async function loadPreview() {
  loading.value = true;
  try {
    preview.value = await api.request<Preview>(
      AdminEndpoints.jiangyiRestockPreview(props.deviceId),
      'GET'
    );
    rows.value = (preview.value?.lines || []).map((l) => ({
      ...l,
      priceYuan: (l.salePriceCents / 100).toFixed(2)
    }));
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载上架清单失败'));
  } finally {
    loading.value = false;
  }
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function pollProgress() {
  if (!historyNo.value) return;
  try {
    progress.value = await api.request<Progress>(
      AdminEndpoints.jiangyiRestockProgress(props.deviceId, historyNo.value),
      'GET'
    );
    if (progress.value?.ready) {
      stopPoll();
      ElMessage.success('识别参数已应用（APPLIED），可正常购物');
    }
  } catch {
    /* 轮询失败不弹窗，下一轮重试 */
  }
}

async function doSubmit() {
  if (!rows.value.length) {
    ElMessage.warning('清单为空，无法提交');
    return;
  }
  if (!confirmDelist.value) {
    ElMessage.warning('请先勾选「确认覆盖该层清单」');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `将向将邑提交第 ${floorNum.value} 层共 ${rows.value.length} 行最终库存；该层未列出的商品会被下架。确认提交？`,
      '同步上架到将邑',
      { type: 'warning', confirmButtonText: '提交', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  submitting.value = true;
  stopPoll();
  try {
    const lines = rows.value.map((r) => ({
      barCode: r.barCode,
      stockNum: Number(r.stockNum) || 0,
      salePriceCents: Math.round((Number(r.priceYuan) || 0) * 100)
    }));
    const res = await api.request<SubmitResult>(
      AdminEndpoints.jiangyiRestockSubmit(props.deviceId),
      'POST',
      {
        floorNum: floorNum.value,
        lines,
        confirmDelist: true
      }
    );
    historyNo.value = res.historyNo;
    progress.value = res.progress;
    ElMessage.success(`已提交，historyNo=${res.historyNo}，轮询参数下发进度…`);
    if (!res.progress?.ready) {
      pollTimer = setInterval(pollProgress, 5000);
    }
  } catch (e) {
    ElMessage.error(errorMessage(e, '提交失败'));
  } finally {
    submitting.value = false;
  }
}

onUnmounted(stopPoll);
</script>

<template>
  <div class="jy-restock">
    <div class="jy-section-label">补货上架 · 同步到将邑（§7.11/§7.12）</div>
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="一次提交 = 该层最终库存：未列出的商品会被下架。清单来自柜内库存+挂接条码+我方售价；无挂接的 SKU 已跳过。"
    />
    <div style="margin: 12px 0; display: flex; gap: 12px; align-items: center">
      <span>层号</span>
      <el-input-number v-model="floorNum" :min="1" :max="10" />
      <el-button :loading="loading" @click="loadPreview">加载清单</el-button>
    </div>

    <el-table v-if="rows.length" :data="rows" border size="small">
      <el-table-column prop="skuName" label="商品" min-width="160" />
      <el-table-column prop="barCode" label="条码" min-width="140" />
      <el-table-column label="库存" width="120">
        <template #default="{ row }"
          ><el-input-number v-model="row.stockNum" :min="0" :disabled="!canEdit"
        /></template>
      </el-table-column>
      <el-table-column label="售价(元)" width="120">
        <template #default="{ row }"
          ><el-input v-model="row.priceYuan" :disabled="!canEdit"
        /></template>
      </el-table-column>
    </el-table>
    <el-empty v-else description="点「加载清单」获取该柜可上架商品" :image-size="60" />

    <div v-if="preview && preview.skippedSkus.length" style="margin-top: 8px">
      <el-tag type="info"
        >跳过（无挂接/无条码，请先在「将邑商品」建档挂接）：{{
          preview.skippedSkus.join('、')
        }}</el-tag
      >
    </div>

    <div style="margin-top: 12px; display: flex; gap: 12px; align-items: center">
      <el-checkbox v-model="confirmDelist" :disabled="!canEdit"
        >确认覆盖该层清单（未列出即下架）</el-checkbox
      >
      <el-button type="primary" :disabled="!canEdit" :loading="submitting" @click="doSubmit"
        >同步上架到将邑</el-button
      >
    </div>

    <el-descriptions v-if="progress" :column="2" border style="margin-top: 12px">
      <el-descriptions-item label="historyNo">{{ historyNo }}</el-descriptions-item>
      <el-descriptions-item label="阶段">{{ progress.stage }}</el-descriptions-item>
      <el-descriptions-item label="进度"
        >{{ progress.applied }} / {{ progress.total }}</el-descriptions-item
      >
      <el-descriptions-item label="可购物">
        <el-tag :type="progress.ready ? 'success' : 'warning'">{{
          progress.ready ? '是' : '否（参数应用中）'
        }}</el-tag>
      </el-descriptions-item>
    </el-descriptions>
  </div>
</template>

<style scoped>
.jy-restock {
  margin-top: 8px;
}
</style>
