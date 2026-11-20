<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import { centsToYuanDigits, yuanToCents, jiangyiStageText } from '@aicabinet/shared-uni/format';

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
// MA-225：轮询代际守卫（MA-173 admin 侧同族）——stopPoll/doSubmit 开新一代，
// 迟到的旧响应一律丢弃，不得覆盖新 historyNo 的进度或误停新单轮询。
let pollGeneration = 0;

/** 参数下发进度：阶段文案之外再给一条可读进度条（total=0 时不显示 100%）。 */
const progressPercent = computed(() => {
  const total = progress.value?.total || 0;
  if (!total) return 0;
  const applied = progress.value?.applied || 0;
  return Math.min(100, Math.max(0, Math.round((applied / total) * 100)));
});

async function loadPreview() {
  loading.value = true;
  try {
    preview.value = await api.request<Preview>(
      AdminEndpoints.jiangyiRestockPreview(props.deviceId),
      'GET'
    );
    rows.value = (preview.value?.lines || []).map((l) => ({
      ...l,
      priceYuan: centsToYuanDigits(l.salePriceCents)
    }));
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载上架清单失败'));
  } finally {
    loading.value = false;
  }
}

function stopPoll() {
  pollGeneration += 1; // MA-225：停轮询 = 开新一代，在途响应作废
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function pollProgress() {
  if (!historyNo.value) return;
  const gen = pollGeneration;
  const requested = historyNo.value;
  try {
    const next = await api.request<Progress>(
      AdminEndpoints.jiangyiRestockProgress(props.deviceId, requested),
      'GET'
    );
    if (gen !== pollGeneration || requested !== historyNo.value) return; // MA-225：迟到响应
    progress.value = next;
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
      salePriceCents: yuanToCents(r.priceYuan) ?? 0
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
  <div class="jy-panel">
    <div class="jy-panel-head">
      <div class="jy-panel-title">补货上架 · 同步到将邑</div>
      <span class="jy-panel-hint">改库存/售价不会自动重推，需重新提交一次</span>
    </div>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="jy-panel-alert"
      title="一次提交 = 该层最终库存，未列出的商品会被下架"
      description="清单来自柜内库存 + 挂接条码 + 我方售价；无挂接的 SKU 已跳过。"
    />

    <div class="jy-toolbar">
      <span class="jy-toolbar-label">层号</span>
      <el-input-number
        v-model="floorNum"
        class="jy-floor-input"
        :min="1"
        :max="10"
        controls-position="right"
      />
      <el-button :loading="loading" @click="loadPreview">加载清单</el-button>
      <span v-if="rows.length" class="jy-toolbar-hint">
        第 {{ floorNum }} 层 · {{ rows.length }} 个商品
      </span>
    </div>

    <el-table v-if="rows.length" :data="rows" border size="small">
      <!-- 截断提示走全局 installTableCellNativeTitle 的原生 title（不用 EP 溢出浮层，会盖邻列） -->
      <el-table-column prop="skuName" label="商品" min-width="200" />
      <el-table-column label="条码" min-width="150">
        <template #default="{ row }">
          <span class="jy-mono">{{ row.barCode }}</span>
        </template>
      </el-table-column>
      <el-table-column label="库存" width="140">
        <template #default="{ row }">
          <el-input-number
            v-model="row.stockNum"
            class="jy-cell-input"
            :min="0"
            :disabled="!canEdit"
            size="small"
            controls-position="right"
          />
        </template>
      </el-table-column>
      <el-table-column label="售价(元)" width="140">
        <template #default="{ row }">
          <el-input
            v-model="row.priceYuan"
            class="jy-cell-input"
            :disabled="!canEdit"
            size="small"
          />
        </template>
      </el-table-column>
    </el-table>
    <div v-else class="jy-empty-hint">
      点「加载清单」获取该柜可上架商品：清单 = 柜内库存 + 挂接条码 + 我方售价。
    </div>

    <div v-if="preview && preview.skippedSkus.length" class="jy-skipped">
      <el-tag type="info" size="small">
        跳过（无挂接/无条码，请先在「将邑商品」建档挂接）：{{ preview.skippedSkus.join('、') }}
      </el-tag>
    </div>

    <div class="jy-submit-bar">
      <el-checkbox v-model="confirmDelist" :disabled="!canEdit">
        确认覆盖该层清单（未列出即下架）
      </el-checkbox>
      <el-button
        type="primary"
        :disabled="!canEdit || !rows.length"
        :loading="submitting"
        @click="doSubmit"
        >同步上架到将邑</el-button
      >
    </div>

    <div v-if="progress" class="jy-progress">
      <div class="jy-progress-head">
        <span class="jy-progress-label">参数下发进度</span>
        <el-tag size="small" :type="progress.ready ? 'success' : 'warning'">{{
          progress.ready ? '可购物' : '参数应用中'
        }}</el-tag>
        <span class="jy-progress-meta">
          {{ jiangyiStageText(progress.stage) }} · {{ progress.applied }}/{{ progress.total }} 项 ·
          historyNo <code class="jy-mono">{{ historyNo }}</code>
        </span>
      </div>
      <el-progress
        :percentage="progressPercent"
        :stroke-width="8"
        :status="progress.ready ? 'success' : ''"
      />
    </div>
  </div>
</template>

<style scoped>
.jy-panel {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.jy-panel-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.jy-panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.jy-panel-title::before {
  content: '';
  width: 3px;
  height: 14px;
  border-radius: 2px;
  background: var(--el-color-primary);
}
.jy-panel-hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
.jy-panel-alert :deep(.el-alert__description) {
  margin-top: 2px;
  line-height: 1.5;
}
.jy-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.jy-toolbar-label {
  font-size: var(--admin-font-size-table);
  color: var(--el-text-color-regular);
}
.jy-floor-input {
  width: 120px;
}
.jy-toolbar-hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
/* 单元格内控件填满列宽，避免「列很宽、控件缩在左边」 */
.jy-cell-input {
  width: 100%;
}
.jy-skipped {
  margin-top: 8px;
}
.jy-empty-hint {
  padding: 10px 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  font-size: var(--admin-font-size-sm);
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.jy-submit-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 12px;
}
.jy-progress {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.jy-progress-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.jy-progress-label {
  font-size: var(--admin-font-size-table);
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.jy-progress-meta {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
.jy-mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
</style>
