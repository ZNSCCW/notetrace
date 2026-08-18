package com.notetrace.ai.ollama;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notetrace.ai.ChatProvider;

/**
 * 本地 Ollama 生成实现（免费、无限额、数据不出本机）。
 * 默认模型 qwen2.5:3b（可用 notetrace.ai.ollama.chat-model 切换，如 qwen2.5:7b）。
 * 在 Web UI 选择「本地 AI」时使用。
 */
@Component("ollamaChatProvider")
public class OllamaChatProvider implements ChatProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public OllamaChatProvider(@Value("${notetrace.ai.ollama.base-url}") String baseUrl,
                              @Value("${notetrace.ai.ollama.chat-model:qwen2.5:3b}") String model,
                              ObjectMapper objectMapper) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(600)); // 本地生成较慢，放宽读取超时
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.model = model;
        this.objectMapper = objectMapper;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        String body = restClient.post()
                .uri("/api/chat")
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
            return root.path("message").path("content").asText();
        } catch (Exception e) {
            throw new IllegalStateException("解析 Ollama 生成响应失败", e);
        }
    }
}
