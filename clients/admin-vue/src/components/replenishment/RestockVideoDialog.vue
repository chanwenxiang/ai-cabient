<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import { formatDateTime } from '@aicabinet/shared-uni/format';

/**
 * 补货任务「补货视频查看」（将邑收尾E 入口）。
 *
 * <p>将邑柜补货开门后，柜机按 §4.2.14 上报补货视频到 `jiangyi_order_video`
 * （orderNo = 补货会话 id，协议枢纽不变）。本对话框按补货任务汇总其全部
 * 开门会话的台账行，播放地址由服务端按行现签（短时效）。</p>
 *
 * <p>🔴 权限独立：ops:device:video（交易/补货视频属受约束对象，不复用任务查看权）。</p>
 */

interface RestockVideoRow {
  id: number;
  orderNo: string;
  deviceId: string | null;
  serialNum: number;
  videoQuantity: number;
  videoUrls: string;
  reportedAt: string;
}

interface PlayUrlItem {
  index: number;
  url: string | null;
  signed: boolean;
  playable: boolean;
  reason: string | null;
}

interface PlayUrlView {
  id: number;
  serialNum: number;
  items: PlayUrlItem[];
}

const visible = ref(false);
const taskId = ref<number | null>(null);
const loading = ref(false);
const rows = ref<RestockVideoRow[]>([]);
const detail = ref<PlayUrlView | null>(null);
const detailId = ref<number | null>(null);
const detailLoading = ref(false);

function open(task: { taskId?: number | null }) {
  taskId.value = task.taskId ?? null;
  rows.value = [];
  detail.value = null;
  detailId.value = null;
  visible.value = true;
  void load();
}

async function load() {
  if (!taskId.value) {
    ElMessage.warning('该任务没有关联补货会话');
    return;
  }
  loading.value = true;
  try {
    rows.value = await api.request<RestockVideoRow[]>(
      AdminEndpoints.jiangyiOrderVideosByTask(taskId.value),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '查询补货视频失败'));
  } finally {
    loading.value = false;
  }
}

async function openDetail(row: RestockVideoRow) {
  detailId.value = row.id;
  detailLoading.value = true;
  detail.value = null;
  try {
    detail.value = await api.request<PlayUrlView>(
      AdminEndpoints.jiangyiOrderVideoPlayUrl(row.id),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '获取播放地址失败'));
    detailId.value = null;
  } finally {
    detailLoading.value = false;
  }
}

function clipCount(videoUrls: string | null): number {
  const raw = (videoUrls || '').trim();
  return raw ? raw.split(',').filter((u) => u.trim()).length : 0;
}

defineExpose({ open });
</script>

<template>
  <el-dialog v-model="visible" title="补货视频查看" class="dialog-wide" destroy-on-close>
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="rv-tip"
      title="补货视频含柜内影像，属敏感信息：仅授权账号可查看，播放链接短时效，请勿外传或留存"
    />

    <div v-loading="loading" class="rv-body">
      <el-table :data="rows" size="small" empty-text="该任务暂无补货视频（柜机上报后自动出现在这里）">
        <el-table-column prop="orderNo" label="补货会话" min-width="150">
          <template #default="{ row }">
            <span class="rv-mono">{{ row.orderNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="serialNum" label="分片" width="70" />
        <el-table-column prop="videoQuantity" label="总片数" width="80" />
        <el-table-column label="视频" width="100">
          <template #default="{ row }">
            <el-tag v-if="clipCount(row.videoUrls) === 0" type="info" size="small">未生成</el-tag>
            <span v-else>{{ clipCount(row.videoUrls) }} 段</span>
          </template>
        </el-table-column>
        <el-table-column prop="reportedAt" label="上报时间" min-width="150">
          <template #default="{ row }">{{ formatDateTime(row.reportedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              :loading="detailLoading && detailId === row.id"
              @click="openDetail(row)"
            >
              {{ detailId === row.id && detail ? '重新加载' : '查看视频' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <template v-if="detail || detailLoading">
        <div class="rv-section-label">第 {{ detail?.serialNum ?? '' }} 片录像</div>
        <div v-loading="detailLoading" class="rv-clips">
          <div v-for="clip in detail?.items || []" :key="clip.index" class="rv-clip">
            <div class="rv-clip-head">
              <span>第 {{ clip.index }} 段</span>
              <el-tag v-if="!clip.playable" type="info" size="small">
                {{ clip.reason || '不可播放' }}
              </el-tag>
              <el-tag v-else-if="!clip.signed" type="warning" size="small">外部地址</el-tag>
            </div>
            <video
              v-if="clip.playable && clip.url"
              class="rv-video"
              :src="clip.url"
              controls
              preload="metadata"
            ></video>
          </div>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<style scoped>
.rv-tip {
  margin-bottom: 12px;
}
.rv-body {
  min-height: 120px;
}
.rv-mono {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}
.rv-section-label {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin: 14px 0 6px;
}
.rv-clips {
  min-height: 60px;
}
.rv-clip {
  margin-top: 10px;
}
.rv-clip-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--color-text-secondary);
  margin-bottom: 4px;
}
.rv-video {
  width: 100%;
  max-height: 360px;
  background: #000;
  border-radius: 4px;
}
</style>
