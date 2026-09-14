package com.aicabinet.common.api;

import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对外 HTTP API 版本约定（S-P2-10）。
 * <ul>
 *   <li>当前唯一对外契约：{@link #CURRENT}（路径 {@code /api/v2/**}）</li>
 *   <li>破坏性变更开新主版本（v3），灰度期可同时挂载；废弃版返回 410 Gone</li>
 *   <li>响应统一带 {@link #RESPONSE_HEADER}，便于网关/客户端识别</li>
 *   <li>服务间仍用 {@code /internal/v1/**}，不受本类约束</li>
 * </ul>
 */
public final class ApiVersions {

    public static final String CURRENT = "v2";
    public static final int CURRENT_MAJOR = 2;
    public static final String PATH_PREFIX = "/api/" + CURRENT;
    public static final String RESPONSE_HEADER = "X-Api-Version";
    /** 可选请求头：客户端声明期望版本；与路径不一致时以路径为准并记 warn。 */
    public static final String REQUEST_HEADER = "X-Api-Version";

    private static final Pattern PUBLIC_API_VERSION =
            Pattern.compile("^/api/v(\\d+)(?:/|$)");

    /** 当前支持的公开 API 主版本（仅路径段）。 */
    private static final Set<Integer> SUPPORTED = Set.of(CURRENT_MAJOR);

    private ApiVersions() {
    }

    public static boolean isSupported(int major) {
        return SUPPORTED.contains(major);
    }

    public static OptionalInt parsePublicApiMajor(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return OptionalInt.empty();
        }
        String path = requestUri;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        Matcher m = PUBLIC_API_VERSION.matcher(path);
        if (!m.find()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    public static String unsupportedMessage(int major) {
        return "API v" + major + " 未开放或已下线，请使用 " + PATH_PREFIX + "/";
    }
}
