package com.aicabinet.trade.architecture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标明写事务内刻意保留的远程调用（如 chargeOrder 须与未提交订单同事务可见）。
 * 新增豁免须写清 reason，并在 Code Review 中审视。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowTransactionalRemote {
    String reason();
}
