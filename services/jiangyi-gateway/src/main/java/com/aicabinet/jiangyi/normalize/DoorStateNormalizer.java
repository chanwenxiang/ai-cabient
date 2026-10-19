package com.aicabinet.jiangyi.normalize;

/**
 * 将邑设备上报字段归一（CB-022，方案 §4.3）。
 *
 * <p><b>成因注释（2026-10-19 真机 logcat 修订）</b>：V16 文档写 lockStatus/doorStatus
 * 取值 {@code success|fail}，但真机实际词汇是 {@code {"lockStatus":"success"}} /
 * {@code {"openDoorStatus":"yes"}}——文档与固件不符（跨 V15/V16 系统性偏差）。
 * 归一为三态而非布尔：</p>
 * <ul>
 *   <li>TRUE：success/yes/open/1（含大小写、trim）——明确成功；</li>
 *   <li>FALSE：fail/failed/false/error/0——明确失败，才允许 fail 一单；</li>
 *   <li>null（未知）：缺字段或无法识别的词——<b>只告警不动会话状态</b>。
 *       旧实现把未知词当「明确失败」立即 postOpenFailed，会在固件改词汇时
 *       fail-close 掉一单正在进行的生意（第三轮审计 P0-3）。</li>
 * </ul>
 */
public final class DoorStateNormalizer {

    private DoorStateNormalizer() {}

    /**
     * 锁/门状态三态归一：TRUE=明确成功；FALSE=明确失败；null=未知（缺字段/未识别词）。
     */
    public static Boolean normalize(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toLowerCase()) {
            case "success", "yes", "open", "true", "1" -> Boolean.TRUE;
            case "fail", "failed", "false", "error", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** bigModel/BigModel 归一（取 body 里任一存在的键）：true=doing（进入大模型复核中）。 */
    public static boolean isDoing(String bigModelValue) {
        return bigModelValue != null && "doing".equalsIgnoreCase(bigModelValue.trim());
    }
}
