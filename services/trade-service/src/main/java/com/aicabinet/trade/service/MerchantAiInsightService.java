package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantAiInsightDto;
import com.aicabinet.common.dto.MerchantSkuPerformanceDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class MerchantAiInsightService {

    private final MerchantAnalyticsService analyticsService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String baseUrl;
    private final String model;

    public MerchantAiInsightService(MerchantAnalyticsService analyticsService,
                                    ObjectMapper objectMapper,
                                    @Value("${app.merchant-ai.enabled:true}") boolean enabled,
                                    @Value("${app.merchant-ai.base-url:http://localhost:11434}") String baseUrl,
                                    @Value("${app.merchant-ai.model:qwen2.5:1.5b}") String model) {
        this.analyticsService = analyticsService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public MerchantAiInsightDto insight(Long userId, int days) {
        List<MerchantSkuPerformanceDto> rows = analyticsService.skuPerformance(userId, days);
        String fallback = ruleInsight(rows);
        if (!enabled || rows.isEmpty()) {
            return new MerchantAiInsightDto("RULE", null, fallback, Instant.now(), rows);
        }
        try {
            String prompt = "你是无人零售经营分析助手。根据以下SKU数据，用中文输出简短经营结论和3条可执行建议。"
                    + "不要编造数据，金额单位为分。数据：" + objectMapper.writeValueAsString(rows.stream().limit(30).toList());
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model, "prompt", prompt, "stream", false));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());
            String text = json.path("response").asText("").trim();
            if (response.statusCode() / 100 != 2 || text.isBlank()) throw new IllegalStateException("empty Ollama response");
            return new MerchantAiInsightDto("OLLAMA", model, text, Instant.now(), rows);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MerchantAiInsightDto("RULE", null, fallback, Instant.now(), rows);
        } catch (Exception ignored) {
            return new MerchantAiInsightDto("RULE", null, fallback, Instant.now(), rows);
        }
    }

    static String ruleInsight(List<MerchantSkuPerformanceDto> rows) {
        long best = rows.stream().filter(r -> "BEST_SELLER".equals(r.performanceLevel())).count();
        long slow = rows.stream().filter(r -> "SLOW_MOVER".equals(r.performanceLevel())).count();
        long none = rows.stream().filter(r -> "NO_SALES".equals(r.performanceLevel())).count();
        return "本期共分析 " + rows.size() + " 个商品，其中畅销 " + best + " 个、慢销 " + slow
                + " 个、无销量 " + none + " 个。优先保障畅销品库存，并对慢销和无销量商品执行减量、促销或替换。";
    }
}
