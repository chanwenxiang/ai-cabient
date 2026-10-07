package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("coupon_definition")
@Getter
@Setter
public class CouponDefinition {

    @TableId(type = IdType.AUTO)
    private Long couponDefId;

    private String couponName;

    private String couponType;

    private int denominationCents;

    private int minSpendCents;

    private Integer discountPercent;

    private int validityDays = 30;

    private int maxIssueCount;

    private int issuedCount;

    private Long activityId;

    /**
     * @deprecated 迁移前的「设备范围」，<b>零消费者</b>（服务不读、mapper 不筛、库里全 ALL）
     *     ⇒ 运营改它用户侧毫无变化。V319 起请用 {@link #scopeType}
     *     + {@link #scopeDeviceIds} / {@link #scopeMerchantId}。
     *     <p>保留列是为了不破坏历史数据，<b>不要在业务逻辑里再读它</b>。
     */
    @Deprecated
    private String deviceScope = "ALL";

    /**
     * V319 券可用范围类型：ALL / DEVICE / MERCHANT（见 {@code CouponScopeType}）。
     *
     * <p>🔴 这才是真正生效的范围字段；{@link #deviceScope} 是历史死字段。
     */
    private String scopeType;

    /** V319：{@code scopeType=MERCHANT} 时生效，该商户名下所有柜机可用。 */
    private String scopeMerchantId;

    /** V319：{@code scopeType=DEVICE} 时生效的柜机 ID 集合（空 = 不限制）。 */
    private String[] scopeDeviceIds;

    private String status = "ACTIVE";

    private String description;

    private Instant createdAt;

    private Instant updatedAt;

}
