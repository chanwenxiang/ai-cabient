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
 * 开柜视频自证。本页原先并列的「播放会话录像」已下线（走旧边缘 /ops/sessions/{id}/video，
 * 后端只读 shopping_session.video_uri 且无将邑回退，对将邑柜机恒 404），故本条现在是订单
 * 管理页唯一的视频入口：将邑柜机上报的分片视频，存在私有 OSS 桶里，由服务端签发短时效
 * 播放地址。</p>
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
  /** DC-11：列表脱敏——服务端不再回 videoUrls 对象键，只回解析后的片数统计 */
  videoCount: number;
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

function clipLabel(clip: PlayUrlItem): string {
  const u = (clip.url || '').toLowerCase();
  if (u.includes('left')) return `第 ${clip.index} 路（左镜头）`;
  if (u.includes('right')) return `第 ${clip.index} 路（右镜头）`;
  return `第 ${clip.index} 路`;
}

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

/**
 * 按单拉取柜内视频（V16 §4.3.2.11，方案 A）：正常单默认只留柜机本地 64G，
 * 循环覆盖前拉回才看得到。指令下发是异步的——成功仅表示「柜机收到」，
 * 上传+入库要等一会儿，靠用户点「刷新列表」看结果（与设备信息实时拉取同模式）。
 */
const pulling = ref(false);
async function pullVideo() {
  if (!sessionId.value) {
    ElMessage.warning('该订单没有关联会话，无法拉取');
    return;
  }
  pulling.value = true;
  try {
    await api.request<unknown>(AdminEndpoints.jiangyiOrderVideoPull, 'POST', {
      orderNo: sessionId.value
    });
    ElMessage.success('拉取指令已下发：柜机在线且视频未被覆盖时会自动上传，稍候点「刷新列表」查看');
  } catch (e) {
    ElMessage.error(errorMessage(e, '拉取指令下发失败（常见原因：柜机离线）'));
  } finally {
    pulling.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <!-- append-to-body：本弹窗现在还从「会话时间线」抽屉与「争议审单」抽屉里打开，
       抽屉遮罩是 body 级 z-index，留在卡片内会被它整片盖住（弹窗看得见、点不动）。 -->
  <el-dialog
    v-model="visible"
    title="取货视频复核"
    class="dialog-wide"
    append-to-body
    destroy-on-close
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="ov-tip"
      title="交易视频含顾客影像，属敏感信息：仅授权账号可查看，播放链接短时效，请勿外传或留存"
    />

    <div class="ov-actions">
      <el-button size="small" :loading="loading" @click="load">刷新列表</el-button>
      <el-button size="small" type="primary" :loading="pulling" @click="pullVideo">
        拉取柜内视频
      </el-button>
      <span class="ov-actions-hint">
        正常单视频只存在柜机本地（循环覆盖），需柜机在线时按需拉取；下发后稍候刷新
      </span>
    </div>

    <div v-loading="loading" class="ov-body">
      <el-table
        :data="rows"
        size="small"
        empty-text="该会话暂无柜机视频（正常单视频留在柜机本地，可下发拉取）"
      >
        <el-table-column prop="serialNum" label="分片" width="70" />
        <el-table-column prop="videoQuantity" label="总片数" width="80" />
        <el-table-column label="视频" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.videoCount === 0" type="info" size="small">该片失败</el-tag>
            <span v-else>{{ row.videoCount }} 路镜头</span>
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
              <span>{{ clipLabel(clip) }}</span>
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
.ov-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.ov-actions-hint {
  font-size: 12px;
  color: var(--color-text-secondary);
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
