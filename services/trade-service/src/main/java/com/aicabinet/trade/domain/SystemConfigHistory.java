package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 系统配置变更历史（F1 策略版本）。
 *
 * <p>一行一次**写操作**（{@code upsert} / {@code delete}），{@code oldValue} 是变更前的值 ——
 * 于是「某键的全部历史行按时间倒序」就是它的版本序列，可回滚到任一条的 {@code oldValue}。
 *
 * <p>只在 {@code ops.config.audit.enabled=true} 时写入（默认关 ⇒ 本表零写入，fail-closed）。
 * 读侧（历史查询 / 回滚）不受开关影响：关掉开关只是「不再新增版本」，不该让已有历史不可见。
 */
@TableName("system_config_history")
@Getter
@Setter
public class SystemConfigHistory {

    @TableId(type = IdType.AUTO)
    private Long historyId;

    private String configKey;

    /** 变更前的值；首次创建时为 null（该版本没有更早的值可回滚）。 */
    private String oldValue;

    /** 变更后的值；删除操作时为 null。 */
    private String newValue;

    /** 操作人 user_id；0 = 系统账号（无鉴权上下文的调用）。 */
    private Long operatorId;

    private Instant createdAt;

}
