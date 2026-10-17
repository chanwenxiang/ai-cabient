package com.aicabinet.trade.client;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 内部服务 HTTP 客户端。使用 HttpURLConnection，避免 JDK HttpClient 对 uvicorn 类前端发送
 * {@code Expect: 100-continue} 导致对端返回 400 Invalid HTTP request（历史教训来自已退役的 vision-service）。
 */
final class InternalRestClientFactory {

    private InternalRestClientFactory() {}

    static RestClient create(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        // 端侧结果受理等内部调用；120s 过长易拖垮线程池，与 resilience4j 重试配合用 30s
        factory.setReadTimeout(30_000);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
