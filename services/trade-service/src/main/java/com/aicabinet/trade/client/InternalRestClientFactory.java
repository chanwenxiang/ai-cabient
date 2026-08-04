package com.aicabinet.trade.client;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 内部服务 HTTP 客户端。使用 HttpURLConnection，避免 JDK HttpClient 对 uvicorn 发送
 * {@code Expect: 100-continue} 导致 vision-service 返回 400 Invalid HTTP request。
 */
final class InternalRestClientFactory {

    private InternalRestClientFactory() {}

    static RestClient create(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
