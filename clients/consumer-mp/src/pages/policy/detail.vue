<template>
  <view class="page">
    <app-nav-bar :title="navTitle" />
    <view class="page-body">
      <view class="policy-card">
        <text class="policy-updated">更新日期：2026-08-08 · 版本 V1.2</text>
        <view v-for="section in sections" :key="section.title" class="section">
          <text class="section-title">{{ section.title }}</text>
          <text v-for="(p, i) in section.paragraphs" :key="i" class="section-p">{{ p }}</text>
        </view>
        <view class="related">
          <text class="related-title">相关条款</text>
          <view class="related-row">
            <text
              v-for="link in relatedLinks"
              role="button"
              :key="link.type"
              class="related-link"
              :class="{ on: type === link.type }"
              @click="switchType(link.type)"
              >{{ link.label }}</text
            >
          </view>
          <text class="related-hint">如有疑问可前往帮助中心联系客服</text>
          <text role="button" class="related-link help app-link-chevron" @click="goHelp"
            >帮助中心</text
          >
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
      title: '一、协议的订立与效力',
      paragraphs: [
        '本协议是您与深圳前海易购科创智能设备有限公司（以下简称“本平台”）之间就使用智能开门柜自助购物服务（“扫码开门、取货即走、关门自动结算”）订立的服务协议，依据《中华人民共和国民法典》合同编相关规定订立。',
        '依据《中华人民共和国电子商务法》第四十九条，您提交开门/购买请求且系统实际开启柜门时，您与本平台之间就本次购物行为的服务合同成立。您勾选同意或实际使用本服务，即视为已阅读并同意本协议全部条款。'
      ]
    },
    {
      title: '二、经营者信息与信息披露',
      paragraphs: [
        '依据《电子商务法》第九条、第十七条，本平台公示经营者主体信息。',
        '运营主体：深圳前海易购科创智能设备有限公司；经营地址：【待填：注册地址】；客服联系方式：客服电话 4000182992。',
        '商品以柜机现场标示的价格与信息为准，本平台依法明码标价（《中华人民共和国价格法》第十三条）。'
      ]
    },
    {
      title: '三、账户与登录',
      paragraphs: [
        '您可通过微信授权（获取您的微信 OpenID 与绑定的手机号）或手机号验证码方式登录。上述信息仅用于建立与识别您的购物账户，本平台不收集您的身份证号码等证件信息用于购物。',
        '您应妥善保管账户及授权信息。因您主动泄露或交由他人使用导致的损失，由您自行承担。'
      ]
    },
    {
      title: '四、开门、取货与结算',
      paragraphs: [
        '您扫码开门后即取得柜内商品的临时占有资格。取放商品后请关好柜门，系统将依据柜内识别结果与关门事件自动生成订单并结算；识别与结算记录（含开门期间录像）将作为对账与争议处理依据。',
        '请勿在柜门打开期间离开或由他人代为取放商品；请勿恶意占用、破坏柜机，相关行为将被限制使用并依法追责（《中华人民共和国治安管理处罚法》及《中华人民共和国刑法》相关规定）。'
      ]
    },
    {
      title: '五、支付方式与免密代扣',
      paragraphs: [
        '结算优先使用微信支付分/支付宝免密代扣，未开通免密时使用账户余额。开通免密即表示您授权本平台在关门结算后通过对应支付渠道发起扣款，单笔扣款金额以实际订单为准。',
        '免密扣款基于您与支付服务机构（财付通支付科技有限公司/支付宝（中国）网络技术有限公司）之间的签约关系，您与支付机构之间的权利义务以您与该机构签署的协议为准。依据《中华人民共和国电子商务法》第五十三条，本平台将向您明示扣款金额与明细，您可随时在支付渠道侧关闭免密签约。'
      ]
    },
    {
      title: '六、退货与退款',
      paragraphs: [
        '依据《消费者权益保护法》第二十四条，商品存在质量问题的，您有权要求退款。因本服务为线下柜机即时交付、当场验收，不适用网络远程购物的七日无理由退货规定。',
        '发生多扣款、未取商品被扣款、识别错误等异常时，按本平台《退款规则》处理。'
      ]
    },
    {
      title: '七、免责与不可抗力',
      paragraphs: [
        '因不可抗力（自然灾害、政府行为等）导致的损失，依据《民法典》第一百八十条、第五百九十条部分或全部免除责任；因网络故障、设备异常造成的结算偏差，本平台将依据录像与流水核实后多退少补。'
      ]
    },
    {
      title: '八、协议变更与争议解决',
      paragraphs: [
        '依据《电子商务法》第三十四条，本协议修订将在平台显著位置公示；您在公示后继续使用服务的，视为接受修订条款，如您不同意可停止使用并注销账户。',
        '本协议适用中华人民共和国法律。因本协议产生的争议，双方应友好协商；协商不成的，任何一方可向本平台运营者所在地有管辖权的人民法院提起诉讼。'
      ]
    }
  ],
  privacy: [
    {
      title: '一、个人信息处理者信息',
      paragraphs: [
        '依据《中华人民共和国个人信息保护法》第十七条，本平台作为个人信息处理者，现将处理者身份与联系方式告知如下：',
        '处理者：深圳前海易购科创智能设备有限公司；个人信息保护联系方式：客服电话 4000182992。'
      ]
    },
    {
      title: '二、我们处理的个人信息清单与目的',
      paragraphs: [
        '依据《个人信息保护法》第六条（目的限定与最小必要）及《常见类型移动互联网应用程序必要个人信息范围规定》，我们按以下范围处理您的个人信息：',
        '① 账户登录：手机号、微信 OpenID——用于创建和识别您的账户（《个人信息保护法》第十三条第一款第二项，订立履行合同所必需）；② 开门与使用记录：柜机编号、开门/关门事件、操作时间——用于开门鉴权与订单生成；③ 交易信息：订单、支付与余额流水——用于结算、对账与售后服务；④ 免密签约标识：支付分/代扣协议编号——用于自动扣款。'
      ]
    },
    {
      title: '三、柜内录像（敏感个人信息）',
      paragraphs: [
        '开门期间柜内摄像头会采集您的图像/视频画面。该信息属于《个人信息保护法》第二十八条规定的敏感个人信息，用于商品识别结算、账单审核与消费争议核验，处理具有充分必要性并已采取严格保护措施。',
        '依据第二十九条、第三十条，我们以本政策的显著方式向您告知并取得您的单独同意（您扫码开门即作出上述授权）；柜机机身同时张贴有图像采集提示。录像保存期限为 90 天，到期自动删除。'
      ]
    },
    {
      title: '四、对外提供与共享',
      paragraphs: [
        '依据《个人信息保护法》第二十三条，我们仅在下列情形向第三方提供您的个人信息，并告知接收方名称、处理目的、方式与种类：① 支付服务机构（财付通支付科技有限公司、支付宝（中国）网络技术有限公司）——完成扣款与退款；② 云存储服务商——存储录像等业务数据；③ 个人信息核验服务机构——仅当您以商户/线长身份申请提现时，为完成收款人实名核验而向其提供您主动提交的姓名与身份证号（核验通过后本平台不留存完整证件号）；④ 依据法律法规或有权机关要求依法提供。'
      ]
    },
    {
      title: '五、存储期限',
      paragraphs: [
        '依据《个人信息保护法》第十九条，我们仅在实现处理目的所必要的最短期间内保存您的个人信息：录像为 90 天；交易信息依据《电子商务法》第三十一条自交易完成之日起保存不少于三年；账户信息保存至您注销账户。'
      ]
    },
    {
      title: '六、您的权利',
      paragraphs: [
        '依据《个人信息保护法》第四章，您对您的个人信息享有知情权、决定权，有权查阅、复制、更正、补充、删除，有权限制或拒绝处理，有权撤回同意，并有权要求我们予以解释说明。',
        '您可在小程序内自助查询订单、余额与账单流水；如需行使上述其他权利，可联系客服电话 4000182992。您可在「我的-注销账户」自助注销：注销后您的个人信息将被删除或匿名化（交易记录等法律法规要求留存的除外）；余额、免密协议与进行中的退款需先处理完毕。注销不可恢复，请谨慎操作。'
      ]
    },
    {
      title: '七、安全措施与未成年人保护',
      paragraphs: [
        '我们采用传输加密、访问最小化授权、敏感数据隔离存储等措施保护您的个人信息，并在开展敏感个人信息处理前依法进行个人信息保护影响评估（《个人信息保护法》第五十五条）。',
        '本服务面向完全民事行为能力人。我们不会主动面向不满十四周岁未成年人处理其个人信息；若发现未经监护人同意处理了相关个人信息，将依法尽快删除。'
      ]
    },
    {
      title: '八、政策更新',
      paragraphs: [
        '本政策更新后将在平台显著位置公示；处理目的、方式、信息种类发生变更的，我们将重新取得您的同意。'
      ]
    }
  ],
  refund: [
    {
      title: '一、法律依据与适用情形',
      paragraphs: [
        '本规则依据《中华人民共和国消费者权益保护法》第二十四条（商品或服务质量不符合约定时的退款责任）、《中华人民共和国价格法》第十三条（明码标价）及《中华人民共和国民法典》合同编相关规定制定。',
        '出现下列情形之一的，您有权申请退款：① 多扣款、重复扣款；② 未取商品被扣款或识别数量/单价与实际不符；③ 商品存在质量问题（破损、过期等）；④ 余额异常扣款；⑤ 其他经核实的异常扣款。'
      ]
    },
    {
      title: '二、自助退款',
      paragraphs: [
        '若所在柜机已开启“自助退款”，您可在「我的订单」→ 订单详情中点击“立即退款”，系统核对通过后即时原路退回或退回账户余额。'
      ]
    },
    {
      title: '三、人工申诉与处理时限',
      paragraphs: [
        '不符合自助退款条件时，请提交账单申诉并附情况说明。运营将核对开门录像与识别记录，通常在 24 小时内处理完毕。',
        '审核通过后，款项原路退回支付渠道或退回账户余额；审核不通过将告知理由与依据，您可补充材料再次申诉。'
      ]
    },
    {
      title: '四、到账时间',
      paragraphs: [
        '余额退回即时到账；微信/支付宝渠道退款到账时间以支付机构处理为准，通常为 1-7 个工作日。'
      ]
    },
    {
      title: '五、争议解决',
      paragraphs: [
        '您对处理结果有异议的，可向消费者协会或 12315 平台投诉，或依法向有管辖权的人民法院起诉。依据《消费者权益保护法》第五十五条，经营者提供商品或服务有欺诈行为的，依法承担惩罚性赔偿责任。'
      ]
    }
  ],
  billing: [
    {
      title: '一、订单构成与明码标价',
      paragraphs: [
        '订单金额 = 商品金额 − 优惠券优惠，并按支付方式（微信/支付宝免密或账户余额）完成结算。商品价格以柜机现场标示为准，依据《中华人民共和国价格法》第十三条明码标价。',
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

const relatedLinks: Array<{ type: PolicyType; label: string }> = [
  { type: 'agreement', label: '用户协议' },
  { type: 'privacy', label: '隐私政策' },
  { type: 'refund', label: '退款规则' },
  { type: 'billing', label: '账单说明' }
];

function switchType(next: PolicyType) {
  if (next === type.value) return;
  routeType.value = next;
  uni.setNavigationBarTitle({ title: TITLES[next] });
}

function goHelp() {
  uni.navigateTo({ url: '/pages/help/help' });
}

onLoad((query) => {
  routeType.value = String(query?.type || 'agreement');
});
</script>

<style scoped>
.page {
  min-height: 100%;
  padding: 0;
  box-sizing: border-box;
  background: var(--card-bg, #ffffff);
}
.page-body {
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.policy-card {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  padding: 32rpx 28rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 23, 42, 0.04);
}
.policy-updated {
  display: block;
  text-align: right;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
  margin-bottom: 20rpx;
}
.section {
  margin-bottom: 28rpx;
}
.section-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--color-text-primary);
  margin-bottom: 12rpx;
}
.section-p {
  display: block;
  font-size: var(--font-size-body);
  color: var(--text-muted, #4b5563);
  line-height: 1.7;
  margin-bottom: 10rpx;
}
.related {
  margin-top: 12rpx;
  padding-top: 24rpx;
  border-top: 1rpx solid var(--color-border-subtle);
}
.related-title {
  display: block;
  font-size: var(--font-size-body);
  font-weight: 650;
  color: var(--color-text-primary);
  margin-bottom: 12rpx;
}
.related-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
.related-link {
  font-size: var(--font-size-caption);
  color: var(--brand);
  padding: 8rpx 16rpx;
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
}
.related-link.on {
  background: var(--brand);
  color: var(--white);
  font-weight: 650;
}
.related-link.help {
  display: inline-block;
  margin-top: 16rpx;
  background: transparent;
  padding: 0;
}
.related-hint {
  display: block;
  margin-top: 16rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
</style>
