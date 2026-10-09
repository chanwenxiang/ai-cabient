package com.aicabinet.jiangyi.normalize;

/**
 * 将邑设备上报字段归一（CB-022，方案 §4.3）。
 *
 * <p>两个已知坑（V16 文档原文核对）：</p>
 * <ul>
 *   <li>§4.2.7 lockStatus / §4.2.8 doorStatus 取值 {@code success|fail}；
 *       §4.2.8 原文「如果开门失败，不会上报订单结果」；</li>
 *   <li>§4.2.11 字段名 {@code bigModel}，§4.2.12 写成 {@code BigModel}——
 *       设备固件大小写不可信，解析一律大小写不敏感。</li>
 * </ul>
 */
public final class DoorStateNormalizer {

    private DoorStateNormalizer() {}

    /** 锁/门状态归一：true=success。null/false 无法确认成功的一律按 fail-closed 处理。 */
    public static boolean isSuccess(String status) {
        return status != null && "success".equalsIgnoreCase(status.trim());
    }

    /** bigModel/BigModel 归一（取 body 里任一存在的键）：true=doing（进入大模型复核中）。 */
    public static boolean isDoing(String bigModelValue) {
        return bigModelValue != null && "doing".equalsIgnoreCase(bigModelValue.trim());
    }
}
