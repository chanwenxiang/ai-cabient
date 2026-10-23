<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">识别映射</span>
            <span class="hint">
              端侧识别编号 → 商品的对应表；只有激活的映射参与结算推荐。识别在将邑端侧完成，
              云端不做识别；「识别名称」是厂商模型类目名（多为拼音/英文缩写），核对商品请看「平台商品」中文名
            </span>
          </div>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="load()">
      <el-form-item label="柜机">
        <el-select
          v-model="deviceId"
          filterable
          clearable
          placeholder="选择将邑柜机"
          style="width: 280px"
          @change="onDeviceChange"
        >
          <el-option
            v-for="d in deviceOptions"
            :key="d.deviceId"
            :label="`${d.deviceName || '未命名设备'}（编号 ${d.deviceId}）`"
            :value="d.deviceId"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-if="binding && binding.status !== 'RETIRED'">
        <el-button v-hasPermi="['ops:device:edit']" type="primary" @click="openCreate">
          新增映射
        </el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="deviceId && loaded && !binding"
      type="warning"
      :closable="false"
      show-icon
      title="该柜机未接入将邑"
      description="请先到设备详情的「将邑接入」卡片完成登记与绑定。"
    />

    <template v-if="binding">
      <div class="jy-device-bar">
        <div class="jy-device-item">
          <span class="jy-device-label">设备状态</span>
          <el-tag size="small" :type="binding.status === 'BOUND' ? 'success' : 'info'">
            {{ binding.status === 'BOUND' ? '已接入' : '未接入' }}
          </el-tag>
        </div>
        <div class="jy-device-item">
          <span class="jy-device-label">当前生效模型</span>
          <span class="jy-mono">{{ binding.modelName || '未下发' }}</span>
        </div>
        <div class="jy-device-item">
          <span class="jy-device-label">模型版本</span>
          <span class="jy-mono">{{ binding.classesVersion || '—' }}</span>
        </div>
        <div class="jy-device-item">
          <span class="jy-device-label">工控机机型</span>
          <span class="jy-mono">{{ binding.industrialControlModel || '未登记' }}</span>
        </div>
        <div class="jy-device-item">
          <span class="jy-device-label">最近通信时间</span>
          <span>{{ formatInstant(binding.lastWsOnlineAt) }}</span>
        </div>
      </div>

      <!-- 映射复核（V400）：重训后 classId 整体平移，未复核不结算 -->
      <el-card class="jy-model-card" shadow="never">
        <template #header>
          <div class="page-card-head">
            <div class="page-card-head__meta">
              <div class="page-card-head__title">
                <span class="title">映射复核（结算闸口）</span>
                <span class="hint">
                  将邑识别编号是 classes.txt 按识别名排序后的行号，厂商每次重训都会整体平移
                  （同一商品换编号）。柜机当前模型未经人工复核前，识别整单转人工、在售面下发空表；
                  先拉差异逐行核对，再确认
                </span>
              </div>
            </div>
            <div class="page-card-head__actions">
              <el-button size="small" :loading="reviewStatusLoading" @click="loadReviewStatus()">
                刷新复核状态
              </el-button>
            </div>
          </div>
        </template>

        <el-alert
          :type="reviewStatus?.verified ? 'success' : 'warning'"
          :closable="false"
          show-icon
          :title="
            reviewStatus?.verified
              ? '已复核：当前模型版本可正常结算'
              : '未复核：识别结算与在售面已暂停'
          "
          :description="reviewDescription"
        />

        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent>
          <el-form-item label="模型名">
            <el-input
              v-model="reviewForm.modelName"
              placeholder="柜机正在跑的模型名"
              style="width: 220px"
            />
          </el-form-item>
          <el-form-item label="classes 地址">
            <el-input
              v-model="reviewForm.textUrl"
              placeholder="https://…/classes.txt"
              style="width: 320px"
            />
          </el-form-item>
          <el-form-item label="类目数">
            <el-input-number
              v-model="reviewForm.quantity"
              :min="1"
              :precision="0"
              controls-position="right"
              style="width: 120px"
            />
          </el-form-item>
          <el-form-item label="编号起始">
            <el-select v-model="reviewForm.classIdBase" style="width: 130px">
              <el-option label="0 起" :value="0" />
              <el-option label="1 起" :value="1" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="reviewPlanning" @click="loadReviewPlan()">
              拉取差异（只读）
            </el-button>
          </el-form-item>
        </el-form>
        <div class="field-hint">
          模型列表为空（厂商未下发到本租户）时用厂商给的 classes
          地址手工填；类目数必须与文件行数一致。 确认会重算该柜全部映射并把复核版本认领为本次审阅的
          classes 版本。
        </div>
      </el-card>

      <!-- 模型同步：预览 → 预生成 → 激活 → 下发（CB-023 链路） -->
      <el-card class="jy-model-card" shadow="never">
        <template #header>
          <div class="page-card-head">
            <div class="page-card-head__meta">
              <div class="page-card-head__title">
                <span class="title">模型同步</span>
                <span class="hint">
                  预生成后映射为停用态，人工核对「识别编号 ↔ 商品」再激活；机型参数按字符串
                  相等校验，与设备登记值不一致将拒绝下发
                </span>
              </div>
            </div>
          </div>
        </template>

        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent>
          <el-form-item label="将邑模型">
            <el-select
              v-model="selectedModel"
              placeholder="拉取模型列表"
              style="width: 320px"
              :loading="modelsLoading"
              @change="onModelPicked"
            >
              <el-option
                v-for="m in modelPreviews"
                :key="m.modelName"
                :label="`${m.modelName}（${m.quantity} 类 · ${m.trainedMatchPercent}% 已挂接 · 机型 ${m.industrialControlModel}）`"
                :value="m.modelName"
                :disabled="Boolean(m.rejectReason)"
              >
                <template v-if="m.rejectReason">
                  <span>{{ m.modelName }}（不可用：{{ m.rejectReason }}）</span>
                </template>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="识别编号起始值">
            <el-select v-model="classIdBase" style="width: 150px" @change="loadModels()">
              <el-option label="0 起（第 1 行=0）" :value="0" />
              <el-option label="1 起（第 1 行=1）" :value="1" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button :loading="modelsLoading" @click="loadModels()">刷新模型列表</el-button>
          </el-form-item>
        </el-form>

        <el-alert
          v-if="modelsError"
          class="jy-model-alert"
          type="error"
          :closable="false"
          show-icon
          :title="modelsError"
        />
        <el-alert
          v-else-if="modelsLoaded && modelPreviews.length === 0"
          class="jy-model-alert"
          type="info"
          :closable="false"
          show-icon
          title="将邑未返回可用模型"
          description="模型列表按商户范围返回；为空时可先用「新增映射」逐条挂接，或稍后刷新重试。"
        />

        <template v-if="currentPreview">
          <div class="jy-preview-rows">
            <div v-for="row in currentPreview.rows" :key="row.classId" class="jy-preview-row">
              <span class="jy-mono">编号 {{ row.classId }}</span>
              <span>{{ row.textName }}</span>
            </div>
          </div>
          <div class="jy-model-actions">
            <el-button
              v-hasPermi="['ops:device:edit']"
              :loading="acting === 'sync'"
              @click="doModelSync()"
            >
              预生成映射（停用态）
            </el-button>
            <el-button
              v-hasPermi="['ops:device:edit']"
              type="warning"
              :loading="acting === 'activate'"
              @click="doActivate()"
            >
              激活该模型预生成行
            </el-button>
            <el-button
              v-hasPermi="['ops:device:edit']"
              type="primary"
              :loading="acting === 'push'"
              @click="doPush()"
            >
              下发模型到柜机
            </el-button>
          </div>
        </template>
      </el-card>

      <!-- 识别编号映射表 -->
      <div class="table-scroll">
        <div class="table-scroll-inner">
          <CrudTable
            :table="crud"
            :actions="rowActions"
            :action-width="150"
            empty-text="暂无映射；可「新增映射」逐条创建，或先在「模型同步」预生成"
            sort-field-label="识别编号"
            :csv="csvOptions"
            @action="onAction"
          >
            <el-table-column prop="classId" label="识别编号" width="100" class-name="col-text">
              <template #default="{ row }">
                <span class="jy-mono">{{ row.classId }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="textName" label="识别名称" min-width="150">
              <template #default="{ row }">{{ row.textName || '—' }}</template>
            </el-table-column>
            <el-table-column label="平台商品" min-width="160">
              <template #default="{ row }">
                <span v-if="!row.skuId" class="muted">未挂接</span>
                <span v-else-if="skuNameFor(row.skuId)">{{ skuNameFor(row.skuId) }}</span>
                <span v-else class="jy-mono">{{ row.skuId }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="skuId" label="商品编号" min-width="120" class-name="col-text">
              <template #default="{ row }">{{ row.skuId || '未挂接' }}</template>
            </el-table-column>
            <el-table-column prop="modelName" label="模型" min-width="130">
              <template #default="{ row }">{{ row.modelName || '—' }}</template>
            </el-table-column>
            <el-table-column
              prop="status"
              label="状态"
              width="100"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ row.status === 'ACTIVE' ? '已启用' : '已停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="source"
              label="来源"
              width="110"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">
                <el-tag size="small" type="warning" effect="plain">
                  {{ row.source === 'MODEL_SYNC' ? '模型预生成' : '手工' }}
                </el-tag>
              </template>
            </el-table-column>
          </CrudTable>
        </div>
      </div>

      <!-- 下发审计 -->
      <el-card class="jy-deploy-card" shadow="never">
        <template #header>
          <div class="page-card-head">
            <div class="page-card-head__meta">
              <div class="page-card-head__title">
                <span class="title">下发记录</span>
                <span class="hint"
                  >SENT=已下发等待回执（600s 超时置 FAILED）；CONFIRMED=柜机已应用</span
                >
              </div>
            </div>
            <div class="page-card-head__actions">
              <el-button size="small" @click="loadDeployments()">刷新</el-button>
            </div>
          </div>
        </template>
        <el-table :data="deployments" stripe border size="small">
          <el-table-column prop="modelName" label="模型" min-width="140" />
          <el-table-column prop="industrialControlModel" label="机型" width="90">
            <template #default="{ row }">
              <span class="jy-mono">{{ row.industrialControlModel || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            prop="status"
            label="状态"
            width="120"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag
                size="small"
                :type="
                  row.status === 'CONFIRMED'
                    ? 'success'
                    : row.status === 'FAILED'
                      ? 'danger'
                      : 'warning'
                "
              >
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="下发时间" width="170">
            <template #default="{ row }">{{ formatInstant(row.sentAt) }}</template>
          </el-table-column>
          <el-table-column label="确认时间" width="170">
            <template #default="{ row }">{{ formatInstant(row.confirmedAt) || '—' }}</template>
          </el-table-column>
          <el-table-column prop="failReason" label="失败原因" min-width="160">
            <template #default="{ row }">{{ row.failReason || '—' }}</template>
          </el-table-column>
          <template #empty><el-empty description="尚未下发过模型" /></template>
        </el-table>
      </el-card>
    </template>

    <el-empty v-if="!deviceId" description="选择一台将邑柜机查看其识别映射与模型同步状态" />

    <el-dialog v-model="reviewVisible" title="映射复核差异" width="960px" destroy-on-close>
      <template v-if="reviewPlan">
        <div class="jy-device-bar">
          <div class="jy-device-item">
            <span class="jy-device-label">本次审阅版本</span>
            <span class="jy-mono">{{ reviewPlan.classesVersion }}</span>
          </div>
          <div class="jy-device-item">
            <span class="jy-device-label">柜机当前版本</span>
            <span class="jy-mono">
              {{ reviewPlan.deviceClassesVersion || '未认领（本次确认后认领）' }}
            </span>
          </div>
          <div class="jy-device-item">
            <span class="jy-device-label">保留 / 平移 / 新增 / 失效 / 冲突</span>
            <span class="jy-mono">{{ reviewCountsText }}</span>
          </div>
        </div>

        <el-alert
          v-if="reviewPlan.versionMismatch"
          class="jy-model-alert"
          type="error"
          :closable="false"
          show-icon
          title="柜机当前模型与本次审阅版本不一致"
          :description="`柜机 ${reviewPlan.deviceClassesVersion || '—'} ≠ 审阅 ${reviewPlan.classesVersion}：请审柜机正在跑的那一版，或先下发/确认正确模型`"
        />
        <el-alert
          v-else-if="reviewPlan.counts.conflicts > 0"
          class="jy-model-alert"
          type="error"
          :closable="false"
          show-icon
          title="存在识别名冲突，不能应用"
          description="同一识别名对应多行映射（或 classes 内重名），请先在映射表人工合并/删除多余行"
        />
        <el-alert
          v-else
          class="jy-model-alert"
          type="info"
          :closable="false"
          show-icon
          title="逐行核对后再确认"
          description="平移 = SKU 跟随识别名换编号；新增行先停用等挂 SKU；失效行停用并腾出编号（挂接留档）。确认后本柜结算恢复。"
        />

        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table :data="reviewPlan.rows" stripe border size="small" max-height="420">
              <el-table-column label="处置" width="90" align="center" class-name="col-status">
                <template #default="{ row }">
                  <el-tag size="small" :type="actionTagType(row.action)">
                    {{ actionLabel(row.action) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="识别编号" width="130">
                <template #default="{ row }">
                  <span class="jy-mono">
                    {{ row.classId ?? '—' }}
                    <template v-if="row.oldClassId !== null && row.oldClassId !== row.classId">
                      （原 {{ row.oldClassId }}）
                    </template>
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="识别名称" min-width="200">
                <template #default="{ row }">{{ row.textName || '—' }}</template>
              </el-table-column>
              <el-table-column label="平台商品" min-width="180">
                <template #default="{ row }">
                  <span v-if="!row.skuId" class="muted">未挂接</span>
                  <span v-else-if="skuNameFor(row.skuId)">
                    {{ skuNameFor(row.skuId) }}（{{ row.skuId }}）
                  </span>
                  <span v-else class="jy-mono">{{ row.skuId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90" align="center" class-name="col-status">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                    {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="reason" label="说明" min-width="220">
                <template #default="{ row }">{{ row.reason || '—' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </template>
      <template #footer>
        <el-button @click="reviewVisible = false">关闭</el-button>
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="primary"
          :disabled="!canApplyReview"
          :loading="reviewApplying"
          @click="applyReview()"
        >
          确认复核并应用
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" :title="isCreate ? '新增映射' : '编辑映射'" destroy-on-close>
      <el-form label-width="auto">
        <el-form-item label="识别编号" required>
          <el-input v-if="!isCreate" :model-value="editForm.classId" disabled />
          <el-input-number
            v-else
            v-model="editForm.classId"
            :min="0"
            :precision="0"
            :step="1"
            controls-position="right"
            style="width: 100%"
          />
          <div class="field-hint">
            {{ isCreate ? '模型文件里的编号；同一柜机不可重复' : '模型文件里的编号，不可修改' }}
          </div>
        </el-form-item>
        <el-form-item label="识别名称">
          <el-input
            v-model="editForm.textName"
            placeholder="classes 原文行名，如 kkkl_xb_qsnmw_pttz_330ml"
          />
          <div class="field-hint">
            取设备详情「将邑接入 → 查厂商商品对照（只读）」的「识别名（classes 原文）」列
          </div>
          <div class="field-hint">只作留档；结算判据是「识别编号 + 对应商品」</div>
        </el-form-item>
        <el-form-item label="对应商品" required>
          <el-select v-model="editForm.skuId" filterable style="width: 100%">
            <el-option
              v-for="s in skuSelectOptions"
              :key="s.skuId"
              :label="`${s.skuName || '未命名商品'}（编号 ${s.skuId}）`"
              :value="s.skuId"
            />
          </el-select>
          <div class="field-hint">只列出在售商品；已停用的历史挂接仍保留原值</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="editForm.active" active-text="启用" inactive-text="停用" />
          <div class="field-hint">启用后立即参与识别结算推荐；停用只保留对照关系</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CircleCheck, CircleClose, EditPen } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { errorMessage } from '@/utils/error-message';
import { formatDateTime } from '@aicabinet/shared-uni/format';

/**
 * 识别映射（CB-023 改造）：云端识别（YOLO/阿里云）方案已废弃，本页收编将邑
 * classId 映射管理与模型同步链路（预览→预生成→激活→下发→审计）。
 * 设备维度快捷操作仍在设备详情「将邑接入」卡片，两处共用同一组端点。
 */

interface JiangyiClassMappingRow {
  id: number;
  deviceId: string;
  classId: number;
  modelName: string | null;
  textName: string | null;
  skuId: string | null;
  status: string;
  source: string;
  createdAt: string | null;
  updatedAt: string | null;
}

interface JiangyiBindingInfo {
  deviceId: string;
  deviceSn: string | null;
  identifier: string | null;
  modelName: string | null;
  industrialControlModel: string | null;
  classesVersion: string | null;
  status: string;
  lastWsOnlineAt: string | null;
}

interface ParsedClassRow {
  classId: number;
  textName: string;
  lineNumber: number;
}

interface ModelPreview {
  modelName: string;
  industrialControlModel: string | null;
  quantity: number;
  modelTextUrl: string | null;
  classesVersion: string | null;
  fallbackVersion: string | null;
  rows: ParsedClassRow[];
  rejectReason: string | null;
  trainedMatchPercent: number;
}

interface DeploymentRow {
  id: number;
  deviceId: string;
  modelName: string;
  industrialControlModel: string | null;
  classesVersion: string | null;
  status: string;
  sentAt: string | null;
  confirmedAt: string | null;
  failReason: string | null;
}

interface MappingReviewStatus {
  verified: boolean;
  classesVersion: string | null;
  verifiedVersion: string | null;
  verifiedAt: string | null;
}

interface RebuildCounts {
  kept: number;
  moved: number;
  created: number;
  orphaned: number;
  conflicts: number;
}

interface RebuildRow {
  id: number | null;
  classId: number | null;
  textName: string | null;
  action: string;
  oldClassId: number | null;
  skuId: string | null;
  status: string | null;
  source: string | null;
  reason: string | null;
}

interface RebuildPlan {
  deviceId: string;
  modelName: string;
  classesVersion: string;
  deviceClassesVersion: string | null;
  adoptVersion: boolean;
  versionMismatch: boolean;
  verified: boolean;
  rowCount: number;
  counts: RebuildCounts;
  rows: RebuildRow[];
}

interface DeviceOption {
  deviceId: string;
  deviceName?: string;
}

interface SkuOption {
  skuId: string;
  skuName?: string;
  status?: string;
}

const route = useRoute();
const router = useRouter();
const deviceId = ref('');
const deviceOptions = ref<DeviceOption[]>([]);
const binding = ref<JiangyiBindingInfo | null>(null);
const loaded = ref(false);

const modelPreviews = ref<ModelPreview[]>([]);
const modelsLoading = ref(false);
const modelsLoaded = ref(false);
const modelsError = ref('');
const selectedModel = ref('');
const classIdBase = ref(0);
const acting = ref<'sync' | 'activate' | 'push' | ''>('');
const deployments = ref<DeploymentRow[]>([]);
const reviewStatus = ref<MappingReviewStatus | null>(null);
const reviewStatusLoading = ref(false);
const reviewForm = ref({ modelName: '', textUrl: '', quantity: 1, classIdBase: 0 });
const reviewPlanning = ref(false);
const reviewPlan = ref<RebuildPlan | null>(null);
const reviewVisible = ref(false);
const reviewApplying = ref(false);
const skuOptions = ref<SkuOption[]>([]);
/** 只允许挂接在售商品；编辑既有停用挂接时保留当前项，避免下拉里看不到原值 */
const skuSelectOptions = computed(() => {
  const available = skuOptions.value.filter((s) => !s.status || s.status === 'ACTIVE');
  const current = skuOptions.value.find(
    (s) => s.skuId === editForm.value.skuId && s.status && s.status !== 'ACTIVE'
  );
  return current ? [...available, current] : available;
});
const skuNameMap = computed(() => {
  const map = new Map<string, string>();
  for (const sku of skuOptions.value) {
    if (sku.skuId && sku.skuName) map.set(sku.skuId, sku.skuName);
  }
  return map;
});
function skuNameFor(skuId?: string | null) {
  return skuId ? skuNameMap.value.get(skuId) || '' : '';
}
const saving = ref(false);
const editVisible = ref(false);
const isCreate = ref(false);
const editForm = ref({ classId: 0, textName: '', skuId: '', active: true });

const currentPreview = computed(
  () => modelPreviews.value.find((m) => m.modelName === selectedModel.value) || null
);

const reviewCountsText = computed(() => {
  const c = reviewPlan.value?.counts;
  return c ? `${c.kept} / ${c.moved} / ${c.created} / ${c.orphaned} / ${c.conflicts}` : '—';
});
const canApplyReview = computed(() => {
  const p = reviewPlan.value;
  return !!p && !p.versionMismatch && p.counts.conflicts === 0;
});
const reviewDescription = computed(() => {
  const s = reviewStatus.value;
  if (!s) return '尚未取到复核状态；若柜机刚接入，请按当前模型填写 classes 地址后拉取差异';
  if (s.verified) {
    return `柜机 ${s.classesVersion} 已人工复核（${formatInstant(s.verifiedAt) || '—'}）`;
  }
  return `柜机版本 ${s.classesVersion || '未认领'}；已复核版本 ${s.verifiedVersion || '无'} —— 两者一致才允许识别结算`;
});

function actionLabel(action: string) {
  return (
    (
      { KEEP: '保留', MOVE: '平移', NEW: '新增', ORPHAN: '失效', CONFLICT: '冲突' } as Record<
        string,
        string
      >
    )[action] || action
  );
}

function actionTagType(action: string) {
  if (action === 'CONFLICT') return 'danger';
  if (action === 'MOVE') return 'warning';
  if (action === 'NEW') return 'primary';
  if (action === 'ORPHAN') return 'info';
  return 'success';
}

const crud = useCrudTable<JiangyiClassMappingRow>({
  rowKey: (r) => r.id,
  errorMessage: '加载映射失败',
  autoLoad: false,
  sort: { prop: 'classId', mode: 'local' },
  fetchPage: async () => {
    if (!deviceId.value) return { items: [], total: 0 };
    const data = await api.request<{
      binding: JiangyiBindingInfo | null;
      mappings: JiangyiClassMappingRow[];
    }>(AdminEndpoints.deviceJiangyi(deviceId.value), 'GET');
    binding.value = data.binding;
    return { items: data.mappings || [], total: (data.mappings || []).length };
  }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '将邑识别映射',
  exportPerm: 'ops:device:export',
  headers: ['classId', 'textName', 'SKU', '模型', '状态', '来源'],
  toRows: (rows) =>
    rows.map((r) => [
      r.classId,
      r.textName ?? '',
      r.skuId ?? '',
      r.modelName ?? '',
      r.status,
      r.source
    ])
};

function rowActions(row: JiangyiClassMappingRow): CrudRowAction[] {
  const acts: CrudRowAction[] = [];
  if (row.status === 'ACTIVE') {
    acts.push({
      key: 'disable',
      label: '停用',
      icon: CircleClose,
      type: 'warning',
      perm: 'ops:device:edit'
    });
  } else {
    acts.push({
      key: 'enable',
      label: '启用',
      icon: CircleCheck,
      type: 'primary',
      perm: 'ops:device:edit'
    });
  }
  acts.push({
    key: 'edit',
    label: '挂接 SKU',
    icon: EditPen,
    type: 'primary',
    perm: 'ops:device:edit'
  });
  return acts;
}

function onAction({ key, row }: { key: string; row: JiangyiClassMappingRow }) {
  if (key === 'enable' || key === 'disable') void toggleStatus(row);
  else if (key === 'edit') openEdit(row);
}

async function loadDevices() {
  try {
    const list = await api.request<{ items?: DeviceOption[] }>(
      AdminEndpoints.devicesOptions,
      'GET'
    );
    deviceOptions.value = list.items || [];
  } catch {
    deviceOptions.value = [];
  }
}

async function load() {
  loaded.value = false;
  binding.value = null;
  modelsLoaded.value = false;
  modelsError.value = '';
  modelPreviews.value = [];
  selectedModel.value = '';
  deployments.value = [];
  if (!deviceId.value) return;
  await crud.load({ resetPage: true });
  loaded.value = true;
  void loadDeployments();
  void loadReviewStatus();
  // 已接入设备进页即拉模型，避免运营以为「没有可预生成的模型」；失败只落页内提示，不弹 toast
  if (isBoundDevice()) void loadModels({ silent: true });
}

function isBoundDevice() {
  return binding.value?.status === 'BOUND';
}

async function loadModels(opts?: { silent?: boolean }) {
  modelsLoading.value = true;
  modelsError.value = '';
  try {
    const q = new URLSearchParams({ classIdBase: String(classIdBase.value) });
    modelPreviews.value = await api.request<ModelPreview[]>(
      `${AdminEndpoints.jiangyiModels}?${q.toString()}`,
      'GET'
    );
    modelsLoaded.value = true;
    if (selectedModel.value && !currentPreview.value) selectedModel.value = '';
  } catch (e) {
    modelsError.value = errorMessage(e, '拉取将邑模型列表失败');
    modelPreviews.value = [];
    selectedModel.value = '';
    modelsLoaded.value = false;
    if (!opts?.silent) ElMessage.error(modelsError.value);
  } finally {
    modelsLoading.value = false;
  }
}

async function doModelSync() {
  if (!ensureSelected()) return;
  acting.value = 'sync';
  try {
    const n = await api.request<number>(
      AdminEndpoints.deviceJiangyiModelSync(deviceId.value),
      'POST',
      {
        modelName: selectedModel.value,
        classIdBase: classIdBase.value
      }
    );
    ElMessage.success(`已预生成 ${n} 行映射（停用态），请核对后激活`);
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '预生成失败'));
  } finally {
    acting.value = '';
  }
}

async function doActivate() {
  if (!ensureSelected()) return;
  try {
    await ElMessageBox.confirm(
      '只激活已挂 SKU 的预生成行；未挂 SKU 的行会保持停用（激活也无法参与结算）。已确认 classId ↔ SKU 对照无误？',
      '激活确认',
      { type: 'warning', confirmButtonText: '激活', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  acting.value = 'activate';
  try {
    const n = await api.request<number>(
      AdminEndpoints.deviceJiangyiMappingActivate(deviceId.value),
      'POST',
      { modelName: selectedModel.value }
    );
    ElMessage.success(`已激活 ${n} 行（仅已挂 SKU 的预生成行）`);
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '激活失败'));
  } finally {
    acting.value = '';
  }
}

async function doPush() {
  if (!ensureSelected()) return;
  try {
    await ElMessageBox.confirm(
      '下发前确认：① 映射已激活；② 设备登记机型与模型机型一致。柜机在线才会收到 WS 指令。确认下发？',
      '下发确认',
      { type: 'warning', confirmButtonText: '下发', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  acting.value = 'push';
  try {
    const id = await api.request<number>(
      AdminEndpoints.deviceJiangyiModelPush(deviceId.value),
      'POST',
      {
        modelName: selectedModel.value
      }
    );
    ElMessage.success(`已下发（部署 #${id}），等待柜机回执`);
    loadDeployments();
  } catch (e) {
    ElMessage.error(errorMessage(e, '下发失败'));
  } finally {
    acting.value = '';
  }
}

async function loadDeployments() {
  if (!deviceId.value) return;
  try {
    deployments.value = await api.request<DeploymentRow[]>(
      AdminEndpoints.deviceJiangyiModelDeployments(deviceId.value),
      'GET'
    );
  } catch {
    deployments.value = [];
  }
}

async function loadReviewStatus() {
  if (!deviceId.value) return;
  reviewStatusLoading.value = true;
  try {
    reviewStatus.value = await api.request<MappingReviewStatus>(
      AdminEndpoints.deviceJiangyiMappingReviewStatus(deviceId.value),
      'GET'
    );
  } catch {
    reviewStatus.value = null;
  } finally {
    reviewStatusLoading.value = false;
  }
}

/** 从模型列表带入复核表单：模型可用的前提是 classes 地址能取到，省一次手抄。 */
function onModelPicked() {
  const p = currentPreview.value;
  if (!p) return;
  reviewForm.value.modelName = p.modelName;
  reviewForm.value.quantity = p.quantity;
  if (p.modelTextUrl) reviewForm.value.textUrl = p.modelTextUrl;
}

async function loadReviewPlan() {
  if (!deviceId.value) return;
  const f = reviewForm.value;
  if (!f.modelName.trim() || !f.textUrl.trim()) {
    ElMessage.warning('请填写模型名与 classes 地址');
    return;
  }
  reviewPlanning.value = true;
  try {
    const q = new URLSearchParams({
      modelName: f.modelName.trim(),
      textUrl: f.textUrl.trim(),
      quantity: String(f.quantity),
      classIdBase: String(f.classIdBase)
    });
    reviewPlan.value = await api.request<RebuildPlan>(
      `${AdminEndpoints.deviceJiangyiMappingRebuild(deviceId.value)}?${q.toString()}`,
      'GET'
    );
    reviewVisible.value = true;
  } catch (e) {
    ElMessage.error(errorMessage(e, '拉取差异失败'));
  } finally {
    reviewPlanning.value = false;
  }
}

async function applyReview() {
  const plan = reviewPlan.value;
  if (!plan || !canApplyReview.value) return;
  const c = plan.counts;
  try {
    await ElMessageBox.confirm(
      `将按识别名重算该柜全部映射到版本 ${plan.classesVersion}：平移 ${c.moved} 行、新增 ${c.created} 行（停用）、失效 ${c.orphaned} 行。确认即代表你已逐行核对，本柜识别结算随即恢复。`,
      '确认复核',
      { type: 'warning', confirmButtonText: '确认复核', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  reviewApplying.value = true;
  try {
    const f = reviewForm.value;
    const result = await api.request<{ classesVersion: string; adoptedVersion: boolean }>(
      AdminEndpoints.deviceJiangyiMappingRebuild(deviceId.value),
      'POST',
      {
        modelName: f.modelName.trim(),
        textUrl: f.textUrl.trim(),
        quantity: f.quantity,
        classIdBase: f.classIdBase,
        expectedClassesVersion: plan.classesVersion
      }
    );
    ElMessage.success(`复核已生效（${result.classesVersion}）`);
    reviewVisible.value = false;
    await loadReviewStatus();
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '应用复核失败'));
  } finally {
    reviewApplying.value = false;
  }
}

async function toggleStatus(row: JiangyiClassMappingRow) {
  const next = row.status !== 'ACTIVE';
  try {
    await api.request(
      AdminEndpoints.deviceJiangyiMappingStatus(deviceId.value, row.classId),
      'POST',
      { active: next }
    );
    ElMessage.success(next ? '已启用，下次识别即生效' : '已停用');
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '变更映射状态失败'));
  }
}

async function loadSkus() {
  try {
    const data = await api.request<SkuOption[] | { items?: SkuOption[] }>(
      AdminEndpoints.skusCatalogPage,
      'GET'
    );
    skuOptions.value = Array.isArray(data) ? data : data.items || [];
  } catch {
    skuOptions.value = [];
  }
}

/** 新增时给一个未占用的编号建议（当前最大编号 + 1）；最终仍以人工输入为准。 */
function suggestClassId() {
  const used = crud.items.map((r) => Number(r.classId)).filter((n) => Number.isFinite(n));
  return used.length ? Math.max(...used) + 1 : classIdBase.value;
}

function openCreate() {
  isCreate.value = true;
  editForm.value = {
    classId: suggestClassId(),
    textName: '',
    skuId: '',
    active: true
  };
  editVisible.value = true;
}

function openEdit(row: JiangyiClassMappingRow) {
  isCreate.value = false;
  editForm.value = {
    classId: row.classId,
    textName: row.textName || '',
    skuId: row.skuId || '',
    active: row.status === 'ACTIVE'
  };
  editVisible.value = true;
}

async function saveEdit() {
  const classId = editForm.value.classId;
  if (typeof classId !== 'number' || !Number.isInteger(classId) || classId < 0) {
    ElMessage.warning('识别编号必须是非负整数');
    return;
  }
  if (isCreate.value && crud.items.some((r) => Number(r.classId) === classId)) {
    ElMessage.warning(`识别编号 ${classId} 已存在，请直接编辑该行`);
    return;
  }
  if (!editForm.value.skuId) {
    ElMessage.warning('请选择 SKU');
    return;
  }
  saving.value = true;
  try {
    await api.request(AdminEndpoints.deviceJiangyiMapping(deviceId.value, classId), 'PUT', {
      skuId: editForm.value.skuId,
      textName: editForm.value.textName || undefined,
      // 显式回传状态：后端按 active 落 status，漏传会把已启用行静默停用
      active: editForm.value.active
    });
    ElMessage.success(isCreate.value ? '已新增映射' : '已保存');
    editVisible.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存失败'));
  } finally {
    saving.value = false;
  }
}

function ensureSelected() {
  if (selectedModel.value) return true;
  ElMessage.warning('请先选择将邑模型');
  return false;
}

function formatInstant(t: string | null) {
  return formatDateTime(t);
}

/** 设备详情「去识别映射」深链：?deviceId=xxx 预选；改选后回写 query，keep-alive 恢复时不回跳旧柜。 */
function applyDeviceFromRoute() {
  const fromQuery = typeof route.query.deviceId === 'string' ? route.query.deviceId.trim() : '';
  if (!fromQuery || fromQuery === deviceId.value) return;
  deviceId.value = fromQuery;
  void load();
}

function onDeviceChange() {
  const query = { ...route.query };
  if (deviceId.value) query.deviceId = deviceId.value;
  else delete query.deviceId;
  void router.replace({ query });
  void load();
}

onMounted(() => {
  void loadDevices();
  void loadSkus();
  applyDeviceFromRoute();
});
// 首次 activated 紧随 onMounted（设备/SKU 选项已拉过），跳过防双请求
let activatedOnce = false;
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true;
    return;
  }
  // keep-alive 缓存页恢复时重拉设备/SKU 选项：设备页/商品页新增后切回本页，选择项不缺
  void loadDevices();
  void loadSkus();
  applyDeviceFromRoute();
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
  font-size: var(--admin-font-size-title);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.jy-device-bar {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  margin-bottom: 16px;
}
.jy-device-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 120px;
}
.jy-device-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.field-hint {
  margin-top: 4px;
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.jy-mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
.jy-model-card {
  margin-bottom: 16px;
}
.jy-model-alert {
  margin-bottom: 12px;
}
.jy-preview-rows {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 6px 16px;
  margin-bottom: 12px;
}
.jy-preview-row {
  display: flex;
  gap: 10px;
  align-items: baseline;
  font-size: 13px;
}
.jy-model-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.jy-deploy-card {
  margin-top: 16px;
}
</style>
