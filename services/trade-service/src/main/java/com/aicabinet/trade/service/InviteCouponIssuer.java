package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 邀请专用发券出口（营销 2）。
 *
 * <p>两条调用路径（绑定发新人券、首单发邀请人券）都从这里走，只做三件事：按
 * {@code system_code} 找在架券模板 → 发券 → 返回券 id。找不到模板就返回 {@code null}
 * （功能降级，不报错）。
 *
 * <p>🔴 必须是 {@code REQUIRES_NEW}：调用方要么正持有 member 行锁（首单结算），
 * 要么正处在绑定流程里，发券失败（券发完 / 预算不足）**不能**把调用方的事务标记成
 * rollback-only —— 一旦标记，`onOrderPaid` 那条腿会让会员消费统计与积分一起回滚，
 * 变成「钱收了、积分没了」。
 */
@Service
public class InviteCouponIssuer {

    private static final Logger log = LoggerFactory.getLogger(InviteCouponIssuer.class);

    /** 新人首单券（被邀请人绑定时发）。 */
    public static final String CODE_NEWCOMER = "INVITE_NEWCOMER";
    /** 邀请人奖励券（被邀请人首单支付后发）。 */
    public static final String CODE_INVITER = "INVITE_INVITER";

    private final CouponDefinitionMapper definitionRepository;
    private final CouponService couponService;

    public InviteCouponIssuer(CouponDefinitionMapper definitionRepository,
                              CouponService couponService) {
        this.definitionRepository = definitionRepository;
        this.couponService = couponService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long issue(Long userId, String systemCode) {
        Long defId = definitionRepository.findActiveIdBySystemCode(systemCode);
        if (defId == null) {
            log.info("invite coupon template missing/inactive code={} userId={}", systemCode, userId);
            return null;
        }
        Long couponId = couponService.issueToUser(userId, defId).couponId();
        log.info("invite coupon issued code={} userId={} couponId={}", systemCode, userId, couponId);
        return couponId;
    }
}
