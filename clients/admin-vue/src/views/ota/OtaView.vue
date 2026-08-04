<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">固件版本</span>
            <span class="hint">设备端应用版本发布与灰度</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:ota:publish']" type="primary" @click="openPublish">发布版本</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table v-loading="loading" :data="items" stripe border class="report-table" row-key="releaseId">
          <template #empty><el-empty description="暂无固件版本" /></template>
          <el-table-column prop="appVersion" label="版本" min-width="120" align="center" class-name="col-text" />
          <el-table-column prop="channel" label="渠道" width="100" align="center" class-name="col-text" />
          <el-table-column prop="status" label="状态" width="100" align="center" />
          <el-table-column label="强制" width="80" align="center">
            <template #default="{ row }">{{ row.mandatory ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="灰度%" width="80" align="center">
            <template #default="{ row }">{{ row.grayPercent ?? 100 }}</template>
          </el-table-column>
          <el-table-column prop="minVersion" label="最低版本" width="110" align="center" class-name="col-text" />
          <el-table-column label="发布时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.publishedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="releaseNotes" label="说明" min-width="180" show-overflow-tooltip align="center" />
        </el-table>
      </div>
    </div>
  </el-card>

  <el-dialog v-model="dialog" title="发布固件版本" width="520px" destroy-on-close>
    <el-form label-width="110px">
      <el-form-item label="版本号" required>
        <el-input v-model="form.appVersion" placeholder="例如 1.2.0" />
      </el-form-item>
      <el-form-item label="渠道">
        <el-input v-model="form.channel" placeholder="稳定版 / 测试版（stable / beta）" />
      </el-form-item>
      <el-form-item label="下载地址" required>
        <el-input v-model="form.downloadUrl" placeholder="https://..." />
      </el-form-item>
      <el-form-item label="校验和">
        <el-input v-model="form.checksumSha256" placeholder="sha256（可选）" />
      </el-form-item>
      <el-form-item label="最低版本">
        <el-input v-model="form.minVersion" placeholder="可选" />
      </el-form-item>
      <el-form-item label="灰度 %">
        <el-input-number v-model="form.grayPercent" :min="1" :max="100" />
      </el-form-item>
      <el-form-item label="强制升级">
        <el-switch v-model="form.mandatory" />
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="form.releaseNotes" type="textarea" :rows="3" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="publish">发布</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface OtaRelease {
  releaseId?: number;
  appVersion?: string;
  channel?: string;
  downloadUrl?: string;
  checksumSha256?: string;
  releaseNotes?: string;
  mandatory?: boolean;
  minVersion?: string;
  status?: string;
  publishedAt?: string;
  grayPercent?: number;
}

const loading = ref(false);
const saving = ref(false);
const items = ref<OtaRelease[]>([]);
const dialog = ref(false);
const form = reactive({
  appVersion: '',
  channel: 'stable',
  downloadUrl: '',
  checksumSha256: '',
  releaseNotes: '',
  mandatory: false,
  minVersion: '',
  grayPercent: 100
});

async function load() {
  loading.value = true;
  try {
    items.value = await api.request<OtaRelease[]>('/api/v2/ops/admin/ota/releases', 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function openPublish() {
  form.appVersion = '';
  form.channel = 'stable';
  form.downloadUrl = '';
  form.checksumSha256 = '';
  form.releaseNotes = '';
  form.mandatory = false;
  form.minVersion = '';
  form.grayPercent = 100;
  dialog.value = true;
}

async function publish() {
  if (!form.appVersion.trim() || !form.downloadUrl.trim()) {
    ElMessage.warning('请填写版本号与下载地址');
    return;
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/ota/releases', 'POST', {
      appVersion: form.appVersion.trim(),
      channel: form.channel.trim() || 'stable',
      downloadUrl: form.downloadUrl.trim(),
      checksumSha256: form.checksumSha256.trim() || undefined,
      releaseNotes: form.releaseNotes.trim() || undefined,
      mandatory: form.mandatory,
      minVersion: form.minVersion.trim() || undefined,
      grayPercent: form.grayPercent,
      status: 'PUBLISHED'
    });
    ElMessage.success('已发布');
    dialog.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>
