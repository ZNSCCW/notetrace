package com.notetrace.ai.deepseek;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notetrace.ai.ChatProvider;

/**
 * DeepSeek 生成实现（OpenAI 兼容协议，国内直连，成本极低）。
 * 依赖：环境变量 DEEPSEEK_API_KEY（不落库、不进 git）。
 * 激活：notetrace.ai.chat-provider=deepseek
 */
@Component
@ConditionalOnProperty(name = "notetrace.ai.chat-provider", havingValue = "deepseek")
public class DeepSeekChatProvider implements ChatProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public DeepSeekChatProvider(@Value("${notetrace.ai.deepseek.base-url}") String baseUrl,
                                @Value("${notetrace.ai.deepseek.model}") String model,
                                @Value("${DEEPSEEK_API_KEY:}") String apiKey,
                                ObjectMapper objectMapper) {
        // 连接/读取超时：防止 API 无响应时问答请求无限挂起
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.model = model;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY 未配置（环境变量）");
        }
        String body = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", model,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt)),
                        "stream", false))
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new IllegalStateException("解析 DeepSeek 响应失败", e);
        }
    }
}
