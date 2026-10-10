<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import { formatDateTime } from '@aicabinet/shared-uni/format';

/**
 * 订单取货视频复核（CB-029）。
 *
 * <p>位置：挂到「订单管理」的订单详情 —— 顾客对某笔扣款有异议时，按该笔订单调取柜机
 * 开柜视频自证（与已有的「播放会话录像」并列，但那是旧存储的会话录像，本条是将邑柜机
 * 上报的分片视频，存在私有 OSS 桶里）。</p>
 *
 * <p>🔴 <b>免映射</b>：将邑上报的 orderNo 就是我方 sessionId（网关枢纽约定，V16 §4.3.2.7
 * 「商户服务器生成、需唯一」），所以订单详情拿到 sessionId 直接查即可，不需要额外的映射表。</p>
 *
 * <p>🔴 <b>权限独立</b>：入口用 ops:device:video 控制（不复用订单查看权）—— 交易视频含
 * 顾客影像，属行业团标单独约束的对象。播放地址由服务端签发（短时效），前端拿不到桶读凭证。</p>
 */

interface OrderVideoRow {
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
  orderNo: string;
  deviceId: string | null;
  serialNum: number;
  videoQuantity: number;
  reportedAt: string;
  items: PlayUrlItem[];
}

const visible = ref(false);
const sessionId = ref('');
const loading = ref(false);
const rows = ref<OrderVideoRow[]>([]);
const detail = ref<PlayUrlView | null>(null);
const detailId = ref<number | null>(null);
const detailLoading = ref(false);

function open(order: { sessionId?: string | null }) {
  sessionId.value = (order.sessionId || '').trim();
  detail.value = null;
  detailId.value = null;
  rows.value = [];
  visible.value = true;
  void load();
}

async function load() {
  if (!sessionId.value) {
    ElMessage.warning('该订单没有关联会话，无法调取柜机视频');
    return;
  }
  loading.value = true;
  try {
    rows.value = await api.request<OrderVideoRow[]>(
      AdminEndpoints.jiangyiOrderVideosByOrder(sessionId.value),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '查询订单视频失败'));
  } finally {
    loading.value = false;
  }
}

async function openDetail(row: OrderVideoRow) {
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
  <el-dialog v-model="visible" title="取货视频复核" class="dialog-wide" destroy-on-close>
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="ov-tip"
      title="交易视频含顾客影像，属敏感信息：仅授权账号可查看，播放链接短时效，请勿外传或留存"
    />

    <div v-loading="loading" class="ov-body">
      <el-table :data="rows" size="small" empty-text="该订单暂无柜机视频（正常单视频留在柜机本地）">
        <el-table-column prop="serialNum" label="分片" width="70" />
        <el-table-column prop="videoQuantity" label="总片数" width="80" />
        <el-table-column label="视频" width="100">
          <template #default="{ row }">
            <el-tag v-if="clipCount(row.videoUrls) === 0" type="info" size="small">该片失败</el-tag>
            <span v-else>{{ clipCount(row.videoUrls) }} 段</span>
          </template>
        </el-table-column>
        <el-table-column prop="reportedAt" label="上报时间" min-width="150">
          <template #default="{ row }">{{ formatDateTime(row.reportedAt) }}</template>
        </el-table-column>
        <el-table-column prop="deviceId" label="柜机" min-width="130">
          <template #default="{ row }">{{ row.deviceId || '—' }}</template>
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
        <div class="ov-section-label">第 {{ detail?.serialNum ?? '' }} 片录像</div>
        <div v-loading="detailLoading" class="ov-clips">
          <div v-for="clip in detail?.items || []" :key="clip.index" class="ov-clip">
            <div class="ov-clip-head">
              <span>第 {{ clip.index }} 段</span>
              <el-tag v-if="!clip.playable" type="info" size="small">
                {{ clip.reason || '不可播放' }}
              </el-tag>
              <el-tag v-else-if="!clip.signed" type="warning" size="small">外部地址</el-tag>
            </div>
            <video
              v-if="clip.playable && clip.url"
              class="ov-video"
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
.ov-tip {
  margin-bottom: 12px;
}
.ov-body {
  min-height: 120px;
}
.ov-section-label {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin: 14px 0 6px;
}
.ov-clips {
  min-height: 60px;
}
.ov-clip {
  margin-top: 10px;
}
.ov-clip-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--color-text-secondary);
  margin-bottom: 4px;
}
.ov-video {
  width: 100%;
  max-height: 360px;
  background: #000;
  border-radius: 4px;
}
</style>
