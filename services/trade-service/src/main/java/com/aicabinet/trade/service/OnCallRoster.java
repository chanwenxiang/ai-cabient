package com.aicabinet.trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 值班表：把「某个时刻」解析为「当班人」，用于 P0 告警的短信 / 电话升级。
 *
 * <p>存在的理由是**避免猜收件人**：告警升级必须打给「此刻真的在值班的人」，而不是某个写死的号码。
 * 没有可解析的值班人时**一律不升级**（fail-closed）——「打给谁」这件事猜错的代价（半夜打错人 /
 * 真正该叫的人没被叫醒）远高于「这次没升级」。</p>
 *
 * <p><b>配置格式</b>（系统参数 {@code ops.alert.oncall_roster}，JSON 数组，按顺序取**第一个**匹配项）：</p>
 * <pre>
 * [
 *   {"name":"张三","phone":"13800000000","days":[1,2,3,4,5],"startHour":9,"endHour":18},
 *   {"name":"李四","phone":"13900000000","days":[6,7],"startHour":0,"endHour":24}
 * ]
 * </pre>
 * <ul>
 *   <li>{@code days} 用 ISO 星期（1=周一 … 7=周日）；**省略或空数组 = 每天都算**。</li>
 *   <li>时段为 {@code [startHour, endHour)} 半开区间（0–24）。{@code startHour > endHour} 表示
 *       **跨夜班**（如 22→6），此时 { h ≥ 22 或 h < 6 } 算在班。</li>
 *   <li>{@code phone} 为空的条目视为不可用、直接跳过（跳过后若无人匹配 ⇒ 等价于「没人在班」）。</li>
 * </ul>
 *
 * <p><b>容错策略</b>：整段 JSON 非数组 / 解析异常 ⇒ **空表**（不升级），并记 warn。
 * 单个条目字段缺失（name/phone/时段非法）⇒ 只跳过该条目，不影响其它条目。
 * 刻意**不抛异常**：值班表配置错误不该让告警主流程挂掉，而应表现为「没升级 + 一条明确的告警日志」。</p>
 */
public final class OnCallRoster {

    private static final Logger log = LoggerFactory.getLogger(OnCallRoster.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一条值班安排。{@code days} 为空集表示每天都算。 */
    public record Entry(String name, String phone, Set<Integer> days, int startHour, int endHour) {

        /** 该条目在 {@code at} 时刻是否在班。 */
        public boolean coversAt(LocalDateTime at) {
            int dow = at.getDayOfWeek().getValue();
            if (!days.isEmpty() && !days.contains(dow)) {
                return false;
            }
            int hour = at.getHour();
            if (startHour <= endHour) {
                return hour >= startHour && hour < endHour;
            }
            return hour >= startHour || hour < endHour;  // 跨夜班
        }
    }

    /** 空表（无值班人）——所有解析失败的归宿。 */
    public static final OnCallRoster EMPTY = new OnCallRoster(List.of());

    private final List<Entry> entries;

    private OnCallRoster(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * 解析配置文本。任何解析不了的情形都退化为 {@link #EMPTY}（fail-closed：宁可不升级，也不猜个人打过去）。
     */
    public static OnCallRoster parse(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            log.warn("on-call roster is not valid JSON; escalation disabled this time: {}", e.getMessage());
            return EMPTY;
        }
        if (root == null || !root.isArray()) {
            log.warn("on-call roster must be a JSON array; got {}; escalation disabled this time",
                    root == null ? "null" : root.getNodeType());
            return EMPTY;
        }
        List<Entry> parsed = new ArrayList<>();
        for (JsonNode node : root) {
            Entry entry = parseEntry(node);
            if (entry != null) {
                parsed.add(entry);
            }
        }
        if (parsed.isEmpty()) {
            log.warn("on-call roster parsed to 0 usable entries (size={}); escalation disabled this time",
                    root.size());
        }
        return new OnCallRoster(parsed);
    }

    private static Entry parseEntry(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String phone = text(node, "phone");
        if (phone.isBlank()) {
            return null;  // 没有可拨打的号码 ⇒ 该条目无意义
        }
        Set<Integer> days = new LinkedHashSet<>();
        JsonNode daysNode = node.get("days");
        if (daysNode != null && !daysNode.isNull()) {
            if (!daysNode.isArray()) {
                log.warn("on-call entry for phone {} has a non-array `days`; skipped",
                        maskPhone(phone));
                return null;
            }
            for (JsonNode d : daysNode) {
                if (d.isInt() && d.asInt() >= 1 && d.asInt() <= 7) {
                    days.add(d.asInt());
                }
            }
            // ⚠️ 写了 days 却一个都没解析出来（如 [0,8] 或 ["MO"]）⇒ **跳过该条目**，
            // 不能退化成「每天都算」：那是把配置错误静默放宽成 7×24 值班。
            if (daysNode.size() > 0 && days.isEmpty()) {
                log.warn("on-call entry for phone {} has no usable day-of-week; skipped",
                        maskPhone(phone));
                return null;
            }
        }
        int start = intOr(node, "startHour", 0);
        int end = intOr(node, "endHour", 24);
        if (start < 0 || start > 24 || end < 0 || end > 24 || start == end) {
            log.warn("on-call entry for phone {} has an unusable window [{}, {}); skipped",
                    maskPhone(phone), start, end);
            return null;
        }
        String name = text(node, "name");
        return new Entry(name.isBlank() ? maskPhone(phone) : name, phone, days, start, end);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    private static int intOr(JsonNode node, String field, int fallback) {
        JsonNode v = node.get(field);
        return v != null && v.isInt() ? v.asInt() : fallback;
    }

    /** 按顺序取第一个在班的条目；无人匹配返回空（调用方据此**不升级**）。 */
    public Optional<Entry> currentAt(LocalDateTime at) {
        for (Entry entry : entries) {
            if (entry.coversAt(at)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** 日志脱敏：只留前后各 3/4 位。 */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
