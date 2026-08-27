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
          <el-button v-hasPermi="['ops:ota:publish']" type="primary" @click="openPublish"
            >发布版本</el-button
          >
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="items"
          stripe
          border
          class="report-table"
          row-key="releaseId"
          empty-text=" "
        >
          <template #empty
            ><el-empty v-if="listHydrated && !loading" description="暂无固件版本"
          /></template>
          <el-table-column
            prop="appVersion"
            label="版本"
            min-width="120"
            align="center"
            class-name="col-text"
          />
          <el-table-column
            prop="channel"
            label="渠道"
            width="100"
            align="center"
            class-name="col-text"
          >
            <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100" align="center">
            <template #default="{ row }">{{ statusLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column label="强制" width="80" align="center">
            <template #default="{ row }">{{ row.mandatory ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="灰度%" width="80" align="center">
            <template #default="{ row }">{{ row.grayPercent ?? 100 }}</template>
          </el-table-column>
          <el-table-column label="定向设备" width="110" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.deviceAllowlist?.length" size="small" type="warning" effect="plain">
                {{ row.deviceAllowlist.length }} 台
              </el-tag>
              <span v-else>全量</span>
            </template>
          </el-table-column>
          <el-table-column
            prop="minVersion"
            label="最低版本"
            width="110"
            align="center"
            class-name="col-text"
          />
          <el-table-column label="发布时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.publishedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            prop="releaseNotes"
            label="说明"
            min-width="180"
            show-overflow-tooltip
            align="center"
          />
          <el-table-column label="操作" width="100" align="center" class-name="col-action">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'PUBLISHED'"
                v-hasPermi="['ops:ota:publish']"
                link
                type="danger"
                @click="unpublish(row)"
                >下架</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>

  <el-dialog v-model="dialog" title="发布固件版本" width="520px" destroy-on-close>
    <el-form label-width="110px">
      <el-form-item label="版本号" required>
        <el-input v-model="form.appVersion" placeholder="例如 1.2.0…" />
      </el-form-item>
      <el-form-item label="渠道">
        <el-input v-model="form.channel" placeholder="稳定版 / 测试版（stable / beta）…" />
      </el-form-item>
      <el-form-item label="下载地址" required>
        <el-input v-model="form.downloadUrl" placeholder="https://cdn.example.com/app.apk…" />
      </el-form-item>
      <el-form-item label="校验和">
        <el-input v-model="form.checksumSha256" placeholder="sha256（可选）…" />
      </el-form-item>
      <el-form-item label="最低版本">
        <el-input v-model="form.minVersion" placeholder="可选…" />
      </el-form-item>
      <el-form-item label="灰度 %">
        <el-input-number v-model="form.grayPercent" :min="1" :max="100" />
      </el-form-item>
      <el-form-item label="定向设备">
        <el-select
          v-model="form.deviceAllowlist"
          multiple
          filterable
          clearable
          placeholder="不选则全量 / 按灰度%"
          style="width: 100%"
        >
          <el-option
            v-for="d in deviceOptions"
            :key="d.deviceId"
            :label="`${d.deviceName || d.deviceId}（${d.deviceId}）`"
            :value="d.deviceId"
          />
        </el-select>
        <div class="field-hint">指定后仅这些柜机收到该版本（定向优先于灰度）</div>
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
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import { useDeviceOptions } from '@/composables/useDeviceOptions';
import { formatDateTime } from '@aicabinet/shared-uni/format';

function statusLabel(s?: string) {
  const m: Record<string, string> = {
    DRAFT: '草稿',
    PUBLISHED: '已发布',
    UNPUBLISHED: '已下架',
    ARCHIVED: '已归档'
  };
  return (s && m[s]) || (s ? '未知' : '暂无');
}

function channelLabel(c?: string) {
  const m: Record<string, string> = {
    stable: '稳定版',
    beta: '测试版',
    canary: '灰度',
    internal: '内部'
  };
  return (c && m[c]) || (c ? '未知' : '暂无');
}

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
  deviceAllowlist?: string[];
}

const { deviceOptions, loadDeviceOptions } = useDeviceOptions();
const loading = ref(false);
const listHydrated = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
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
  grayPercent: 100,
  deviceAllowlist: [] as string[]
});

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    const data = await api.request<{ items: OtaRelease[]; total: number }>(
      `/api/v2/ops/admin/ota/releases?${q}`,
      'GET'
    );
    items.value = data.items || [];
    total.value = Number(data.total) || 0;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function onSizeChange() {
  page.value = 1;
  load();
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
  form.deviceAllowlist = [];
  dialog.value = true;
  void loadDeviceOptions();
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
      deviceAllowlist: form.deviceAllowlist.length ? form.deviceAllowlist : undefined,
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

async function unpublish(row: OtaRelease) {
  try {
    await ElMessageBox.confirm(
      `确认下架版本 ${row.appVersion}？设备端将停止收到该版本。`,
      '下架版本',
      {
        type: 'warning',
        confirmButtonText: '下架',
        cancelButtonText: '取消'
      }
    );
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/ota/releases/${row.releaseId}/unpublish`, 'POST', {});
    ElMessage.success('已下架');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '下架失败');
  }
}

onMounted(load);
</script>

<style scoped>
.field-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 4px;
}
</style>
