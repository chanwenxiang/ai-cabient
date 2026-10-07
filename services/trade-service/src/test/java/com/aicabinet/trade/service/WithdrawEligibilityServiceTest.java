package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * V321 提现资质门禁。
 *
 * <p>🔴 测的是**规则本身**（真门槛能被拦住），不是「方法被调用了」——
 * 后者是 mock 测试的自欺：把实现改成永不放行，用例照样绿。
 */
@ExtendWith(MockitoExtension.class)
class WithdrawEligibilityServiceTest {

    @Mock private UserInfoMapper userInfoRepository;
    @Mock private MerchantMapper merchantRepository;

    private WithdrawEligibilityService service() {
        return new WithdrawEligibilityService(userInfoRepository, merchantRepository);
    }

    private static Merchant qualifiedMerchant() {
        Merchant m = new Merchant();
        m.setMerchantId("MCH-A");
        m.setLegalPerson("张三");
        m.setBusinessLicenseUrl("https://file.example/license.pdf");
        return m;
    }

    private static UserInfo user(boolean verified) {
        UserInfo u = new UserInfo();
        u.setUserId(1001L);
        u.setVerified(verified);
        return u;
    }

    // ── 商户主体资质 ────────────────────────────────

    @Test
    @DisplayName("资质齐全 + 申请人已实名 ⇒ 放行")
    void qualifiedAndVerified_passes() {
        when(merchantRepository.findById("MCH-A")).thenReturn(Optional.of(qualifiedMerchant()));
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(user(true)));

        assertDoesNotThrow(() -> service().requireMerchantWithdrawEligible("MCH-A", 1001L));
    }

    @Test
    @DisplayName("🔴 缺法人姓名 ⇒ 403，且消息要说清缺什么")
    void missingLegalPerson_isRejected() {
        Merchant m = qualifiedMerchant();
        m.setLegalPerson("  ");
        when(merchantRepository.findById("MCH-A")).thenReturn(Optional.of(m));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().requireMerchantWithdrawEligible("MCH-A", null));
        assertTrue(e.getReason().contains("法人"), "消息应指出缺法人：" + e.getReason());
    }

    @Test
    @DisplayName("🔴 缺营业执照 ⇒ 403，消息要说清缺什么")
    void missingLicense_isRejected() {
        Merchant m = qualifiedMerchant();
        m.setBusinessLicenseUrl(null);
        when(merchantRepository.findById("MCH-A")).thenReturn(Optional.of(m));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().requireMerchantWithdrawEligible("MCH-A", null));
        assertTrue(e.getReason().contains("营业执照"), "消息应指出缺营业执照：" + e.getReason());
    }

    @Test
    @DisplayName("🔴 两项都缺 ⇒ 消息列出两项（不是只报第一个）")
    void missingBoth_listsBothItems() {
        Merchant m = new Merchant();
        m.setMerchantId("MCH-A");
        when(merchantRepository.findById("MCH-A")).thenReturn(Optional.of(m));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().requireMerchantWithdrawEligible("MCH-A", null));
        assertTrue(e.getReason().contains("法人") && e.getReason().contains("营业执照"),
                "两项都缺时应同时指出：" + e.getReason());
    }

    @Test
    @DisplayName("🔴 用 403 而不是 400（400=请求写错，403=不被允许）")
    void usesForbiddenNotBadRequest() {
        Merchant m = new Merchant();
        m.setMerchantId("MCH-A");
        when(merchantRepository.findById("MCH-A")).thenReturn(Optional.of(m));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().requireMerchantWithdrawEligible("MCH-A", null));
        assertTrue(e.getStatusCode().value() == 403, "应为 403，实际 " + e.getStatusCode());
    }

    // ── 申请人实名 ──────────────────────────────────

    @Test
    @DisplayName("🔴 申请人未实名 ⇒ 403（这是本项的核心缺口）")
    void unverifiedApplicant_isRejected() {
        when(merchantRepository.findById("MCH-A")).thenReturn(Optional.of(qualifiedMerchant()));
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(user(false)));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().requireMerchantWithdrawEligible("MCH-A", 1001L));
        assertTrue(e.getReason().contains("实名"), "消息应指出未实名：" + e.getReason());
    }

    @Test
    @DisplayName("🔴 提示语必须给出**能去的地方**（原实现只说「去『我的』」，那是C 端页面，商户管理员未必有）")
    void unverifiedMessage_pointsSomewhereReachable() {
        when(merchantRepository.findById("MCH-A")).thenReturn(Optional.of(qualifiedMerchant()));
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.of(user(false)));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().requireMerchantWithdrawEligible("MCH-A", 1001L));
        // 必须提到「换账号」这条路 —— 否则商户管理员只能反复点同一句错。
        assertTrue(e.getReason().contains("账号"),
                "应提示可改用已实名账号发起：" + e.getReason());
    }

    @Test
    @DisplayName("申请人账号不存在 ⇒ 403（不是 500，也不是静默放行）")
    void applicantNotFound_isRejected() {
        when(merchantRepository.findById("MCH-A")).thenReturn(Optional.of(qualifiedMerchant()));
        when(userInfoRepository.findById(1001L)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().requireMerchantWithdrawEligible("MCH-A", 1001L));
        assertTrue(e.getStatusCode().value() == 403);
    }

    @Test
    @DisplayName("submitter 为 null（运营代提现）⇒ 只查商户资质，**不查实名**")
    void nullSubmitter_skipsIdentityCheck() {
        when(merchantRepository.findById("MCH-A")).thenReturn(Optional.of(qualifiedMerchant()));

        // 🔴 若实现错误地把 null 当成「未实名」，这里会红 ——
        //    运营代提现的 submitter 是运营人员，拿运营实名代表商户资质是错的。
        assertDoesNotThrow(() -> service().requireMerchantWithdrawEligible("MCH-A", null));
    }

    // ── 线长侧 ──────────────────────────────────────

    @Test
    @DisplayName("线长绑定了已实名用户 ⇒ 放行")
    void lineVerified_passes() {
        when(userInfoRepository.findById(2002L)).thenReturn(Optional.of(user(true)));
        assertDoesNotThrow(() -> service().requireLineWithdrawEligible(2002L));
    }

    @Test
    @DisplayName("🔴 线长未绑定用户 ⇒ 403（拿不出实名证明就打款，与商户未实名同类风险）")
    void lineWithoutLinkedUser_isRejected() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().requireLineWithdrawEligible(null));
        assertTrue(e.getReason().contains("绑定"), "消息应提示去绑定实名：" + e.getReason());
    }

    @Test
    @DisplayName("🔴 线长用户未实名 ⇒ 403")
    void lineUnverified_isRejected() {
        when(userInfoRepository.findById(2002L)).thenReturn(Optional.of(user(false)));
        assertThrows(ResponseStatusException.class,
                () -> service().requireLineWithdrawEligible(2002L));
    }

    // ── 商户不存在 ──────────────────────────────────

    @Test
    @DisplayName("商户不存在 ⇒ 404（不是 403：那是「找错了」，不是「不被允许」）")
    void merchantNotFound_is404() {
        when(merchantRepository.findById("MCH-X")).thenReturn(Optional.empty());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().requireMerchantWithdrawEligible("MCH-X", null));
        assertTrue(e.getStatusCode().value() == 404, "应为 404，实际 " + e.getStatusCode());
    }
}
