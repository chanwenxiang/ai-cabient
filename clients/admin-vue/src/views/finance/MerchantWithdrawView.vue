<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">商户提现</span>
            <!-- 打款模式由下方 payoutMode 提示条按后端真实配置说明，此处不再写死环境口径 -->
            <span class="hint">手续费见系统参数 · 到账金额 = 申请金额 − 手续费</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <template v-if="tab === 'withdraws' && auth.hasPerm('ops:merchant-withdraw:review')">
            <el-button
              type="success"
              plain
              :disabled="!wdCrud.hasSelection"
              :loading="wdBatchLoading === 'approve'"
              @click="batchReviewWithdraws(true)"
              >批量通过</el-button
            >
            <el-button
              type="danger"
              plain
              :disabled="!wdCrud.hasSelection"
              :loading="wdBatchLoading === 'reject'"
              @click="batchReviewWithdraws(false)"
              >批量驳回</el-button
            >
          </template>
          <!-- 刷新保留在页头：除重查当前 tab 列表外还需同步刷新打款模式提示；
               CrudTable 内建刷新只查列表，故两个表格均 :show-refresh="false" -->
          <el-button
            :icon="Refresh"
            :loading="tab === 'withdraws' ? wdCrud.loading : walletsCrud.loading"
            @click="reload"
            >刷新</el-button
          >
        </div>
      </div>
    </template>

    <el-alert
      v-if="payoutMode?.note"
      class="mb"
      :type="payoutMode.mockEnabled ? 'warning' : 'error'"
      :closable="false"
      show-icon
      :title="payoutMode.note"
    />

    <el-tabs v-model="tab" @tab-change="onTab">
      <el-tab-pane label="商户钱包" name="wallets">
        <el-form
          inline
          class="filter-bar filter-bar--compact"
          @submit.prevent="walletsCrud.search()"
        >
          <el-form-item label="关键词">
            <el-input
              v-model="keyword"
              clearable
              placeholder="商户编号 / 名称 / 手机"
              style="width: 220px"
              @keyup.enter="walletsCrud.search()"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="walletsCrud.search()">查询</el-button>
          </el-form-item>
        </el-form>

        <div class="table-scroll">
          <div class="table-scroll-inner">
            <CrudTable
              :table="walletsCrud"
              row-key="merchantId"
              selectable
              :actions="walletRowActions"
              :action-width="130"
              empty-text="暂无商户钱包"
              :show-refresh="false"
              @action="onWalletAction"
            >
              <el-table-column
                prop="merchantId"
                label="商户编号"
                min-width="120"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              />
              <el-table-column
                prop="merchantName"
                label="名称"
                min-width="140"
                class-name="col-text"
                label-class-name="col-text"
              />
              <el-table-column
                label="联系电话"
                width="130"
                class-name="col-text"
                label-class-name="col-text"
              >
                <template #default="{ row }">{{ row.contactPhone || '暂无' }}</template>
              </el-table-column>
              <el-table-column
                label="余额(元)"
                width="110"
                align="center"
                class-name="col-money"
                label-class-name="col-money"
              >
                <template #default="{ row }">{{ yuan(row.balanceCents) }}</template>
              </el-table-column>
              <el-table-column
                label="冻结(元)"
                width="110"
                align="center"
                class-name="col-money"
                label-class-name="col-money"
              >
                <template #default="{ row }">{{ yuan(row.frozenCents) }}</template>
              </el-table-column>
              <el-table-column
                label="可用(元)"
                width="110"
                align="center"
                class-name="col-money"
                label-class-name="col-money"
              >
                <template #default="{ row }">{{ yuan(row.availableCents) }}</template>
              </el-table-column>
              <el-table-column
                prop="status"
                label="状态"
                width="90"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">{{
                  displayLabel('merchant_status', row.status, '暂无')
                }}</template>
              </el-table-column>
            </CrudTable>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="提现审核" name="withdraws">
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="wdCrud.search()">
          <el-form-item label="状态">
            <el-select
              v-model="wdStatus"
              clearable
              placeholder="全部"
              style="width: 160px"
              @change="wdCrud.search()"
            >
              <el-option
                v-for="item in withdrawStatusOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="wdCrud.search()">查询</el-button>
          </el-form-item>
        </el-form>

        <div class="table-scroll">
          <CrudTable
            :table="wdCrud"
            row-key="requestId"
            selectable
            :actions="wdRowActionsFn"
            empty-text="暂无提现申请"
            :show-refresh="false"
            @action="onWdAction"
          >
            <el-table-column
              prop="requestId"
              label="单号"
              width="80"
              class-name="col-text"
              label-class-name="col-text"
            />
            <el-table-column
              prop="requestNo"
              label="业务单号"
              min-width="160"
              class-name="col-text"
              label-class-name="col-text"
            >
              <template #default="{ row }">
                <span class="cell-id">{{ displayBizNo(row.requestNo) }}</span>
              </template>
            </el-table-column>
            <el-table-column
              prop="merchantId"
              label="商户编号"
              width="120"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            />
            <el-table-column
              prop="merchantName"
              label="商户"
              min-width="120"
              class-name="col-text"
              label-class-name="col-text"
            />
            <el-table-column
              label="收款方"
              min-width="160"
              class-name="col-text"
              label-class-name="col-text"
            >
              <!-- V307 收款方快照（只读对账用）：户名 + 掩码账号 + 开户行 -->
              <template #default="{ row }">
                <template v-if="row.payeeAccountName || row.payeeAccountNoMask">
                  <div class="payee-cell">
                    <span class="payee-cell__name">{{ row.payeeAccountName || '—' }}</span>
                    <span class="payee-cell__meta">
                      {{ row.payeeAccountType === 'COMPANY' ? '对公' : '对私' }}
                      {{ row.payeeAccountNoMask || '' }}
                      {{ row.payeeBankName || '' }}
                    </span>
                  </div>
                </template>
                <template v-else>—</template>
              </template>
            </el-table-column>
            <el-table-column
              label="金额(元)"
              width="100"
              align="center"
              class-name="col-money"
              label-class-name="col-money"
            >
              <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
            </el-table-column>
            <el-table-column
              label="手续费"
              width="90"
              align="center"
              class-name="col-money"
              label-class-name="col-money"
            >
              <template #default="{ row }">{{ yuan(row.feeCents || 0) }}</template>
            </el-table-column>
            <el-table-column
              prop="status"
              label="状态"
              width="120"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">{{ withdrawStatusLabel(row.status) }}</template>
            </el-table-column>
            <el-table-column
              label="通道"
              width="100"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">{{
                displayLabel('pay_channel', row.payChannel, '未知')
              }}</template>
            </el-table-column>
            <el-table-column
              prop="payoutRef"
              label="回执"
              min-width="140"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">{{ displayBizNo(row.payoutRef, '无') }}</template>
            </el-table-column>
            <el-table-column
              label="渠道对账"
              min-width="150"
              class-name="col-status"
              label-class-name="col-status"
            >
              <!-- V307 渠道对账字段：真实出款后的渠道单号/批次号 + 我方幂等键（渠道侧商户单号） -->
              <template #default="{ row }">
                <template v-if="row.channelOrderNo || row.channelBatchNo || row.idemKey">
                  <div class="payee-cell">
                    <span class="payee-cell__name">{{
                      displayBizNo(row.channelOrderNo || row.channelBatchNo)
                    }}</span>
                    <span v-if="row.idemKey" class="payee-cell__meta">{{
                      displayBizNo(row.idemKey)
                    }}</span>
                  </div>
                </template>
                <template v-else>—</template>
              </template>
            </el-table-column>
            <el-table-column
              prop="payoutMessage"
              label="打款说明"
              min-width="140"
              class-name="col-text"
              label-class-name="col-text"
            />
            <el-table-column
              prop="reviewRemark"
              label="审核备注"
              min-width="120"
              class-name="col-text"
              label-class-name="col-text"
            />
            <el-table-column
              label="申请时间"
              width="170"
              align="center"
              class-name="col-status"
              label-class-name="col-status"
            >
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
          </CrudTable>
        </div>
      </el-tab-pane>

      <!-- 按商户提现费率覆盖（2026-10-16 拍板：默认 6‰、不写死、可按商户配）：
           写入 sys_config key merchant.withdraw.fee_bps.{商户号}，后端读路径 merchantFeeBpsFor 已支持 -->
      <el-tab-pane v-if="auth.hasPerm('ops:config:edit')" label="提现费率" name="fee">
        <el-alert
          class="mb"
          type="info"
          :closable="false"
          show-icon
          title="手续费在提现申请时快照：到账 = 申请额 − 固定费 − 申请额 × 万分比。全局默认 60 万分比（6‰，对齐老系统标准档）；此处只配单个商户的降档或免收。单笔上限 ¥10000 内 6‰ 最多 ¥60，封顶留 0=不封顶（配 ¥25/¥50 几乎永不触发）。"
        />
        <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="loadFeePolicy">
          <el-form-item label="商户编号">
            <el-input
              v-model="feeMerchantId"
              clearable
              placeholder="商户号（如 100000000001）"
              style="width: 220px"
              @keyup.enter="loadFeePolicy"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="feeLoading" @click="loadFeePolicy">查询</el-button>
          </el-form-item>
        </el-form>

        <template v-if="feeLoaded">
          <el-descriptions :column="1" border class="mb">
            <el-descriptions-item label="全局费率">
              {{ feeGlobalBps }} 万分比（{{ bpsText(feeGlobalBps) }}）
              <span class="muted">
                + 固定 {{ yuan(feeFlatCents) }} · 单笔封顶
                {{ feeCapCents > 0 ? yuan(feeCapCents) : '不封顶' }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="本商户覆盖">
              <span v-if="feeOverrideBps == null" class="muted">未配置（走全局）</span>
              <span v-else>{{ feeOverrideBps }} 万分比（{{ bpsText(feeOverrideBps) }}）</span>
            </el-descriptions-item>
            <el-descriptions-item label="生效费率">
              {{ feeEffectiveBps }} 万分比 · 提现 {{ yuan(SAMPLE_WITHDRAW_CENTS) }} 示例：手续费
              {{ yuan(feeExampleCents) }}，到账
              {{ yuan(SAMPLE_WITHDRAW_CENTS - feeExampleCents) }}
            </el-descriptions-item>
          </el-descriptions>

          <el-form label-width="150px" style="max-width: 560px">
            <el-form-item label="覆盖费率（万分比）">
              <el-input-number
                v-model="feeDraftBps"
                :min="0"
                :max="1000"
                :step="10"
                controls-position="right"
                style="width: 200px"
              />
              <span class="muted" style="margin-left: 8px">0 = 该商户免收；40 = 4‰</span>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="feeSaving" @click="saveFeeOverride">
                保存覆盖
              </el-button>
              <el-button
                v-if="feeOverrideBps != null"
                type="danger"
                plain
                :loading="feeSaving"
                @click="clearFeeOverride"
              >
                清除覆盖（回到全局）
              </el-button>
            </el-form-item>
          </el-form>
        </template>
        <el-empty
          v-else-if="!feeLoading"
          description="输入商户编号后查询当前费率"
          :image-size="72"
        />
      </el-tab-pane>
    </el-tabs>

    <ResizableDrawer
      v-model="ledgerVisible"
      title="钱包流水"
      storage-key="admin.drawer.withdraw.ledger"
      :default-width="520"
      :min-width="420"
    >
      <el-table v-loading="!ledgerHydrated" :data="ledgers" size="small" stripe empty-text=" ">
        <template #empty>
          <el-empty v-if="ledgerHydrated" description="暂无流水" :image-size="64" />
        </template>
        <el-table-column
          label="类型"
          width="120"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{
            displayLabel('wallet_entry_type', row.entryType, '未知')
          }}</template>
        </el-table-column>
        <el-table-column
          label="变动(元)"
          width="100"
          align="center"
          class-name="col-money"
          label-class-name="col-money"
        >
          <template #default="{ row }">{{ yuan(row.amountCents) }}</template>
        </el-table-column>
        <el-table-column
          label="余额后"
          width="100"
          align="center"
          class-name="col-money"
          label-class-name="col-money"
        >
          <template #default="{ row }">{{ yuan(row.balanceAfter) }}</template>
        </el-table-column>
        <el-table-column
          label="冻结后"
          width="100"
          align="center"
          class-name="col-money"
          label-class-name="col-money"
        >
          <template #default="{ row }">{{ yuan(row.frozenAfter) }}</template>
        </el-table-column>
        <el-table-column
          label="关联单号"
          min-width="140"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">
            <span v-if="row.refId">
              <small class="muted">{{
                displayLabel('wallet_ref_type', row.refType, '关联')
              }}</small>
              {{ displayBizNo(row.refId) }}
            </span>
            <span v-else class="muted">暂无</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="remark"
          label="备注"
          min-width="120"
          class-name="col-text"
          label-class-name="col-text"
        />
        <el-table-column
          label="时间"
          width="160"
          align="center"
          class-name="col-status"
          label-class-name="col-status"
        >
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </ResizableDrawer>

    <el-dialog
      v-model="adjustVisible"
      title="商户调账"
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
    >
      <div v-if="adjustTarget" class="dialog-merchant">
        <div class="dialog-merchant__name">
          {{ adjustTarget.merchantName || adjustTarget.merchantId }}
        </div>
        <div class="dialog-merchant__id">商户 {{ adjustTarget.merchantId }}</div>
        <div class="dialog-balance">
          <span
            >当前余额 <b>{{ yuan(adjustTarget.balanceCents) }}</b></span
          >
          <span
            >可用 <b>{{ yuan(adjustTarget.availableCents) }}</b></span
          >
        </div>
      </div>
      <el-form label-position="top" @submit.prevent="submitAdjust">
        <el-form-item label="调整金额（元，负数扣减）" required>
          <el-input-number
            v-model="adjustForm.amount"
            :precision="2"
            :step="10"
            :min="-1000000"
            :max="1000000"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="adjustForm.remark" maxlength="100" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjustSaving" @click="submitAdjust">确认调账</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="withdrawVisible"
      title="代商户提现"
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
    >
      <div v-if="withdrawTarget" class="dialog-merchant">
        <div class="dialog-merchant__name">
          {{ withdrawTarget.merchantName || withdrawTarget.merchantId }}
        </div>
        <div class="dialog-merchant__id">商户 {{ withdrawTarget.merchantId }}</div>
        <div class="dialog-balance">
          <span
            >可用余额 <b>{{ yuan(withdrawTarget.availableCents) }}</b></span
          >
          <span
            >冻结 <b>{{ yuan(withdrawTarget.frozenCents) }}</b></span
          >
          <!-- CB-035：T+1 待结算透出——解锁时刻可配（默认次日零点） -->
          <span v-if="(withdrawTarget.pendingSettleCents ?? 0) > 0">
            待结算（T+1 可提） <b>{{ yuan(withdrawTarget.pendingSettleCents) }}</b></span
          >
        </div>
      </div>
      <el-form label-position="top" @submit.prevent="submitWithdraw">
        <el-form-item label="提现金额（元）" required>
          <el-input-number
            v-model="withdrawForm.amount"
            :precision="2"
            :step="10"
            :min="1"
            :max="10000000"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="withdrawVisible = false">取消</el-button>
        <el-button type="warning" :loading="withdrawSaving" @click="submitWithdraw"
          >确认提现</el-button
        >
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import ResizableDrawer from '@/components/ResizableDrawer.vue';
import {
  CircleCheck,
  CircleClose,
  Coin,
  List,
  Money,
  Refresh,
  RefreshRight,
  Unlock
} from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { errorMessage, isUserDismiss } from '@/utils/error-message';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { useAuthStore } from '@/stores/auth';
import CrudTable, { type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { createLoadSeq } from '@/composables/createLoadSeq';
import { displayBizNo, formatDateTime } from '@aicabinet/shared-uni/format';
import { displayLabel } from '@aicabinet/shared-dict';
import { useDictOptions } from '@/composables/useDictOptions';
import { yuanTextZero as yuan, yuanToCents, bpsText } from '@/utils/display';
import {
  buildMerchantWithdrawReviewBody,
  canCancelFailedMerchantWithdraw,
  canRetryMerchantWithdrawPayout,
  canReviewMerchantWithdraw
} from '@/utils/money-ui-contracts';

// loadSeq 仅剩打款模式提示在用；两个列表的分页/多选/竞态已由 useCrudTable 收口
const loadSeq = createLoadSeq();

interface SystemConfigRow {
  configKey: string;
  configValue: string;
  description?: string | null;
  updatedAt?: string | null;
}

interface WalletRow {
  merchantId: string;
  merchantName?: string;
  contactPhone?: string;
  status?: string;
  balanceCents?: number;
  frozenCents?: number;
  availableCents?: number;
  /** CB-035：已入账但未到 T+1 可提现日的金额（与提现闸门同口径） */
  pendingSettleCents?: number;
}

interface LedgerRow {
  ledgerId?: number;
  merchantId?: string;
  entryType?: string;
  amountCents?: number;
  balanceAfter?: number;
  frozenAfter?: number;
  refType?: string;
  refId?: string;
  remark?: string;
  createdAt?: string;
}

interface Withdraw {
  requestId: number;
  requestNo: string;
  merchantId: string;
  merchantName?: string;
  amountCents: number;
  status: string;
  payChannel?: string;
  payoutRef?: string;
  payoutMessage?: string;
  reviewRemark?: string;
  createdAt?: string;
  paidAt?: string;
}

const auth = useAuthStore();
const tab = ref('wallets');
const payoutMode = ref<{ mockEnabled?: boolean; note?: string } | null>(null);
// —— 按商户提现费率覆盖（写 sys_config key merchant.withdraw.fee_bps.{商户号}）——
// 命名带单位：BPS = 万分比，CENTS = 分，避免读代码时猜单位
const FEE_GLOBAL_BPS_KEY = 'merchant.withdraw.fee_bps';
const FEE_FLAT_CENTS_KEY = 'merchant.withdraw.fee_cents';
const FEE_CAP_CENTS_KEY = 'merchant.withdraw.fee_cap_cents';
const FEE_BPS_OVERRIDE_PREFIX = 'merchant.withdraw.fee_bps.';
const FEE_OVERRIDE_DESC =
  '按商户覆盖的提现手续费万分比（40=4‰，0=该商户免收）；未配置则走全局 merchant.withdraw.fee_bps';
const SAMPLE_WITHDRAW_CENTS = 100000; // 示例提现额 ¥1000
const feeMerchantId = ref('');
const feeLoading = ref(false);
const feeLoaded = ref(false);
const feeSaving = ref(false);
const feeGlobalBps = ref(0);
const feeFlatCents = ref(0);
const feeCapCents = ref(0);
const feeOverrideBps = ref<number | null>(null);
const feeDraftBps = ref(0);
const feeEffectiveBps = computed(() => feeOverrideBps.value ?? feeGlobalBps.value);
// 与后端 WithdrawFeeCalculator 同式：固定 + 金额×bps/10000，再按封顶取小（0=不封顶）。
// 🔴 封顶必须参与示例，否则运营配了封顶后这里的到账额会比真实到账额高，等于给商户报错数。
const feeExampleCents = computed(() => {
  const raw =
    feeFlatCents.value + Math.floor((SAMPLE_WITHDRAW_CENTS * feeEffectiveBps.value) / 10000);
  return feeCapCents.value > 0 ? Math.min(raw, feeCapCents.value) : raw;
});
const keyword = ref('');
const wdStatus = ref('');
const ledgerVisible = ref(false);
const ledgerHydrated = ref(false);
const ledgers = ref<LedgerRow[]>([]);
const adjustVisible = ref(false);
const adjustSaving = ref(false);
const adjustTarget = ref<WalletRow | null>(null);
const adjustForm = ref({ amount: 0, remark: '' });
const withdrawVisible = ref(false);
const withdrawSaving = ref(false);
const withdrawTarget = ref<WalletRow | null>(null);
const withdrawForm = ref({ amount: 0 });
const wdBatchLoading = ref<'approve' | 'reject' | ''>('');

// 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态 / 空态全部内建。
// 两个 tab 的内容默认同时挂载，首查必须跟随当前激活 tab（并与打款模式提示同批初始化），
// 因此 autoLoad:false，由 onMounted(reload) / onTab 显式首查。
const walletsCrud = useCrudTable<WalletRow>({
  rowKey: (r) => r.merchantId,
  autoLoad: false,
  fetchPage: (params) => {
    const q = new URLSearchParams({ page: String(params.page), size: String(params.size) });
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim());
    return api.request<{ items: WalletRow[]; total: number }>(
      AdminEndpoints.merchantWalletsList(q),
      'GET'
    );
  }
});

const wdCrud = useCrudTable<Withdraw>({
  rowKey: (r) => r.requestId,
  autoLoad: false,
  fetchPage: (params) => {
    const q = new URLSearchParams({ page: String(params.page), size: String(params.size) });
    if (wdStatus.value) q.set('status', wdStatus.value);
    return api.request<{ items: Withdraw[]; total: number }>(
      AdminEndpoints.merchantWithdrawsList(q),
      'GET'
    );
  }
});

const withdrawStatusOptions = useDictOptions('merchant_withdraw_status');

function canReviewWithdraw(row: Withdraw) {
  return canReviewMerchantWithdraw(row.status, auth.hasPerm('ops:merchant-withdraw:review'));
}

function canRetryWithdrawPayout(row: Withdraw) {
  return canRetryMerchantWithdrawPayout(row.status, auth.hasPerm('ops:merchant-withdraw:review'));
}

function canCancelFailedWithdraw(row: Withdraw) {
  return canCancelFailedMerchantWithdraw(row.status, auth.hasPerm('ops:merchant-withdraw:review'));
}

function walletRowActions(_row: WalletRow): CrudRowAction[] {
  return [
    {
      key: 'adjust',
      label: '调账',
      icon: Coin,
      type: 'primary',
      perm: 'ops:merchant-withdraw:adjust'
    },
    { key: 'ledgers', label: '流水', icon: List },
    {
      key: 'withdraw',
      label: '代提现',
      icon: Money,
      type: 'warning',
      perm: 'ops:merchant-withdraw:adjust'
    }
  ];
}

function onWalletAction({ key, row }: { key: string; row: WalletRow }) {
  if (key === 'adjust') openAdjust(row);
  else if (key === 'ledgers') void showLedgers(row);
  else if (key === 'withdraw') openWithdraw(row);
}

/** 审核行操作：权限内嵌在 can* 判定（状态+权限双条件），与迁移前一致 */
function wdActions(row: Withdraw): CrudRowAction[] {
  if (canReviewWithdraw(row)) {
    return [
      { key: 'approve', label: '通过并打款', icon: CircleCheck, type: 'success' },
      { key: 'reject', label: '驳回', icon: CircleClose, type: 'danger' }
    ];
  }
  if (canRetryWithdrawPayout(row)) {
    const acts: CrudRowAction[] = [
      { key: 'payout', label: '重试打款', icon: RefreshRight, type: 'primary' }
    ];
    if (canCancelFailedWithdraw(row)) {
      acts.push({ key: 'cancel', label: '取消解冻', icon: Unlock, type: 'danger' });
    }
    return acts;
  }
  return [];
}

/** 当前页无可操作行时传 undefined，让 CrudTable 不渲染操作列，避免终态列表整列空白 */
const wdRowActionsFn = computed<((row: Withdraw) => CrudRowAction[]) | undefined>(() =>
  wdCrud.items.some(
    (row) => canReviewWithdraw(row) || canRetryWithdrawPayout(row) || canCancelFailedWithdraw(row)
  )
    ? wdActions
    : undefined
);

function onWdAction({ key, row }: { key: string; row: Withdraw }) {
  if (key === 'approve') void review(row, true);
  else if (key === 'reject') void review(row, false);
  else if (key === 'payout') void payout(row);
  else if (key === 'cancel') void cancelFailed(row);
}

function withdrawStatusLabel(status?: string) {
  return displayLabel('merchant_withdraw_status', status, '未知状态');
}

function onTab() {
  reload();
}

function reload() {
  void loadPayoutMode();
  if (tab.value === 'withdraws') void wdCrud.load();
  else if (tab.value === 'fee' && feeMerchantId.value.trim()) void loadFeePolicy();
  else void walletsCrud.load();
}

async function loadFeePolicy() {
  const merchantId = feeMerchantId.value.trim();
  if (!merchantId) {
    ElMessage.warning('请输入商户编号');
    return;
  }
  const seq = loadSeq.begin('loadFeePolicy');
  feeLoading.value = true;
  try {
    const rows = await api.request<SystemConfigRow[]>(AdminEndpoints.systemConfigs, 'GET');
    if (!loadSeq.isCurrent(seq, 'loadFeePolicy')) return;
    const readConfigNumber = (key: string): number | null => {
      const raw = rows.find((r) => r.configKey === key)?.configValue;
      if (raw == null || raw.trim() === '') return null;
      const n = Number(raw.trim());
      return Number.isFinite(n) && n >= 0 ? n : null;
    };
    feeGlobalBps.value = readConfigNumber(FEE_GLOBAL_BPS_KEY) ?? 0;
    feeFlatCents.value = readConfigNumber(FEE_FLAT_CENTS_KEY) ?? 0;
    feeCapCents.value = readConfigNumber(FEE_CAP_CENTS_KEY) ?? 0;
    feeOverrideBps.value = readConfigNumber(FEE_BPS_OVERRIDE_PREFIX + merchantId);
    feeDraftBps.value = feeOverrideBps.value ?? feeGlobalBps.value;
    feeLoaded.value = true;
  } catch (e) {
    if (!loadSeq.isCurrent(seq, 'loadFeePolicy')) return;
    feeLoaded.value = false;
    ElMessage.error(errorMessage(e, '读取费率配置失败'));
  } finally {
    feeLoading.value = false;
  }
}

async function saveFeeOverride() {
  const merchantId = feeMerchantId.value.trim();
  if (!merchantId) {
    ElMessage.warning('请输入商户编号');
    return;
  }
  feeSaving.value = true;
  try {
    await api.request(AdminEndpoints.systemConfigs, 'PUT', {
      configKey: FEE_BPS_OVERRIDE_PREFIX + merchantId,
      configValue: String(feeDraftBps.value ?? 0),
      description: FEE_OVERRIDE_DESC
    });
    ElMessage.success(
      `已保存：${merchantId} 提现费率 ${feeDraftBps.value} 万分比（下一笔申请起生效）`
    );
    await loadFeePolicy();
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存失败'));
  } finally {
    feeSaving.value = false;
  }
}

async function clearFeeOverride() {
  const merchantId = feeMerchantId.value.trim();
  if (!merchantId) return;
  try {
    await ElMessageBox.confirm(
      `确认清除 ${merchantId} 的费率覆盖？清除后该商户回到全局费率。`,
      '清除覆盖',
      { type: 'warning' }
    );
  } catch (e) {
    if (isUserDismiss(e)) return;
    ElMessage.error(errorMessage(e, '操作中断'));
    return;
  }
  feeSaving.value = true;
  try {
    await api.request(AdminEndpoints.systemConfig(FEE_BPS_OVERRIDE_PREFIX + merchantId), 'DELETE');
    ElMessage.success('已清除覆盖（该商户走全局费率）');
    await loadFeePolicy();
  } catch (e) {
    ElMessage.error(errorMessage(e, '清除失败'));
  } finally {
    feeSaving.value = false;
  }
}

async function loadPayoutMode() {
  const seq = loadSeq.begin('loadPayoutMode');
  try {
    payoutMode.value = await api.request(AdminEndpoints.merchantWithdrawsPayoutMode, 'GET');
  } catch {
    if (!loadSeq.isCurrent(seq, 'loadPayoutMode')) return;
    payoutMode.value = {
      mockEnabled: true,
      note: '无法读取打款模式；当前可能为记账打款（非真实转账）'
    };
  }
}

function openAdjust(row: WalletRow) {
  adjustTarget.value = row;
  adjustForm.value = { amount: 0, remark: '' };
  adjustVisible.value = true;
}

async function submitAdjust() {
  if (!adjustTarget.value) return;
  const amountCents = yuanToCents(adjustForm.value.amount);
  if (amountCents == null || amountCents === 0) {
    ElMessage.warning('请输入非零调整金额');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认对商户 ${adjustTarget.value.merchantId} 调账 ${yuan(amountCents)}？该操作将写入审计日志。`,
      '商户调账二次确认',
      { type: 'warning', confirmButtonText: '确认调账', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  adjustSaving.value = true;
  try {
    await api.request(AdminEndpoints.merchantWalletAdjust(adjustTarget.value.merchantId), 'POST', {
      amountCents,
      remark: adjustForm.value.remark.trim() || '运营调账'
    });
    ElMessage.success('已调账');
    adjustVisible.value = false;
    await walletsCrud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '调账失败'));
  } finally {
    adjustSaving.value = false;
  }
}

async function showLedgers(row: WalletRow) {
  ledgers.value = [];
  ledgerVisible.value = true;
  try {
    ledgers.value = await api.request<LedgerRow[]>(
      AdminEndpoints.merchantWalletLedgers(row.merchantId),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载流水失败'));
    ledgers.value = [];
  } finally {
    ledgerHydrated.value = true;
  }
}

function openWithdraw(row: WalletRow) {
  withdrawTarget.value = row;
  withdrawForm.value = { amount: 0 };
  withdrawVisible.value = true;
}

async function submitWithdraw() {
  if (!withdrawTarget.value) return;
  const amountCents = yuanToCents(withdrawForm.value.amount);
  if (amountCents == null || amountCents <= 0) {
    ElMessage.warning('请输入大于 0 的提现金额');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认代商户 ${withdrawTarget.value.merchantId} 发起提现 ${yuan(amountCents)}？`,
      '代提现二次确认',
      { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  withdrawSaving.value = true;
  try {
    await api.request(
      AdminEndpoints.merchantWalletWithdraw(withdrawTarget.value.merchantId),
      'POST',
      { amountCents }
    );
    ElMessage.success('已提交提现');
    withdrawVisible.value = false;
    tab.value = 'withdraws';
    await wdCrud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '代提现失败'));
  } finally {
    withdrawSaving.value = false;
  }
}

async function review(row: Withdraw, approve: boolean) {
  try {
    await ElMessageBox.confirm(
      approve ? `确认通过该提现申请并打款 ¥${yuan(row.amountCents)}？` : `确认驳回该提现申请？`,
      approve ? '通过并打款' : '驳回申请',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    );
    await api.request(
      AdminEndpoints.merchantWithdrawReview(row.requestId),
      'POST',
      buildMerchantWithdrawReviewBody(approve)
    );
    ElMessage.success(approve ? '已通过' : '已驳回');
    await wdCrud.load();
  } catch (e: unknown) {
    if (!isUserDismiss(e)) {
      ElMessage.error(errorMessage(e, '审核失败'));
    }
  }
}

async function batchReviewWithdraws(approve: boolean) {
  const targets = wdCrud
    .pickSelected(wdCrud.items)
    .filter((r) =>
      canReviewMerchantWithdraw(r.status, auth.hasPerm('ops:merchant-withdraw:review'))
    );
  if (!targets.length) {
    ElMessage.warning('请先勾选待审核提现申请');
    return;
  }
  const label = approve ? '通过并打款' : '驳回';
  try {
    await ElMessageBox.confirm(
      approve
        ? `确认批量通过 ${targets.length} 条提现申请并打款？`
        : `确认批量驳回 ${targets.length} 条提现申请？`,
      `批量${label}`,
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  wdBatchLoading.value = approve ? 'approve' : 'reject';
  try {
    const results = await Promise.allSettled(
      targets.map((row) =>
        api.request(
          AdminEndpoints.merchantWithdrawReview(row.requestId),
          'POST',
          buildMerchantWithdrawReviewBody(approve, { batch: true })
        )
      )
    );
    const ok = results.filter((r) => r.status === 'fulfilled').length;
    const fail = results.length - ok;
    if (fail === 0) ElMessage.success(`已${approve ? '通过' : '驳回'} ${ok} 条`);
    else ElMessage.warning(`批量${label}完成：成功 ${ok}，失败 ${fail}`);
    wdCrud.clearSelection();
    await wdCrud.load();
  } finally {
    wdBatchLoading.value = '';
  }
}

async function payout(row: Withdraw) {
  try {
    await ElMessageBox.confirm(
      `确认对该提现申请（¥${yuan(row.amountCents)}）重新打款？`,
      '重试打款',
      {
        type: 'warning',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      }
    );
    await api.request(AdminEndpoints.merchantWithdrawPayout(row.requestId), 'POST', {});
    ElMessage.success('已重试打款');
    await wdCrud.load();
  } catch (e: unknown) {
    if (!isUserDismiss(e)) {
      ElMessage.error(errorMessage(e, '打款失败'));
    }
  }
}

async function cancelFailed(row: Withdraw) {
  try {
    await ElMessageBox.confirm(
      `确认取消该失败提现（¥${yuan(row.amountCents)}）并解冻资金？取消后不可再打款。`,
      '取消解冻',
      {
        type: 'warning',
        confirmButtonText: '确定解冻',
        cancelButtonText: '返回'
      }
    );
    await api.request(AdminEndpoints.merchantWithdrawCancel(row.requestId), 'POST', {
      remark: '运营取消解冻'
    });
    ElMessage.success('已取消并解冻');
    await wdCrud.load();
  } catch (e: unknown) {
    if (!isUserDismiss(e)) {
      ElMessage.error(errorMessage(e, '取消失败'));
    }
  }
}

onMounted(reload);
</script>

<style scoped>
.mb {
  margin-bottom: 12px;
}
/* V307 收款方/渠道对账列：主行关键值 + 副行补充信息 */
.payee-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.35;
}
.payee-cell__name {
  color: var(--layout-text);
  font-variant-numeric: tabular-nums;
}
.payee-cell__meta {
  color: var(--layout-muted);
  font-size: var(--admin-font-size-sm);
  font-variant-numeric: tabular-nums;
}
.dialog-merchant {
  padding: 12px 14px;
  margin-bottom: 16px;
  border: 1px solid var(--layout-border);
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.dialog-merchant__name {
  font-weight: 600;
  font-size: var(--admin-font-size-title);
}
.dialog-merchant__id {
  color: var(--layout-muted);
  font-size: var(--admin-font-size-sm);
  margin-top: 2px;
}
.dialog-balance {
  display: flex;
  gap: 20px;
  margin-top: 10px;
  font-size: var(--admin-font-size-table);
  color: var(--layout-muted);
}
.dialog-balance b {
  color: var(--layout-text);
  font-variant-numeric: tabular-nums;
}
</style>
