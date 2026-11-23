package com.aicabinet.trade.payout;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 「用户确认收款」票据存储（MA-407 确认模式）。
 *
 * <p><b>为什么放 Redis 而不是加表</b>：票据只是<b>24 小时内的 UX 凭证</b>
 * （{@code package_info} 用于小程序拉起微信确认页），过期即无用，且丢了不影响资金正确性：
 * 钱在渠道侧已锁定，最终靠回调 / 定时查单收口。加表要为它设计清理、归档、审计，收益不匹配。
 *
 * <p>🔴 <b>存活期纪律</b>（幂等键存活期=重试窗口）：键 <b>有 TTL 且真有读取点</b>
 * （{@code /wallet/withdraws/{id}/confirm-ticket}），24h 后微信侧单据本就会过期关闭，
 * TTL 取 25h 留一小时时钟余量。
 *
 * <p>🔴 <b>丢了会怎样</b>：商户看不到「去确认收款」按钮 ⇒ 不点确认 ⇒ 微信 24h 后自动退回，
 * 定时查单把单据置 FAILED 并解冻。是「体验降级」，不是「钱丢了」。
 */
@Component
public class PayoutConfirmTicketStore {

    /** 微信用确认收款页的有效期是 24h(单据创建后)，票据多留 1h 兜时钟差。 */
    static final Duration TTL = Duration.ofHours(25);
    private static final String KEY_PREFIX = "aicabinet:payout:confirm:v1:";

    private final RedissonClient redisson;

    public PayoutConfirmTicketStore(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /** 票据内容。字段全部可重建（不依赖本地自增 id 以外的敏感信息）。 */
    public record Ticket(long requestId, String payChannel, String packageInfo, Instant expireAt) {

        String encode() {
            return requestId + "|" + safe(payChannel) + "|" + expireAt.toEpochMilli() + "|" + safe(packageInfo);
        }

        static Ticket decode(String raw) {
            String[] parts = raw.split("\\|", 4);
            if (parts.length < 4) {
                return null;
            }
            try {
                return new Ticket(Long.parseLong(parts[0]), parts[1],
                        parts[3], Instant.ofEpochMilli(Long.parseLong(parts[2])));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String safe(String v) {
            return v == null ? "" : v;
        }
    }

    public void save(String outBillNo, Ticket ticket) {
        if (outBillNo == null || outBillNo.isBlank()) {
            return;
        }
        RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + outBillNo.trim(), StringCodec.INSTANCE);
        bucket.set(ticket.encode(), TTL);
    }

    public Optional<Ticket> find(String outBillNo) {
        if (outBillNo == null || outBillNo.isBlank()) {
            return Optional.empty();
        }
        RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + outBillNo.trim(), StringCodec.INSTANCE);
        String raw = bucket.get();
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(Ticket.decode(raw));
    }

    public void clear(String outBillNo) {
        if (outBillNo == null || outBillNo.isBlank()) {
            return;
        }
        redisson.getBucket(KEY_PREFIX + outBillNo.trim(), StringCodec.INSTANCE).delete();
    }
}
