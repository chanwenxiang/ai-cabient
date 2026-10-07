package com.aicabinet.trade.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯流量主广告数据客户端（切片 3：收益对账的数据源）。
 *
 * <p>调用微信 {@code publisher/stat} 系列接口，把广告曝光/点击/收入拉回本地落库。
 * <b>只读</b>：不触碰任何资金，只把「腾讯那边算出来的收入」抄回来做对账。
 *
 * <p>🔴 <b>为什么必须落库而不能「需要时现拉」</b>：
 * 微信侧两个接口最大跨度都是 <b>90 天</b>（{@code publisher_adpos_general} /
 * {@code publisher_adunit_general}），超窗取不到。所以必须<b>定期拉并保存</b>；
 * 等哪天想查半年前的收入时再拉，来不及了。
 *
 * <p>🔴 <b>金额单位</b>：微信返回的 {@code income} 与 {@code ecpm} <b>本身就是「分」</b>，
 * 本类原样返回整数分，<b>不做任何单位换算</b> —— 换算层只允许有一个地方做，
 * 否则「展示时除 100」与「落库时除 100」迟早有一处漏。
 *
 * <p>🔴 <b>预估 vs 结算不能混</b>：
 * {@code publisher_adpos_general} 是<b>预估</b>（每天变），
 * {@code publisher_settlement} 是<b>结算</b>（半月一次，有 {@code sett_status}）。
 * 收入确认必须以结算为准；预估只用于趋势展示。两者用不同方法名分开拉，
 * 由调用方分别落库（表里的 {@code data_source} 区分）。
 */
@Component
public class WeChatPublisherStatClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatPublisherStatClient.class);

    /**
     * 微信接口地址（两个模板：有/无广告位过滤）。
     *
     * <p>⚠️ 刻意<b>不</b>把 access_token 拼进常量 —— 它会出现在异常 message 里。
     * 走 uri 模板变量由 {@code RestClient} 做编码。
     *
     * <p>🔴 <b>为什么分两个模板而不是拼条件串</b>：{@code RestClient.get().uri()} 返回
     * {@code RequestHeadersUriSpec}，<b>没有 {@code queryParam} 方法</b>
     * （那是 {@code post()} 返回的 {@code RequestBodySpec} 才有的）。
     * 要带查询参数只能放进模板 —— 所以用两个常量避开「传空串给微信」的问题。
     */
    private static final String STAT_URL_NO_SLOT =
            "https://api.weixin.qq.com/publisher/stat"
                    + "?action={action}&access_token={token}"
                    + "&start_date={startDate}&end_date={endDate}"
                    + "&page={page}&page_size={pageSize}";
    private static final String STAT_URL_WITH_SLOT =
            STAT_URL_NO_SLOT + "&ad_slot={adSlot}";

    /** 微信文档：每页最大 90 条（不是 100）。超了会被拒。 */
    static final int PAGE_SIZE_MAX = 90;

    /**
     * 防无限翻页的硬上限。
     *
     * <p>正常 90 天 × 1 个广告位 = 90 条，一页就够。这个上限是给「
     * {@code total_num} 与实际返回条数不一致」这种异常情况兜底 ——
     * 没有它，一个错误的 total_num 就能让任务一直翻页直到天亮。
     */
    private static final int MAX_PAGES = 20;

    private final WeChatMiniAppClient miniAppClient;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WeChatPublisherStatClient(WeChatMiniAppClient miniAppClient,
                                      ObjectMapper objectMapper) {
        this.miniAppClient = miniAppClient;
        this.objectMapper = objectMapper;
        // 与 WeChatMiniAppClient 同一约定：本仓没有共享 RestClient bean，
        // 各 client 自行 create()（改注入方式会牵动所有现存 client，不值得）
        this.restClient = RestClient.create();
    }

    /**
     * 拉广告汇总数据（按广告位，<b>预估</b>）。
     *
     * @param adSlot 广告位类型名（如 {@code SLOT_ID_WEAPP_BANNER}）；
     *               <b>null 表示全部类型</b>（微信侧默认行为）。
     *               只关心自己那几个位时<b>应当传</b>，可显著减少数据量。
     * @return 每个广告位每天一行
     */
    public List<AdPosGeneralRow> fetchAdPosGeneral(LocalDate start, LocalDate end, String adSlot) {
        return fetchPaged("publisher_adpos_general", start, end, adSlot, this::parseAdPosRow);
    }

    /**
     * 拉广告细分数据（按广告单元，<b>预估</b>）。
     *
     * <p>⚠️ 落库时<b>不要</b>把 {@code ad_unit_id} 放进唯一键 —— 微信在广告位重建后
     * 会换ID，放进唯一键会把同一广告位拆成两行（详见 V310 迁移注释第 2 点）。
     */
    public List<AdUnitGeneralRow> fetchAdUnitGeneral(LocalDate start, LocalDate end, String adSlot) {
        return fetchPaged("publisher_adunit_general", start, end, adSlot, node -> {
            JsonNode item = node.path("stat_item");
            AdUnitGeneralRow row = new AdUnitGeneralRow();
            row.adUnitId = node.path("ad_unit_id").asText("");
            row.adUnitName = node.path("ad_unit_name").asText("");
            row.adSlot = item.path("ad_slot").asText("");
            row.date = parseDate(item.path("date").asText(""));
            row.reqSuccCount = item.path("req_succ_count").asLong(0L);
            row.exposureCount = item.path("exposure_count").asLong(0L);
            row.clickCount = item.path("click_count").asLong(0L);
            row.incomeCents = item.path("income").asLong(0L);
            row.ecpmCents = item.path("ecpm").asDouble(Double.NaN);
            return row;
        });
    }

    /**
     * 拉结算收入（<b>已结算口径</b>，按半月）。
     *
     * <p>🔴 与预估数据的区别：{@code publisher_settlement} 的 {@code sett_status}
     * 有 5 态（1 结算中/ 2,3 已结算 / 4 付款中 / 5 已付款），
     * <b>只有 2/3/5 算真正到账</b>；1 和 4 还在流程里。
     * 本类原样返回 {@code settStatus}，由调用方决定要不要计入已确认收入。
     */
    public List<SettlementRow> fetchSettlement(LocalDate start, LocalDate end) {
        // 结算接口不按广告位筛（它本身就是按结算区间的），故adSlot 固定传空
        return fetchPaged("publisher_settlement", start, end, null, node -> {
            List<SettlementRow> out = new ArrayList<>();
            for (JsonNode item : node.path("settlement_list")) {
                SettlementRow row = new SettlementRow();
                row.zone = item.path("zone").asText("");
                row.month = item.path("month").asText("");
                row.order = item.path("order").asInt(0);
                row.settStatus = item.path("sett_status").asInt(0);
                row.settledRevenueCents = item.path("settled_revenue").asLong(0L);
                row.settlementNo = item.path("sett_no").asText("");
                for (JsonNode slotRevenue : item.path("slot_revenue")) {
                    SlotRevenue sr = new SlotRevenue();
                    sr.adSlot = slotRevenue.path("slot_id").asText("");
                    sr.settledRevenueCents = slotRevenue.path("slot_settled_revenue").asLong(0L);
                    row.bySlot.add(sr);
                }
                out.add(row);
            }
            // settlement_list 一行里含全部结算区间 ⇒ 返回 List 让调用方统一处理
            return out;
        }).stream().flatMap(List::stream).toList();
    }

    /**
     * 通用分页拉取。
     *
     * <p>⚠️ 每个 action 的 {@code list[]} 元素结构不同，故用 {@code rowParser} 回调，
     * 而不是硬编码一种解析 —— 这样加新 action 时不用改分页逻辑。
     */
    private <T> List<T> fetchPaged(String action, LocalDate start, LocalDate end,
                                   String adSlot, RowParser<T> rowParser) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start/end 不能为空");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end 不能早于 start: " + start + ".." + end);
        }
        List<T> out = new ArrayList<>();
        int page = 1;
        // totalNum 提到循环外：分页上限的告警要报告「微信说一共多少条」，
        // 而它只在循环内赋值 —— 声明在循环里会让告警拿不到（编译期就报找不到符号）。
        long totalNum = -1L;
        while (page <= MAX_PAGES) {
            JsonNode root = callOnce(action, start, end, adSlot, page);
            JsonNode list = root.path("list");
            for (JsonNode item : list) {
                T parsed = rowParser.parse(item);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
            totalNum = root.path("total_num").asLong(0L);
            long fetched = (long) page * PAGE_SIZE_MAX;
            if (list.isEmpty() || fetched >= totalNum) {
                break;
            }
            page++;
        }
        if (page > MAX_PAGES) {
            // 不是失败，但必须留痕：total_num 异常大时我们只取了前 MAX_PAGES 页，
            // 数据不完整。若不告警，对账时会把「没拉全」误判成「腾讯少给了钱」。
            log.warn("{} 分页达上限 page={} totalNum={}，本次数据可能不完整 action={} range={}..{}",
                    MAX_PAGES, page, totalNum, action, start, end);
        }
        return out;
    }

    private JsonNode callOnce(String action, LocalDate start, LocalDate end, String adSlot, int page) {
        String body;
        try {
            // 🔴 本仓 RestClient 3.5 的 `get()` 返回 `RequestHeadersUriSpec`，
            //   **它没有 uriBuilder()**（那是 `post()` 返回的 RequestBodySpec 才有的）。
            //   要带查询参数只能把参数拼进 uri 模板 —— 也就是本仓WeChatWebOAuthClient
            //   已在用的形态（`uri(URL, var1, var2, ...)`）。
            //   模板里的 {slot} 段在 adSlot 为空时用 base（不带该参数），
            //   见下面两个分支 —— 不能传空串（会让微信收到 slot= 空值）。
            String template = (adSlot == null || adSlot.isBlank())
                    ? STAT_URL_NO_SLOT
                    : STAT_URL_WITH_SLOT;
            // 两个模板的变量顺序一致（{action}{token}{startDate}{endDate}{page}{pageSize}），
            // 末尾多一个 {adSlot}。传 adSlot（可能为 null）给不含该占位的模板是无害的 ——
            // RestClient 只替换模板里存在的占位符。
            body = restClient.get()
                    .uri(template, action, miniAppClient.accessToken(),
                            start.toString(), end.toString(), page, PAGE_SIZE_MAX, adSlot)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            // 🔴 网络/HTTP 失败必须抛出，**不能**返回空列表 ——
            //    空列表会被调用方理解成「今天没有收入」，把故障伪装成「没数据」。
            throw new IllegalStateException("publisher/stat 调用失败 action=" + action
                    + " range=" + start + ".." + end, e);
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            // 微信错误格式：{"base_resp":{"err_msg":"...","ret":2009}}
            // 2009=无效的流量主（还没开通），45010=无效的接口名
            // 🔴 这里**必须抛异常而不是当空数据**：未开通流量主 ≠ 今天没收入
            JsonNode baseResp = node.path("base_resp");
            int ret = baseResp.path("ret").asInt(0);
            if (ret != 0) {
                throw new WeChatApiException(ret, baseResp.path("err_msg").asText(""));
            }
            return node;
        } catch (WeChatApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 publisher/stat 响应失败 action=" + action, e);
        }
    }

    private AdPosGeneralRow parseAdPosRow(JsonNode item) {
        AdPosGeneralRow row = new AdPosGeneralRow();
        row.adSlot = item.path("ad_slot").asText("");
        if (row.adSlot.isBlank()) {
            // 老接口只回 slot_id 不回 ad_slot ⇒ 跳过而不是落一行空广告位
            //（落空广告位会污染汇总，且永远匹配不上运营配的 ad_slot）
            log.debug("adposgeneral 记录缺 ad_slot，跳过: {}", item);
            return null;
        }
        row.date = parseDate(item.path("date").asText(""));
        row.reqSuccCount = item.path("req_succ_count").asLong(0L);
        row.exposureCount = item.path("exposure_count").asLong(0L);
        row.clickCount = item.path("click_count").asLong(0L);
        row.incomeCents = item.path("income").asLong(0L);
        row.ecpmCents = item.path("ecpm").asDouble(Double.NaN);
        return row;
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface RowParser<T> {
        /** 返回 null 表示「这条不该落库」（如缺关键字段）。 */
        T parse(JsonNode item);
    }

    /** 微信业务错误码（{@code base_resp.ret}）。 */
    public static class WeChatApiException extends RuntimeException {
        private final int ret;
        private final String errMsg;

        public WeChatApiException(int ret, String errMsg) {
            super("微信接口错误 ret=" + ret + " msg=" + errMsg);
            this.ret = ret;
            this.errMsg = errMsg;
        }

        public int ret() {
            return ret;
        }

        public String errMsg() {
            return errMsg;
        }

        /** 2009 = 无效的流量主（账号还没开通流量主）。 */
        public boolean isPublisherNotEnabled() {
            return ret == 2009;
        }

        /** 45009 = 请求过于频繁；45010 = 无效的接口名。 */
        public boolean isRateLimitedOrBadAction() {
            return ret == 45009 || ret == 45010;
        }
    }

    /** 广告汇总行（按广告位×日）。金额单位<b>分</b>。 */
    public static class AdPosGeneralRow {
        public String adSlot;
        public LocalDate date;
        public long reqSuccCount;
        public long exposureCount;
        public long clickCount;
        /** 收入，单位分。 */
        public long incomeCents;
        /** 千次曝光收益，单位分；{@link Double#NaN} 表示微信未返回。 */
        public double ecpmCents;
    }

    /** 广告细分行（按广告单元×日）。 */
    public static class AdUnitGeneralRow {
        public String adUnitId;
        public String adUnitName;
        public String adSlot;
        public LocalDate date;
        public long reqSuccCount;
        public long exposureCount;
        public long clickCount;
        public long incomeCents;
        public double ecpmCents;
    }

    /** 结算行（半月区间）。 */
    public static class SettlementRow {
        public String zone;
        public String month;
        /** 1 = 上半月，2 = 下半月。 */
        public int order;
        /** 1 结算中/ 2,3 已结算 / 4 付款中 / 5 已付款。 */
        public int settStatus;
        public long settledRevenueCents;
        public String settlementNo;
        public List<SlotRevenue> bySlot = new ArrayList<>();

        /** 只有 2/3/5 算真正结算完成（1 结算中、4 付款中都还在流程里）。 */
        public boolean isSettled() {
            return settStatus == 2 || settStatus == 3 || settStatus == 5;
        }
    }

    /** 结算行内的广告位明细。 */
    public static class SlotRevenue {
        public String adSlot;
        public long settledRevenueCents;
    }
}