<template>
  <el-card class="page-card report-page demo-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">识别演示</span>
            <span class="hint">上传商品图，识别后匹配商品名称与价格</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-tag type="info" effect="plain">仅预览，不扣款</el-tag>
          <el-button v-if="canAccessPath('/vision-mappings')" @click="goPath('/vision-mappings')"
            >识别映射</el-button
          >
          <el-button v-if="canAccessPath('/skus')" @click="goPath('/skus')">商品管理</el-button>
          <el-button v-if="canAccessPath('/sku-vision')" @click="goPath('/sku-vision')"
            >识别入驻</el-button
          >
        </div>
      </div>
    </template>

    <div class="demo-layout">
      <section class="upload-panel">
        <div
          class="drop-zone"
          :class="{ dragging: dragging, 'has-image': !!previewUrl }"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="onDrop"
          @click="triggerPick"
        >
          <label for="recognition-demo-file-input" class="hidden-input-label">选择商品图片</label>
          <input
            id="recognition-demo-file-input"
            ref="fileInput"
            type="file"
            accept="image/*"
            class="hidden-input"
            @change="onPick"
          />
          <img
            v-if="previewUrl"
            :src="previewUrl"
            alt="预览"
            class="preview-image"
            width="640"
            height="360"
          />
          <div v-else class="drop-placeholder">
            <el-icon :size="48"><UploadFilled /></el-icon>
            <p>点击或拖拽上传商品图片</p>
            <span>支持 JPG / PNG，建议单品正面清晰图</span>
          </div>
        </div>

        <div class="actions">
          <el-button
            v-hasPermi="['ops:sku:demo']"
            type="primary"
            :loading="recognizing"
            :disabled="!imageFile"
            @click="runRecognize"
          >
            开始识别
          </el-button>
          <el-button :disabled="!imageFile" @click="clearImage">清空</el-button>
        </div>
      </section>

      <section class="result-panel">
        <el-empty v-if="!result && !recognizing" description="识别结果将显示在这里" />

        <div v-if="recognizing" class="loading-block">
          <el-skeleton :rows="4" animated />
        </div>

        <template v-if="result && !recognizing">
          <el-alert
            :title="result.hint || '识别完成'"
            :type="result.needReview ? 'warning' : 'success'"
            show-icon
            :closable="false"
            class="result-alert"
          />

          <div v-if="result.items?.length" class="result-list">
            <div v-for="item in result.items" :key="item.skuId" class="result-item">
              <div class="item-main">
                <div class="name-cell">
                  <strong>{{ item.skuName || item.skuId }}</strong>
                  <small class="cell-id">{{ item.skuId }} · ×{{ item.quantity }}</small>
                </div>
              </div>
              <div class="item-price">
                <div v-if="item.unitPriceCents != null" class="unit">
                  ¥{{ cents(item.unitPriceCents) }}
                </div>
                <div v-if="item.lineAmountCents != null" class="line">
                  小计 ¥{{ cents(item.lineAmountCents) }}
                </div>
                <div class="confidence">置信度 {{ Math.round((item.confidence || 0) * 100) }}%</div>
              </div>
            </div>

            <div v-if="totalCents > 0" class="total-row">
              <span>合计</span>
              <strong>¥{{ cents(totalCents) }}</strong>
            </div>
          </div>

          <el-descriptions v-else :column="1" border size="small" class="meta-block">
            <el-descriptions-item label="检测类名">
              {{ (result.detectedClasses || []).join('、') || '无' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-descriptions :column="1" border size="small" class="meta-block">
            <el-descriptions-item label="模型">{{
              result.modelVersion || '无'
            }}</el-descriptions-item>
            <el-descriptions-item label="整体置信度">
              {{ Math.round((result.overallConfidence || 0) * 100) }}%
            </el-descriptions-item>
            <el-descriptions-item label="自动扣款">
              <el-tag :type="result.needReview ? 'warning' : 'success'" size="small">
                {{ result.needReview ? '需人工审核' : '可自动扣款' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </template>
      </section>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { UploadFilled } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { DevRecognitionPreviewDto } from '@aicabinet/shared-types';
import { authFetch } from '@/api/client';
import { useNavAccess } from '@/composables/useNavAccess';

const { canAccessPath, goPath } = useNavAccess();
const dragging = ref(false);
const recognizing = ref(false);
const imageFile = ref<File | null>(null);
const previewUrl = ref('');
const result = ref<DevRecognitionPreviewDto | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);

const totalCents = computed(() =>
  (result.value?.items || []).reduce((sum, i) => sum + (i.lineAmountCents ?? 0), 0)
);

function cents(v: number) {
  return (v / 100).toFixed(2);
}

function setFile(file: File | null) {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
  imageFile.value = file;
  previewUrl.value = file ? URL.createObjectURL(file) : '';
  result.value = null;
}

function triggerPick() {
  fileInput.value?.click();
}

function onPick(ev: Event) {
  const file = (ev.target as HTMLInputElement).files?.[0];
  if (file) setFile(file);
}

function onDrop(ev: DragEvent) {
  dragging.value = false;
  const file = ev.dataTransfer?.files?.[0];
  if (file?.type.startsWith('image/')) setFile(file);
}

function clearImage() {
  setFile(null);
  if (fileInput.value) fileInput.value.value = '';
}

async function runRecognize() {
  if (!imageFile.value) return;
  recognizing.value = true;
  try {
    const base =
      (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
    const form = new FormData();
    form.append('image', imageFile.value);
    form.append('deviceId', 'CAB-001');
    const res = await authFetch(`${base}/api/v2/ops/recognition-preview`, {
      method: 'POST',
      body: form
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok || json.code !== 0) {
      throw new Error(json.message || `识别失败 (${res.status})`);
    }
    result.value = json.data as DevRecognitionPreviewDto;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '识别失败');
  } finally {
    recognizing.value = false;
  }
}

onBeforeUnmount(() => {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta {
  min-width: 0;
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
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.demo-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}

@media (max-width: 900px) {
  .demo-layout {
    grid-template-columns: 1fr;
  }
}

.drop-zone {
  min-height: 320px;
  border: 2px dashed var(--el-border-color);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  overflow: hidden;
  background: var(--el-fill-color-light);
  transition:
    border-color 0.2s,
    background 0.2s,
    box-shadow 0.2s;
}

.drop-zone.dragging,
.drop-zone:hover {
  border-color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 14%, var(--el-fill-color-light));
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--el-color-primary) 35%, transparent);
}

.preview-image {
  width: 100%;
  height: 100%;
  max-height: 420px;
  object-fit: contain;
}

.drop-placeholder {
  text-align: center;
  color: var(--el-text-color-secondary);
  padding: 24px;
}

.drop-placeholder p {
  margin: 12px 0 4px;
  font-size: 16px;
  color: var(--el-text-color-primary);
}

.hidden-input {
  display: none;
}
.hidden-input-label {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}

.result-panel {
  min-height: 320px;
}
.result-alert {
  margin-bottom: 16px;
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.result-item {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-light);
}

.name-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
}
.name-cell strong {
  font-weight: 650;
  font-size: 16px;
}
.name-cell small {
  color: var(--el-text-color-secondary);
  font-family: inherit;
}

.item-price {
  text-align: right;
  flex-shrink: 0;
}

.item-price .unit {
  font-size: 18px;
  font-weight: 700;
  color: var(--el-color-danger);
  font-variant-numeric: tabular-nums;
}

.item-price .line {
  margin-top: 4px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.item-price .confidence {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.total-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  margin-top: 4px;
  border-top: 1px dashed var(--el-border-color);
  font-size: 16px;
}

.meta-block {
  margin-top: 16px;
}
.loading-block {
  padding: 8px 0;
}
</style>
