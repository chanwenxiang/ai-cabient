<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';

const props = defineProps<{ deviceId: string; canEdit: boolean }>();

interface ShelfItem {
  productId: number;
  productName: string;
  salePriceYuan: number;
  stock: number;
}
interface ShelfFloor {
  floorNum: number;
  floorOrientation: string | null;
  items: ShelfItem[];
}
interface VendorShelf {
  deviceName: string;
  identifier: string;
  totalFloorNum: number;
  floors: ShelfFloor[];
}
interface CaptureProduct {
  productId: number;
  name: string;
  barCode: string;
  mainImg: string;
  onShelf: boolean;
}
interface CaptureSession {
  sessionId: number;
  deviceIdentifier: string;
  productId: number;
  phase: string;
  error: string;
}

const shelfLoading = ref(false);
const shelf = ref<VendorShelf | null>(null);

const listLoading = ref(false);
const products = ref<CaptureProduct[]>([]);
const session = ref<CaptureSession | null>(null);
const busy = ref(false);
let pollTimer: ReturnType<typeof setInterval> | null = null;

const TERMINAL = ['DONE', 'FAILED', 'CANCELLED'];

async function loadShelf() {
  shelfLoading.value = true;
  try {
    shelf.value = await api.request<VendorShelf>(
      AdminEndpoints.jiangyiVendorShelf(props.deviceId),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '读取厂商货道失败'));
  } finally {
    shelfLoading.value = false;
  }
}

async function loadList() {
  listLoading.value = true;
  try {
    products.value = await api.request<CaptureProduct[]>(
      AdminEndpoints.jiangyiCabinetCaptureList(props.deviceId),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '读取待采集商品失败'));
  } finally {
    listLoading.value = false;
  }
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function pollStatus() {
  if (!session.value) return;
  try {
    session.value = await api.request<CaptureSession>(
      AdminEndpoints.jiangyiCabinetCaptureStatus(props.deviceId, session.value.sessionId),
      'GET'
    );
    if (TERMINAL.includes(session.value.phase)) {
      stopPoll();
      if (session.value.phase === 'DONE') ElMessage.success('柜内采集完成，等待将邑审核');
      else if (session.value.phase === 'FAILED')
        ElMessage.error('柜内采集失败：' + (session.value.error || '未知'));
    }
  } catch {
    /* 下一轮重试 */
  }
}

async function startCapture(p: CaptureProduct) {
  if (!p.onShelf) {
    ElMessage.warning('该商品未上架，先补货上架再采集');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `开始后设备会自动开锁并录制，请在柜前按提示展示「${p.name}」；同一台柜同时只能有一个采集会话。`,
      '开始柜内采集',
      { type: 'warning', confirmButtonText: '开始', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  busy.value = true;
  stopPoll();
  try {
    session.value = await api.request<CaptureSession>(
      AdminEndpoints.jiangyiCabinetCaptureStart(props.deviceId),
      'POST',
      { productId: p.productId }
    );
    ElMessage.success(`已开始，会话 ${session.value.sessionId}`);
    pollTimer = setInterval(pollStatus, 1000);
  } catch (e) {
    ElMessage.error(errorMessage(e, '开始采集失败'));
  } finally {
    busy.value = false;
  }
}

async function finishCapture() {
  if (!session.value) return;
  busy.value = true;
  try {
    session.value = await api.request<CaptureSession>(
      AdminEndpoints.jiangyiCabinetCaptureFinish(props.deviceId, session.value.sessionId),
      'POST'
    );
    stopPoll();
    await pollStatus();
  } catch (e) {
    ElMessage.error(errorMessage(e, '结束采集失败'));
  } finally {
    busy.value = false;
  }
}

async function cancelCapture() {
  if (!session.value) return;
  busy.value = true;
  try {
    session.value = await api.request<CaptureSession>(
      AdminEndpoints.jiangyiCabinetCaptureCancel(props.deviceId, session.value.sessionId),
      'POST'
    );
    stopPoll();
    ElMessage.info('已取消采集会话');
  } catch (e) {
    ElMessage.error(errorMessage(e, '取消采集失败'));
  } finally {
    busy.value = false;
  }
}

onUnmounted(stopPoll);
</script>

<template>
  <div class="jy-capture">
    <div class="jy-section-label">厂商货道核对（§7.13，只读）</div>
    <el-button :loading="shelfLoading" @click="loadShelf">读取厂商当前货道</el-button>
    <template v-if="shelf">
      <el-descriptions :column="3" border style="margin-top: 12px">
        <el-descriptions-item label="设备">{{ shelf.deviceName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="编码">{{ shelf.identifier || '—' }}</el-descriptions-item>
        <el-descriptions-item label="总层数">{{ shelf.totalFloorNum }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="shelf.floors" border size="small" style="margin-top: 8px" row-key="floorNum">
        <el-table-column type="expand">
          <template #default="{ row }">
            <el-table :data="row.items" size="small" border>
              <el-table-column prop="productName" label="商品" min-width="180" />
              <el-table-column prop="productId" label="厂商商品 ID" width="120" />
              <el-table-column prop="salePriceYuan" label="售价(元)" width="100" />
              <el-table-column prop="stock" label="库存" width="90" />
            </el-table>
          </template>
        </el-table-column>
        <el-table-column prop="floorNum" label="层号" width="90" />
        <el-table-column prop="floorOrientation" label="朝向" width="90">
          <template #default="{ row }">{{ row.floorOrientation || '单门不区分' }}</template>
        </el-table-column>
        <el-table-column label="商品数" width="90">
          <template #default="{ row }">{{ row.items.length }}</template>
        </el-table-column>
      </el-table>
    </template>

    <div class="jy-section-label">柜内采集（§7.14~§7.18）</div>
    <el-alert type="info" :closable="false" show-icon title="柜内采集规范（§7.3）">
      <template #default>
        <div class="capture-rules">
          <div>· 商品须已补货上架到该柜；每次只采集一个商品</div>
          <div>· 开始录制后拉开柜门，拿出商品，在上下摄像头前缓慢转动展示各角度</div>
          <div>· 放回原位、关好柜门后再点「结束采集」</div>
          <div>· 上下摄像头各需 30~100 张有效画面，任一不足 30 张需重新采集</div>
          <div>· 未结束会话占用该柜约 15 分钟，异常时点「取消会话」释放</div>
        </div>
      </template>
    </el-alert>
    <el-button style="margin: 8px 0" :loading="listLoading" @click="loadList"
      >加载待采集商品</el-button
    >
    <el-table v-if="products.length" :data="products" border size="small">
      <el-table-column prop="name" label="商品" min-width="180" />
      <el-table-column prop="barCode" label="条码" min-width="140" />
      <el-table-column label="已上架" width="90">
        <template #default="{ row }">
          <el-tag :type="row.onShelf ? 'success' : 'info'">{{ row.onShelf ? '是' : '否' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button
            size="small"
            type="primary"
            :disabled="!canEdit || !row.onShelf || busy"
            @click="startCapture(row)"
            >开始采集</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-else description="点「加载待采集商品」查询（无待采集则空）" :image-size="60" />

    <el-descriptions v-if="session" :column="2" border style="margin-top: 12px">
      <el-descriptions-item label="会话">{{ session.sessionId }}</el-descriptions-item>
      <el-descriptions-item label="商品 ID">{{ session.productId }}</el-descriptions-item>
      <el-descriptions-item label="阶段">{{ session.phase }}</el-descriptions-item>
      <el-descriptions-item label="错误">{{ session.error || '—' }}</el-descriptions-item>
    </el-descriptions>
    <div
      v-if="session && !TERMINAL.includes(session.phase)"
      style="margin-top: 8px; display: flex; gap: 12px"
    >
      <el-button type="success" :loading="busy" :disabled="!canEdit" @click="finishCapture"
        >结束采集</el-button
      >
      <el-button type="danger" plain :loading="busy" :disabled="!canEdit" @click="cancelCapture"
        >取消会话</el-button
      >
    </div>
  </div>
</template>

<style scoped>
.jy-capture {
  margin-top: 8px;
}
</style>
