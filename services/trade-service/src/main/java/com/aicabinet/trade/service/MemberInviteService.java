package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MemberInviteSummaryDto;
import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.support.InviteCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * 会员邀请归因与发奖（营销 2，2026-11-23 用户拍板：新人满 ¥19 减 ¥5、邀请人 ¥5 无门槛）。
 *
 * <p>三个口径（改动前先读这三条）：
 * <ol>
 *   <li><b>只有「零消费记录」能绑</b>：绑定要求 {@code order_count = 0}，否则老用户会被"补录"成新客，
 *       等于给存量用户倒送拉新成本。</li>
 *   <li><b>新人券在绑定时发，邀请人券在被邀请人首单支付后发</b>：新人券要能在**首单**用掉才有意义，
 *       首单之后再发就变成"二单券"；邀请人券则必须等真实成交，防止「注册即套利」。</li>
 *   <li><b>结算幂等靠 {@code member.invite_settled_at}</b>：支付成功的三条路径
 *       （支付分回调 / 落单结算 / 欠款补缴）都会调 {@code MemberService.onOrderPaid}，
 *       没有这个标记就会重复发券。</li>
 * </ol>
 *
 * <p>⚠️ 未做设备级反作弊：全仓没有设备指纹/同 IP 画像，硬写「同设备 24h 去重」只会得到一个
 * 恒真的判据（看着防了，实际没防）。当前依赖的是「同一 userId 只能绑一次 + 绑定前必须零消费 +
 * 邀请人券只在真实支付后发」三条可验证约束。
 */
@Service
public class MemberInviteService implements FirstOrderInviteSettler {

    private static final Logger log = LoggerFactory.getLogger(MemberInviteService.class);

    /** 邀请活动总开关（2026-11-23 产品下线，默认关；关闭后 bind 直接拒、结算不发券，但已有券不回收）。 */
    public static final String KEY_ENABLED = "marketing.invite.enabled";

    private final MemberMapper memberRepository;
    private final InviteCouponIssuer couponIssuer;
    private final SystemConfigService systemConfigService;

    public MemberInviteService(MemberMapper memberRepository,
                               InviteCouponIssuer couponIssuer,
                               SystemConfigService systemConfigService) {
        this.memberRepository = memberRepository;
        this.couponIssuer = couponIssuer;
        this.systemConfigService = systemConfigService;
    }

    // ── 我的邀请 ──────────────────────────────────────────

    /**
     * 我的邀请码 + 邀请进度。存量会员（V501 之前建的）没有码，这里惰性补一个 ——
     * 不用迁移回填：回填要 UPDATE 全表，而这里只在用户真正打开邀请页时才写一行。
     */
    public MemberInviteSummaryDto summary(Long userId) {
        Member me = memberRepository.findByUserId(userId).orElse(null);
        if (me == null) {
            return new MemberInviteSummaryDto(null, 0, 0, enabled());
        }
        repairInviteeCouponIfMissing(me);
        return new MemberInviteSummaryDto(
                ensureInviteCode(me),
                (int) memberRepository.countByInvitedBy(userId),
                (int) memberRepository.countSettledByInvitedBy(userId),
                enabled());
    }

    private String ensureInviteCode(Member me) {
        if (me.getInviteCode() != null && !me.getInviteCode().isBlank()) {
            return me.getInviteCode();
        }
        String code = InviteCodes.next(memberRepository::existsInviteCode);
        // 条件更新（invite_code IS NULL）：并发两个请求同时补码时只有一个能写进去，
        // 另一个读回赢家的码，而不是把对方刚写的覆盖掉。
        if (memberRepository.writeInviteCodeIfAbsent(me.getUserId(), code) == 0) {
            return memberRepository.findByUserId(me.getUserId())
                    .map(Member::getInviteCode)
                    .orElse(code);
        }
        return code;
    }

    // ── 绑定 ─────────────────────────────────────────────

    /**
     * 绑定邀请人。**不开事务**：绑定是一条条件 UPDATE（原子），后续发券在
     * {@link InviteCouponIssuer} 的独立事务里；包成一个大事务反而会造成
     * 「券已发、绑定回滚」的窗口。
     */
    public MemberInviteSummaryDto bind(Long userId, String rawCode) {
        if (!enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邀请活动暂未开启");
        }
        String code = InviteCodes.normalize(rawCode);
        if (code.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写邀请码");
        }
        Member me = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会员信息不存在，请刷新重试"));
        if (me.getInvitedBy() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已绑定过邀请人，不能重复绑定");
        }
        if (me.getOrderCount() != null && me.getOrderCount() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有消费记录，不支持绑定邀请人");
        }
        Member inviter = memberRepository.findByInviteCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "邀请码无效"));
        if (userId.equals(inviter.getUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能绑定自己的邀请码");
        }
        // 条件更新把「已绑过 / 刚下了单」的并发情况关在一条 SQL 里，避免「读-判-写」之间的缝隙
        int rows = memberRepository.bindInviter(userId, inviter.getUserId(), Instant.now());
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "绑定失败：状态已变化，请刷新后重试");
        }
        // 先绑定后发券：绑定失败时不能白送券（券是真金白银）。发券失败则留待下次打开邀请页补发。
        try {
            Long couponId = couponIssuer.issue(userId, InviteCouponIssuer.CODE_NEWCOMER);
            if (couponId != null) {
                memberRepository.writeInviteeCouponId(userId, couponId);
            }
        } catch (Exception e) {
            log.warn("invite newcomer coupon failed userId={}", userId, e);
        }
        return summary(userId);
    }

    /** 绑了但券没发成（券发完 / 模板下架后又上架）时，打开邀请页补一次。 */
    private void repairInviteeCouponIfMissing(Member me) {
        if (me.getInvitedBy() == null || me.getInviteeCouponId() != null) {
            return;
        }
        if (me.getOrderCount() != null && me.getOrderCount() > 0) {
            return; // 已消费过：新人券已经用不上了，不再补
        }
        try {
            Long couponId = couponIssuer.issue(me.getUserId(), InviteCouponIssuer.CODE_NEWCOMER);
            if (couponId != null) {
                memberRepository.writeInviteeCouponId(me.getUserId(), couponId);
            }
        } catch (Exception e) {
            log.warn("invite newcomer coupon repair failed userId={}", me.getUserId(), e);
        }
    }

    // ── 首单结算 ──────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>调用方是 {@code MemberService.onOrderPaid}，此刻外层事务**正持有 member 行锁**；
     * 所以这里只调 {@link InviteCouponIssuer}（独立事务、只写券相关表），绝不碰 member 行。
     */
    @Override
    public Long settleInviterReward(Long inviteeUserId, Long inviterUserId, String orderId) {
        if (!enabled() || inviterUserId == null || inviterUserId.equals(inviteeUserId)) {
            return null;
        }
        Long couponId = couponIssuer.issue(inviterUserId, InviteCouponIssuer.CODE_INVITER);
        if (couponId == null) {
            return null;
        }
        log.info("invite reward settled invitee={} inviter={} order={} coupon={}",
                inviteeUserId, inviterUserId, orderId, couponId);
        return couponId;
    }

    private boolean enabled() {
        return systemConfigService.getBoolean(KEY_ENABLED, false);
    }
}
