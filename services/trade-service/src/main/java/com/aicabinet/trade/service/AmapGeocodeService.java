package com.aicabinet.trade.service;

import com.aicabinet.common.dto.GeoDistrictNode;
import com.aicabinet.common.dto.GeocodeResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.Setter;

/**
 * 高德地理编码 / 行政区划查询。
 *
 * <h2>为什么一定要带行政区（2026-09-23 实测）</h2>
 * 高德 {@code /v3/geocode/geo} 在**不限城市**时是全国范围模糊匹配、取第一条，
 * 于是自由文本地址会被解析到毫不相干的省份：
 * <pre>
 *   address=万达广场   → 四川省南充市南部县   106.061110,31.352784
 *   address=人民路1号  → 陕西省安康市旬阳市   109.382008,32.832531
 *   address=测试门店   → 广东省梅州市兴宁市   115.734125,24.131625
 *   address=万达广场 &amp; city=310000 → 上海市杨浦区  121.513513,31.300068  ← 限定后才对
 * </pre>
 * 所以本服务把「省/市/区」的 adcode 作为**必填的收敛依据**，并且在解析结果
 * 落到所选行政区之外时**直接报错**，而不是把错误坐标静默写进设备点位。
 *
 * <h2>为什么校验用 adcode 前缀</h2>
 * 行政区划编码是分层的：省级 {@code XX0000}、市级 {@code XXXX00}、区县级 {@code XXXXXX}。
 * 因此「所选行政区」的前缀必须能覆盖解析结果，否则就是跑到别的市/区去了。
 * 直辖市（如 310000 / 310100 上海城区）也满足该规则，无需特判。
 */
@Service
@Getter
@Setter
public class AmapGeocodeService {

    /** 行政区划变动极少，缓存一天即可；按 parent 维度缓存，级联每层一次请求。 */
    private static final long DISTRICT_CACHE_TTL_MS = 24L * 60 * 60 * 1000;
    private static final String CHINA_ADCODE = "100000";

    private final String amapWebKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Map<String, CachedDistricts> districtCache = new ConcurrentHashMap<>();

    private record CachedDistricts(List<GeoDistrictNode> nodes, long expiresAt) {}

    public AmapGeocodeService(
            @Value("${aicabinet.amap.web-key:}") String amapWebKey,
            ObjectMapper objectMapper) {
        this.amapWebKey = amapWebKey == null ? "" : amapWebKey.trim();
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return !amapWebKey.isBlank();
    }

    /**
     * 按地址解析坐标。
     *
     * @param address        详细地址（可含省市区文字，但**不依赖**它来定位城市）
     * @param cityAdcode     所选城市的 adcode（6 位）；为空则退化为全国模糊匹配
     * @param districtAdcode 所选区县的 adcode（6 位）；给定时校验更严格
     */
    public GeocodeResponse geocode(String address, String cityAdcode, String districtAdcode) {
        ensureConfigured();
        String addr = address == null ? "" : address.trim();
        if (addr.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "地址不能为空");
        }
        String city = normalizeAdcode(cityAdcode);
        String district = normalizeAdcode(districtAdcode);
        String requiredPrefix = adcodePrefix(district != null ? district : city);

        List<String> candidates = new ArrayList<>();
        if (city != null) {
            candidates.add(city);
        }
        // 市辖区（如「上海城区」310100）作为 city 参数未必被高德接受，
        // 退一步用省级码再试一次；两次都不符合校验才报错。
        String provinceFallback = city == null ? null : provinceAdcodeOf(city);
        if (provinceFallback != null && !candidates.contains(provinceFallback)) {
            candidates.add(provinceFallback);
        }
        if (candidates.isEmpty()) {
            candidates.add(null);
        }

        GeocodeResponse firstParsed = null;
        for (String cityParam : candidates) {
            GeocodeResponse parsed = requestGeocode(addr, cityParam);
            if (parsed == null) {
                continue;
            }
            if (firstParsed == null) {
                firstParsed = parsed;
            }
            if (requiredPrefix == null || matchesPrefix(parsed.adcode(), requiredPrefix)) {
                return parsed;
            }
        }
        if (firstParsed == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    requiredPrefix == null
                            ? "未找到该地址对应的坐标，请把地址写得更具体"
                            : "在所选行政区（adcode=" + (district != null ? district : city)
                                    + "）内没有匹配到该地址。请确认详细地址与该行政区是否相符，或换一个行政区再试。");
        }
        if (requiredPrefix == null) {
            return firstParsed;
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "解析结果落在所选行政区之外：高德把它解析到了「"
                        + join(firstParsed.province(), firstParsed.city(), firstParsed.district())
                        + "」，与所选行政区不一致。请把详细地址写得更具体（如加门牌号或门店名）。");
    }

    /**
     * 行政区划下一级。{@code parent} 为空时返回省级列表。
     *
     * <p>实测返回体很小（全国省份 4.4KB、区县 1.5–2.2KB），适合做级联的懒加载来源。
     */
    public List<GeoDistrictNode> districts(String parent) {
        ensureConfigured();
        String key = parent == null ? "" : parent.trim();
        CachedDistricts cached = districtCache.get(key);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.nodes();
        }
        String keywords = key.isEmpty() ? CHINA_ADCODE : key;
        JsonNode root = getJson(UriComponentsBuilder
                .fromUriString("https://restapi.amap.com/v3/config/district")
                .queryParam("key", amapWebKey)
                .queryParam("keywords", keywords)
                .queryParam("subdistrict", "1")
                .queryParam("extensions", "base")
                .build()
                .encode()
                .toUri(), "行政区划查询失败");
        assertAmapOk(root, "行政区划查询失败");
        List<GeoDistrictNode> nodes = new ArrayList<>();
        JsonNode districts = root.path("districts");
        if (districts.isArray()) {
            for (JsonNode top : districts) {
                JsonNode children = top.path("districts");
                if (!children.isArray()) {
                    continue;
                }
                for (JsonNode child : children) {
                    String adcode = child.path("adcode").asText("");
                    String name = child.path("name").asText("");
                    if (adcode.isBlank() || name.isBlank()) {
                        continue;
                    }
                    nodes.add(new GeoDistrictNode(adcode, name, child.path("level").asText("")));
                }
            }
        }
        districtCache.put(key, new CachedDistricts(nodes, System.currentTimeMillis() + DISTRICT_CACHE_TTL_MS));
        return nodes;
    }

    // ---- 内部 ----

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未配置高德 Web Key（AMAP_WEB_KEY），地址解析不可用");
        }
    }

    /**
     * 单次地理编码请求；无命中返回 null（由调用方决定试下一个 city）。
     *
     * <p>🔴 区分两类失败，否则会把「换一个候选 city 还能成」的情况误判成硬错误：
     * <ul>
     *   <li>高德**服务/鉴权层**报错（无效 Key、超配额、平台不匹配）⇒ 抛出，重试没有意义；</li>
     *   <li>**取不到数据**（{@code ENGINE_RESPONSE_DATA_ERROR} 等，实测「宁波 + 天安门」就是这个）⇒
     *       返回 null，让调用方继续试省级候选。</li>
     * </ul>
     */
    private GeocodeResponse requestGeocode(String addr, String cityParam) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("https://restapi.amap.com/v3/geocode/geo")
                .queryParam("key", amapWebKey)
                .queryParam("address", addr);
        if (cityParam != null && !cityParam.isBlank()) {
            builder.queryParam("city", cityParam);
        }
        JsonNode root = getJson(builder.build().encode().toUri(), "地理编码失败");
        if (!"1".equals(root.path("status").asText())) {
            String infocode = root.path("infocode").asText("");
            if (isFatalAmapCode(infocode)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "高德地理编码不可用：" + root.path("info").asText("未知错误") + "（" + infocode + "）");
            }
            return null;
        }
        JsonNode geos = root.path("geocodes");
        if (!geos.isArray() || geos.isEmpty()) {
            return null;
        }
        JsonNode first = geos.get(0);
        String location = first.path("location").asText("");
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        double lng;
        double lat;
        try {
            lng = Double.parseDouble(parts[0]);
            lat = Double.parseDouble(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        String formatted = first.path("formatted_address").asText(addr);
        if (formatted.isBlank() || "[]".equals(formatted)) {
            formatted = addr;
        }
        return new GeocodeResponse(
                lng,
                lat,
                formatted,
                nullIfBlank(first.path("adcode").asText("")),
                nullIfBlank(first.path("province").asText("")),
                nullIfBlank(first.path("city").asText("")),
                nullIfBlank(first.path("district").asText("")),
                nullIfBlank(first.path("level").asText("")));
    }

    /** 鉴权/配额类错误：重试无意义，必须让运营看到真实原因。 */
    private static boolean isFatalAmapCode(String infocode) {
        return switch (infocode) {
            case "10001", // INVALID_USER_KEY
                    "10002", // SERVICE_NOT_AVAILABLE
                    "10003", // DAILY_QUERY_OVER_LIMIT
                    "10004", // ACCESS_TOO_FREQUENT
                    "10009", // USERKEY_PLAT_NOMATCH
                    "10012", // INSUFFICIENT_PRIVILEGES
                    "10013", // USER_KEY_RECYCLED
                    "20000", // INVALID_PARAMS
                    "20800" // INVALID_SIGNATURE
                    -> true;
            default -> false;
        };
    }

    private JsonNode getJson(URI uri, String errorPrefix) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "高德服务异常（HTTP " + response.statusCode() + "）");
            }
            return objectMapper.readTree(response.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, errorPrefix + "：" + e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, errorPrefix + "：" + e.getMessage());
        }
    }

    private void assertAmapOk(JsonNode root, String errorPrefix) {
        if (!"1".equals(root.path("status").asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, root.path("info").asText(errorPrefix));
        }
    }

    /** 只接受 6 位数字的 adcode，其余（含空白）一律视为「未选」。 */
    private static String normalizeAdcode(String adcode) {
        if (adcode == null) {
            return null;
        }
        String v = adcode.trim();
        return v.matches("\\d{6}") ? v : null;
    }

    /**
     * 行政区划编码的前缀规则：省级 {@code XX0000} → {@code XX}；
     * 市级 {@code XXXX00} → {@code XXXX}；区县级保持原样。
     */
    static String adcodePrefix(String adcode) {
        if (adcode == null || adcode.length() < 6) {
            return adcode;
        }
        if (adcode.endsWith("0000")) {
            return adcode.substring(0, 2);
        }
        if (adcode.endsWith("00")) {
            return adcode.substring(0, 4);
        }
        return adcode;
    }

    /** 市级码退到省级码；已是省级码则返回自身。 */
    static String provinceAdcodeOf(String adcode) {
        if (adcode == null || adcode.length() < 6) {
            return null;
        }
        return adcode.substring(0, 2) + "0000";
    }

    private static boolean matchesPrefix(String adcode, String prefix) {
        return adcode != null && adcode.startsWith(prefix);
    }

    private static String nullIfBlank(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /**
     * 拼接行政区名（只用于报错文案）。
     *
     * 🔴 直辖市下高德返回的 `city` 与 `province` **同名**（`上海市` / `上海市` / `浦东新区`），
     * 顺序拼接会叠成「上海市上海市浦东新区」，前端把它当报错文案显示出来，看着像 bug。
     * ⇒ 用**子串**判断跳过「已经出现过」的片段（`sb.indexOf(p) < 0` 才追加）。
     * 实测只在「整段同名」时命中：区县名与省名不是同一字符串（最多带上级**前缀**，
     * 如「上海市」与「上海城区」），不会被误删。
     */
    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank() && sb.indexOf(p) < 0) {
                sb.append(p);
            }
        }
        return sb.toString();
    }
}
