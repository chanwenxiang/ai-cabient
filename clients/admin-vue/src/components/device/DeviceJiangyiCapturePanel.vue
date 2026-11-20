<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import { jiangyiPhaseText } from '@aicabinet/shared-uni/format';

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
// MA-225：轮询代际守卫（MA-173 admin 侧同族）——stopPoll/startCapture 会开新一代，
// 迟到的旧响应一律丢弃，不得覆盖新 sessionId 或误停新单轮询。
let pollGeneration = 0;

const TERMINAL = ['DONE', 'FAILED', 'CANCELLED'];

/** 会话阶段标签配色：终结态各自可区分，进行中统一 warning。 */
function phaseTagType(phase: string): 'success' | 'danger' | 'info' | 'warning' {
  if (phase === 'DONE') return 'success';
  if (phase === 'FAILED') return 'danger';
  if (phase === 'CANCELLED') return 'info';
  return 'warning';
}

/** 层表「本层商品」摘要：三列定宽会剩半屏空白，把层内商品名摊平正好填满并省一次展开。 */
function floorItemNames(floor: ShelfFloor): string {
  return (floor.items || []).map((i) => i.productName).join('、');
}

async function loadShelf() {
  shelfLoading.value = true;
  try {
    shelf.value = await api.request<VendorShelf>(
      AdminEndpoints.jiangyiVendorShelf(props.deviceId),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '读取厂商在售清单失败'));
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
  pollGeneration += 1; // MA-225：停轮询 = 开新一代，在途响应作废
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function pollStatus() {
  if (!session.value) return;
  const gen = pollGeneration;
  try {
    const next = await api.request<CaptureSession>(
      AdminEndpoints.jiangyiCabinetCaptureStatus(props.deviceId, session.value.sessionId),
      'GET'
    );
    if (gen !== pollGeneration) return; // MA-225：迟到响应，新一代已开
    session.value = next;
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
    <div class="jy-panel">
      <div class="jy-panel-head">
        <div class="jy-panel-title">厂商侧在售核对（只读）</div>
        <span class="jy-panel-hint">只读：核对上架结果，不改厂商数据</span>
        <el-button size="small" class="jy-panel-action" :loading="shelfLoading" @click="loadShelf"
          >读取厂商在售清单</el-button
        >
      </div>
      <template v-if="shelf">
        <el-descriptions :column="3" border size="small" class="jy-meta">
          <el-descriptions-item label="设备">{{ shelf.deviceName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="编码">{{ shelf.identifier || '—' }}</el-descriptions-item>
          <el-descriptions-item label="总层数">{{ shelf.totalFloorNum }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="shelf.floors" border size="small" row-key="floorNum">
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
          <el-table-column prop="floorNum" label="层号" width="100" />
          <el-table-column label="朝向" width="120">
            <template #default="{ row }">{{ row.floorOrientation || '单门不区分' }}</template>
          </el-table-column>
          <el-table-column label="商品数" width="100">
            <template #default="{ row }">{{ row.items.length }}</template>
          </el-table-column>
          <!-- 截断提示走全局 installTableCellNativeTitle 的原生 title（不用 EP 溢出浮层，会盖邻列） -->
          <el-table-column label="本层商品" min-width="260">
            <template #default="{ row }">
              <span v-if="row.items.length">{{ floorItemNames(row) }}</span>
              <span v-else class="jy-dim">（空层）</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <div v-else class="jy-empty-hint">
        尚未读取——点右上「读取厂商在售清单」，核对柜内实际在售与提交结果是否一致。
      </div>
    </div>

    <div class="jy-panel">
      <div class="jy-panel-head">
        <div class="jy-panel-title">柜内采集</div>
        <span class="jy-panel-hint">每柜同时只允许一个会话</span>
        <el-button size="small" class="jy-panel-action" :loading="listLoading" @click="loadList"
          >加载待采集商品</el-button
        >
      </div>
      <el-alert type="info" :closable="false" show-icon title="操作规范" class="jy-panel-alert">
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

      <el-table v-if="products.length" :data="products" border size="small" class="jy-table">
        <el-table-column prop="name" label="商品" min-width="180" />
        <el-table-column label="条码" min-width="140">
          <template #default="{ row }"
            ><span class="jy-mono">{{ row.barCode }}</span></template
          >
        </el-table-column>
        <el-table-column label="已上架" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.onShelf ? 'success' : 'info'">{{
              row.onShelf ? '是' : '否'
            }}</el-tag>
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
      <div v-else class="jy-empty-hint">
        点右上「加载待采集商品」查询；已上架商品会标「是」，未上架需先补货上架再采集。
      </div>

      <div v-if="session" class="jy-session">
        <div class="jy-session-head">
          <span class="jy-session-label">采集会话 #{{ session.sessionId }}</span>
          <el-tag size="small" :type="phaseTagType(session.phase)">{{
            jiangyiPhaseText(session.phase)
          }}</el-tag>
          <span class="jy-session-meta">
            商品 ID {{ session.productId
            }}<template v-if="session.error"> · {{ session.error }}</template>
          </span>
        </div>
        <div v-if="!TERMINAL.includes(session.phase)" class="jy-session-actions">
          <el-button type="success" :loading="busy" :disabled="!canEdit" @click="finishCapture"
            >结束采集</el-button
          >
          <el-button type="danger" plain :loading="busy" :disabled="!canEdit" @click="cancelCapture"
            >取消会话</el-button
          >
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.jy-capture {
  display: flex;
  flex-direction: column;
}
.jy-panel {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.jy-panel-head {
  display: flex;
  align-items: center;
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
/* 面板级动作放标题行右端：省掉一整行按钮，也让两个面板的头部对齐 */
.jy-panel-action {
  margin-left: auto;
}
.jy-panel-alert :deep(.el-alert__description) {
  margin-top: 2px;
  line-height: 1.5;
}
.jy-meta {
  margin-bottom: 10px;
}
.capture-rules {
  display: grid;
  gap: 2px;
  font-size: var(--admin-font-size-sm);
  line-height: 1.6;
  color: var(--el-text-color-regular);
}
.jy-table {
  margin-top: 10px;
}
.jy-empty-hint {
  padding: 10px 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  font-size: var(--admin-font-size-sm);
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.jy-session {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.jy-session-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.jy-session-label {
  font-size: var(--admin-font-size-table);
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.jy-session-meta {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
.jy-session-actions {
  display: flex;
  gap: 12px;
  margin-top: 10px;
}
.jy-mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
.jy-dim {
  color: var(--el-text-color-placeholder);
}
</style>
