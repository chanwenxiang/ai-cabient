package com.aicabinet.trade.client;

import com.aicabinet.common.http.OutboundHttp;
import org.springframework.web.client.RestClient;

/**
 * 内部服务 HTTP 客户端。使用 HttpURLConnection，避免 JDK HttpClient 对 uvicorn 类前端发送
 * {@code Expect: 100-continue} 导致对端返回 400 Invalid HTTP request（历史教训来自已退役的 vision-service）。
 */
final class InternalRestClientFactory {

    private InternalRestClientFactory() {}

    static RestClient create(String baseUrl) {
        // S1（2026-11-12）：档位与构造统一到 common-core 的 OutboundHttp（10s / 30s 内部 RPC）。
        // 本类保留为「内部服务」语义的入口，不再自己拼 SimpleClientHttpRequestFactory。
        return OutboundHttp.internalRpc().baseUrl(baseUrl).build();
    }
}
