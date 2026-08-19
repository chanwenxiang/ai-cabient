<template>
  <view class="page">
    <app-nav-bar :title="navTitle" />
    <view class="page-body">
      <view class="policy-card">
        <text class="policy-updated">更新日期：2026-08-08</text>
        <view v-for="section in sections" :key="section.title" class="section">
          <text class="section-title">{{ section.title }}</text>
          <text v-for="(p, i) in section.paragraphs" :key="i" class="section-p">{{ p }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';

interface PolicySection {
  title: string;
  paragraphs: string[];
}

const type = computed(() => (routeType.value || 'agreement') as PolicyType);

type PolicyType = 'agreement' | 'privacy' | 'refund' | 'billing';

const routeType = ref('');

const TITLES: Record<PolicyType, string> = {
  agreement: '用户协议',
  privacy: '隐私政策',
  refund: '退款规则',
  billing: '账单说明'
};

const CONTENTS: Record<PolicyType, PolicySection[]> = {
  agreement: [
    {
      title: '一、服务说明',
      paragraphs: [
        '本协议是您与 AI 开门柜平台（以下简称“本平台”）就使用自助开门购物服务订立的协议。本服务基于智能柜机实现“扫码开门、取货即走、关门自动结算”。',
        '使用本服务即表示您已阅读并同意本协议全部条款。若您不同意，请停止使用本服务。'
      ]
    },
    {
      title: '二、账户与授权',
      paragraphs: [
        '您可通过微信授权或手机号验证登录本平台。登录后需按提示完成实名信息与支付方式（免密支付或账户余额）的开通准备。',
        '您应妥善保管账户及授权信息。因您主动泄露或交由他人使用导致的损失，由您自行承担。'
      ]
    },
    {
      title: '三、使用规则',
      paragraphs: [
        '打开柜门前请确认柜机编号与商品信息。取放商品后请关好柜门，系统将根据识别结果自动生成订单并扣款。',
        '请勿在柜门打开期间离开或由他人代为取放商品；请遵守购物秩序，恶意占用、破坏柜机等行为将被限制使用并依法追责。'
      ]
    },
    {
      title: '四、支付与结算',
      paragraphs: [
        '结算优先使用微信/支付宝免密支付；未开通免密时使用账户余额。订单金额以系统识别与结算结果为准。',
        '若您对账单有疑问，可在订单详情提交申诉，运营核对录像后按退款规则处理。'
      ]
    },
    {
      title: '五、免责与协议变更',
      paragraphs: [
        '因不可抗力、网络故障、设备异常等导致的损失，本平台将在合理范围内协助处理，但不承担法律规定之外的赔偿责任。',
        '本平台可根据业务需要修订本协议，修订后的协议将在公告中公示；继续使用服务视为接受修订后的条款。'
      ]
    }
  ],
  privacy: [
    {
      title: '一、我们收集的信息',
      paragraphs: [
        '为提供开门购物服务，我们会收集：手机号、微信 OpenID 等账户信息；柜机编号、开门/关门事件等使用信息；开门期间的录像画面；订单、支付与余额流水等信息。',
        '开门录像仅用于识别结算、账单审核与争议处理，不用于其他目的。'
      ]
    },
    {
      title: '二、信息的使用',
      paragraphs: [
        '我们使用上述信息完成身份识别、开门鉴权、自动结算、余额管理、争议处理、客服与安全风控。',
        '未经您的同意，我们不会将您的个人信息用于本政策未说明的其他用途。'
      ]
    },
    {
      title: '三、存储与保护',
      paragraphs: [
        '信息存储于受控的服务器与对象存储，传输与存储采用加密措施，并对访问权限进行最小化管控。',
        '录像等敏感数据按业务需要设定保留期限，到期自动清理。'
      ]
    },
    {
      title: '四、共享与披露',
      paragraphs: [
        '除为实现服务所必需的合作方（如支付渠道、云存储服务商）外，我们不会向第三方共享您的个人信息。',
        '在法律法规要求或配合司法机关调查时，我们可能依法提供必要信息。'
      ]
    },
    {
      title: '五、您的权利',
      paragraphs: [
        '您有权查询本人的订单、余额与账单流水；如需更正信息、删除录像或注销账户，可联系客服处理。',
        '本政策更新后将在平台公告中公示，重大变更将以显著方式通知您。'
      ]
    }
  ],
  refund: [
    {
      title: '一、适用范围',
      paragraphs: [
        '当出现多扣款、识别错误（未取商品被扣款）、重复扣款、余额异常扣款等情形时，您可申请退款。',
        '退款以实际扣款与录像核对结果为准，按以下流程处理。'
      ]
    },
    {
      title: '二、自助退款',
      paragraphs: [
        '若所在柜机已开启“自助退款”，您可在「我的订单」→ 订单详情中点击“立即退款”，系统核对通过后即时原路退回或退回账户余额。'
      ]
    },
    {
      title: '三、人工申诉',
      paragraphs: [
        '不符合自助退款条件时，请提交账单申诉并附情况说明。运营将核对开门录像与识别记录，通常在 24 小时内处理。',
        '审核通过后，款项退回原支付渠道或账户余额；审核不通过将告知原因，您可补充材料再次申诉。'
      ]
    },
    {
      title: '四、特别说明',
      paragraphs: [
        '免单/全额退款仅适用于运营核实确认的异常场景；恶意申诉将被拒绝并可能限制账户功能。',
        '退款到账时间以支付渠道为准，余额退回即时到账，微信/支付宝退回通常为 1-7 个工作日。'
      ]
    }
  ],
  billing: [
    {
      title: '一、订单构成',
      paragraphs: [
        '订单金额 = 商品金额 − 优惠券优惠，并按支付方式（微信/支付宝免密或账户余额）完成结算。',
        '订单生成后可在「我的订单」查看明细，含商品、数量、金额、支付方式与时间。'
      ]
    },
    {
      title: '二、余额明细',
      paragraphs: [
        '「我的」页面可查看余额流水，包括充值、购物扣款、订单退款、补扣与运营调整，每条流水均记录业务类型与发生时间。',
        '余额仅用于本平台购物结算，不支持提现（另有规定的除外）。'
      ]
    },
    {
      title: '三、预授权与冻结',
      paragraphs: [
        '部分支付方式会在开门前预授权或冻结一定金额，关门结算后按实际订单金额扣款并释放多余冻结。',
        '冻结金额在账单结算完成前不可使用，具体以支付渠道与订单状态为准。'
      ]
    },
    {
      title: '四、对账说明',
      paragraphs: [
        '若订单金额与您的消费预期不一致，请先核对商品清单；仍有疑问可在订单详情提交账单申诉。',
        '账单相关疑问可联系客服，我们将按退款规则与录像记录协助核对。'
      ]
    }
  ]
};

const sections = computed(() => CONTENTS[type.value] || CONTENTS.agreement);
const navTitle = computed(() => TITLES[type.value] || TITLES.agreement);

onLoad((query) => {
  routeType.value = String(query?.type || 'agreement');
});
</script>

<style scoped>
.page {
  min-height: 100%;
  padding: 0;
  box-sizing: border-box;
  background: #ffffff;
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.policy-card {
  background: #fff;
  border-radius: 22rpx;
  padding: 32rpx 28rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.04);
}
.policy-updated {
  display: block;
  text-align: right;
  font-size: 22rpx;
  color: #94a3b8;
  margin-bottom: 20rpx;
}
.section {
  margin-bottom: 28rpx;
}
.section-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: #111827;
  margin-bottom: 12rpx;
}
.section-p {
  display: block;
  font-size: 25rpx;
  color: #4b5563;
  line-height: 1.7;
  margin-bottom: 10rpx;
}
</style>
