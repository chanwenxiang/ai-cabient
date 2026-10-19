package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserConsentLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserConsentLogMapper extends BaseTradeMapper<UserConsentLog> {

    /**
     * 幂等写入：唯一键 (user_id, consent_type, policy_version) 冲突即忽略。
     * 登录路径每次发 token 都会调用本方法，靠 DB 层幂等免重查。
     */
    @Insert("INSERT INTO user_consent_log (user_id, consent_type, policy_version) "
            + "VALUES (#{userId}, #{consentType}, #{policyVersion}) "
            + "ON CONFLICT (user_id, consent_type, policy_version) DO NOTHING")
    int insertIgnoreConflict(@Param("userId") Long userId,
                             @Param("consentType") String consentType,
                             @Param("policyVersion") String policyVersion);
}
