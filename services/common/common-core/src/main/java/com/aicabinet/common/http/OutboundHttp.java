package com.aicabinet.common.http;

import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 出站 HTTP 客户端的**唯一构造入口**（结构化清单 S1）。
 *
 * <p>缺陷类（同一形状修了四轮、每次换一个落点）：R20-4（支付客户端零超时）、R20-10（设备回调
 * trade 零超时）、R19（网关零超时）、R21-1（6 处，含微信登录主链）。每一轮的修法都是「在这个类里
 * 补两行 setTimeout」⇒ 超时值散落在 12 个类里各写一份，下一个新 client 照样裸建，
 * 而且 2026-11-12 全量清点时又挖出两处从未进过任何清单的
 * {@code RestClient.create()}（{@code WeChatPublisherStatClient} / {@code WeChatWebOAuthClient}）。</p>
 *
 * <p>本类把「怎么建一个出站客户端」收敛成一个入口，配门禁
 * {@code scripts/check-outbound-client-factory.mjs} 拦绕过。两个硬性保证：</p>
 * <ul>
 *   <li><b>不存在「不设超时」的构造路径</b>：{@link #builder(int, int)} 只接受显式毫秒数，
 *       非正数直接抛（0/负数在 Spring 侧语义是<b>无限等待</b>，正是本族事故形态）；</li>
 *   <li><b>档位有名字</b>：调用方写的是 {@code OutboundHttp.publicApi()} 这种意图，
 *       而不是一行凭手感填的 {@code 10_000}，评审时能一眼看出「登录主链用了 60s 下载档」这类错配。</li>
 * </ul>
 *
 * <p>选型说明：继续用 {@link SimpleClientHttpRequestFactory}（HttpURLConnection）而不是 JDK
 * HttpClient —— 内部调用打过 uvicorn 类对端时，JDK HttpClient 会发 {@code Expect: 100-continue}
 * 导致对端 400 Invalid HTTP request（历史教训来自已退役的 vision-service，见
 * {@code InternalRestClientFactory} 原注释）。</p>
 */
public final class OutboundHttp {

    // ── 档位常量（改动请连注释一起改，评审要看的是「为什么是这个量级」） ──

    /** 公网 API（微信/支付宝/实名/厂商）连接预算：3s。 */
    public static final int CONNECT_PUBLIC_MS = 3_000;

    /** 公网 API 读预算：10s。下单/退款/code2Session 正常回执为秒级，10s 已含对端抖动重试余量。 */
    public static final int READ_PUBLIC_MS = 10_000;

    /**
     * 条码查询读预算：8s。上游本地库未命中会回源编码中心，比公网 API 档位慢，
     * 但仍必须封顶——否则一次查询挂住即占住一个 servlet 线程。
     */
    public static final int READ_BARCODE_MS = 8_000;

    /** 内部服务/协议代扣网关连接预算：10s。 */
    public static final int CONNECT_INTERNAL_MS = 10_000;

    /** 内部调用读预算：30s。120s 过长会拖垮线程池，与 resilience4j 重试配合取 30s。 */
    public static final int READ_INTERNAL_MS = 30_000;

    /** 账单/文件下载读预算：60s（连接仍按公网档）。微信对账单下载实测远超普通 RPC。 */
    public static final int READ_BULK_DOWNLOAD_MS = 60_000;

    /**
     * 告警投递预算：3s + 5s。单次尝试最坏 8s，配 3 次尝试 ⇒ 单渠道最坏 24s；
     * 告警从支付回调/定时任务线程发出，不能长时间挂住。
     */
    public static final int CONNECT_ALERT_MS = 3_000;

    public static final int READ_ALERT_MS = 5_000;

    /**
     * 回调线程内同步调用预算：2s + 5s。设备 MQTT 回调线程里调 trade 记开门事件
     * （失败还重试 3 次），预算必须比 servlet 侧更紧——挂住的是全设备门磁的 ACK。
     */
    public static final int CONNECT_CALLBACK_THREAD_MS = 2_000;

    public static final int READ_CALLBACK_THREAD_MS = 5_000;

    private OutboundHttp() {}

    // ── 原语 ──

    /**
     * 显式毫秒工厂。**唯一的** {@code SimpleClientHttpRequestFactory} 创建点。
     *
     * @throws IllegalArgumentException 任一为非正数（0/负数＝无限等待，正是本族事故形态）
     */
    public static SimpleClientHttpRequestFactory factory(int connectMillis, int readMillis) {
        requirePositive(connectMillis, "connectMillis");
        requirePositive(readMillis, "readMillis");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMillis);
        factory.setReadTimeout(readMillis);
        return factory;
    }

    /**
     * 带响应缓冲的工厂：**微信系 API 必须用这个**。
     *
     * <p>{@code RestClient.create()}（Spring 6.1+）对 JSON body 不再预缓冲、不发
     * {@code Content-Length} 而改 chunked，微信 API 对无 {@code Content-Length} 的请求一律回
     * 412 Precondition Failed（实证 2026-10-23：{@code getuserphonenumber} 与
     * {@code subscribe/send} 双双 412，后者曾被误判成「用户未订阅」）。</p>
     */
    public static ClientHttpRequestFactory bufferingFactory(int connectMillis, int readMillis) {
        return new BufferingClientHttpRequestFactory(factory(connectMillis, readMillis));
    }

    /** 显式预算的 builder（少数非标准档位用，如条码 8s、回调线程 2s/5s）。 */
    public static RestClient.Builder builder(int connectMillis, int readMillis) {
        return RestClient.builder().requestFactory(factory(connectMillis, readMillis));
    }

    // ── 档位 ──

    /** 公网 API：3s 连接 / 10s 读，不缓冲。 */
    public static RestClient.Builder publicApi() {
        return builder(CONNECT_PUBLIC_MS, READ_PUBLIC_MS);
    }

    /**
     * 微信系公网 API：3s / 10s 且**带缓冲**（保证 {@code Content-Length}）。
     * 打微信开放接口一律用这个档位，别用 {@link #publicApi()}。
     */
    public static RestClient.Builder publicApiBuffered() {
        return RestClient.builder().requestFactory(bufferingFactory(CONNECT_PUBLIC_MS, READ_PUBLIC_MS));
    }

    /** 内部服务/代扣网关：10s / 30s。 */
    public static RestClient.Builder internalRpc() {
        return builder(CONNECT_INTERNAL_MS, READ_INTERNAL_MS);
    }

    /** 账单与文件下载：3s 连接 / 60s 读。 */
    public static RestClient.Builder bulkDownload() {
        return builder(CONNECT_PUBLIC_MS, READ_BULK_DOWNLOAD_MS);
    }

    /** 条码查询：3s 连接 / 8s 读（回源编码中心）。 */
    public static RestClient.Builder barcodeLookup() {
        return builder(CONNECT_PUBLIC_MS, READ_BARCODE_MS);
    }

    /** 回调线程内的同步内部调用：2s / 5s。 */
    public static RestClient.Builder callbackThreadRpc() {
        return builder(CONNECT_CALLBACK_THREAD_MS, READ_CALLBACK_THREAD_MS);
    }

    // ── 第二条出站技术栈：java.net.http.HttpClient ──
    //
    // 本仓除 RestClient 外还有 4 处用 JDK HttpClient（高德地理编码、DevOps Hub、
    // 本机 Ollama、XXL 接线自检）。它们碰不到上面任何判据——不纳管的话「出站超时已单源」
    // 这句话就是假的。两条硬要求由门禁 check-outbound-client-factory 第 ④ 条守：
    //   ① 必须设 connectTimeout（不设＝连接阶段无限等待）；
    //   ② 必须是**字段/常量初始化**，禁止每次调用新建（每个 HttpClient 自带
    //      selector/executor 线程，不 close 只能等 GC —— MerchantAiInsightService 原形态）。

    /** 第三方公网查询（高德）连接预算：5s。 */
    public static final int CONNECT_GEOCODE_MS = 5_000;

    /** DevOps Hub / 自检探针连接预算：3s。 */
    public static final int CONNECT_HUB_MS = 3_000;

    /** 本机推理服务（Ollama）连接预算：2s。 */
    public static final int CONNECT_LOCAL_LLM_MS = 2_000;

    /**
     * JDK HttpClient 的唯一构造入口：强制带 connectTimeout。
     * 调用方继续链式写 `.followRedirects(...)`（重定向策略是各站点自己的判据，
     * 例如 XXL 自检刻意 NEVER——反代会把 404 变成「302 → 登录页 → 200 + HTML」，
     * 跟随重定向会把「配错」伪装成「通」），但**超时值不再各处凭手感填**。
     */
    public static HttpClient.Builder jdk(int connectMillis) {
        requirePositive(connectMillis, "connectMillis");
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMillis));
    }

    private static void requirePositive(int millis, String name) {
        if (millis <= 0) {
            throw new IllegalArgumentException(
                    "OutboundHttp." + name + " 必须为正数毫秒（0/负数＝无限等待），实际 " + millis);
        }
    }
}
