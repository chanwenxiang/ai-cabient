<template>
  <view class="page-root">
    <app-nav-bar :title="cfg.title" />
    <view class="page-body">
      <view v-if="loadError" class="banner-err">
        <text>{{ loadError }}</text>
        <text role="button" aria-label="重试" class="banner-retry" @click="load">重试</text>
      </view>

      <empty-state
        v-if="!loading && overview && !overview.bound"
        :icon="cfg.emptyIcon"
        :title="cfg.emptyTitle"
        :hint="cfg.emptyHint"
      />

      <template v-else-if="overview && overview.bound">
        <view class="summary-card">
          <text class="role-tag">{{ cfg.roleTag }}</text>
          <text class="name">{{ displayName }}</text>
          <view class="bal-row">
            <text class="bal-label">可用余额</text>
            <text class="bal-value">{{ fmtMoney(overview.availableCents) }}</text>
          </view>
          <view class="bal-sub">
            <text>账面 {{ fmtMoney(overview.balanceCents) }}</text>
            <text>冻结 {{ fmtMoney(overview.frozenCents) }}</text>
            <!-- CB-035：T+1 待结算透出——与提现闸门同口径，当天销售款次日可提 -->
            <text v-if="pendingSettle > 0" class="pending-settle"
              >待结算 {{ fmtMoney(pendingSettle) }}</text
            >
          </view>
          <!-- CB-086：欠款可见。后端 availableCents 按 0 钳制，负余额只在「账面」那个数字里，
               用户看不出可提现为什么是 0——这里补一句原因与处置口径。纯展示：不新增写入口，
               结清仍走运营后台调账/线下打款后入账，抵扣由后续销售入账自动完成。 -->
          <text v-if="arrearsCents > 0" class="tip"
            >当前欠款
            {{ fmtMoney(arrearsCents) }}，后续入账自动抵扣，结清前不可提现；连续欠款满锁柜账龄（默认
            90 天）将锁柜停售，结清自动复机</text
          >
        </view>

        <view class="action-card">
          <view class="amount-field">
            <input
              class="amount-input"
              type="digit"
              maxlength="10"
              v-model="amountYuan"
              placeholder="提现金额（元）"
              placeholder-class="amount-ph"
            />
          </view>
          <text v-if="maxWithdrawHint" class="withdraw-hint">{{ maxWithdrawHint }}</text>
          <text v-if="minWithdrawHint" class="withdraw-hint">{{ minWithdrawHint }}</text>
          <!-- 2026-11-20：实名采集入口归位商户端。此处只在「有提现面」时显示状态，
               未实名给可点入口，避免再让用户去消费者小程序填证件号。 -->
          <view
            v-if="identityVerified === false"
            class="identity-row"
            role="button"
            aria-label="去完成实名认证"
            @click="goRealname"
          >
            <text class="identity-warn">尚未实名 · 去实名</text>
          </view>
          <text v-else-if="identityVerified === true" class="identity-ok">已实名 · 可发起提现</text>
          <!-- 2026-10-20：实名等后端拒绝原因（约 50 字）用 banner 全文展示——
               toast 会被截断成 19 字，商户/线长看不到「去完成实名」的关键指引 -->
          <view v-if="withdrawError" class="banner-err">
            <text>{{ withdrawError }}</text>
          </view>
          <!-- H53：绑定多商户时必须显式选择提现商户（后端未指定且多绑定返回 400「请指定提现商户」） -->
          <picker
            v-if="multiMerchant"
            :range="merchantOptions"
            range-key="label"
            @change="onMerchantPick"
          >
            <view class="picker">提现商户：{{ selectedMerchantLabel }}</view>
          </picker>
          <app-button
            :disabled="submitting"
            :loading="submitting"
            label="申请提现"
            @click="submitWithdraw"
          />
          <text class="tip">{{ cfg.tip }}</text>
        </view>

        <view class="section">
          <text class="section-title">最近提现</text>
          <view v-for="w in overview.recentWithdraws || []" :key="w.requestId" class="row-item">
            <view class="row-main">
              <text>{{ fmtMoney(w.amountCents) }}</text>
              <text class="status">{{ withdrawStatus(w.status) }}</text>
            </view>
            <text class="row-sub"
              >{{ displayBizNo(w.requestNo)
              }}{{
                w.payChannel ? ` · ${displayLabel('pay_channel', w.payChannel, w.payChannel)}` : ''
              }}</text
            >
            <text class="row-sub"
              >手续费
              {{
                w.feeCents == null
                  ? '—'
                  : `${fmtMoney(w.feeCents)}${Number(w.feeCents) === 0 ? '（免收）' : ''}`
              }}
              · 到账
              {{
                fmtMoney(Math.max(0, Number(w.amountCents ?? 0) - Number(w.feeCents ?? 0)))
              }}</text
            >
            <text v-if="w.payoutRef || w.payoutMessage" class="row-sub"
              >回执 {{ w.payoutRef ? displayBizNo(w.payoutRef) : w.payoutMessage }}</text
            >
            <text
              v-if="w.reviewRemark && w.status !== 'PAID'"
              class="row-sub"
              :class="{ fail: w.status === 'FAILED' || w.status === 'REJECTED' }"
              >备注 {{ w.reviewRemark }}</text
            >
            <text v-if="w.paidAt || w.createdAt" class="row-sub">{{
              formatTime(w.paidAt || w.createdAt)
            }}</text>
            <!-- MA-407 确认收款模式：渠道已锁资金、等用户在微信点确认。不给入口钱会吊在渠道，
                 24h 后微信自动退回（定时查单才落 FAILED 并解冻）——所以这里必须有按钮。 -->
            <view
              v-if="canConfirmPayout(w)"
              class="confirm-row"
              role="button"
              aria-label="去微信确认收款"
              @click="openConfirmPage(w)"
            >
              <text class="confirm-btn">{{
                confirmingId === Number(w.requestId) ? '正在打开微信…' : '去确认收款'
              }}</text>
              <text class="confirm-hint">已提交微信，需你确认收款后到账（24 小时内有效）</text>
            </view>
          </view>
          <empty-state
            v-if="!(overview.recentWithdraws || []).length"
            compact
            icon="/static/menu/wallet.png"
            title="暂无提现记录"
            hint="提交提现后会出现在这里"
          />
        </view>

        <view class="section">
          <text class="section-title">最近流水</text>
          <view v-for="l in overview.recentLedgers || []" :key="l.ledgerId" class="row-item">
            <view class="row-main">
              <text>{{ ledgerTitleOf(l) }}</text>
              <text
                :class="{ credit: Number(l.amountCents) > 0, debit: Number(l.amountCents) < 0 }"
              >
                {{ formatSigned(l.amountCents) }}
              </text>
            </view>
            <text v-if="ledgerRemarkOf(l)" class="row-sub">{{ ledgerRemarkOf(l) }}</text>
            <text v-if="ledgerRefOf(l)" class="row-sub">{{ ledgerRefOf(l) }}</text>
            <text v-if="l.balanceAfter != null" class="row-sub"
              >余额后 {{ fmtMoney(l.balanceAfter) }} · 冻结后 {{ fmtMoney(l.frozenAfter) }}</text
            >
            <text v-if="cfg.showLedgerTime && l.createdAt" class="row-sub">{{
              formatTime(l.createdAt)
            }}</text>
          </view>
          <empty-state
            v-if="!(overview.recentLedgers || []).length"
            compact
            icon="/static/menu/billing.png"
            title="暂无流水记录"
            :hint="cfg.ledgerEmptyHint"
          />
        </view>
      </template>

      <view v-if="loading" class="loading-inline">{{ UI_COPY.loading }}</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
// 🔴 组件里必须用 onPageShow：小程序端 uni 运行时把组件的 pageLifetimes.show 映射成
// onPageShow，组件级的 onShow 永远不会被调用（页面级 onShow 只对页面实例生效）。
import { onPageShow } from '@dcloudio/uni-app';
import { useAutoRefresh } from '@/composables/use-auto-refresh';
import { displayLabel } from '@aicabinet/shared-dict';
import {
  displayBizNo,
  emptyDisplay,
  formatDateTimeMinute,
  formatDateTimeShort,
  fmtMoney,
  yuanToCents
} from '@aicabinet/shared-uni/format';
import EmptyState from '@aicabinet/shared-uni/components/empty-state.vue';
import { isRetriableMpTransportError } from '@aicabinet/shared-uni/request';
import {
  merchantApi,
  isMerchantLoggedIn,
  handleUnauthorized,
  hasPerm,
  fetchWithdrawConfirmTicket
} from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type {
  OpenApiLineWalletOverviewDto,
  OpenApiMerchantWalletOverviewDto
} from '@aicabinet/shared-types';
import { secureRandomToken } from '@/utils/secure-id';
import {
  buildWalletWithdrawBody,
  buildWalletWithdrawRequestNo,
  isTerminalWithdrawStatus,
  validateWalletWithdrawAmount,
  validateWalletWithdrawMerchant
} from '@/utils/money-ui-contracts';
import { walletLedgerRefText, walletLedgerRemark, walletLedgerTitle } from '@/utils/wallet-display';
import {
  lineWithdrawMinCents,
  loadMerchantFlags,
  merchantWithdrawMinCents
} from '@/utils/merchant-config';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

export type WalletPageRole = 'merchant' | 'line';

const props = defineProps<{ role: WalletPageRole }>();

type Overview = OpenApiMerchantWalletOverviewDto | OpenApiLineWalletOverviewDto;

const cfg = computed(() =>
  props.role === 'merchant'
    ? {
        title: '商户钱包',
        emptyIcon: '/static/menu/wallet.png',
        emptyTitle: '暂无商户钱包',
        emptyHint: '当前账号未绑定开通经营工具的商户，无法查看可提现余额',
        roleTag: '商户 · 可自主提现',
        tip: '分账入账后可提现；大额需运营审核，到账以银行/微信回执为准。发起提现前需完成实名认证（未实名可在本页或「我的 → 实名认证」完成）。',
        withdrawStatusDict: 'merchant_withdraw_status',
        ledgerEmptyHint: '分账入账与提现变动会显示在这里',
        showLedgerTime: true,
        showRefType: true
      }
    : {
        title: '线长钱包',
        emptyIcon: '/static/menu/line-wallet.png',
        emptyTitle: '未绑定线长身份',
        emptyHint: '线长钱包仅对已绑定的线长成员开放。商户主体提现请使用「商户钱包」',
        roleTag: '线长 · 可自主提现',
        tip: '提交后由运营审核；到账以银行/微信回执为准。发起提现前需完成实名认证（未实名可在本页或「我的 → 实名认证」完成）。',
        withdrawStatusDict: 'line_withdraw_status',
        ledgerEmptyHint: '佣金入账与提现变动会显示在这里',
        showLedgerTime: true,
        showRefType: false
      }
);

const loading = ref(false);
const submitting = ref(false);
const loadError = ref('');
/** null = 未取到（运营号段/网络异常），不展示实名状态，避免把提现页卡住 */
const identityVerified = ref<boolean | null>(null);
/** 提现被拒的全文原因（实名未完成等）——banner 展示，toast 会截断 */
const withdrawError = ref('');
const amountYuan = ref('');
const overview = ref<Overview | null>(null);
// CB-035：待结算 = 已入账但未到 T+1 可提现日的钱，与后端提现闸门同口径（仅商户钱包返回；线长佣金无此概念）
const pendingSettle = computed(() => {
  const o = overview.value;
  return o && 'pendingSettleCents' in o ? Number(o.pendingSettleCents ?? 0) : 0;
});
// CB-086：欠款 = 账面负余额的绝对值。提现闸门对负余额恒判「可用余额不足」，
// 而 availableCents 本身已钳到 0 ⇒ 页面上必须单独解释这个 0 是「欠款未清」。
const arrearsCents = computed(() => {
  const o = overview.value;
  const balance = o && 'balanceCents' in o ? Number(o.balanceCents ?? 0) : 0;
  return balance < 0 ? -balance : 0;
});
const maxWithdrawHint = computed(() =>
  overview.value?.availableCents == null
    ? ''
    : `最多可提现 ${fmtMoney(overview.value.availableCents)}`
);
const minWithdrawCents = computed(() =>
  props.role === 'merchant' ? merchantWithdrawMinCents() : lineWithdrawMinCents()
);
const minWithdrawHint = computed(() => {
  const min = minWithdrawCents.value;
  if (min == null) return '';
  return min > 0 ? `最低提现 ${fmtMoney(min)}` : '当前无单笔提现下限';
});

// H53：多商户绑定时的提现商户选择；单商户不渲染选择器、请求也不带 merchantId
const { me, refresh: refreshMerchantMe } = useMerchantMe();
const selectedMerchantId = ref('');
const boundMerchants = computed(() => (props.role === 'merchant' ? me.value?.merchants || [] : []));
const multiMerchant = computed(() => boundMerchants.value.length > 1);
const merchantOptions = computed(() =>
  boundMerchants.value.map((m) => ({
    merchantId: m.merchantId,
    label: `${emptyDisplay(m.merchantName, 'text')} · ${emptyDisplay(m.merchantId, 'text')}`
  }))
);
const selectedMerchantLabel = computed(() => {
  const hit = merchantOptions.value.find((m) => m.merchantId === selectedMerchantId.value);
  return hit?.label || '请选择';
});

function onMerchantPick(e: { detail: { value: string | number } }) {
  const idx = Number(e.detail.value);
  const next = merchantOptions.value[idx]?.merchantId || '';
  if (!next || next === selectedMerchantId.value) return;
  selectedMerchantId.value = next;
  overview.value = null;
  // 清掉在途提现的幂等键（2026-10-23 第五轮审计 L-5）：requestNo 全局唯一，
  // 跨商户复用会撞旧商户的单 → 409「提现单号已被其它请求占用」且文案对商户费解
  pendingRequestNo = '';
  void load();
}

const displayName = computed(() => {
  const o = overview.value;
  if (!o) return '';
  if (props.role === 'merchant') {
    const m = o as OpenApiMerchantWalletOverviewDto;
    return emptyDisplay(m.merchantName, 'text');
  }
  const l = o as OpenApiLineWalletOverviewDto;
  return `${emptyDisplay(l.managerName, 'text')} · ${emptyDisplay(l.phone, 'text')}`;
});

function formatTime(t?: string) {
  return props.role === 'merchant' ? formatDateTimeShort(t, '') : formatDateTimeMinute(t, '暂无');
}

function formatSigned(cents?: number) {
  const n = Number(cents) || 0;
  if (n > 0) return `+${fmtMoney(n)}`;
  return fmtMoney(n);
}

function withdrawStatus(status?: string) {
  return displayLabel(cfg.value.withdrawStatusDict, status, '未知状态');
}

/** 正在拉起的提现单（防连点：微信确认页是半屏页，连点会叠多张）。 */
const confirmingId = ref<number | null>(null);

/** 微信 JSAPI 最小声明（小程序全局对象，uni 无包装）。 */
interface WxMerchantTransferApi {
  canIUse?: (name: string) => boolean;
  requestMerchantTransfer?: (opts: {
    mchId: string;
    appId: string;
    package: string;
    success?: (r: { errMsg?: string }) => void;
    fail?: (r: { errMsg?: string }) => void;
  }) => void;
}

/**
 * 哪些提现单有「去确认收款」入口：商户 + 微信通道 + PAYING 三者同时成立。
 *
 * <p>线长提现走银行通道、且线长端没有确认入口（后端也直接判失败并说清原因），
 * 所以这里必须按角色 + 通道裁剪——否则按钮点了只会报错。
 */
function canConfirmPayout(w: { status?: string; payChannel?: string }) {
  return props.role === 'merchant' && w.status === 'PAYING' && w.payChannel === 'WECHAT';
}

/**
 * MA-407 确认收款模式：取票据 → `wx.requestMerchantTransfer` 拉起微信确认页。
 *
 * 🔴 三个纪律：
 *  ① mchId/appId 用服务端下发的（必须与建单时一致），不猜；
 *  ② `success` 只代表「用户点完了」，<b>不等于</b>已到账——到账以微信回调/查单为准，
 *    故这里刷新状态而不是直接改本地状态；
 *  ③ 票据 404（过期）或 409（已终态）都顺手 `load()` 一次，让状态与真实一致。
 */
async function openConfirmPage(w: { requestId?: number | string }) {
  if (confirmingId.value != null || w.requestId == null) return;
  const requestId = Number(w.requestId);
  confirmingId.value = requestId;
  try {
    const ticket = await fetchWithdrawConfirmTicket(w.requestId);
    // #ifdef MP-WEIXIN
    const wx = (globalThis as unknown as { wx?: WxMerchantTransferApi }).wx;
    if (
      !wx ||
      typeof wx.requestMerchantTransfer !== 'function' ||
      (typeof wx.canIUse === 'function' && !wx.canIUse('requestMerchantTransfer'))
    ) {
      showError('当前微信版本不支持确认收款，请升级微信后重试');
      return;
    }
    wx.requestMerchantTransfer({
      mchId: ticket.mchId,
      appId: ticket.appId,
      package: ticket.packageInfo,
      success: () => {
        showSuccess('已确认收款，到账后本页自动更新');
        // 回调/查单可能略慢于用户点击，留 2s 再刷新一次，避免「点了还显示待确认」
        setTimeout(() => void load(), 2000);
      },
      fail: (r) => {
        showError(
          r?.errMsg?.includes(':cancel')
            ? '已取消确认，24 小时内可再次确认收款'
            : '打开微信确认页失败，请稍后重试'
        );
      }
    });
    // #endif
    // #ifndef MP-WEIXIN
    showError('请在微信中打开商户端完成确认收款');
    // #endif
  } catch (e) {
    showError(e instanceof Error ? e.message : '获取确认信息失败');
    void load();
  } finally {
    confirmingId.value = null;
  }
}

function ledgerTitleOf(l: { entryType?: string; remark?: string }) {
  return walletLedgerTitle(l.entryType, l.remark);
}

function ledgerRemarkOf(l: { entryType?: string; remark?: string }) {
  return walletLedgerRemark(l.remark, walletLedgerTitle(l.entryType, l.remark));
}

function ledgerRefOf(l: { refType?: string; refId?: string }) {
  return walletLedgerRefText(l.refType, l.refId);
}

/** 请求序号守卫（2026-10-23 审计 M1）：多商户快速切换 picker 时，迟到的旧商户响应
 * 不得覆盖余额展示——「看着 B 的名字、读着 A 的余额」足以引起误判/客诉 */
let loadSeq = 0;

async function load() {
  if (!isMerchantLoggedIn()) {
    handleUnauthorized();
    return;
  }
  const seq = ++loadSeq;
  loading.value = !overview.value;
  loadError.value = '';
  try {
    if (props.role === 'merchant') {
      // H53：确保 me.merchants 已就绪，用于多商户判断与默认选中（软失败不阻断展示）
      await refreshMerchantMe().catch(() => null);
      if (
        multiMerchant.value &&
        !merchantOptions.value.some((m) => m.merchantId === selectedMerchantId.value)
      ) {
        // 默认选中第一个（与后端未指定时的旧行为一致），用户可通过 picker 切换
        selectedMerchantId.value = merchantOptions.value[0]?.merchantId || '';
      }
      const data = await merchantApi.wallet(selectedMerchantId.value || undefined);
      if (seq !== loadSeq) return;
      overview.value = data;
    } else {
      const data = await merchantApi.lineWallet();
      if (seq !== loadSeq) return;
      overview.value = data;
    }
  } catch (e) {
    if (seq !== loadSeq) return;
    loadError.value = e instanceof Error ? e.message : '加载失败';
  }
  try {
    // 实名状态独立取：运营号段 400 / 网络失败只隐藏状态，不阻断钱包展示。
    const account = await merchantApi.account();
    if (seq === loadSeq) identityVerified.value = !!account?.verified;
  } catch {
    if (seq === loadSeq) identityVerified.value = null;
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

/** 商户端自助实名（提现打款合规），与消费者购物无关。 */
function goRealname() {
  uni.navigateTo({ url: '/pages/realname/realname' });
}

// 提现幂等键状态（接口层审计 H1）：跨提交持久——超时重试复用同键，语义见 submitWithdraw 内注释
let pendingRequestNo = '';
let pendingWithdrawCents = 0;

async function submitWithdraw() {
  // P2：权限前置裁剪——无权用户提交前提示，而非点击后才吃后端 403
  const requiredPerm =
    props.role === 'merchant' ? 'merchant:wallet:apply' : 'merchant:line-wallet:withdraw';
  if (!hasPerm(me.value, requiredPerm)) {
    showError('当前账号没有提现权限，请联系管理员');
    return;
  }
  const amountCents = yuanToCents(amountYuan.value);
  const available = Number(overview.value?.availableCents ?? 0);
  const amountErr = validateWalletWithdrawAmount({
    amountCents,
    availableCents: available,
    minAmountCents: minWithdrawCents.value ?? undefined
  });
  if (amountErr === 'INVALID_AMOUNT') {
    showError('请输入金额');
    return;
  }
  if (amountErr === 'BELOW_MIN_AMOUNT') {
    showError(`最低提现 ${fmtMoney(minWithdrawCents.value ?? 0)}`);
    return;
  }
  if (amountErr === 'EXCEEDS_AVAILABLE') {
    showError(`超出可提现余额（最多 ${fmtMoney(available)}）`);
    return;
  }
  const merchantErr = validateWalletWithdrawMerchant({
    role: props.role,
    boundMerchantCount: boundMerchants.value.length,
    merchantId: selectedMerchantId.value
  });
  if (merchantErr === 'MERCHANT_REQUIRED') {
    showError('请先选择提现商户');
    return;
  }
  submitting.value = true;
  withdrawError.value = '';
  try {
    // H53：多商户绑定必须显式指定提现商户；单商户/未知绑定交由后端自动解析
    // 接口层审计（2026-10-20）H1：requestNo 是后端幂等键，原实现每次点击重新生成——
    // 请求超时（结果未知）后重试会变成新的一笔，穿透后端去重造成重复扣减可提余额。
    // 改为：同金额的传输类失败重试复用同一 requestNo；成功或业务性失败后才换新。
    if (!pendingRequestNo || pendingWithdrawCents !== amountCents) {
      pendingRequestNo = buildWalletWithdrawRequestNo({
        nowMs: Date.now(),
        randomSuffix: secureRandomToken(5)
      });
      // 此处校验已保证 amountCents 非 null（INVALID_AMOUNT 先拦）；?? 仅为类型收窄
      pendingWithdrawCents = amountCents ?? 0;
    }
    const body = buildWalletWithdrawBody({
      role: props.role,
      amountCents: amountCents!,
      requestNo: pendingRequestNo,
      merchantId: selectedMerchantId.value || undefined,
      boundMerchantCount: boundMerchants.value.length
    });
    if (props.role === 'merchant') {
      await merchantApi.walletWithdraw(body);
    } else {
      await merchantApi.lineWalletWithdraw(body);
    }
    pendingRequestNo = '';
    pendingWithdrawCents = 0;
    showSuccess('已提交');
    amountYuan.value = '';
    await load();
  } catch (e) {
    // 传输类错误（超时/断网）结果未知：保留 requestNo 供重试去重；业务性失败（如余额不足）
    // 服务端必然未受理，换新键避免下一笔被幂等拒绝。
    if (!isRetriableMpTransportError(e)) {
      pendingRequestNo = '';
      pendingWithdrawCents = 0;
    }
    const msg = e instanceof Error ? e.message : '提交失败';
    withdrawError.value = msg;
    showError(msg);
  } finally {
    submitting.value = false;
  }
}

onPageShow(async () => {
  await loadMerchantFlags({ refresh: true });
  await load();
});

/**
 * 提现审核 / 打款由运营与支付通道异步处理：还有未到终态的提现单时每 10 秒静默跟进一次，
 * 全部到终态即停表。load() 在已有快照时不闪 loading，可安全重复调用。
 */
useAutoRefresh({
  intervalMs: 10_000,
  load,
  shouldContinue: () =>
    (overview.value?.recentWithdraws || []).some((w) => !isTerminalWithdrawStatus(w.status)),
  maxDurationMs: 300_000,
  canRefresh: () => !submitting.value && isMerchantLoggedIn()
});

/**
 * 2026-11-10：页面层（wallet / line-wallet）开了 enablePullDownRefresh，但下拉回调必须写在
 * 页面实例上，组件里注册的 onPullDownRefresh 在小程序端不会触发。这里暴露 reload 给外壳页调用。
 */
defineExpose({ reload: load });
</script>

<style scoped>
.summary-card,
.action-card,
.section {
  background: var(--card-bg, #ffffff);
  border-radius: var(--card-radius, 22rpx);
  padding: 28rpx;
  margin-bottom: 20rpx;
  border: 1rpx solid var(--card-border, var(--color-border));
}
.role-tag {
  font-size: var(--font-size-sm);
  color: var(--brand, #0d9488);
  font-weight: 600;
}
.name {
  display: block;
  margin-top: 8rpx;
  color: var(--text-muted, #64748b);
  font-size: var(--font-size-caption);
}
.bal-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-top: 24rpx;
}
.bal-label {
  color: var(--text-muted, #64748b);
}
.bal-value {
  font-size: var(--font-size-display);
  font-weight: 700;
  color: var(--text-primary, #0f172a);
}
.bal-sub {
  display: flex;
  gap: 24rpx;
  margin-top: 12rpx;
  color: var(--text-subtle, #94a3b8);
  font-size: var(--font-size-sm);
}
.amount-field {
  display: flex;
  align-items: center;
  width: 100%;
  height: 108rpx;
  min-height: 108rpx;
  margin-bottom: 16rpx;
  padding: 0 28rpx;
  box-sizing: border-box;
  /* 2026-11-02：灰底+灰边看起来像禁用态；改白底 + 浅边，输入区更像可编辑控件 */
  background: #ffffff;
  border: 1rpx solid #e2e8f0;
  border-radius: var(--radius-panel);
}
.amount-input {
  flex: 1;
  width: 100%;
  height: 108rpx;
  min-height: 108rpx;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  font-size: var(--font-size-xl);
  color: var(--text-primary, #0f172a);
  line-height: normal;
}
.amount-field :deep(uni-input),
.amount-field :deep(.uni-input-wrapper),
.amount-field :deep(.uni-input-input),
.amount-field :deep(input) {
  width: 100% !important;
  height: 108rpx !important;
  min-height: 108rpx !important;
  line-height: normal !important;
  font-size: var(--font-size-xl) !important;
  color: var(--text-primary, #0f172a) !important;
}
.amount-ph {
  color: var(--text-subtle);
  font-size: var(--font-size-md);
  line-height: normal;
}
.withdraw-hint {
  display: block;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
  margin: -6rpx 0 12rpx;
}
.identity-row {
  display: flex;
  align-items: center;
  min-height: 88rpx;
  padding: 0 var(--page-gutter);
  margin-bottom: 12rpx;
  border: 1rpx solid var(--card-border);
  border-radius: var(--radius-card);
}
.identity-warn {
  font-size: var(--font-size-body);
  color: var(--color-warning);
}
.identity-ok {
  display: block;
  font-size: var(--font-size-sm);
  color: var(--color-success);
  margin-bottom: 12rpx;
}
.picker {
  margin-bottom: 16rpx;
  padding: 20rpx var(--page-gutter, 24rpx);
  background: var(--page-bg, #ededed);
  border: 1rpx solid var(--color-border);
  border-radius: var(--radius-panel);
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary, #0f172a);
}
.tip {
  display: block;
  margin-top: 12rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #94a3b8);
  line-height: 1.45;
}
.section-title {
  font-weight: 600;
  margin-bottom: 12rpx;
  display: block;
}
.row-item {
  padding: 16rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f1f5f9);
}
.row-main {
  display: flex;
  justify-content: space-between;
  gap: 16rpx;
  font-size: var(--font-size-md);
}
.row-sub {
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #94a3b8);
  margin-top: 4rpx;
  display: block;
  /* 长回执/备注不撑破行布局，与其他列表页 ellipsis 口径一致 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-sub.fail {
  color: var(--color-danger);
}
/* MA-407 确认收款入口：只有待确认单出现，满宽主按钮 + 88rpx 热区。 */
.confirm-row {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4rpx;
  width: 100%;
  min-height: 96rpx;
  box-sizing: border-box;
  margin-top: 14rpx;
  padding: 14rpx 20rpx;
  border: 1rpx solid var(--brand, #0d9488);
  border-radius: var(--radius-control);
  background: var(--brand, #0d9488);
}
.confirm-btn {
  flex-shrink: 0;
  font-size: var(--font-size-body);
  font-weight: 600;
  color: var(--color-white);
}
.confirm-hint {
  font-size: var(--font-size-sm);
  color: var(--color-white);
  line-height: 1.4;
  text-align: center;
}
.status {
  color: var(--brand, #0d9488);
  font-weight: 500;
}
.credit {
  color: var(--brand);
  font-weight: 600;
}
.debit {
  color: var(--color-danger);
  font-weight: 600;
}
.banner-err {
  background: #f9eded;
  color: var(--color-danger);
  padding: 16rpx 20rpx;
  border-radius: var(--radius-control);
  margin-bottom: 16rpx;
  font-size: var(--font-size-body);
}
.banner-retry {
  margin-left: 16rpx;
  text-decoration: underline;
}
.loading-inline {
  text-align: center;
  color: var(--text-subtle, #94a3b8);
  padding: 40rpx;
}
.page-body {
  padding: 24rpx var(--page-gutter, 24rpx) calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
