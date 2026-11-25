package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.UserConsentLog;
import com.aicabinet.trade.mapper.UserConsentLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final UserConsentLogService self;

    public UserConsentLogService(UserConsentLogMapper consentLogMapper,
                                 @Lazy UserConsentLogService self) {
        this.consentLogMapper = consentLogMapper;
        this.self = self;
    }

    /** 异常兜底必须在事务代理外：PostgreSQL 事务 aborted 后，事务内 catch 不能挽救提交。 */
    public void recordPrivacyConsent(Long userId) {
        try {
            self.recordPrivacyConsentInNewTransaction(userId);
        } catch (Exception e) {
            log.warn("consent log write failed (non-blocking) userId={}", userId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPrivacyConsentInNewTransaction(Long userId) {
        record(userId, UserConsentLog.TYPE_PRIVACY_POLICY, PRIVACY_POLICY_VERSION);
    }

    /** 记录自助注销动作留痕（AccountService.deleteOwnAccount 调用；异常不阻断）。 */
    public void recordAccountDelete(Long userId) {
        if (userId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            // 注销外层事务会匿名化 user_info；留痕必须等它提交后再写，避免同事务内的
            // PostgreSQL 错误被 catch 后仍标 rollback-only，最终把已完成的注销一起回滚。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeAccountDeleteQuietly(userId);
                }
            });
            return;
        }
        writeAccountDeleteQuietly(userId);
    }

    private void writeAccountDeleteQuietly(Long userId) {
        try {
            record(userId, UserConsentLog.TYPE_ACCOUNT_DELETE, PRIVACY_POLICY_VERSION);
        } catch (Exception e) {
            // afterCommit 已无法回滚注销主流程；写失败只告警，不能把成功的注销读成失败。
            log.warn("account delete consent log write failed (non-blocking) userId={}", userId, e);
        }
    }

    private void record(Long userId, String type, String version) {
        if (userId == null) {
            return;
        }
        consentLogMapper.insertIgnoreConflict(userId, type, version);
    }
}
