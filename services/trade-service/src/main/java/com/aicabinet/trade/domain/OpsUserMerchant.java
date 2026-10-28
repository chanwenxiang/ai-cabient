package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_user_merchant")
@Getter
@Setter
public class OpsUserMerchant {

    @TableField(exist = false)
    private OpsUserMerchantId id;

    private Long userId;

    private String merchantId;

    /** V410：绑定建立时刻。DB 侧另有 DEFAULT NOW() 兜底，两条腿都不依赖调用方赋值。 */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    /** V410：谁建立的这条绑定（取证列，刻意无外键，见 V410 头注释）；系统/演示种子路径为 null。 */
    private Long createdBy;

    public OpsUserMerchant() {}

    public OpsUserMerchant(Long userId, String merchantId) {
        setId(new OpsUserMerchantId(userId, merchantId));
    }

    public OpsUserMerchantId getId() {
        if (id == null && userId != null && merchantId != null) {
            id = new OpsUserMerchantId(userId, merchantId);
        }
        return id;
    }
    public void setId(OpsUserMerchantId id) {
        this.id = id;
        if (id != null) {
            this.userId = id.getUserId();
            this.merchantId = id.getMerchantId();
        }
    }

}
