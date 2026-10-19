package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.UserConsentLog;
import com.aicabinet.trade.mapper.UserConsentLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用户同意留痕（AU-149，V394）。
 *
 * 版本口径：当前隐私政策版本号 {@link #PRIVACY_POLICY_VERSION}——政策正文改版时同步
 * 升版本号 ⇒ 老用户下次登录会落一条新版本同意（唯一键含版本号，天然重放）。
 * 记录失败不阻断登录/注销主流程（与 {@code auditPhoneVerify} 同口径：留痕是旁路）。
 */
@Service
public class UserConsentLogService {

    private static final Logger log = LoggerFactory.getLogger(UserConsentLogService.class);

    /** 隐私政策当前版本（改版时递增：YYYY-MM）。 */
    public static final String PRIVACY_POLICY_VERSION = "2026-10";

    private final UserConsentLogMapper consentLogMapper;

    public UserConsentLogService(UserConsentLogMapper consentLogMapper) {
        this.consentLogMapper = consentLogMapper;
    }

    /** 记录隐私政策同意（登录发 token 时调用；DB 层幂等，异常不阻断）。 */
    public void recordPrivacyConsent(Long userId) {
        record(userId, UserConsentLog.TYPE_PRIVACY_POLICY, PRIVACY_POLICY_VERSION);
    }

    /** 记录自助注销动作留痕（AccountService.deleteOwnAccount 调用；异常不阻断）。 */
    public void recordAccountDelete(Long userId) {
        record(userId, UserConsentLog.TYPE_ACCOUNT_DELETE, PRIVACY_POLICY_VERSION);
    }

    private void record(Long userId, String type, String version) {
        if (userId == null) {
            return;
        }
        try {
            consentLogMapper.insertIgnoreConflict(userId, type, version);
        } catch (Exception e) {
            log.warn("consent log write failed (non-blocking) userId={} type={}", userId, type, e);
        }
    }
}
