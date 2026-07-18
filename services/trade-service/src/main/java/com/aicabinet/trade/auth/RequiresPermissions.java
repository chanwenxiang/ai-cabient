package com.aicabinet.trade.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RuoYi-style permission gate for controller (or service) methods.
 * Prefer annotating API entry points; add/remove a permission = edit the annotation value.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermissions {

    /** Permission codes, e.g. {@code ops:rbac:role:add}. */
    String[] value();

    /** AND = all required; OR = any one is enough. */
    Logical logical() default Logical.AND;

    enum Logical {
        AND,
        OR
    }
}
