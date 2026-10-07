package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * V321 提现资质门禁：实名 + 商户主体资质。
 *
 * <p>🔴 <b>为什么单独一个类</b>：商户提现（{@code MerchantWithdrawService}）与
 * 线长提现（{@code LineWithdrawService}）是两条独立实现，但**资质要求相同**。
 * 各写一份 ⇒ 将来加规则只改一边，出现「商户要校验、线长不校验」的数据分裂。
 *
 * <p>🔴 <b>不做什么（重要）</b>：本类**不采集也不存储完整证件号**。
 * 实名是**委托第三方**校验的（{@code IdentityVerifyClient} 只传姓名 +证件后 4 位），
 * 本地只留 {@code user.verified} 布尔位——
 * 这是**有意的隐私设计**，不是遗漏。为做风控而把完整证件号存进自己库
 * 反而**扩大了泄露面**。⚠️ 不要因为「合规焦虑」把它改回去。
 *
 * <p>⚠️ {@code user_realname_auth} 表在本设计下**永远是 0 行**，不是缺陷。
 */
@Service
public class WithdrawEligibilityService {

    private final UserInfoMapper userInfoRepository;
    private final MerchantMapper merchantRepository;

    public WithdrawEligibilityService(UserInfoMapper userInfoRepository,
                                      MerchantMapper merchantRepository) {
        this.userInfoRepository = userInfoRepository;
        this.merchantRepository = merchantRepository;
    }

    /**
     * 商户侧提现资格校验。
     *
     * @param submitterUserId 申请人（可空 —— 运营代提现场景）
     * @throws ResponseStatusException 403（资质不足）—— 刻意用 403 而非 400：
     *         400 表达「请求写错了」，403 表达「请求没错但你不被允许」，
     *         客户端据此可以区别「改参数重试」还是「走补资质流程」。
     */
    public void requireMerchantWithdrawEligible(String merchantId, Long submitterUserId) {
        requireMerchantQualified(merchantId);
        if (submitterUserId != null) {
            requireUserVerified(submitterUserId, "申请人");
        }
    }

    /** 线长侧提现资格校验（线长通过 {@code line_manager.user_id} 关联用户）。 */
    public void requireLineWithdrawEligible(Long linkedUserId) {
        if (linkedUserId == null) {
            // 🔴 线长未绑定用户 ⇒ 无法确认实名。
            //   放行还是拒？**拒** —— 拿不出实名证明就打款，与「商户未实名就打款」
            //   是同一类风险。错误消息要写清怎么办，而不是只说不行。
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "该线长账号未绑定实名用户，无法发起提现；请先完成实名并绑定");
        }
        requireUserVerified(linkedUserId, "线长");
    }

    /**
     * 商户主体资质：法人 + 营业执照。
     *
     * <p>⚠️ 判定依据是**字段有没有值**，不是「证照是否审过」——
     * 系统里没有「资质审核状态」这个概念（未取证）。
     * 🔴 若将来引入审核状态，这里必须改成「审核通过」而不是「已填写」，
     * 因为「上传了营业执照」≠「营业执照有效」。
     */
    private void requireMerchantQualified(String merchantId) {
        Merchant m = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在"));
        boolean hasLegalPerson = m.getLegalPerson() != null && !m.getLegalPerson().isBlank();
        boolean hasLicense = m.getBusinessLicenseUrl() != null
                && !m.getBusinessLicenseUrl().isBlank();
        if (!hasLegalPerson && !hasLicense) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "商户资质不完整：缺法人姓名与营业执照，无法发起提现；请先在「商户管理」补齐");
        }
        if (!hasLegalPerson) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "商户资质不完整：缺法人姓名，无法发起提现；请先在「商户管理」补齐");
        }
        if (!hasLicense) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "商户资质不完整：缺营业执照，无法发起提现；请先在「商户管理」补齐");
        }
    }

    private void requireUserVerified(Long userId, String roleLabel) {
        UserInfo u = userInfoRepository.findById(userId).orElse(null);
        if (u == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, roleLabel + "账号不存在");
        }
        if (!u.isVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    roleLabel + "尚未完成实名，无法发起提现；请先在「我的」完成实名认证");
        }
    }
}
