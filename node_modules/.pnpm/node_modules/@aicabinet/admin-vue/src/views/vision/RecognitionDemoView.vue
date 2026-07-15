<template>
  <div class="demo-page">
    <el-card class="demo-card">
      <template #header>
        <div class="demo-header">
          <div>
            <h2>识别 Demo</h2>
            <p class="subtitle">上传商品图片，YOLO 识别后自动匹配 SKU 名称与价格</p>
          </div>
          <el-tag type="info">仅预览，不扣款</el-tag>
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
            <input ref="fileInput" type="file" accept="image/*" class="hidden-input" @change="onPick" />
            <img v-if="previewUrl" :src="previewUrl" alt="预览" class="preview-image" />
            <div v-else class="drop-placeholder">
              <el-icon :size="48"><UploadFilled /></el-icon>
              <p>点击或拖拽上传商品图片</p>
              <span>支持 JPG / PNG，建议单品正面清晰图</span>
            </div>
          </div>

          <div class="actions">
            <el-button type="primary" size="large" :loading="recognizing" :disabled="!imageFile" @click="runRecognize">
              开始识别
            </el-button>
            <el-button size="large" :disabled="!imageFile" @click="clearImage">清空</el-button>
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
                  <div class="item-name">{{ item.skuName || item.skuId }}</div>
                  <div class="item-meta">
                    <code>{{ item.skuId }}</code>
                    <span>× {{ item.quantity }}</span>
                  </div>
                </div>
                <div class="item-price">
                  <div v-if="item.unitPriceCents != null" class="unit">¥{{ cents(item.unitPriceCents) }}</div>
                  <div v-if="item.lineAmountCents != null" class="line">小计 ¥{{ cents(item.lineAmountCents) }}</div>
                  <div class="confidence">置信度 {{ Math.round((item.confidence || 0) * 100) }}%</div>
                </div>
              </div>

              <div v-if="totalCents > 0" class="total-row">
                <span>合计</span>
                <strong>¥{{ cents(totalCents) }}</strong>
              </div>
            </div>

            <el-descriptions v-else :column="1" border size="small" class="meta-block">
              <el-descriptions-item label="YOLO 检测类">
                {{ (result.detectedClasses || []).join('、') || '无' }}
              </el-descriptions-item>
              <el-descriptions-item label="模型">{{ result.modelVersion || '—' }}</el-descriptions-item>
            </el-descriptions>

            <el-descriptions :column="1" border size="small" class="meta-block">
              <el-descriptions-item label="模型">{{ result.modelVersion || '—' }}</el-descriptions-item>
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
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { UploadFilled } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { DevRecognitionPreviewDto } from '@aicabinet/shared-types';

const dragging = ref(false);
const recognizing = ref(false);
const imageFile = ref<File | null>(null);
const previewUrl = ref('');
const result = ref<DevRecognitionPreviewDto | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);

const totalCents = computed(() =>
  (result.value?.items || []).reduce((sum, i) => sum + (i.lineAmountCents || 0), 0)
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
    const token = localStorage.getItem('admin_token');
    const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || window.location.origin;
    const form = new FormData();
    form.append('image', imageFile.value);
    form.append('deviceId', 'CAB-001');
    const res = await fetch(`${base}/api/v2/ops/recognition-preview`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
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
.demo-page {
  max-width: 1080px;
  margin: 0 auto;
}

.demo-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.demo-header h2 {
  margin: 0 0 4px;
  font-size: 20px;
}

.subtitle {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
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
  transition: border-color 0.2s, background 0.2s, box-shadow 0.2s;
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
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-light);
}

.item-name {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 6px;
}

.item-meta {
  display: flex;
  gap: 10px;
  align-items: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.item-price {
  text-align: right;
  flex-shrink: 0;
}

.item-price .unit {
  font-size: 20px;
  font-weight: 700;
  color: var(--el-color-danger);
}

.item-price .line {
  margin-top: 4px;
  font-size: 14px;
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
