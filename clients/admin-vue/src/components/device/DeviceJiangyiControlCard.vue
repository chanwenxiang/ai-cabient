<template>
  <el-card v-if="binding?.status === 'BOUND'" class="page-card control-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">设备控制（信号 / 音量 / 柜门）</span>
            <span class="hint">识别开门柜套件的远程控制；从「将邑对接」移入概览，随设备一起看</span>
          </div>
        </div>
      </div>
    </template>

    <div class="jy-signal-row">
      <span>
        信号强度：
        <strong>{{
          signal?.signalStrength != null ? `${signal.signalStrength}/4` : '未上报（设备未回报）'
        }}</strong>
        <span v-if="signal?.reportedAt" class="jy-signal-at">
          （{{ formatDateTime(signal.reportedAt) }}）
        </span>
      </span>
      <el-button size="small" :loading="signalLoading" @click="loadSignal">刷新</el-button>
      <el-button size="small" :loading="pullLoading" @click="pullDeviceInfo">实时拉取</el-button>
    </div>
    <div class="jy-signal-hint">
      信号强度是将邑的可选功能；实测当前固件不回报，数值为空属正常，待厂商固件支持后才会出现。
    </div>

    <div v-if="canEdit" class="jy-sound-row">
      <span class="jy-sound-label">音量设置：</span>
      <el-switch v-model="soundOn" active-text="开" inactive-text="关" />
      <el-radio-group v-model="soundLevel" size="small">
        <el-radio-button :value="0">大</el-radio-button>
        <el-radio-button :value="1">中</el-radio-button>
        <el-radio-button :value="2">小</el-radio-button>
      </el-radio-group>
      <el-button type="primary" size="small" :loading="soundSaving" @click="saveSound"
        >下发</el-button
      >
    </div>

    <div class="jy-signal-row">
      <span>
        门磁：
        <strong>门 {{ switchLabel(doorState?.doorState) }}</strong>
        ·
        <strong>锁 {{ switchLabel(doorState?.lockState) }}</strong>
        <span v-if="doorState?.reportedAt" class="jy-signal-at">
          （{{ formatDateTime(doorState.reportedAt) }}）
        </span>
      </span>
      <el-button size="small" :loading="doorStateLoading" @click="loadDoorState">刷新</el-button>
      <template v-if="canEdit">
        <el-button size="small" :loading="doorQueryLoading" @click="queryDoorState"
          >查询门磁</el-button
        >
        <el-button type="warning" size="small" :loading="closeDoorLoading" @click="closeDoor"
          >关闭柜门</el-button
        >
        <el-button type="danger" plain size="small" :loading="rebootLoading" @click="rebootDevice"
          >重启设备</el-button
        >
      </template>
    </div>
    <div class="jy-signal-hint">
      门磁是设备自报口径，仅作现场参考； 「未知」=
      设备回报了但锁控读不出（固件初值），与「未上报」不同。 关门与重启均无回执，下发后需现场确认。
    </div>

    <div v-if="canEdit" class="jy-actions">
      <el-popconfirm
        :title="
          binding.authEnabled
            ? '停用后设备无法领取 token、存量 token 即刻吊销，确认停用？'
            : '激活后该设备可凭 SN 领取 token（请确认设备已在现场），确认激活？'
        "
        confirm-button-text="确认"
        cancel-button-text="取消"
        @confirm="toggleAuth"
      >
        <template #reference>
          <el-button
            :type="binding.authEnabled ? 'warning' : 'success'"
            plain
            :loading="authSaving"
          >
            {{ binding.authEnabled ? '停用 token 签发' : '激活 token 签发' }}
          </el-button>
        </template>
      </el-popconfirm>
      <el-popconfirm
        title="退役后该柜机停止识别开门与上报，确认退役？"
        confirm-button-text="退役"
        cancel-button-text="取消"
        @confirm="doRetire"
      >
        <template #reference>
          <el-button type="danger" plain :loading="retireSaving">退役设备</el-button>
        </template>
      </el-popconfirm>
    </div>
  </el-card>
</template>

<script setup lang="ts">
/**
 * 设备控制卡（信号 / 音量 / 柜门 / token 签发 / 退役）——2026-10-27 从「将邑对接」卡
 * 移入设备概览：这些是柜机本体的远程操作，运营进门就该看到，不该藏在第三个标签页里。
 * 仅 BOUND 状态渲染；退役后整卡消失（将邑对接卡会显示退役说明）。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage, httpStatus, isUserDismiss } from '@/utils/error-message';
import { formatDateTime } from '@aicabinet/shared-uni/format';

const props = defineProps<{ deviceId: string; canEdit: boolean }>();

interface JiangyiBindingLite {
  status: string;
  authEnabled?: boolean;
}
const binding = ref<JiangyiBindingLite | null>(null);
const bindingLoading = ref(false);

async function loadBinding() {
  bindingLoading.value = true;
  try {
    const data = await api.request<{ binding: JiangyiBindingLite | null }>(
      AdminEndpoints.deviceJiangyi(props.deviceId),
      'GET'
    );
    binding.value = data.binding;
  } catch {
    binding.value = null;
  } finally {
    bindingLoading.value = false;
  }
}

// ===== 信号强度 =====
interface SignalView {
  signalStrength?: number | null;
  reportedAt?: string | null;
}
const signal = ref<SignalView | null>(null);
const signalLoading = ref(false);
const pullLoading = ref(false);

async function loadSignal() {
  signalLoading.value = true;
  try {
    signal.value = await api.request<SignalView>(
      AdminEndpoints.deviceJiangyiSignal(props.deviceId)
    );
  } catch {
    signal.value = null;
  } finally {
    signalLoading.value = false;
  }
}

/** 下发 getDeviceInfo：设备回执后信号强度异步落库。当前固件未实现该回执。 */
async function pullDeviceInfo() {
  pullLoading.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiDeviceInfo(props.deviceId), 'POST');
    ElMessage.info('已下发实时拉取；该接口为厂商可选，固件未实现时不会回报信号');
  } catch (e) {
    ElMessage.error(errorMessage(e, '拉取失败（设备可能离线）'));
  } finally {
    pullLoading.value = false;
  }
}

// ===== 音量 =====
const soundOn = ref(true);
const soundLevel = ref(1);
const soundSaving = ref(false);

async function saveSound() {
  soundSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiSound(props.deviceId), 'PUT', {
      switchOn: soundOn.value,
      volume: soundLevel.value
    });
    ElMessage.success('音量设置已下发');
  } catch (e) {
    ElMessage.error(errorMessage(e, '音量下发失败'));
  } finally {
    soundSaving.value = false;
  }
}

// ===== 门磁 / 关门 / 重启 =====
interface DoorStateView {
  doorState?: 'open' | 'close' | 'unknown' | null;
  lockState?: 'open' | 'close' | 'unknown' | null;
  reportedAt?: string | null;
}
const doorState = ref<DoorStateView | null>(null);
const doorStateLoading = ref(false);
const doorQueryLoading = ref(false);
const closeDoorLoading = ref(false);
const rebootLoading = ref(false);

/** unknown = 设备回了但锁控 GPIO 读不出来（固件 uploadDoorState 初值），与「从未上报」区分。 */
const switchLabel = (v?: string | null) =>
  v === 'open' ? '开' : v === 'close' ? '关' : v === 'unknown' ? '未知' : '未上报';

async function loadDoorState() {
  doorStateLoading.value = true;
  try {
    doorState.value = await api.request<DoorStateView>(
      AdminEndpoints.deviceJiangyiDoorState(props.deviceId)
    );
  } catch {
    // 保留上次快照：一次网络抖动不该把已读到的门磁状态抹成「未上报」
  } finally {
    doorStateLoading.value = false;
  }
}

/** 下发 getDoorState：设备经回执异步落库，故延迟两次刷新（弱网下设备回执可能超过 3s）。 */
async function queryDoorState() {
  doorQueryLoading.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiDoorState(props.deviceId), 'POST');
    ElMessage.info('已下发门磁查询；设备回报后自动刷新（可选接口，固件未实现则无回执）');
    window.setTimeout(() => void loadDoorState(), 3000);
    window.setTimeout(() => void loadDoorState(), 10000);
  } catch (e) {
    ElMessage.error(errorMessage(e, '门磁查询下发失败（设备可能离线）'));
  } finally {
    doorQueryLoading.value = false;
  }
}

/**
 * 维护类指令下发（reboot/closeDoor）：409 = 设备有进行中的消费者会话或已签到补货任务，
 * 后端拦下；运营二次确认后带 force=true 重试，审计记「强制」。返回是否走了强制。
 */
async function postDoorCommand(path: string, forceTitle: string): Promise<boolean> {
  try {
    await api.request(path, 'POST');
    return false;
  } catch (e) {
    if (httpStatus(e) !== 409) throw e;
    await ElMessageBox.confirm(`${errorMessage(e, '设备使用中')}。仍要下发？`, forceTitle, {
      type: 'warning',
      confirmButtonText: '强制下发',
      cancelButtonText: '取消'
    });
    await api.request(`${path}?force=true`, 'POST');
    return true;
  }
}

async function closeDoor() {
  try {
    await ElMessageBox.confirm(
      '将向柜机下发关门/回锁指令。该指令为厂商可选命令、无回执，下发后请到现场确认柜门已锁。',
      '关闭柜门',
      { type: 'warning', confirmButtonText: '下发', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  closeDoorLoading.value = true;
  try {
    const forced = await postDoorCommand(
      AdminEndpoints.deviceJiangyiCloseDoor(props.deviceId),
      '强制关门'
    );
    ElMessage.success(
      forced
        ? '已强制下发关门指令（越过进行中业务，无回执，请现场确认已锁）'
        : '关门指令已下发（无回执，请现场确认已锁）'
    );
  } catch (e) {
    if (!isUserDismiss(e)) ElMessage.error(errorMessage(e, '关门指令下发失败（设备可能离线）'));
  } finally {
    closeDoorLoading.value = false;
  }
}

async function rebootDevice() {
  try {
    await ElMessageBox.confirm(
      '将重启柜机主控。设备会短暂离线（WS 断开、下单/开门不可用），确认现场无人在购物。',
      '重启设备',
      { type: 'warning', confirmButtonText: '重启', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  rebootLoading.value = true;
  try {
    const forced = await postDoorCommand(
      AdminEndpoints.deviceJiangyiReboot(props.deviceId),
      '强制重启'
    );
    ElMessage.success(
      forced ? '已强制下发重启（越过进行中业务），设备将短暂离线' : '重启指令已下发，设备将短暂离线'
    );
  } catch (e) {
    if (!isUserDismiss(e)) ElMessage.error(errorMessage(e, '重启指令下发失败（设备可能离线）'));
  } finally {
    rebootLoading.value = false;
  }
}

// ===== token 签发 / 退役 =====
const authSaving = ref(false);
const retireSaving = ref(false);

async function toggleAuth() {
  const activated = binding.value?.authEnabled === true;
  authSaving.value = true;
  try {
    const url = activated
      ? AdminEndpoints.deviceJiangyiDeactivate(props.deviceId)
      : AdminEndpoints.deviceJiangyiActivate(props.deviceId);
    await api.request(url, 'POST');
    ElMessage.success(activated ? '已停用，存量 token 已吊销' : '已激活，设备可凭 SN 领取 token');
    await loadBinding();
  } catch (e) {
    ElMessage.error(errorMessage(e, activated ? '停用失败' : '激活失败'));
  } finally {
    authSaving.value = false;
  }
}

async function doRetire() {
  retireSaving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiRetire(props.deviceId), 'POST');
    ElMessage.success('已退役；「将邑对接」页会显示退役说明');
    await loadBinding();
  } catch (e) {
    ElMessage.error(errorMessage(e, '退役失败'));
  } finally {
    retireSaving.value = false;
  }
}

const ready = computed(() => binding.value?.status === 'BOUND');

onMounted(async () => {
  await loadBinding();
  if (ready.value) {
    void loadSignal();
    void loadDoorState();
  }
});
</script>

<style scoped>
.control-card {
  margin-top: 16px;
}
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: var(--admin-font-size-title, 15px);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.jy-signal-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 12px 0 8px;
}
.jy-signal-at {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.jy-signal-hint {
  margin: 6px 0 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}
.jy-sound-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 4px 0 14px;
}
.jy-sound-label {
  font-size: 13px;
}
.jy-actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}
</style>
